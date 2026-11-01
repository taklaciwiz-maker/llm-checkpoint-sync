package com.metakamera.core.isobmff;

import com.metakamera.core.ByteSource;
import com.metakamera.core.ByteUtils;
import com.metakamera.core.EditReport;
import com.metakamera.core.EditSpec;
import com.metakamera.core.ProgressListener;
import com.metakamera.core.UnsupportedFormatException;
import com.metakamera.core.icc.IccProfileEditor;
import com.metakamera.core.tiff.ExifTiff;
import com.metakamera.core.xmp.XmpEditor;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Plans and writes one HEIF/AVIF edit.
 *
 * <p>Planning (everything in memory, nothing written yet): parse, edit the Exif / XMP item
 * payloads and the ICC 'colr' properties, decide where every new payload goes (in place, appended
 * to idat, or stored in a new 'mdat' inserted right after 'meta'), re-serialize 'meta' and recompute every
 * construction-method-0 offset. Writing then streams the input once, applying in-place patches
 * and zero-fills on the fly.
 */
final class HeifPlan {

    static final int KIND_EXIF = 1;
    static final int KIND_XMP = 2;

    private static final int KEEP = 0;
    private static final int APPEND = 1;
    private static final int FIXED = 2;

    /** Per-iloc-entry bookkeeping (original values are needed on every layout iteration). */
    private static final class ItemPlan {
        final HeifIloc.Item item;
        final long origBase;
        final long[] origOffsets;
        /** Ranges the item occupies in the ORIGINAL file / idat (clipped, for overlap tests). */
        final List<long[]> occupied;
        int kind = KEEP;
        long appendRel;

        ItemPlan(HeifIloc.Item item, List<long[]> occupied) {
            this.item = item;
            this.origBase = item.baseOffset;
            this.origOffsets = new long[item.extents.size()];
            for (int i = 0; i < origOffsets.length; i++) origOffsets[i] = item.extents.get(i).offset;
            this.occupied = occupied;
        }
    }

    /** Items sharing identical data ranges are edited once. */
    private static final class Group {
        final int kind;
        final int cm;
        final long[][] ranges;
        final String label;
        final HeifMeta.Infe infe;
        final List<HeifIloc.Item> items = new ArrayList<HeifIloc.Item>();

        Group(int kind, int cm, long[][] ranges, String label, HeifMeta.Infe infe) {
            this.kind = kind;
            this.cm = cm;
            this.ranges = ranges;
            this.label = label;
            this.infe = infe;
        }
    }

    /** Bytes written over (data != null) or zero-filled (data == null) in the original file. */
    private static final class Patch {
        final long pos;
        final long len;
        final byte[] data;

        Patch(long pos, long len, byte[] data) {
            this.pos = pos;
            this.len = len;
            this.data = data;
        }
    }

    /** Result of one meta layout pass. */
    private final class Layout {
        long newMetaSize;
        long delta;
        long blockPos;
        long payloadPos;
        final Map<BoxHeader, Integer> newRel = new HashMap<BoxHeader, Integer>();

        /** New position of original byte position {@code p}. */
        long map(long p) throws UnsupportedFormatException {
            long ms = f.metaStart();
            long me = f.metaEnd();
            if (p < ms) return p;
            if (p < me) return ms + mapInMeta((int) (p - ms));
            return p + delta + appendBlockLen;
        }

        private long mapInMeta(int rel) throws UnsupportedFormatException {
            if (rel < m.childrenStart) return rel;
            if (rel >= m.childrenEnd) return newMetaSize - (m.raw.length - rel);
            for (int i = 0; i < m.children.size(); i++) {
                BoxHeader c = m.children.get(i);
                if (rel >= c.offset && rel < c.end()) {
                    if (!stable(c)) {
                        throw new UnsupportedFormatException("HEIF yapısı desteklenmiyor (öğe verisi meta içinde)");
                    }
                    return newRel.get(c) + (rel - c.offset);
                }
            }
            return rel;
        }
    }

    final HeifFile f;
    final HeifMeta m;
    private final EditSpec spec;
    final EditReport rep = new EditReport();

    private final List<ItemPlan> plans = new ArrayList<ItemPlan>();
    private final List<Patch> patches = new ArrayList<Patch>();
    private final List<byte[]> appended = new ArrayList<byte[]>();
    private long appendedTotal;
    private long appendBlockLen;
    private int appendHeaderLen;
    /** Edited idat payload (grows when payloads are appended), null when idat is unchanged. */
    private byte[] idat;
    private int idatLen;
    private final Map<BoxHeader, byte[]> propRepl = new LinkedHashMap<BoxHeader, byte[]>();
    private HeifIloc.Item newExif;
    private byte[] origIlocSerialized;

    private boolean changed;

    // results of finish()
    private byte[] newMeta;
    private Layout layout;

    private HeifPlan(HeifFile f, EditSpec spec) {
        this.f = f;
        this.m = f.meta;
        this.spec = spec;
    }

    static HeifPlan plan(ByteSource src, EditSpec spec) throws IOException {
        HeifFile f = HeifFile.open(src);
        HeifPlan p = new HeifPlan(f, spec);
        if (spec != null) p.build();
        return p;
    }

    // ================================================================== planning

