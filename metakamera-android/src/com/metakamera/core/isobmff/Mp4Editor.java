package com.metakamera.core.isobmff;

import com.metakamera.core.ByteSource;
import com.metakamera.core.ByteUtils;
import com.metakamera.core.EditReport;
import com.metakamera.core.EditSpec;
import com.metakamera.core.MediaInfo;
import com.metakamera.core.ProgressListener;
import com.metakamera.core.UnsupportedFormatException;

import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * MP4 / MOV / 3GP / M4V metadata editor.
 *
 * Rewrites the device identity of a video:
 * <ul>
 *   <li>QuickTime Keys metadata (moov/meta, handler 'mdta'): com.apple.quicktime.make / model /
 *       software (what iPhones write; read by Apple Photos, Google Photos, exiftool "Keys:Make"),
 *       plus existing keys ending in ".make"/".manufacturer"/".model"; optional removal of
 *       com.android.version. 'mdta' metas inside moov/udta (ffmpeg style) are updated too.</li>
 *   <li>QuickTime user data text atoms moov/udta '©mak' / '©mod' / '©swr' and iTunes-style
 *       udta/meta('mdir')/ilst items.</li>
 * </ul>
 * Only 'moov' is rebuilt (in memory, at most 256 MB). Media data is streamed verbatim. When the
 * 'moov' size changes, an adjacent 'free' box absorbs the difference if possible; otherwise every
 * absolute offset that points after 'moov' (stco/co64/saio, tfhd base_data_offset, tfra) is shifted.
 */
public final class Mp4Editor {

    static final long MAX_MOOV = 256L * 1024 * 1024;
    static final long MAX_FRAGMENT_BOX = 64L * 1024 * 1024;

    private static final String KEY_PREFIX = "com.apple.quicktime.";
    private static final String KEY_ANDROID_VERSION = "com.android.version";

    private static final String[] FIELD = {"Make", "Model", "Software"};
    private static final String[] KEY_SUFFIX = {"make", "model", "software"};
    /** User-data atoms per field; the first one is created when missing (make/model only). */
    private static final String[][] UDTA_ATOMS = {
            {"©mak"},
            {"©mod", "©mdl"},
            {"©swr"},
    };
    private static final String[] ILST_ATOMS = {"©mak", "©mod", "©swr"};

    private static final String NONE = "(yok)";
    private static final String REMOVED = "(silindi)";

    private static final int PLAN_NONE = 0;
    private static final int PLAN_SHRINK_FREE = 1;
    private static final int PLAN_REMOVE_FREE = 2;
    private static final int PLAN_EXTEND_FREE = 3;
    private static final int PLAN_INSERT_FREE = 4;
    private static final int PLAN_SHIFT = 5;

    private Mp4Editor() {
    }

    // =================================================================== public API

    public static void edit(ByteSource src, OutputStream out, EditSpec spec, EditReport report,
                            ProgressListener listener) throws IOException {
        if (report == null) report = new EditReport();
        try {
            editImpl(src, out, spec, report, listener);
        } catch (EOFException e) {
            throw new UnsupportedFormatException("Video dosyası eksik veya kesik görünüyor");
        } catch (RuntimeException e) {
            throw new UnsupportedFormatException("Video dosyası bozuk veya desteklenmeyen yapıda");
        }
    }

    public static void inspect(ByteSource src, MediaInfo info) throws IOException {
        try {
            inspectImpl(src, info);
        } catch (IOException e) {
            // odd or damaged metadata: keep whatever was found
        } catch (RuntimeException e) {
            // same
        }
    }

    // =================================================================== top level scan

    /** Top-level boxes plus the start of an unparseable tail (copied verbatim). */
    private static final class TopLevel {
        final List<BoxHeader> boxes = new ArrayList<BoxHeader>();
        long tailStart;
        boolean garbageTail;
        int moovIndex = -1;
        int moovCount;
    }

