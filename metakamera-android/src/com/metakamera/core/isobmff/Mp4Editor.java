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
 *       software (what iPhones write; read by Apple Photos, OPPO Gallery, exiftool "Keys:Make"),
 *       com.android.manufacturer / model / version (Huawei Gallery, Immich), vendor keys
 *       (com.oplus.product.model, com.honor.lens.model, ...) and other keys ending in
 *       ".make"/".manufacturer"/".model" — see {@link Mp4BrandPolicy}. 'mdta' metas inside
 *       moov/udta (ffmpeg style) and moov/trak (track level, iPhone lens model and 35 mm equivalent
 *       focal length) are updated too.</li>
 *   <li>QuickTime user data text atoms moov/udta '©mak' / '©mod' / '©swr', iTunes-style
 *       udta/meta('mdir')/ilst items, Samsung udta/smta/'mdln' and 3GPP udta/'auth' naming the
 *       original device.</li>
 * </ul>
 * Only 'moov' is rebuilt (in memory, at most 256 MB). Media data is streamed verbatim. When the
 * 'moov' size changes, an adjacent 'free' box absorbs the difference if possible; otherwise every
 * absolute offset that points after 'moov' (stco/co64/saio, tfhd base_data_offset, tfra) is shifted.
 */
public final class Mp4Editor {

    static final long MAX_MOOV = 256L * 1024 * 1024;
    static final long MAX_FRAGMENT_BOX = 64L * 1024 * 1024;

    private static final String[] FIELD = {"Make", "Model", "Software"};
    /** User-data atoms per field; the first one is created when missing (make/model only). */
    private static final String[][] UDTA_ATOMS = {
            {"©mak"},
            {"©mod", "©mdl"},
            {"©swr"},
    };
    private static final String[] ILST_ATOMS = {"©mak", "©mod", "©swr"};

    /** Standard Keys kinds per field (make, model, software). */
    private static final int[] STD_KIND = {Mp4BrandPolicy.STD_MAKE, Mp4BrandPolicy.STD_MODEL,
            Mp4BrandPolicy.STD_SOFTWARE};
    /** Keys kinds created in moov/meta when missing, in this order. */
    private static final int[] CREATED_KINDS = {Mp4BrandPolicy.STD_MAKE, Mp4BrandPolicy.STD_MODEL,
            Mp4BrandPolicy.STD_SOFTWARE, Mp4BrandPolicy.ANDROID_MAKE, Mp4BrandPolicy.ANDROID_MODEL};
    private static final String KEY_LOCATION = "com.apple.quicktime.location.ISO6709";

    private static final String NONE = "(yok)";
    private static final String REMOVED = "(silindi)";
    private static final String TRACE = "Orijinal marka izi kaldırıldı";

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
        /** Track-level 'mdta' metas (moov/trak/meta, e.g. iPhone lens model), with their 'trak'. */
        final List<QtKeysMeta> trakKeys = new ArrayList<QtKeysMeta>();
        final List<Mp4Box> trakKeysParent = new ArrayList<Mp4Box>();
        /** Expanded Samsung moov/udta/'smta' boxes. */
        final List<Mp4Box> smtas = new ArrayList<Mp4Box>();
        Mp4Box firstVideoTrak;
        /** 'mdta' meta of the first video track (null if none). */
        QtKeysMeta videoTrakKeys;
        /** The first video track has a 'meta' that must not be touched (other handler / unreadable). */
        boolean videoTrakForeignMeta;
        Mp4Box firstVideoTkhd;
        Mp4Box mvhd;

        /** Every 'mdta' key table: moov/meta, moov/udta/meta, moov/trak/meta. */
        List<QtKeysMeta> allKeys() {
            List<QtKeysMeta> r = new ArrayList<QtKeysMeta>();
            if (keys != null) r.add(keys);
            r.addAll(udtaKeys);
            r.addAll(trakKeys);
            return r;
        }
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
                    if ("smta".equals(u.type)) {
                        if (expandSmta(u)) m.smtas.add(u);
                        continue;
                    }
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