    private void build() throws IOException {
        if (m.iloc != null) {
            origIlocSerialized = m.iloc.serialize();
            for (int i = 0; i < m.iloc.items.size(); i++) {
                HeifIloc.Item it = m.iloc.items.get(i);
                plans.add(new ItemPlan(it, occupied(it)));
            }
        }
        int exifCount = editItems();
        editIcc();
        createExif(exifCount);
        if (!changed) return;
        finish();
    }

    /** Clipped ranges an item occupies (lenient: used only to protect other data). */
    private List<long[]> occupied(HeifIloc.Item it) {
        List<long[]> r = new ArrayList<long[]>();
        int cm = it.constructionMethod();
        if (it.dataRefIndex != 0 || (cm != 0 && cm != 1)) return r;
        long limit;
        if (cm == 1) {
            int[] ir = m.idatRange();
            if (ir == null) return r;
            limit = ir[1] - ir[0];
        } else {
            limit = f.len;
        }
        for (int i = 0; i < it.extents.size(); i++) {
            HeifIloc.Extent x = it.extents.get(i);
            if (it.baseOffset > Long.MAX_VALUE - x.offset) continue;
            long s = it.baseOffset + x.offset;
            if (s >= limit) continue;
            long e;
            if (x.length == 0) {
                BoxHeader b = cm == 0 ? f.topBoxAt(s) : null;
                e = b != null ? b.end() : limit;
            } else {
                e = x.length > limit - s ? limit : s + x.length;
            }
            r.add(new long[]{s, e});
        }
        return r;
    }

    private ItemPlan planOf(HeifIloc.Item it) {
        for (int i = 0; i < plans.size(); i++) {
            if (plans.get(i).item == it) return plans.get(i);
        }
        return null;
    }

    static int kindOf(HeifMeta.Infe e) {
        if (e.version >= 2 && "Exif".equals(e.itemType)) return KIND_EXIF;
        boolean mime = e.version < 2 || "mime".equals(e.itemType);
        if (mime && e.contentType != null
                && e.contentType.trim().toLowerCase(Locale.ROOT).startsWith("application/rdf+xml")) {
            return KIND_XMP;
        }
        return 0;
    }

    /** Edits Exif and XMP items; returns the number of Exif items present (readable or not). */
    private int editItems() throws IOException {
        int exifCount = 0;
        Map<String, Group> groups = new LinkedHashMap<String, Group>();
        for (int i = 0; i < m.infes.size(); i++) {
            HeifMeta.Infe e = m.infes.get(i);
            int kind = kindOf(e);
            if (kind == 0) continue;
            if (kind == KIND_EXIF) exifCount++;
            if (kind == KIND_XMP && !spec.editXmp) continue;
            String label = kind == KIND_EXIF ? "Exif" : "XMP";
            if (e.protectionIndex != 0) {
                rep.warn("HEIF " + label + " öğesi şifreli; değiştirilmedi");
                continue;
            }
            HeifIloc.Item it = m.iloc == null ? null : m.iloc.find(e.id);
            if (it == null) {
                rep.warn("HEIF " + label + " öğesinin konumu bulunamadı; değiştirilmedi");
                continue;
            }
            long[][] r = f.ranges(it);
            int cm = it.constructionMethod();
            if (r == null || (cm == 0 && f.touchesMeta(r))) {
                rep.warn("HEIF " + label + " verisi okunamadı veya desteklenmeyen konumda; değiştirilmedi");
                continue;
            }
            if (HeifFile.total(r) > HeifFile.ITEM_MAX) {
                rep.warn("HEIF " + label + " verisi çok büyük; değiştirilmedi");
                continue;
            }
            StringBuilder key = new StringBuilder().append(kind).append('|').append(cm);
            for (int k = 0; k < r.length; k++) key.append('|').append(r[k][0]).append('-').append(r[k][1]);
            Group g = groups.get(key.toString());
            if (g == null) {
                g = new Group(kind, cm, r, label, e);
                groups.put(key.toString(), g);
            }
            if (!g.items.contains(it)) g.items.add(it);
        }
        for (Group g : groups.values()) {
            byte[] data = f.read(g.cm, g.ranges);
            byte[] np = g.kind == KIND_EXIF ? editExif(data) : editXmp(data, g.infe);
            if (np == null) continue;
            if (g.cm == 0) placeInFile(g, np);
            else placeInIdat(g, np);
            changed = true;
        }
        return exifCount;
    }

    // ------------------------------------------------------------------ Exif

    static boolean isTiff(byte[] b, int i) {
        if (i < 0 || i + 8 > b.length) return false;
        return (b[i] == 'I' && b[i + 1] == 'I' && b[i + 2] == 42 && b[i + 3] == 0)
                || (b[i] == 'M' && b[i + 1] == 'M' && b[i + 2] == 0 && b[i + 3] == 42);
    }

    /** Start of the TIFF header inside an Exif item payload, or -1. */
    static int tiffStart(byte[] p) {
        if (p.length < 12) return -1;
        long off = ByteUtils.u32be(p, 0);
        if (off <= p.length - 12 && isTiff(p, 4 + (int) off)) return 4 + (int) off;
        // tolerate writers that got the offset wrong
        if (p.length >= 18 && p[4] == 'E' && p[5] == 'x' && p[6] == 'i' && p[7] == 'f' && p[8] == 0 && p[9] == 0
                && isTiff(p, 10)) {
            return 10;
        }
        for (int i = 4; i + 8 <= p.length && i < 4 + 64; i++) {
            if (isTiff(p, i)) return i;
        }
        return -1;
    }