    private static TopLevel scan(ByteSource src) throws IOException {
        TopLevel tl = new TopLevel();
        long len = src.length();
        long pos = 0;
        while (pos < len) {
            if (len - pos < 8) break;
            BoxHeader b;
            try {
                b = BoxHeader.read(src, pos, len);
            } catch (UnsupportedFormatException e) {
                if (tl.moovCount > 0) {
                    tl.garbageTail = true;
                    break;
                }
                throw new UnsupportedFormatException("Bozuk video dosyası: geçersiz kutu yapısı");
            }
            if (b == null) break;
            if ("moov".equals(b.type)) {
                if (tl.moovIndex < 0) tl.moovIndex = tl.boxes.size();
                tl.moovCount++;
            }
            tl.boxes.add(b);
            pos = b.end();
        }
        tl.tailStart = pos;
        return tl;
    }

    // =================================================================== moov model

    private static final class Moov {
        Mp4Box root;
        final List<Mp4OffsetTable> tables = new ArrayList<Mp4OffsetTable>();
        /** A sample table could not be parsed: offsets cannot be shifted safely. */
        String corruptTables;
        /** First moov/udta (null if absent). */
        Mp4Box udta;
        boolean udtaUnreadable;
        /** moov/meta with handler 'mdta' (null if absent). */
        QtKeysMeta keys;
        /** A moov/meta that must not be touched (other handler / unreadable) exists. */
        String foreignMoovMeta;
        /** 'mdta' metas inside moov/udta (ffmpeg style): update only. */
        final List<QtKeysMeta> udtaKeys = new ArrayList<QtKeysMeta>();
        /** iTunes-style metas ('mdir') in moov/udta. */
        final List<Mp4Box> itunesMetas = new ArrayList<Mp4Box>();
        /** iTunes-style meta directly in moov (read-only). */
        Mp4Box moovItunesMeta;
        Mp4Box firstVideoTkhd;
        Mp4Box mvhd;
    }

    private static Moov parseMoov(byte[] moovBytes, EditReport report) throws UnsupportedFormatException {
        Moov m = new Moov();
        m.root = Mp4Box.parseRoot(moovBytes);
        try {
            m.root.expand(0);
        } catch (UnsupportedFormatException e) {
            throw new UnsupportedFormatException("Bozuk 'moov' kutusu");
        }
        for (Mp4Box c : m.root.children) {
            String t = c.type;
            if ("cmov".equals(t)) {
                throw new UnsupportedFormatException("Sıkıştırılmış QuickTime başlığı (cmov) desteklenmiyor");
            } else if ("mvhd".equals(t)) {
                if (m.mvhd == null) m.mvhd = c;
            } else if ("trak".equals(t)) {
                parseTrak(m, c);
            } else if ("udta".equals(t)) {
                if (m.udta != null) continue;
                m.udta = c;
                if (!c.tryExpand(0)) {
                    m.udtaUnreadable = true;
                    if (report != null) report.warn("Video kullanıcı verisi (udta) okunamadı; dokunulmadı");
                    continue;
                }
                for (Mp4Box u : c.children) {
                    if (!"meta".equals(u.type)) continue;
                    if (!QtKeysMeta.expandMeta(u)) continue;
                    String h = QtKeysMeta.handler(u);
                    if (QtKeysMeta.MDTA.equals(h)) {
                        try {
                            m.udtaKeys.add(QtKeysMeta.open(u));
                        } catch (UnsupportedFormatException e) {
                            if (report != null) report.warn("udta/meta Keys tablosu bozuk; atlandı");
                        }
                    } else if (QtKeysMeta.MDIR.equals(h)) {
                        m.itunesMetas.add(u);
                    }
                }
            } else if ("meta".equals(t)) {
                if (m.keys != null) continue;
                if (!QtKeysMeta.expandMeta(c)) {
                    m.foreignMoovMeta = "okunamadı";
                    continue;
                }
                String h = QtKeysMeta.handler(c);
                if (QtKeysMeta.MDTA.equals(h)) {
                    try {
                        m.keys = QtKeysMeta.open(c);
                    } catch (UnsupportedFormatException e) {
                        m.foreignMoovMeta = "Keys tablosu bozuk";
                    }
                } else {
                    // another handler directly in moov: never touched (only read by inspect)
                    if (QtKeysMeta.MDIR.equals(h)) m.moovItunesMeta = c;
                    m.foreignMoovMeta = "işleyici '" + (h == null ? "?" : h.trim()) + "'";
                }
            }
        }
        if (m.keys != null) m.foreignMoovMeta = null;
        return m;
    }