    /** Samsung 'smta': child atoms after 4 bytes of version/flags (exiftool: Start => 4). */
    private static boolean expandSmta(Mp4Box smta) {
        if (smta.isContainer()) return true;
        if (smta.src == null) return false;
        boolean full = QtKeysMeta.hasVersionFlags(smta.src, smta.origPayloadOffset(), smta.origPayloadLength());
        return smta.tryExpand(full ? 4 : 0);
    }

    private static void parseTrak(Moov m, Mp4Box trak) {
        if (!trak.tryExpand(0)) {
            m.corruptTables = "trak";
            return;
        }
        // track-level Keys (QuickTime style without version/flags on iPhones; both styles accepted)
        QtKeysMeta trakKeys = null;
        boolean foreignMeta = false;
        for (Mp4Box c : trak.children) {
            if (!"meta".equals(c.type)) continue;
            if (!QtKeysMeta.expandMeta(c) || !QtKeysMeta.MDTA.equals(QtKeysMeta.handler(c))) {
                foreignMeta = true;
                continue;
            }
            try {
                QtKeysMeta k = QtKeysMeta.open(c);
                m.trakKeys.add(k);
                m.trakKeysParent.add(trak);
                if (trakKeys == null) trakKeys = k;
            } catch (UnsupportedFormatException e) {
                foreignMeta = true;
            }
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
            if (hp.length >= 12 && "vide".equals(ByteUtils.fourcc(hp, 8))) {
                m.firstVideoTkhd = trak.child("tkhd");
                m.firstVideoTrak = trak;
                m.videoTrakKeys = trakKeys;
                m.videoTrakForeignMeta = foreignMeta;
            }
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
        Mp4BrandPolicy policy = new Mp4BrandPolicy(spec);
        // device names found before any change (to recognise a 3GPP 'auth' naming the original device)
        List<String> oldIdentity = oldIdentity(m);

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
        if (m.keys != null) editKeys(m.keys, policy, "Keys", true, report);
        for (QtKeysMeta k : m.udtaKeys) editKeys(k, policy, "Keys/udta", false, report);
        List<Integer> emptied = new ArrayList<Integer>();
        for (int i = 0; i < m.trakKeys.size(); i++) {
            QtKeysMeta k = m.trakKeys.get(i);
            int before = k.count();
            editKeys(k, policy, "Keys/trak", false, report);
            if (before > 0 && k.isEmpty()) emptied.add(i);
        }
        for (int j = emptied.size() - 1; j >= 0; j--) {
            // this edit removed the last track-level key: drop the now empty 'meta'
            int i = emptied.get(j);
            QtKeysMeta k = m.trakKeys.remove(i);
            m.trakKeysParent.remove(i).removeChild(k.meta);
            if (m.videoTrakKeys == k) m.videoTrakKeys = null;
        }
        createTrackLens(m, policy, report);
        createFocal35(m, policy, report);

        // ---- iTunes-style ilst items
        for (Mp4Box meta : m.itunesMetas) editItunes(meta, values, report);

        // ---- QuickTime / 3GPP user data atoms, Samsung 'smta'
        if (!m.udtaUnreadable) {
            editUserData(m, values, report);
            editSamsungModel(m, policy, report);
            editAuthors(m, policy, oldIdentity, report);
        }

        // drop a user-data box that this edit emptied
        if (m.udta != null && m.udta.isContainer() && m.udta.modified && m.udta.children.isEmpty()) {
            m.root.removeChild(m.udta);
            m.udta = null;
        }
    }

    /**
     * Applies {@link Mp4BrandPolicy} to every key of one 'mdta' table; the primary table (moov/meta)
     * also gets the standard (and, for non-Apple targets, com.android.*) keys that are missing.
     */
    private static void editKeys(QtKeysMeta k, Mp4BrandPolicy policy, String where, boolean primary,
                                 EditReport report) {
        boolean[] present = new boolean[Mp4BrandPolicy.KIND_COUNT];
        List<Integer> removals = new ArrayList<Integer>();
        for (int i = 1; i <= k.count(); i++) {
            String name = k.name(i);
            int kind = Mp4BrandPolicy.classify(name);
            present[kind] = true;
            String target = policy.target(kind);
            if (target == null) continue;
            boolean numeric = Mp4BrandPolicy.isNumericKind(kind);
            String old = numeric ? numberText(k, i) : k.value(i);
            String field = Mp4BrandPolicy.field(kind);
            if (numeric && target.length() > 0) {
                long v = Long.parseLong(target);
                Long cur = k.number(i);
                if (cur == null || cur.longValue() != v) {
                    k.setNumber(i, v);
                    report.change("Video " + field + " (" + keyLabel(kind, where, name) + "): " + show(old) + " → "
                            + target);
                }
                continue;
            }
            if (target.length() == 0) {
                removals.add(i);
                if (kind == Mp4BrandPolicy.ANDROID_VERSION) {
                    report.change("com.android.version anahtarı kaldırıldı");
                } else if (policy.isTraceRemoval(kind)) {
                    report.change(TRACE + " (" + where + ": " + name + "): " + show(old));
                } else {
                    report.change("Video " + field + " (" + keyLabel(kind, where, name) + "): " + show(old)
                            + " → " + REMOVED);
                }
            } else if (!target.equals(old)) {
                k.setValue(i, target);
                report.change("Video " + field + " (" + keyLabel(kind, where, name) + "): " + show(old) + " → "
                        + target);
            }
        }
        for (int j = removals.size() - 1; j >= 0; j--) k.remove(removals.get(j));
        if (primary) {
            for (int kind : CREATED_KINDS) {
                if (present[kind]) continue;
                String v = policy.create(kind);
                if (v == null) continue;
                String name = Mp4BrandPolicy.keyName(kind);
                k.add(name, v);
                report.change("Video " + Mp4BrandPolicy.field(kind) + " (" + keyLabel(kind, where, name) + "): "
                        + NONE + " → " + v);
            }
        }
        k.commit();
    }

    private static String keyLabel(int kind, String where, String name) {
        boolean std = kind == Mp4BrandPolicy.STD_MAKE || kind == Mp4BrandPolicy.STD_MODEL
                || kind == Mp4BrandPolicy.STD_SOFTWARE;
        return std ? where : where + ": " + name;
    }

    /** Decimal text of a numeric key's value, null when unreadable. */
    private static String numberText(QtKeysMeta k, int index) {
        Long v = k.number(index);
        return v == null ? null : String.valueOf(v);
    }

    /** 1-based index of the first key of this kind, or 0. */
    private static int findKind(QtKeysMeta k, int kind) {
        for (int i = 1; i <= k.count(); i++) {
            if (Mp4BrandPolicy.classify(k.name(i)) == kind) return i;
        }
        return 0;
    }

    /**
     * Apple target with a lens model: adds com.apple.quicktime.camera.lens_model to the first video
     * track (QuickTime-style trak/meta, as iPhones write it) when no key table holds one.
     */
    private static void createTrackLens(Moov m, Mp4BrandPolicy policy, EditReport report) {
        String lens = policy.createLens();
        if (lens == null || m.firstVideoTrak == null) return;
        for (QtKeysMeta k : m.allKeys()) {
            if (findKind(k, Mp4BrandPolicy.APPLE_LENS) > 0) return;
        }
        QtKeysMeta k = m.videoTrakKeys;
        if (k == null) {
            if (m.videoTrakForeignMeta) return;
            k = QtKeysMeta.create(m.firstVideoTrak);
            m.videoTrakKeys = k;
            m.trakKeys.add(k);
            m.trakKeysParent.add(m.firstVideoTrak);
        }
        k.add(Mp4BrandPolicy.KEY_APPLE_LENS, lens);
        k.commit();
        report.change("Video LensModel (Keys/trak): " + NONE + " → " + lens);
    }

    /**
     * Apple target with a 35 mm equivalent focal length: adds
     * com.apple.quicktime.camera.focal_length.35mm_equivalent (signed integer, type 21) to the key
     * table that holds the lens_model key — the video track for iPhone files or a freshly created
     * lens key — when no key table has one. Without a lens_model key nothing is created.
     */
    private static void createFocal35(Moov m, Mp4BrandPolicy policy, EditReport report) {
        Integer f = policy.createFocal35();
        if (f == null) return;
        for (QtKeysMeta k : m.allKeys()) {
            if (findKind(k, Mp4BrandPolicy.APPLE_FOCAL35) > 0) return;
        }
        QtKeysMeta host = null;
        String where = null;
        List<QtKeysMeta> trak = new ArrayList<QtKeysMeta>();
        if (m.videoTrakKeys != null) trak.add(m.videoTrakKeys);
        trak.addAll(m.trakKeys);
        for (QtKeysMeta k : trak) {
            if (host == null && findKind(k, Mp4BrandPolicy.APPLE_LENS) > 0) {
                host = k;
                where = "Keys/trak";
            }
        }
        if (host == null && m.keys != null && findKind(m.keys, Mp4BrandPolicy.APPLE_LENS) > 0) {
            host = m.keys;
            where = "Keys";
        }
        for (QtKeysMeta k : m.udtaKeys) {
            if (host == null && findKind(k, Mp4BrandPolicy.APPLE_LENS) > 0) {
                host = k;
                where = "Keys/udta";
            }
        }
        if (host == null) return;
        host.addNumber(Mp4BrandPolicy.KEY_APPLE_FOCAL35, f.intValue());
        host.commit();
        report.change("Video " + Mp4BrandPolicy.field(Mp4BrandPolicy.APPLE_FOCAL35) + " (" + where + "): " + NONE
                + " → " + f);
    }

    /** Samsung moov/udta/smta/'mdln' (model code, exiftool "SamsungModel"). */
    private static void editSamsungModel(Moov m, Mp4BrandPolicy policy, EditReport report) {
        String target = policy.samsungModelTarget();
        if (target == null) return;
        for (Mp4Box smta : m.smtas) {
            for (Mp4Box mdln : smta.childrenOf("mdln")) {
                byte[] p = mdln.payloadBytes();
                String old = QtData.decodeRawText(p);
                if (target.length() == 0) {
                    smta.removeChild(mdln);
                    if (policy.removeTraces) report.change(TRACE + " (UserData: smta/mdln): " + show(old));
                    else report.change("Video Model (UserData: smta/mdln): " + show(old) + " → " + REMOVED);
                } else if (!target.equals(old)) {
                    mdln.setPayload(QtData.encodeRawText(p, target));
                    report.change("Video Model (UserData: smta/mdln): " + show(old) + " → " + target);
                }
            }
        }
    }

    /** 3GPP moov/udta/'auth' whose text names the original device (newer Samsung firmware). */
    private static void editAuthors(Moov m, Mp4BrandPolicy policy, List<String> oldIdentity, EditReport report) {
        String target = policy.authorTarget();
        if (target == null || m.udta == null || !m.udta.isContainer() || oldIdentity.isEmpty()) return;
        for (Mp4Box a : m.udta.childrenOf("auth")) {
            byte[] p = a.payloadBytes();
            String old = QtData.decode3gppText(p);
            if (old == null || !mentions(old, oldIdentity)) continue;
            if (target.length() == 0) {
                m.udta.removeChild(a);
                if (policy.removeTraces) report.change(TRACE + " (UserData: auth): " + old);
                else report.change("Video Author (UserData: auth): " + old + " → " + REMOVED);
            } else if (!target.equals(old)) {
                a.setPayload(QtData.encode3gppText(p, target));
                report.change("Video Author (UserData: auth): " + old + " → " + target);
            }
        }
    }

    /**
     * Lower-case make/model values the file names before the edit (all key tables, user data, smta,
     * iTunes items); "galaxy" is added for Samsung files (their 'auth' holds the marketing name).
     */
    private static List<String> oldIdentity(Moov m) {
        List<String> r = new ArrayList<String>();
        for (QtKeysMeta k : m.allKeys()) {
            for (int i = 1; i <= k.count(); i++) {
                int kind = Mp4BrandPolicy.classify(k.name(i));
                if (Mp4BrandPolicy.isMakeKind(kind) || Mp4BrandPolicy.isModelKind(kind)) addIdentity(r, k.value(i));
            }
        }
        if (m.udta != null && m.udta.isContainer() && !m.udtaUnreadable) {
            for (int f = 0; f < 2; f++) {
                for (String t : UDTA_ATOMS[f]) {
                    for (Mp4Box a : m.udta.childrenOf(t)) addIdentity(r, QtData.decodeText(a.payloadBytes()));
                }
            }
        }
        for (Mp4Box smta : m.smtas) {
            for (Mp4Box mdln : smta.childrenOf("mdln")) addIdentity(r, QtData.decodeRawText(mdln.payloadBytes()));
        }
        for (Mp4Box meta : m.itunesMetas) {
            Mp4Box ilst = meta.child("ilst");
            if (ilst == null || !ilst.isContainer()) continue;
            for (int f = 0; f < 2; f++) {
                for (Mp4Box item : ilst.childrenOf(ILST_ATOMS[f])) addIdentity(r, QtData.itemValue(item));
            }
        }
        if (!m.smtas.isEmpty() || r.contains("samsung")) addIdentity(r, "galaxy");
        return r;
    }

    private static void addIdentity(List<String> list, String s) {
        if (s == null) return;
        String t = s.trim().toLowerCase(Locale.US);
        if (t.length() >= 2 && !list.contains(t)) list.add(t);
    }

    private static boolean mentions(String text, List<String> identity) {
        String t = text.toLowerCase(Locale.US);
        for (String id : identity) {
            if (t.contains(id)) return true;
        }
        return false;
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

        // device identity: Keys com.apple.quicktime.* -> Keys com.android.manufacturer/model ->
        // udta ©mak/©mod -> Samsung smta/mdln (model) -> iTunes items
        String[] found = new String[3];
        List<QtKeysMeta> keyMetas = new ArrayList<QtKeysMeta>();
        if (m.keys != null) keyMetas.add(m.keys);
        keyMetas.addAll(m.udtaKeys);
        int[] androidKind = {Mp4BrandPolicy.ANDROID_MAKE, Mp4BrandPolicy.ANDROID_MODEL};
        boolean location = false;
        boolean locationZero = true;
        for (QtKeysMeta k : keyMetas) {
            for (int f = 0; f < 3; f++) {
                if (found[f] == null) found[f] = keyValue(k, STD_KIND[f]);
            }
            int li = k.find(KEY_LOCATION);
            if (li > 0) {
                location = true;
                if (!isZeroLocation(k.value(li))) locationZero = false;
            }
        }
        for (QtKeysMeta k : keyMetas) {
            for (int f = 0; f < 2; f++) {
                if (found[f] == null) found[f] = keyValue(k, androidKind[f]);
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
        for (Mp4Box smta : m.smtas) {
            Mp4Box mdln = smta.child("mdln");
            if (mdln != null && found[1] == null) found[1] = nonEmpty(QtData.decodeRawText(mdln.payloadBytes()));
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
        // lens: track-level key first (where iPhones write it), then moov-level tables
        List<QtKeysMeta> lensMetas = new ArrayList<QtKeysMeta>(m.trakKeys);
        lensMetas.addAll(keyMetas);
        for (QtKeysMeta k : lensMetas) {
            if (info.lensModel == null) info.lensModel = keyValue(k, Mp4BrandPolicy.APPLE_LENS);
            if (info.focalLength35mm == 0) {
                int fi = findKind(k, Mp4BrandPolicy.APPLE_FOCAL35);
                Long f = fi > 0 ? k.number(fi) : null;
                if (f != null && f.longValue() > 0 && f.longValue() <= 100000) info.focalLength35mm = f.intValue();
            }
        }
        info.gpsStatus = !location ? "yok" : (locationZero ? "maskelenmiş" : "var");
    }

    /** Trimmed value of the first non-empty key of this kind, or null. */
    private static String keyValue(QtKeysMeta k, int kind) {
        for (int i = 1; i <= k.count(); i++) {
            if (Mp4BrandPolicy.classify(k.name(i)) != kind) continue;
            String v = nonEmpty(k.value(i));
            if (v != null) return v;
        }
        return null;
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