    private byte[] editExif(byte[] data) throws IOException {
        int ts = tiffStart(data);
        if (ts < 0) {
            rep.warn("HEIF Exif verisi okunamadı (TIFF başlığı yok); değiştirilmedi");
            return null;
        }
        byte[] tiff = Arrays.copyOfRange(data, ts, data.length);
        byte[] nt;
        try {
            nt = ExifTiff.edit(tiff, spec, Integer.MAX_VALUE, rep);
        } catch (IOException e) {
            rep.warn("HEIF Exif verisi bozuk; değiştirilmedi");
            return null;
        }
        if (nt == null || nt == tiff || Arrays.equals(nt, tiff)) return null;
        byte[] r = new byte[ts + nt.length];
        System.arraycopy(data, 0, r, 0, ts);
        System.arraycopy(nt, 0, r, ts, nt.length);
        return r;
    }

    // ------------------------------------------------------------------ XMP

    private byte[] editXmp(byte[] data, HeifMeta.Infe infe) {
        String enc = infe.contentEncoding == null ? "" : infe.contentEncoding.trim().toLowerCase(Locale.ROOT);
        byte[] body = data;
        int wrap = -1; // -1 plain, 0 zlib, 1 raw deflate
        if (enc.length() > 0) {
            if (!"deflate".equals(enc)) {
                rep.warn("HEIF XMP verisi desteklenmeyen biçimde sıkıştırılmış (" + enc + "); değiştirilmedi");
                return null;
            }
            body = inflate(data, false);
            wrap = 0;
            if (body == null) {
                body = inflate(data, true);
                wrap = 1;
            }
            if (body == null) {
                rep.warn("HEIF XMP verisi açılamadı; değiştirilmedi");
                return null;
            }
        }
        int n = body.length;
        while (n > 0 && body[n - 1] == 0) n--;
        String s = decodeUtf8(body, n);
        if (s == null) {
            rep.warn("HEIF XMP verisi UTF-8 değil; değiştirilmedi");
            return null;
        }
        String ns = XmpEditor.edit(s, spec, rep);
        if (ns == null || ns == s || ns.equals(s)) return null;
        byte[] nb = ns.getBytes(ByteUtils.UTF_8);
        byte[] full = Arrays.copyOf(nb, nb.length + (body.length - n));
        if (wrap >= 0) full = deflate(full, wrap == 1);
        return full;
    }

    static String decodeUtf8(byte[] b, int n) {
        try {
            return ByteUtils.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(b, 0, n)).toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }

    static byte[] inflate(byte[] in, boolean nowrap) {
        Inflater inf = new Inflater(nowrap);
        try {
            if (nowrap) {
                byte[] x = Arrays.copyOf(in, in.length + 1); // raw inflate wants one dummy byte
                inf.setInput(x);
            } else {
                inf.setInput(in);
            }
            ByteArrayOutputStream o = new ByteArrayOutputStream(Math.max(64, in.length * 4));
            byte[] buf = new byte[16384];
            int stalls = 0;
            while (!inf.finished()) {
                int k = inf.inflate(buf);
                if (k == 0) {
                    if (inf.needsInput() || inf.needsDictionary() || ++stalls > 4) return null;
                    continue;
                }
                o.write(buf, 0, k);
                if (o.size() > HeifFile.ITEM_MAX) return null;
            }
            return o.toByteArray();
        } catch (DataFormatException e) {
            return null;
        } catch (RuntimeException e) {
            return null;
        } finally {
            inf.end();
        }
    }

    static byte[] deflate(byte[] in, boolean nowrap) {
        Deflater d = new Deflater(Deflater.BEST_COMPRESSION, nowrap);
        try {
            d.setInput(in);
            d.finish();
            ByteArrayOutputStream o = new ByteArrayOutputStream(in.length / 2 + 64);
            byte[] buf = new byte[16384];
            while (!d.finished()) {
                int k = d.deflate(buf);
                o.write(buf, 0, k);
            }
            return o.toByteArray();
        } finally {
            d.end();
        }
    }

    // ------------------------------------------------------------------ ICC

    private void editIcc() {
        if (!spec.editIcc || spec.icc == null) return;
        if (!m.iprpParsed) {
            for (int i = 0; i < m.warnings.size(); i++) rep.warn(m.warnings.get(i));
            return;
        }
        for (int i = 0; i < m.ipcoProps.size(); i++) {
            BoxHeader c = m.ipcoProps.get(i);
            if (!"colr".equals(c.type) || c.payloadSize() < 4) continue;
            int ps = (int) c.payloadOffset();
            String ct = ByteUtils.fourcc(m.raw, ps);
            if (!"prof".equals(ct) && !"rICC".equals(ct)) continue;
            byte[] prof = Arrays.copyOfRange(m.raw, ps + 4, (int) c.end());
            byte[] np;
            try {
                np = IccProfileEditor.edit(prof, spec.icc, rep);
            } catch (IOException e) {
                rep.warn("HEIF ICC profili düzenlenemedi; değiştirilmedi");
                continue;
            }
            if (np == null || np == prof || Arrays.equals(np, prof)) continue;
            byte[] payload = new byte[4 + np.length];
            System.arraycopy(m.raw, ps, payload, 0, 4);
            System.arraycopy(np, 0, payload, 4, np.length);
            propRepl.put(c, HeifBuf.box("colr", payload, c.largeSize));
            changed = true;
        }
    }