    private static void parseTrak(Moov m, Mp4Box trak) {
        if (!trak.tryExpand(0)) {
            m.corruptTables = "trak";
            return;
        }
        Mp4Box mdia = trak.child("mdia");
        if (mdia == null) return;
        if (!mdia.tryExpand(0)) {
            m.corruptTables = "mdia";
            return;
        }
        Mp4Box hdlr = mdia.child("hdlr");
        if (m.firstVideoTkhd == null && hdlr != null) {
            byte[] hp = hdlr.payloadBytes();
            if (hp.length >= 12 && "vide".equals(ByteUtils.fourcc(hp, 8))) m.firstVideoTkhd = trak.child("tkhd");
        }
        Mp4Box minf = mdia.child("minf");
        if (minf == null) return;
        if (!minf.tryExpand(0)) {
            m.corruptTables = "minf";
            return;
        }
        Mp4Box stbl = minf.child("stbl");
        if (stbl == null) return;
        if (!stbl.tryExpand(0)) {
            m.corruptTables = "stbl";
            return;
        }
        for (Mp4Box s : stbl.children) {
            if (!Mp4OffsetTable.isOffsetBox(s.type)) continue;
            try {
                m.tables.add(Mp4OffsetTable.parse(s));
            } catch (UnsupportedFormatException e) {
                m.corruptTables = s.type;
            }
        }
    }

    // =================================================================== edit