    // ------------------------------------------------------------------ new Exif item

    static boolean nonEmpty(String s) {
        return s != null && s.replace("\0", "").length() > 0;
    }

    /** {width, height} from the 'ispe' property associated with {@code id}, or null. */
    static int[] ispe(HeifMeta m, long id) {
        List<Integer> l = m.assoc.get(id);
        if (l == null) return null;
        for (int i = 0; i < l.size(); i++) {
            BoxHeader b = m.property(l.get(i));
            if (b == null || !"ispe".equals(b.type) || b.payloadSize() < 12) continue;
            int p = (int) b.payloadOffset();
            long w = ByteUtils.u32be(m.raw, p + 4);
            long h = ByteUtils.u32be(m.raw, p + 8);
            if (w > Integer.MAX_VALUE || h > Integer.MAX_VALUE) return null;
            return new int[]{(int) w, (int) h};
        }
        return null;
    }

    private void createExif(int exifCount) {
        if (exifCount > 0 || !spec.createExifIfMissing) return;
        if (!nonEmpty(spec.make) && !nonEmpty(spec.model)) return;
        if (m.primaryId < 0) {
            rep.warn("HEIF birincil görüntüsü (pitm) bulunamadı; EXIF eklenemedi");
            return;
        }
        if (m.iinfBox == null || m.iloc == null) {
            rep.warn("HEIF yapısı eksik (iinf/iloc); EXIF eklenemedi");
            return;
        }
        if (!m.irefParsed) {
            rep.warn("HEIF iref kutusu bozuk; EXIF eklenemedi");
            return;
        }
        long id = m.maxId + 1;
        if (id > 0xFFFFFFFFL) {
            rep.warn("HEIF öğe numarası sınırı aşıldı; EXIF eklenemedi");
            return;
        }
        int[] wh = ispe(m, m.primaryId);
        byte[] tiff = ExifTiff.createMinimal(spec, wh == null ? 0 : wh[0], wh == null ? 0 : wh[1]);
        byte[] payload = new byte[10 + tiff.length];
        payload[3] = 6;
        payload[4] = 'E';
        payload[5] = 'x';
        payload[6] = 'i';
        payload[7] = 'f';
        System.arraycopy(tiff, 0, payload, 10, tiff.length);

        HeifIloc.Item it = new HeifIloc.Item();
        it.id = id;
        it.extents.add(new HeifIloc.Extent(0, 0, payload.length));
        m.iloc.items.add(it);
        ItemPlan ip = new ItemPlan(it, new ArrayList<long[]>());
        ip.kind = APPEND;
        ip.appendRel = append(payload);
        plans.add(ip);
        newExif = it;
        changed = true;

        rep.change("HEIF Exif öğesi oluşturuldu");
        created("Make", spec.make);
        created("Model", spec.model);
        created("Software", spec.software);
        created("HostComputer", spec.hostComputer);
        created("LensMake", spec.lensMake);
        created("LensModel", spec.lensModel);
    }

    private void created(String name, String v) {
        if (nonEmpty(v)) rep.change("EXIF " + name + " eklendi: " + v.replace("\0", ""));
    }

    // ------------------------------------------------------------------ placement

    private long append(byte[] payload) {
        long rel = appendedTotal;
        appended.add(payload);
        appendedTotal += payload.length;
        return rel;
    }

    private void patch(long pos, byte[] data) {
        if (data.length > 0) patches.add(new Patch(pos, data.length, data));
    }

    private void zero(long start, long end) {
        if (end > start) patches.add(new Patch(start, end - start, null));
    }

    /** Ranges (original file coordinates) that must never be overwritten for a group. */
    private List<long[]> fileGuards(Group g) {
        List<long[]> o = new ArrayList<long[]>();
        for (int i = 0; i < plans.size(); i++) {
            ItemPlan p = plans.get(i);
            if (g.items.contains(p.item) || p.item.constructionMethod() != 0 || p.item.dataRefIndex != 0) continue;
            o.addAll(p.occupied);
        }
        for (int i = 0; i < f.top.size(); i++) {
            BoxHeader b = f.top.get(i);
            o.add(new long[]{b.offset, b.offset + b.headerSize});
        }
        o.add(new long[]{f.metaStart(), f.metaEnd()});
        return o;
    }

    private List<long[]> idatGuards(Group g) {
        List<long[]> o = new ArrayList<long[]>();
        for (int i = 0; i < plans.size(); i++) {
            ItemPlan p = plans.get(i);
            if (g.items.contains(p.item) || p.item.constructionMethod() != 1 || p.item.dataRefIndex != 0) continue;
            o.addAll(p.occupied);
        }
        return o;
    }