    private static void editImpl(ByteSource src, OutputStream out, EditSpec spec, EditReport report,
                                 ProgressListener listener) throws IOException {
        long len = src.length();
        TopLevel tl = scan(src);
        if (tl.moovCount == 0) throw new UnsupportedFormatException("Video dosyasında 'moov' kutusu bulunamadı");
        if (tl.moovCount > 1) throw new UnsupportedFormatException("Video dosyasında birden fazla 'moov' kutusu var");
        if (tl.garbageTail) report.warn("Dosya sonundaki tanınmayan veri olduğu gibi korundu");
        BoxHeader moovH = tl.boxes.get(tl.moovIndex);
        if (moovH.size > MAX_MOOV) throw new UnsupportedFormatException("Video başlığı (moov) çok büyük");

        byte[] moovBytes = ByteUtils.readBytes(src, moovH.offset, (int) moovH.size);
        Moov m = parseMoov(moovBytes, report);

        applyMetadata(m, spec, report);

        // ---- size bookkeeping
        long oldSize = moovH.size;
        long threshold = moovH.end();
        long delta = m.root.size() - oldSize;
        BoxHeader next = tl.moovIndex + 1 < tl.boxes.size() ? tl.boxes.get(tl.moovIndex + 1) : null;
        boolean nextFree = next != null && ("free".equals(next.type) || "skip".equals(next.type));
        int nextHdr = next != null && next.largeSize ? 16 : 8;
        boolean dataAfter = threshold < len;

        int plan = PLAN_NONE;
        if (delta == 0) {
            plan = PLAN_NONE;
        } else if (delta > 0 && nextFree && next.size == delta) {
            plan = PLAN_REMOVE_FREE;
        } else if (delta > 0 && nextFree && next.size - delta >= nextHdr
                && (next.largeSize || next.size - delta <= Mp4OffsetTable.MAX_U32)) {
            plan = PLAN_SHRINK_FREE;
        } else if (delta < 0 && nextFree && (next.largeSize || next.size - delta <= Mp4OffsetTable.MAX_U32)) {
            plan = PLAN_EXTEND_FREE;
        } else if (delta <= -8) {
            plan = PLAN_INSERT_FREE;
        } else {
            plan = PLAN_SHIFT;
        }

        if (plan == PLAN_SHIFT && dataAfter) {
            if (m.corruptTables != null) {
                throw new UnsupportedFormatException("Video örnek tablosu bozuk ('" + m.corruptTables
                        + "'); veri ofsetleri güncellenemedi");
            }
            delta = shiftTables(m, threshold, delta, report);
        }
        boolean shifting = plan == PLAN_SHIFT && dataAfter && delta != 0;

        byte[] newMoov = m.root.isDirty() ? m.root.toBytes() : moovBytes;
        if (newMoov.length - oldSize != delta) {
            throw new UnsupportedFormatException("İç hata: moov boyutu tutarsız");
        }

        // ---- output length (for progress)
        long total = len + delta;
        if (plan == PLAN_SHRINK_FREE || plan == PLAN_REMOVE_FREE || plan == PLAN_EXTEND_FREE
                || plan == PLAN_INSERT_FREE) {
            total = len;
        }

        // ---- write
        long done = 0;
        int fragmentsPatched = 0;
        for (int i = 0; i < tl.boxes.size(); i++) {
            BoxHeader b = tl.boxes.get(i);
            if (i == tl.moovIndex) {
                out.write(newMoov);
                done += newMoov.length;
                if (plan == PLAN_SHRINK_FREE) {
                    long ns = next.size - delta;
                    writeFreeHeader(out, next, ns);
                    long payload = ns - nextHdr;
                    ByteUtils.copyRange(src, next.offset + nextHdr, payload, out, listener, done + nextHdr, total);
                    done += ns;
                    i++;
                } else if (plan == PLAN_REMOVE_FREE) {
                    i++;
                } else if (plan == PLAN_EXTEND_FREE) {
                    long ns = next.size - delta;
                    writeFreeHeader(out, next, ns);
                    long payload = next.size - next.headerSize;
                    ByteUtils.copyRange(src, next.offset + next.headerSize, payload, out, listener,
                            done + nextHdr, total);
                    writeZeros(out, -delta);
                    done += ns;
                    i++;
                } else if (plan == PLAN_INSERT_FREE) {
                    byte[] h = new byte[8];
                    ByteUtils.put32be(h, 0, -delta);
                    System.arraycopy(ByteUtils.fourccBytes("free"), 0, h, 4, 4);
                    out.write(h);
                    writeZeros(out, -delta - 8);
                    done += -delta;
                }
                progress(listener, done, total);
                continue;
            }
            if (shifting && ("moof".equals(b.type) || "mfra".equals(b.type))) {
                if (b.size > MAX_FRAGMENT_BOX) {
                    throw new UnsupportedFormatException("Video parça başlığı ('" + b.type + "') çok büyük");
                }
                byte[] box = ByteUtils.readBytes(src, b.offset, (int) b.size);
                try {
                    if ("moof".equals(b.type)) fragmentsPatched += Mp4Fragments.patchMoof(box, threshold, delta);
                    else fragmentsPatched += Mp4Fragments.patchMfra(box, threshold, delta);
                } catch (UnsupportedFormatException e) {
                    if (e.getMessage() != null && e.getMessage().startsWith("Parçalı")) throw e;
                    throw new UnsupportedFormatException("Parçalı video yapısı bozuk ('" + b.type + "'); "
                            + "veri ofsetleri güncellenemedi");
                }
                out.write(box);
                done += box.length;
                progress(listener, done, total);
                continue;
            }
            ByteUtils.copyRange(src, b.offset, b.size, out, listener, done, total);
            done += b.size;
        }
        if (tl.tailStart < len) {
            ByteUtils.copyRange(src, tl.tailStart, len - tl.tailStart, out, listener, done, total);
            done += len - tl.tailStart;
        }
        if (fragmentsPatched > 0) {
            report.change("Video parça ofsetleri güncellendi (" + fragmentsPatched + " adet)");
        }
        progress(listener, done, total);
    }