    static boolean intersects(long[][] a, List<long[]> b) {
        for (int i = 0; i < a.length; i++) {
            for (int k = 0; k < b.size(); k++) {
                if (a[i][0] < b.get(k)[1] && b.get(k)[0] < a[i][1]) return true;
            }
        }
        return false;
    }

    /** a minus b (interval subtraction). */
    static List<long[]> subtract(long[][] a, List<long[]> b) {
        List<long[]> cur = new ArrayList<long[]>();
        for (int i = 0; i < a.length; i++) cur.add(new long[]{a[i][0], a[i][1]});
        for (int k = 0; k < b.size(); k++) {
            long cs = b.get(k)[0];
            long ce = b.get(k)[1];
            List<long[]> next = new ArrayList<long[]>();
            for (int i = 0; i < cur.size(); i++) {
                long s = cur.get(i)[0];
                long e = cur.get(i)[1];
                if (ce <= s || cs >= e) {
                    next.add(cur.get(i));
                    continue;
                }
                if (cs > s) next.add(new long[]{s, cs});
                if (ce < e) next.add(new long[]{ce, e});
            }
            cur = next;
        }
        return cur;
    }

    private static long total(List<long[]> l) {
        long t = 0;
        for (int i = 0; i < l.size(); i++) t += l.get(i)[1] - l.get(i)[0];
        return t;
    }

    private void placeInFile(Group g, byte[] np) {
        List<long[]> guards = fileGuards(g);
        long oldLen = HeifFile.total(g.ranges);
        if (g.ranges.length == 1 && np.length <= oldLen && !intersects(g.ranges, guards)) {
            long s = g.ranges[0][0];
            patch(s, np);
            zero(s + np.length, g.ranges[0][1]);
            for (int i = 0; i < g.items.size(); i++) g.items.get(i).extents.get(0).length = np.length;
            return;
        }
        long rel = append(np);
        List<long[]> z = subtract(g.ranges, guards);
        if (total(z) < oldLen) {
            rep.warn("Eski " + g.label + " verisinin bir kısmı başka verilerle paylaşıldığı için silinemedi");
        }
        for (int i = 0; i < z.size(); i++) zero(z.get(i)[0], z.get(i)[1]);
        for (int i = 0; i < g.items.size(); i++) {
            HeifIloc.Item it = g.items.get(i);
            it.extents.clear();
            it.extents.add(new HeifIloc.Extent(0, 0, np.length));
            ItemPlan ip = planOf(it);
            ip.kind = APPEND;
            ip.appendRel = rel;
        }
    }

    private void ensureIdat() {
        if (idat != null) return;
        int[] ir = m.idatRange();
        idatLen = ir[1] - ir[0];
        idat = Arrays.copyOfRange(m.raw, ir[0], ir[1]);
    }

    private void placeInIdat(Group g, byte[] np) throws UnsupportedFormatException {
        ensureIdat();
        List<long[]> guards = idatGuards(g);
        long oldLen = HeifFile.total(g.ranges);
        if (g.ranges.length == 1 && np.length <= oldLen && !intersects(g.ranges, guards)) {
            int s = (int) g.ranges[0][0];
            System.arraycopy(np, 0, idat, s, np.length);
            Arrays.fill(idat, s + np.length, (int) g.ranges[0][1], (byte) 0);
            for (int i = 0; i < g.items.size(); i++) g.items.get(i).extents.get(0).length = np.length;
            return;
        }
        if ((long) idatLen + np.length > HeifFile.META_MAX) {
            throw new UnsupportedFormatException("HEIF idat kutusu çok büyük");
        }
        int pos = idatLen;
        if (idat.length < idatLen + np.length) idat = Arrays.copyOf(idat, idatLen + np.length);
        System.arraycopy(np, 0, idat, pos, np.length);
        idatLen += np.length;
        List<long[]> z = subtract(g.ranges, guards);
        if (total(z) < oldLen) {
            rep.warn("Eski " + g.label + " verisinin bir kısmı başka verilerle paylaşıldığı için silinemedi");
        }
        for (int i = 0; i < z.size(); i++) Arrays.fill(idat, (int) z.get(i)[0], (int) z.get(i)[1], (byte) 0);
        for (int i = 0; i < g.items.size(); i++) {
            HeifIloc.Item it = g.items.get(i);
            it.extents.clear();
            it.extents.add(new HeifIloc.Extent(0, 0, np.length));
            setLocation(it, pos);
            planOf(it).kind = FIXED;
        }
    }

    /**
     * Points a single-extent item at {@code pos}: through base_offset when the iloc has no
     * extent_offset field (offset_size 0, e.g. GPAC), else through extent_offset (base 0), so
     * that no field has to grow.
     */
    private void setLocation(HeifIloc.Item it, long pos) {
        if (m.iloc.offsetSize == 0 && m.iloc.baseOffsetSize > 0) {
            it.baseOffset = pos;
            it.extents.get(0).offset = 0;
        } else {
            it.baseOffset = 0;
            it.extents.get(0).offset = pos;
        }
    }

    // ================================================================== meta re-serialization

    private byte[] iinfNew;
    private byte[] irefNew;
    private byte[] iprpNew;
    private byte[] idatNew;
    private byte[] ilocRaw;