    /** Shifts every chunk/aux offset after moov; widens 32-bit tables when needed. Returns final delta. */
    private static long shiftTables(Moov m, long threshold, long delta, EditReport report)
            throws UnsupportedFormatException {
        long oldSize = m.root.srcLen;
        for (int iter = 0; iter < 16; iter++) {
            boolean widened = false;
            for (Mp4OffsetTable t : m.tables) {
                if (t.needsWiden(threshold, delta)) {
                    t.widen();
                    widened = true;
                }
            }
            if (!widened) break;
            for (Mp4OffsetTable t : m.tables) {
                if (t.wasWidened()) t.apply(threshold, delta);
            }
            delta = m.root.size() - oldSize;
        }
        int shifted = 0;
        int widened = 0;
        for (Mp4OffsetTable t : m.tables) {
            if (t.affected(threshold) || t.wasWidened()) {
                t.apply(threshold, delta);
                shifted++;
                if (t.wasWidened()) widened++;
            }
        }
        long check = m.root.size() - oldSize;
        if (check != delta) throw new UnsupportedFormatException("İç hata: ofset tablosu boyutu tutarsız");
        if (shifted > 0) {
            report.change("Video veri ofsetleri güncellendi (" + (delta > 0 ? "+" : "") + delta + " bayt, "
                    + shifted + " tablo)");
        }
        if (widened > 0) report.change("32 bit ofset tablosu 64 bit'e (co64) dönüştürüldü (" + widened + ")");
        return delta;
    }

    private static void writeFreeHeader(OutputStream out, BoxHeader orig, long size) throws IOException {
        byte[] type = ByteUtils.fourccBytes(orig.type);
        if (orig.largeSize) {
            byte[] h = new byte[16];
            ByteUtils.put32be(h, 0, 1);
            System.arraycopy(type, 0, h, 4, 4);
            ByteUtils.put64be(h, 8, size);
            out.write(h);
        } else {
            byte[] h = new byte[8];
            ByteUtils.put32be(h, 0, size);
            System.arraycopy(type, 0, h, 4, 4);
            out.write(h);
        }
    }

    private static void writeZeros(OutputStream out, long n) throws IOException {
        byte[] z = new byte[(int) Math.min(64 * 1024, Math.max(1, n))];
        while (n > 0) {
            int k = (int) Math.min(z.length, n);
            out.write(z, 0, k);
            n -= k;
        }
    }

    private static void progress(ProgressListener l, long done, long total) {
        if (l != null) l.onProgress(done, total);
    }

    // =================================================================== metadata edits

    private static void applyMetadata(Moov m, EditSpec spec, EditReport report) {
        String[] values = {spec.make, spec.model, spec.software};

        // ---- QuickTime Keys (moov/meta 'mdta')
        boolean wantCreate = false;
        boolean wantAny = false;
        for (String v : values) {
            if (v != null && v.length() > 0) wantCreate = true;
            if (v != null) wantAny = true;
        }
        if (m.keys == null && m.foreignMoovMeta != null && wantAny) {
            report.warn("moov/meta düzenlenmedi (" + m.foreignMoovMeta + "); yalnızca udta güncellendi");
        }
        if (m.keys == null && m.foreignMoovMeta == null && wantCreate) {
            if (!m.udtaKeys.isEmpty() && m.udta != null) {
                // ffmpeg-style Keys inside moov/udta/meta: move them to moov/meta (QuickTime style, as
                // iPhones write) instead of adding a second key table, which readers such as ffmpeg and
                // Android's MPEG4Extractor do not merge.
                QtKeysMeta k = m.udtaKeys.remove(0);
                m.udta.removeChild(k.meta);
                k.meta.prefix = Mp4Box.EMPTY;
                k.meta.modified = true;
                m.root.addChild(k.meta);
                m.keys = k;
                report.change("Video Keys meta verisi moov/meta konumuna taşındı");
            } else {
                m.keys = QtKeysMeta.create(m.root);
            }
        }
        if (m.keys != null) editKeys(m.keys, values, spec.removeMakerNote, true, report);
        for (QtKeysMeta k : m.udtaKeys) editKeys(k, values, spec.removeMakerNote, false, report);

        // ---- iTunes-style ilst items
        for (Mp4Box meta : m.itunesMetas) editItunes(meta, values, report);

        // ---- QuickTime user data text atoms
        if (!m.udtaUnreadable) editUserData(m, values, report);

        // drop a user-data box that this edit emptied
        if (m.udta != null && m.udta.isContainer() && m.udta.modified && m.udta.children.isEmpty()) {
            m.root.removeChild(m.udta);
            m.udta = null;
        }
    }

    private static boolean keyMatches(String name, int field) {
        String n = name.toLowerCase(Locale.US);
        if (n.equals(KEY_PREFIX + KEY_SUFFIX[field])) return true;
        if (field == 0) return n.endsWith(".make") || n.endsWith(".manufacturer");
        if (field == 1) return n.endsWith(".model");
        return false;
    }