    private boolean stable(BoxHeader c) {
        if (c == m.ilocBox) return false;
        if (c == m.iinfBox && iinfNew != null) return false;
        if (c == m.irefBox && irefNew != null) return false;
        if (c == m.iprpBox && iprpNew != null) return false;
        return true; // verbatim, or idat (whose old bytes keep their position)
    }

    private void finish() throws UnsupportedFormatException {
        if (newExif != null) {
            iinfNew = buildIinf();
            irefNew = buildIref();
        }
        if (!propRepl.isEmpty()) iprpNew = buildIprp();
        if (idat != null) idatNew = HeifBuf.box("idat", Arrays.copyOf(idat, idatLen), m.idatBox.largeSize);
        ilocRaw = m.ilocBox == null ? null : m.rawBox(m.ilocBox);

        // New payloads go into a new 'mdat' inserted right after 'meta' (not at the end of the
        // file: motion-photo videos such as a trailing 'mpvd' box are located relative to EOF).
        if (appendedTotal > 0) {
            appendHeaderLen = appendedTotal + 8 > 0xFFFFFFFFL ? 16 : 8;
            appendBlockLen = appendHeaderLen + appendedTotal;
        }

        int ilocLen = ilocRaw == null ? 0 : ilocRaw.length;
        boolean forceSerialize = false;
        byte[] ilocBytes = null;
        for (int iter = 0; ; iter++) {
            Layout l = layout(ilocLen);
            assignOffsets(l);
            byte[] ib = null;
            if (m.iloc != null) {
                m.iloc.growToFit();
                ib = m.iloc.serialize();
                if (!forceSerialize && Arrays.equals(ib, origIlocSerialized)) ib = ilocRaw;
            }
            int n = ib == null ? 0 : ib.length;
            if (n == ilocLen) {
                ilocBytes = ib;
                layout = l;
                break;
            }
            ilocLen = n;
            if (iter >= 8) forceSerialize = true;
            if (iter >= 40) throw new UnsupportedFormatException("HEIF iloc yerleşimi hesaplanamadı");
        }
        newMeta = assemble(ilocBytes, layout);
        if (newMeta.length != layout.newMetaSize) throw new UnsupportedFormatException("HEIF meta boyutu tutarsız");
    }

    /** Replacement bytes of a meta child, or null when it is copied verbatim. */
    private byte[] childBytes(BoxHeader c, byte[] ilocBytes) {
        if (c == m.ilocBox) return ilocBytes;
        if (c == m.iinfBox && iinfNew != null) return iinfNew;
        if (c == m.irefBox && irefNew != null) return irefNew;
        if (c == m.iprpBox && iprpNew != null) return iprpNew;
        if (c == m.idatBox && idatNew != null) return idatNew;
        return null;
    }

    private long childLen(BoxHeader c, int ilocLen) {
        if (c == m.ilocBox) return ilocLen;
        byte[] b = childBytes(c, null);
        return b != null ? b.length : c.size;
    }

    private boolean insertIrefAfter(BoxHeader c) {
        return irefNew != null && m.irefBox == null && c == m.iinfBox;
    }

    private Layout layout(int ilocLen) {
        Layout l = new Layout();
        long pos = m.childrenStart;
        for (int i = 0; i < m.children.size(); i++) {
            BoxHeader c = m.children.get(i);
            l.newRel.put(c, (int) pos);
            pos += childLen(c, ilocLen);
            if (insertIrefAfter(c)) pos += irefNew.length;
        }
        pos += m.raw.length - m.childrenEnd;
        l.newMetaSize = pos;
        l.delta = pos - m.raw.length;
        l.blockPos = f.metaEnd() + l.delta;
        l.payloadPos = l.blockPos + appendHeaderLen;
        return l;
    }

    private void assignOffsets(Layout l) throws UnsupportedFormatException {
        for (int i = 0; i < plans.size(); i++) {
            ItemPlan p = plans.get(i);
            HeifIloc.Item it = p.item;
            if (p.kind == APPEND) {
                setLocation(it, l.payloadPos + p.appendRel);
                continue;
            }
            if (p.kind != KEEP || it.constructionMethod() != 0 || it.dataRefIndex != 0) continue;
            mapItem(p, l);
        }
    }

    private static void mapItem(ItemPlan p, Layout l) throws UnsupportedFormatException {
        HeifIloc.Item it = p.item;
        long base = p.origBase;
        long[] offs = p.origOffsets;
        int n = offs.length;
        it.baseOffset = base;
        for (int k = 0; k < n; k++) it.extents.get(k).offset = offs[k];
        if (n == 0) return;
        long[] mapped = new long[n];
        long s = 0;
        boolean same = true;
        for (int k = 0; k < n; k++) {
            if (base > Long.MAX_VALUE - offs[k]) return; // nonsense values: keep them as they are
            long abs = base + offs[k];
            mapped[k] = l.map(abs);
            long d = mapped[k] - abs;
            if (k == 0) s = d;
            else if (d != s) same = false;
        }
        if (same) {
            if (s == 0) return;
            if (base != 0 && base + s >= 0) {
                it.baseOffset = base + s;
                return;
            }
            boolean ok = true;
            for (int k = 0; k < n; k++) if (offs[k] + s < 0) ok = false;
            if (ok) {
                for (int k = 0; k < n; k++) it.extents.get(k).offset = offs[k] + s;
                return;
            }
        }
        it.baseOffset = 0;
        for (int k = 0; k < n; k++) it.extents.get(k).offset = mapped[k];
    }