    private static void editKeys(QtKeysMeta k, String[] values, boolean removeAndroidVersion, boolean primary,
                                 EditReport report) {
        String where = primary ? "Keys" : "Keys/udta";
        for (int f = 0; f < values.length; f++) {
            String v = values[f];
            if (v == null) continue;
            String std = KEY_PREFIX + KEY_SUFFIX[f];
            List<Integer> idx = new ArrayList<Integer>();
            for (int i = 1; i <= k.count(); i++) {
                if (keyMatches(k.name(i), f)) idx.add(i);
            }
            if (v.length() == 0) {
                for (int j = idx.size() - 1; j >= 0; j--) {
                    int i = idx.get(j);
                    String name = k.name(i);
                    String old = k.value(i);
                    k.remove(i);
                    report.change("Video " + FIELD[f] + " (" + label(where, name, std) + "): "
                            + show(old) + " → " + REMOVED);
                }
                continue;
            }
            boolean hasStd = false;
            for (int i : idx) {
                String name = k.name(i);
                if (name.equals(std)) hasStd = true;
                String old = k.value(i);
                if (v.equals(old)) continue;
                k.setValue(i, v);
                report.change("Video " + FIELD[f] + " (" + label(where, name, std) + "): " + show(old)
                        + " → " + v);
            }
            if (primary && !hasStd) {
                k.add(std, v);
                report.change("Video " + FIELD[f] + " (" + where + "): " + NONE + " → " + v);
            }
        }
        if (removeAndroidVersion) {
            for (int i = k.count(); i >= 1; i--) {
                if (KEY_ANDROID_VERSION.equals(k.name(i))) {
                    k.remove(i);
                    report.change("com.android.version anahtarı kaldırıldı");
                }
            }
        }
        k.commit();
    }

    private static String label(String where, String name, String std) {
        return name.equals(std) ? where : where + ": " + name;
    }

    private static String show(String v) {
        return v == null ? NONE : v;
    }

    private static void editItunes(Mp4Box meta, String[] values, EditReport report) {
        Mp4Box ilst = meta.child("ilst");
        if (ilst == null || !ilst.isContainer()) return;
        for (int f = 0; f < values.length; f++) {
            String v = values[f];
            if (v == null) continue;
            for (Mp4Box item : ilst.childrenOf(ILST_ATOMS[f])) {
                String old = QtData.itemValue(item);
                if (v.length() == 0) {
                    ilst.removeChild(item);
                    report.change("Video " + FIELD[f] + " (ItemList): " + show(old) + " → " + REMOVED);
                } else if (!v.equals(old)) {
                    QtData.setItemValue(item, v);
                    report.change("Video " + FIELD[f] + " (ItemList): " + show(old) + " → " + v);
                }
            }
        }
    }

    private static void editUserData(Moov m, String[] values, EditReport report) {
        for (int f = 0; f < values.length; f++) {
            String v = values[f];
            if (v == null) continue;
            List<Mp4Box> atoms = new ArrayList<Mp4Box>();
            if (m.udta != null) {
                for (String t : UDTA_ATOMS[f]) atoms.addAll(m.udta.childrenOf(t));
            }
            if (v.length() == 0) {
                for (Mp4Box a : atoms) {
                    String old = QtData.decodeText(a.payloadBytes());
                    m.udta.removeChild(a);
                    report.change("Video " + FIELD[f] + " (UserData): " + show(old) + " → " + REMOVED);
                }
                continue;
            }
            for (Mp4Box a : atoms) {
                byte[] p = a.payloadBytes();
                String old = QtData.decodeText(p);
                if (v.equals(old)) continue;
                a.setPayload(QtData.encodeText(p, v));
                report.change("Video " + FIELD[f] + " (UserData): " + show(old) + " → " + v);
            }
            if (atoms.isEmpty() && f <= 1) {
                if (m.udta == null) {
                    m.udta = Mp4Box.newContainer("udta", null);
                    m.root.addChild(m.udta);
                }
                m.udta.addChild(Mp4Box.newLeaf(UDTA_ATOMS[f][0], QtData.encodeText(null, v)));
                report.change("Video " + FIELD[f] + " (UserData): " + NONE + " → " + v);
            }
        }
    }