    private byte[] normalized(BoxHeader c) {
        byte[] b = m.rawBox(c);
        if (c.sizeToEnd) ByteUtils.put32be(b, 0, b.length);
        return b;
    }

    private byte[] assemble(byte[] ilocBytes, Layout l) {
        ByteArrayOutputStream o = new ByteArrayOutputStream((int) l.newMetaSize);
        if (m.largeHeader) {
            HeifBuf.w32(o, 1);
            o.write('m');
            o.write('e');
            o.write('t');
            o.write('a');
            HeifBuf.wN(o, l.newMetaSize, 8);
            o.write(m.raw, 16, m.childrenStart - 16);
        } else {
            HeifBuf.w32(o, l.newMetaSize);
            o.write(m.raw, 4, m.childrenStart - 4);
        }
        for (int i = 0; i < m.children.size(); i++) {
            BoxHeader c = m.children.get(i);
            byte[] b = childBytes(c, ilocBytes);
            if (b == null) b = normalized(c);
            o.write(b, 0, b.length);
            if (insertIrefAfter(c)) o.write(irefNew, 0, irefNew.length);
        }
        o.write(m.raw, m.childrenEnd, m.raw.length - m.childrenEnd);
        return o.toByteArray();
    }

    private byte[] buildIinf() throws UnsupportedFormatException {
        BoxHeader b = m.iinfBox;
        HeifBuf r = new HeifBuf(m.raw, (int) b.payloadOffset(), (int) b.end(), "iinf");
        r.skip(4);
        if (m.iinfVersion == 0) r.u16();
        else r.u32();
        int entries = r.pos;
        long count = m.iinfCount + 1;
        int ver = m.iinfVersion;
        if (ver == 0 && count > 0xFFFF) ver = 1;
        if (count > 0xFFFFFFFFL) throw new UnsupportedFormatException("HEIF öğe sayısı sınırı aşıldı");
        ByteArrayOutputStream o = new ByteArrayOutputStream((int) b.size + 32);
        o.write(ver);
        o.write(m.iinfFlags >>> 16);
        HeifBuf.w16(o, m.iinfFlags);
        if (ver == 0) HeifBuf.w16(o, (int) count);
        else HeifBuf.w32(o, count);
        List<BoxHeader> kids = HeifMeta.children(m.raw, entries, (int) b.end(), "iinf");
        for (int i = 0; i < kids.size(); i++) {
            byte[] k = normalized(kids.get(i));
            o.write(k, 0, k.length);
        }
        int tail = kids.isEmpty() ? entries : (int) kids.get(kids.size() - 1).end();
        o.write(m.raw, tail, (int) b.end() - tail);
        // the new infe: version 2 (or 3 for 32-bit IDs), hidden, item_type 'Exif', empty name
        ByteArrayOutputStream e = new ByteArrayOutputStream(16);
        boolean v3 = newExif.id > 0xFFFF;
        if (v3) HeifBuf.w32(e, newExif.id);
        else HeifBuf.w16(e, (int) newExif.id);
        HeifBuf.w16(e, 0);
        e.write('E');
        e.write('x');
        e.write('i');
        e.write('f');
        e.write(0);
        byte[] infe = HeifBuf.box("infe", HeifBuf.full(v3 ? 3 : 2, 1, e.toByteArray()), false);
        o.write(infe, 0, infe.length);
        return HeifBuf.box("iinf", o.toByteArray(), b.largeSize);
    }

    private static byte[] refBox(int version, String type, long from, long[] to) {
        ByteArrayOutputStream o = new ByteArrayOutputStream(8 + to.length * 4);
        if (version == 0) HeifBuf.w16(o, (int) from);
        else HeifBuf.w32(o, from);
        HeifBuf.w16(o, to.length);
        for (int i = 0; i < to.length; i++) {
            if (version == 0) HeifBuf.w16(o, (int) to[i]);
            else HeifBuf.w32(o, to[i]);
        }
        return HeifBuf.box(type, o.toByteArray(), false);
    }

    private byte[] buildIref() {
        long from = newExif.id;
        long to = m.primaryId;
        boolean big = from > 0xFFFF || to > 0xFFFF;
        int version;
        int flags;
        if (m.irefBox == null) {
            version = big ? 1 : 0;
            flags = 0;
        } else {
            version = big ? 1 : m.irefVersion;
            flags = m.irefFlags;
        }
        ByteArrayOutputStream o = new ByteArrayOutputStream(64);
        for (int i = 0; i < m.refs.size(); i++) {
            HeifMeta.Ref r = m.refs.get(i);
            byte[] rb = refBox(version, r.type, r.from, r.to);
            o.write(rb, 0, rb.length);
        }
        byte[] cdsc = refBox(version, "cdsc", from, new long[]{to});
        o.write(cdsc, 0, cdsc.length);
        return HeifBuf.box("iref", HeifBuf.full(version, flags, o.toByteArray()),
                m.irefBox != null && m.irefBox.largeSize);
    }

    private byte[] buildIprp() {
        BoxHeader ipco = m.ipcoBox;
        ByteArrayOutputStream pc = new ByteArrayOutputStream((int) ipco.size + 1024);
        for (int i = 0; i < m.ipcoProps.size(); i++) {
            BoxHeader c = m.ipcoProps.get(i);
            byte[] b = propRepl.get(c);
            if (b == null) b = normalized(c);
            pc.write(b, 0, b.length);
        }
        int tail = m.ipcoProps.isEmpty() ? (int) ipco.payloadOffset()
                : (int) m.ipcoProps.get(m.ipcoProps.size() - 1).end();
        pc.write(m.raw, tail, (int) ipco.end() - tail);
        byte[] newIpco = HeifBuf.box("ipco", pc.toByteArray(), ipco.largeSize);

        BoxHeader iprp = m.iprpBox;
        ByteArrayOutputStream pr = new ByteArrayOutputStream((int) iprp.size + 1024);
        for (int i = 0; i < m.iprpChildren.size(); i++) {
            BoxHeader c = m.iprpChildren.get(i);
            byte[] b = c == ipco ? newIpco : normalized(c);
            pr.write(b, 0, b.length);
        }
        int t2 = m.iprpChildren.isEmpty() ? (int) iprp.payloadOffset()
                : (int) m.iprpChildren.get(m.iprpChildren.size() - 1).end();
        pr.write(m.raw, t2, (int) iprp.end() - t2);
        return HeifBuf.box("iprp", pr.toByteArray(), iprp.largeSize);
    }

    // ================================================================== writing

    long outputLength() {
        if (!changed) return f.len;
        return f.len + layout.delta + appendBlockLen;
    }

    void write(OutputStream out, ProgressListener listener) throws IOException {
        ByteSource src = f.src;
        if (!changed) {
            ByteUtils.copyRange(src, 0, f.len, out, listener, 0, f.len);
            if (listener != null && f.len == 0) listener.onProgress(0, 0);
            return;
        }
        Collections.sort(patches, new Comparator<Patch>() {
            @Override
            public int compare(Patch a, Patch b) {
                return a.pos < b.pos ? -1 : (a.pos == b.pos ? 0 : 1);
            }
        });
        Sink s = new Sink(src, out, listener, outputLength());
        copyPatched(s, 0, f.metaStart());
        s.bytes(newMeta, 0, newMeta.length);
        if (appendBlockLen > 0) {
            if (appendHeaderLen == 8) {
                byte[] h = new byte[8];
                ByteUtils.put32be(h, 0, 8 + appendedTotal);
                h[4] = 'm';
                h[5] = 'd';
                h[6] = 'a';
                h[7] = 't';
                s.bytes(h, 0, 8);
            } else if (appendHeaderLen == 16) {
                byte[] h = new byte[16];
                ByteUtils.put32be(h, 0, 1);
                h[4] = 'm';
                h[5] = 'd';
                h[6] = 'a';
                h[7] = 't';
                ByteUtils.put64be(h, 8, 16 + appendedTotal);
                s.bytes(h, 0, 16);
            }
            for (int i = 0; i < appended.size(); i++) s.bytes(appended.get(i), 0, appended.get(i).length);
        }
        copyPatched(s, f.metaEnd(), f.len);
        s.finish();
    }

    private void copyPatched(Sink s, long start, long end) throws IOException {
        long cur = start;
        for (int i = 0; i < patches.size(); i++) {
            Patch p = patches.get(i);
            long ps = p.pos;
            long pe = p.pos + p.len;
            if (pe <= cur || ps >= end) continue;
            long a = Math.max(ps, cur);
            if (a > cur) {
                s.copy(cur, a - cur);
                cur = a;
            }
            long b = Math.min(pe, end);
            if (p.data != null) s.bytes(p.data, (int) (a - ps), (int) (b - a));
            else s.zeros(b - a);
            cur = b;
        }
        if (cur < end) s.copy(cur, end - cur);
    }

    /** Output with byte-level progress. */
    private static final class Sink {
        private final ByteSource src;
        private final OutputStream out;
        private final ProgressListener listener;
        private final long total;
        private long done;
        private long lastReport;

        Sink(ByteSource src, OutputStream out, ProgressListener listener, long total) {
            this.src = src;
            this.out = out;
            this.listener = listener;
            this.total = total;
        }

        void copy(long pos, long len) throws IOException {
            if (len <= 0) return;
            ByteUtils.copyRange(src, pos, len, out, listener, done, total);
            done += len;
            lastReport = done;
        }

        void bytes(byte[] b, int off, int len) throws IOException {
            if (len <= 0) return;
            out.write(b, off, len);
            done += len;
            tick();
        }

        void zeros(long len) throws IOException {
            byte[] z = new byte[(int) Math.min(65536, Math.max(1, len))];
            while (len > 0) {
                int n = (int) Math.min(z.length, len);
                out.write(z, 0, n);
                len -= n;
                done += n;
            }
            tick();
        }

        private void tick() {
            if (listener != null && done - lastReport >= 1024 * 1024) {
                lastReport = done;
                listener.onProgress(done, total);
            }
        }

        void finish() {
            if (listener != null) listener.onProgress(done, total);
        }
    }
}