    // =================================================================== inspect

    private static void inspectImpl(ByteSource src, MediaInfo info) throws IOException {
        TopLevel tl = scan(src);
        if (tl.moovIndex < 0) return;
        BoxHeader moovH = tl.boxes.get(tl.moovIndex);
        if (moovH.size > MAX_MOOV) return;
        byte[] moovBytes = ByteUtils.readBytes(src, moovH.offset, (int) moovH.size);
        Moov m = parseMoov(moovBytes, null);

        // creation time (mvhd: seconds since 1904-01-01 UTC)
        if (m.mvhd != null) {
            byte[] p = m.mvhd.payloadBytes();
            long secs = -1;
            if (p.length >= 12 && p[0] == 1) secs = ByteUtils.u64be(p, 4);
            else if (p.length >= 8 && p[0] == 0) secs = ByteUtils.u32be(p, 4);
            if (secs > 0) {
                long ms = (secs - 2082844800L) * 1000L;
                info.creationTimeMillis = ms > 0 ? ms : 0;
            }
        }

        // pixel size from the first video track header (16.16 fixed point at the end of tkhd)
        if (m.firstVideoTkhd != null) {
            byte[] p = m.firstVideoTkhd.payloadBytes();
            if (p.length >= 84) {
                info.width = (int) (ByteUtils.u32be(p, p.length - 8) >>> 16);
                info.height = (int) (ByteUtils.u32be(p, p.length - 4) >>> 16);
            }
        }

        // device identity: Keys first, then udta Keys, udta text atoms, iTunes items
        String[] found = new String[3];
        List<QtKeysMeta> keyMetas = new ArrayList<QtKeysMeta>();
        if (m.keys != null) keyMetas.add(m.keys);
        keyMetas.addAll(m.udtaKeys);
        boolean location = false;
        boolean locationZero = true;
        for (QtKeysMeta k : keyMetas) {
            for (int f = 0; f < 3; f++) {
                if (found[f] != null) continue;
                int i = k.find(KEY_PREFIX + KEY_SUFFIX[f]);
                if (i > 0) found[f] = nonEmpty(k.value(i));
            }
            int li = k.find("com.apple.quicktime.location.ISO6709");
            if (li > 0) {
                location = true;
                if (!isZeroLocation(k.value(li))) locationZero = false;
            }
        }
        if (m.udta != null && m.udta.isContainer()) {
            for (int f = 0; f < 3; f++) {
                if (found[f] != null) continue;
                for (String t : UDTA_ATOMS[f]) {
                    Mp4Box a = m.udta.child(t);
                    if (a != null && found[f] == null) found[f] = nonEmpty(QtData.decodeText(a.payloadBytes()));
                }
            }
            Mp4Box xyz = m.udta.child("©xyz");
            if (xyz != null) {
                location = true;
                if (!isZeroLocation(QtData.decodeText(xyz.payloadBytes()))) locationZero = false;
            }
        }
        List<Mp4Box> itunes = new ArrayList<Mp4Box>(m.itunesMetas);
        if (m.moovItunesMeta != null) itunes.add(m.moovItunesMeta);
        for (Mp4Box meta : itunes) {
            Mp4Box ilst = meta.child("ilst");
            if (ilst == null || !ilst.isContainer()) continue;
            for (int f = 0; f < 3; f++) {
                if (found[f] != null) continue;
                Mp4Box item = ilst.child(ILST_ATOMS[f]);
                if (item != null) found[f] = nonEmpty(QtData.itemValue(item));
            }
        }
        info.make = found[0];
        info.model = found[1];
        info.software = found[2];
        info.gpsStatus = !location ? "yok" : (locationZero ? "maskelenmiş" : "var");
    }

    private static String nonEmpty(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.length() == 0 ? null : t;
    }

    /** ISO 6709 string like "+00.0000+000.0000/" (all digits zero). */
    private static boolean isZeroLocation(String s) {
        if (s == null) return true;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '1' && c <= '9') return false;
        }
        return true;
    }
}
