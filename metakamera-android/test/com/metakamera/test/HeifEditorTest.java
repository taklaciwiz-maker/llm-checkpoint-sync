package com.metakamera.test;

import com.metakamera.core.ByteArraySource;
import com.metakamera.core.EditReport;
import com.metakamera.core.EditSpec;
import com.metakamera.core.MediaFormat;
import com.metakamera.core.MediaInfo;
import com.metakamera.core.MetadataEditor;
import com.metakamera.core.ProgressListener;
import com.metakamera.core.UnsupportedFormatException;
import com.metakamera.core.isobmff.HeifEditor;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Tests for {@link HeifEditor}: real heif-enc / exiftool fixtures (HEIC + AVIF), a hand-built
 * layout matrix (iloc versions 0/1/2, field sizes, base-offset styles, meta before/after mdat,
 * idat items, multi-extent items, size-0 / largesize mdat, trailers), Exif creation, ICC/XMP
 * editing, idempotency, byte-identity for unchanged specs and fuzzing.
 *
 * <p>The HEIF builder ({@link HB}) and parser ({@link TM}) in this file are independent of the
 * production code. Results are checked with exiftool (reference reader), exiftool -validate,
 * heif-info and decoded-pixel equality (heif-convert + ImageMagick).
 */
public final class HeifEditorTest {

    static final String[] VENDOR = {"HUAWEI", "ALN-AL00", "VNDRNOTE", "SN-HW-123456", "LSN-999", "Ali Veli",
            "HUAW", "C00E120R1P1", "0123456789ABCDEF"};

    static boolean tools() {
        return T.hasTool("heif-enc") && T.hasTool("heif-convert") && T.hasTool("heif-info")
                && T.hasTool("exiftool") && T.hasTool("convert");
    }

    // =====================================================================================
    // Independent ISO-BMFF / HEIF parser (test side)
    // =====================================================================================

    static long u32(byte[] b, int o) {
        return ((long) (b[o] & 0xFF) << 24) | ((b[o + 1] & 0xFF) << 16) | ((b[o + 2] & 0xFF) << 8) | (b[o + 3] & 0xFF);
    }

    static int u16(byte[] b, int o) {
        return ((b[o] & 0xFF) << 8) | (b[o + 1] & 0xFF);
    }

    static long uN(byte[] b, int o, int n) {
        long v = 0;
        for (int i = 0; i < n; i++) v = (v << 8) | (b[o + i] & 0xFF);
        return v;
    }

    static String cc(byte[] b, int o) {
        return new String(b, o, 4, StandardCharsets.ISO_8859_1);
    }

    static final class TB2 {
        String type;
        int off;
        int size;
        int hs;

        int end() {
            return off + size;
        }

        int pay() {
            return off + hs;
        }
    }

    static List<TB2> boxes(byte[] b, int s, int e) {
        List<TB2> l = new ArrayList<TB2>();
        int p = s;
        while (p + 8 <= e) {
            TB2 x = new TB2();
            x.off = p;
            long sz = u32(b, p);
            x.type = cc(b, p + 4);
            x.hs = 8;
            if (sz == 1) {
                sz = (u32(b, p + 8) << 32) | u32(b, p + 12);
                x.hs = 16;
            } else if (sz == 0) {
                sz = e - p;
            }
            if (sz < x.hs || p + sz > e) throw new AssertionError("bad box " + x.type + " @" + p);
            x.size = (int) sz;
            l.add(x);
            p += x.size;
        }
        return l;
    }

    static TB2 first(List<TB2> l, String type) {
        for (TB2 x : l) if (x.type.equals(type)) return x;
        return null;
    }

    static final class TItem {
        long id;
        int cm;
        int dri;
        long base;
        List<long[]> ext = new ArrayList<long[]>();
    }

    /** Parsed meta of a whole file. */
    static final class TM {
        byte[] f;
        List<TB2> top;
        TB2 meta;
        List<TB2> kids;
        int ilocVersion;
        int[] ilocSizes;
        Map<Long, TItem> iloc = new LinkedHashMap<Long, TItem>();
        Map<Long, String> types = new LinkedHashMap<Long, String>();
        Map<Long, Integer> infeFlags = new LinkedHashMap<Long, Integer>();
        Map<Long, String> contentEnc = new LinkedHashMap<Long, String>();
        List<long[]> cdsc = new ArrayList<long[]>();
        int irefVersion = -1;
        long primary = -1;
        List<TB2> ipco = new ArrayList<TB2>();
        Map<Long, List<int[]>> ipma = new LinkedHashMap<Long, List<int[]>>();
        int idatStart = -1;
        int idatLen;

        TB2 kid(String t) {
            return first(kids, t);
        }
    }

    static TM parse(byte[] f) {
        TM m = new TM();
        m.f = f;
        m.top = boxes(f, 0, f.length - lenOfTrailer(f));
        m.meta = first(m.top, "meta");
        T.check(m.meta != null, "no meta");
        m.kids = boxes(f, m.meta.pay() + 4, m.meta.end());
        for (TB2 k : m.kids) {
            int p = k.pay();
            if (k.type.equals("pitm")) {
                m.primary = f[p] == 0 ? u16(f, p + 4) : u32(f, p + 4);
            } else if (k.type.equals("iloc")) {
                int v = f[p] & 0xFF;
                m.ilocVersion = v;
                int os = (f[p + 4] & 0xFF) >> 4, ls = f[p + 4] & 15, bs = (f[p + 5] & 0xFF) >> 4;
                int is = (v == 1 || v == 2) ? f[p + 5] & 15 : 0;
                m.ilocSizes = new int[]{os, ls, bs, is};
                int q = p + 6;
                long n;
                if (v < 2) {
                    n = u16(f, q);
                    q += 2;
                } else {
                    n = u32(f, q);
                    q += 4;
                }
                for (long i = 0; i < n; i++) {
                    TItem it = new TItem();
                    if (v < 2) {
                        it.id = u16(f, q);
                        q += 2;
                    } else {
                        it.id = u32(f, q);
                        q += 4;
                    }
                    if (v == 1 || v == 2) {
                        it.cm = u16(f, q) & 15;
                        q += 2;
                    }
                    it.dri = u16(f, q);
                    q += 2;
                    it.base = uN(f, q, bs);
                    q += bs;
                    int ec = u16(f, q);
                    q += 2;
                    for (int e = 0; e < ec; e++) {
                        q += is;
                        long eo = uN(f, q, os);
                        q += os;
                        long el = uN(f, q, ls);
                        q += ls;
                        it.ext.add(new long[]{eo, el});
                    }
                    m.iloc.put(it.id, it);
                }
            } else if (k.type.equals("iinf")) {
                int v = f[p] & 0xFF;
                int q = p + 4 + (v == 0 ? 2 : 4);
                for (TB2 e : boxes(f, q, k.end())) {
                    int ep = e.pay();
                    int ev = f[ep] & 0xFF;
                    int fl = (int) (u32(f, ep) & 0xFFFFFF);
                    int r = ep + 4;
                    long id;
                    if (ev == 2) {
                        id = u16(f, r);
                        r += 2;
                    } else {
                        id = u32(f, r);
                        r += 4;
                    }
                    r += 2;
                    String type = cc(f, r);
                    r += 4;
                    while (f[r] != 0) r++;
                    r++;
                    m.types.put(id, type);
                    m.infeFlags.put(id, fl);
                    if (type.equals("mime")) {
                        int s0 = r;
                        while (r < e.end() && f[r] != 0) r++;
                        String ct = new String(f, s0, r - s0, StandardCharsets.UTF_8);
                        r++;
                        String enc = "";
                        if (r < e.end()) {
                            int s1 = r;
                            while (r < e.end() && f[r] != 0) r++;
                            enc = new String(f, s1, r - s1, StandardCharsets.UTF_8);
                        }
                        m.types.put(id, "mime:" + ct);
                        m.contentEnc.put(id, enc);
                    }
                }
            } else if (k.type.equals("iref")) {
                int v = f[p] & 0xFF;
                m.irefVersion = v;
                for (TB2 r : boxes(f, p + 4, k.end())) {
                    int q = r.pay();
                    long from = v == 0 ? u16(f, q) : u32(f, q);
                    q += v == 0 ? 2 : 4;
                    int c = u16(f, q);
                    q += 2;
                    for (int i = 0; i < c; i++) {
                        long to = v == 0 ? u16(f, q) : u32(f, q);
                        q += v == 0 ? 2 : 4;
                        if (r.type.equals("cdsc")) m.cdsc.add(new long[]{from, to});
                    }
                }
            } else if (k.type.equals("iprp")) {
                for (TB2 c : boxes(f, p, k.end())) {
                    if (c.type.equals("ipco")) m.ipco.addAll(boxes(f, c.pay(), c.end()));
                    if (c.type.equals("ipma")) {
                        int q = c.pay();
                        int v = f[q] & 0xFF;
                        int fl = (int) (u32(f, q) & 0xFFFFFF);
                        q += 4;
                        long n = u32(f, q);
                        q += 4;
                        for (long i = 0; i < n; i++) {
                            long id = v < 1 ? u16(f, q) : u32(f, q);
                            q += v < 1 ? 2 : 4;
                            int cnt = f[q++] & 0xFF;
                            List<int[]> l = new ArrayList<int[]>();
                            for (int j = 0; j < cnt; j++) {
                                if ((fl & 1) != 0) {
                                    int x = u16(f, q);
                                    q += 2;
                                    l.add(new int[]{x & 0x7FFF, x >>> 15});
                                } else {
                                    int x = f[q++] & 0xFF;
                                    l.add(new int[]{x & 0x7F, x >>> 7});
                                }
                            }
                            m.ipma.put(id, l);
                        }
                    }
                }
            } else if (k.type.equals("idat")) {
                m.idatStart = k.pay();
                m.idatLen = k.end() - k.pay();
            }
        }
        return m;
    }

    /** Trailing bytes after the last parseable top-level box (best effort, for the trailer tests). */
    static int lenOfTrailer(byte[] f) {
        int p = 0;
        while (p + 8 <= f.length) {
            long sz = u32(f, p);
            if (sz == 1) {
                if (p + 16 > f.length) break;
                sz = (u32(f, p + 8) << 32) | u32(f, p + 12);
            } else if (sz == 0) {
                sz = f.length - p;
            }
            if (sz < 8 || p + sz > f.length) break;
            p += (int) sz;
        }
        return f.length - p;
    }

    static long[][] absRanges(TM m, long id) {
        TItem it = m.iloc.get(id);
        long[][] r = new long[it.ext.size()][];
        for (int i = 0; i < r.length; i++) {
            long s = it.base + it.ext.get(i)[0];
            r[i] = new long[]{s, s + it.ext.get(i)[1]};
        }
        return r;
    }

    static byte[] itemData(TM m, long id) {
        TItem it = m.iloc.get(id);
        T.check(it != null, "no iloc entry for item " + id);
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (long[] e : it.ext) {
            long s = it.base + e[0];
            if (it.cm == 1) o.write(m.f, (int) (m.idatStart + s), (int) e[1]);
            else o.write(m.f, (int) s, (int) e[1]);
        }
        return o.toByteArray();
    }

    static long itemOfType(TM m, String type) {
        for (Map.Entry<Long, String> e : m.types.entrySet()) if (e.getValue().equals(type)) return e.getKey();
        return -1;
    }

    /** The box directly after meta (where the editor inserts its new mdat). */
    static TB2 newMdat(TM m) {
        int i = m.top.indexOf(m.meta);
        T.check(i + 1 < m.top.size(), "a box follows meta");
        TB2 b = m.top.get(i + 1);
        T.eq("mdat", b.type, "inserted box type");
        return b;
    }

    /**
     * New position of an original byte position when everything the editor adds is inserted at
     * the meta box (meta growth + the new mdat): {@code growth} = output length - input length.
     */
    static long mapOld(TM m0, long p, long growth) {
        return p < m0.meta.off ? p : p + growth;
    }

    static byte[] tiffOf(byte[] exifPayload) {
        int off = (int) u32(exifPayload, 0);
        return Arrays.copyOfRange(exifPayload, 4 + off, exifPayload.length);
    }

    // =====================================================================================
    // Independent HEIF builder (test side)
    // =====================================================================================

    static byte[] box(String type, byte[]... parts) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        int n = 8;
        for (byte[] p : parts) n += p.length;
        o.writeBytes(be(n, 4));
        o.writeBytes(type.getBytes(StandardCharsets.ISO_8859_1));
        for (byte[] p : parts) o.writeBytes(p);
        return o.toByteArray();
    }

    static byte[] full(int v, int flags, byte[]... parts) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(v);
        o.writeBytes(be(flags, 3));
        for (byte[] p : parts) o.writeBytes(p);
        return o.toByteArray();
    }

    static byte[] be(long v, int n) {
        byte[] r = new byte[n];
        for (int i = 0; i < n; i++) r[i] = (byte) (v >>> (8 * (n - 1 - i)));
        return r;
    }

    static byte[] cat(byte[]... parts) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (byte[] p : parts) o.writeBytes(p);
        return o.toByteArray();
    }

    static byte[] str(String s) {
        return cat(s.getBytes(StandardCharsets.UTF_8), new byte[1]);
    }

    /** Coded image + its properties, taken from a heif-enc file. */
    static final class Img {
        String type;
        String brand;
        List<byte[]> props = new ArrayList<byte[]>();
        List<Boolean> ess = new ArrayList<Boolean>();
        byte[] data;
        File src;
        String pixels;
        int w;
        int h;
    }

    static Img extract(File f) throws IOException {
        byte[] b = T.read(f);
        TM m = parse(b);
        Img img = new Img();
        img.src = f;
        img.brand = cc(b, 8);
        img.type = m.types.get(m.primary);
        img.data = itemData(m, m.primary);
        for (int[] a : m.ipma.get(m.primary)) {
            TB2 p = m.ipco.get(a[0] - 1);
            img.props.add(Arrays.copyOfRange(b, p.off, p.end()));
            img.ess.add(a[1] == 1);
            if (p.type.equals("ispe")) {
                img.w = (int) u32(b, p.pay() + 4);
                img.h = (int) u32(b, p.pay() + 8);
            }
        }
        img.pixels = pixels(f);
        return img;
    }

    /** Builder options. */
    static final class HB {
        Img img;
        int ilocVersion = 0;
        int offSize = 4;
        int lenSize = 4;
        int baseSize = 4;
        int idxSize = 0;
        /** 0 = base 0 + absolute extent offsets; 1 = base = item start; 2 = base = mdat payload start. */
        int mode = 1;
        boolean mdatFirst;
        boolean ilocLast;
        boolean dinf;
        boolean freeInMeta;
        boolean metaBeforeImage; // metadata items before the image data inside mdat
        byte[] exif;
        int exifCm;
        int exifSplit = 1;
        boolean sharedExif;
        byte[] xmp;
        String xmpEnc;
        byte[] icc;
        long imageId = 1;
        long exifId = 2;
        long xmpId = 3;
        long sharedId = 4;
        int irefVersion = 0;
        boolean mdatToEof;
        boolean mdatLarge;
        byte[] boxAfter;
        byte[] trailer;

        // results
        int mdatPayloadAbs;
        int metaOff;

        byte[] build() {
            // ---- mdat payload: image, metadata (or metadata first), with gaps between exif parts
            ByteArrayOutputStream md = new ByteArrayOutputStream();
            List<long[]> imgExt = new ArrayList<long[]>();
            List<long[]> exifExt = new ArrayList<long[]>();
            List<long[]> xmpExt = new ArrayList<long[]>();
            ByteArrayOutputStream idat = new ByteArrayOutputStream();
            if (!metaBeforeImage) put(md, img.data, imgExt);
            if (exif != null) {
                ByteArrayOutputStream target = exifCm == 1 ? idat : md;
                if (exifCm == 1) idat.writeBytes("IDAT".getBytes(StandardCharsets.US_ASCII));
                int n = exifSplit;
                int step = (exif.length + n - 1) / n;
                // parts are stored in reverse order with junk between them
                long[][] parts = new long[n][];
                for (int i = n - 1; i >= 0; i--) {
                    int s = i * step;
                    int e = Math.min(exif.length, s + step);
                    if (n > 1) target.writeBytes("gap".getBytes(StandardCharsets.US_ASCII));
                    parts[i] = new long[]{target.size(), e - s};
                    target.write(exif, s, e - s);
                }
                exifExt.addAll(Arrays.asList(parts));
            }
            if (xmp != null) put(md, xmp, xmpExt);
            if (metaBeforeImage) put(md, img.data, imgExt);

            byte[] ftyp = box("ftyp", img.brand.getBytes(StandardCharsets.US_ASCII), be(0, 4),
                    "mif1".getBytes(StandardCharsets.US_ASCII), img.brand.getBytes(StandardCharsets.US_ASCII),
                    "miaf".getBytes(StandardCharsets.US_ASCII));
            int mdatHdr = mdatLarge ? 16 : 8;
            byte[] meta0 = meta(0, imgExt, exifExt, xmpExt, idat.toByteArray());
            if (mdatFirst) {
                mdatPayloadAbs = ftyp.length + mdatHdr;
                metaOff = ftyp.length + mdatHdr + md.size();
            } else {
                mdatPayloadAbs = ftyp.length + meta0.length + mdatHdr;
                metaOff = ftyp.length;
            }
            byte[] meta = meta(mdatPayloadAbs, imgExt, exifExt, xmpExt, idat.toByteArray());
            T.check(meta.length == meta0.length, "builder: meta size depends on values");
            byte[] mdat;
            if (mdatLarge) {
                mdat = cat(be(1, 4), "mdat".getBytes(StandardCharsets.US_ASCII), be(16 + md.size(), 8), md.toByteArray());
            } else {
                mdat = cat(be(mdatToEof ? 0 : 8 + md.size(), 4), "mdat".getBytes(StandardCharsets.US_ASCII), md.toByteArray());
            }
            byte[] out = mdatFirst ? cat(ftyp, mdat, meta) : cat(ftyp, meta, mdat);
            if (boxAfter != null) out = cat(out, boxAfter);
            if (trailer != null) out = cat(out, trailer);
            return out;
        }

        private static void put(ByteArrayOutputStream o, byte[] d, List<long[]> ext) {
            ext.add(new long[]{o.size(), d.length});
            o.writeBytes(d);
        }

        private byte[] ilocItem(long id, int cm, List<long[]> ext, long payloadAbs) {
            // ext: offsets relative to the mdat payload (cm 0) or idat (cm 1)
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            o.writeBytes(be(id, ilocVersion < 2 ? 2 : 4));
            if (ilocVersion >= 1) o.writeBytes(be(cm, 2));
            o.writeBytes(be(0, 2));
            long origin = cm == 1 ? 0 : payloadAbs;
            long base;
            long[] offs = new long[ext.size()];
            if (mode == 0) {
                base = 0;
                for (int i = 0; i < offs.length; i++) offs[i] = origin + ext.get(i)[0];
            } else if (mode == 1) {
                base = origin + ext.get(0)[0];
                for (int i = 0; i < offs.length; i++) offs[i] = origin + ext.get(i)[0] - base;
            } else {
                base = origin;
                for (int i = 0; i < offs.length; i++) offs[i] = ext.get(i)[0];
            }
            T.check(baseSize > 0 || base == 0, "builder: base needs base_offset_size");
            o.writeBytes(be(base, baseSize));
            o.writeBytes(be(ext.size(), 2));
            for (int i = 0; i < offs.length; i++) {
                T.check(offSize > 0 || offs[i] == 0, "builder: offset needs offset_size");
                if (ilocVersion >= 1 && idxSize > 0) o.writeBytes(be(0, idxSize));
                o.writeBytes(be(offs[i], offSize));
                o.writeBytes(be(ext.get(i)[1], lenSize));
            }
            return o.toByteArray();
        }

        private byte[] infe(long id, int flags, String type, String contentType, String enc) {
            int v = id > 0xFFFF ? 3 : 2;
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            o.writeBytes(be(id, v == 2 ? 2 : 4));
            o.writeBytes(be(0, 2));
            o.writeBytes(type.getBytes(StandardCharsets.ISO_8859_1));
            o.writeBytes(str(""));
            if (contentType != null) o.writeBytes(str(contentType));
            if (enc != null) o.writeBytes(str(enc));
            return box("infe", full(v, flags, o.toByteArray()));
        }

        private byte[] ref(String type, long from, long to) {
            int n = irefVersion == 0 ? 2 : 4;
            return box(type, be(from, n), be(1, 2), be(to, n));
        }

        byte[] meta(long payloadAbs, List<long[]> imgExt, List<long[]> exifExt, List<long[]> xmpExt, byte[] idat) {
            List<byte[]> kids = new ArrayList<byte[]>();
            kids.add(box("hdlr", full(0, 0, be(0, 4), "pict".getBytes(StandardCharsets.US_ASCII), new byte[12], new byte[1])));
            if (dinf) {
                byte[] url = box("url ", full(0, 1));
                kids.add(box("dinf", box("dref", full(0, 0, be(1, 4), url))));
            }
            if (freeInMeta) kids.add(box("free", "free space inside meta".getBytes(StandardCharsets.US_ASCII)));
            kids.add(box("pitm", imageId > 0xFFFF ? full(1, 0, be(imageId, 4)) : full(0, 0, be(imageId, 2))));

            // iloc
            ByteArrayOutputStream il = new ByteArrayOutputStream();
            int count = 1 + (exif != null ? 1 : 0) + (xmp != null ? 1 : 0) + (sharedExif ? 1 : 0);
            il.write((offSize << 4) | lenSize);
            il.write((baseSize << 4) | (ilocVersion >= 1 ? idxSize : 0));
            il.writeBytes(be(count, ilocVersion < 2 ? 2 : 4));
            il.writeBytes(ilocItem(imageId, 0, imgExt, payloadAbs));
            if (exif != null) il.writeBytes(ilocItem(exifId, exifCm, exifExt, payloadAbs));
            if (sharedExif) il.writeBytes(ilocItem(sharedId, exifCm, exifExt, payloadAbs));
            if (xmp != null) il.writeBytes(ilocItem(xmpId, 0, xmpExt, payloadAbs));
            byte[] iloc = box("iloc", full(ilocVersion, 0, il.toByteArray()));
            if (!ilocLast) kids.add(iloc);

            // iinf
            ByteArrayOutputStream ii = new ByteArrayOutputStream();
            ii.writeBytes(infe(imageId, 0, img.type, null, null));
            if (exif != null) ii.writeBytes(infe(exifId, 1, "Exif", null, null));
            if (sharedExif) ii.writeBytes(infe(sharedId, 1, "Exif", null, null));
            if (xmp != null) ii.writeBytes(infe(xmpId, 1, "mime", "application/rdf+xml", xmpEnc));
            kids.add(box("iinf", full(0, 0, be(count, 2), ii.toByteArray())));

            // iref
            if (exif != null || xmp != null) {
                ByteArrayOutputStream ir = new ByteArrayOutputStream();
                if (exif != null) ir.writeBytes(ref("cdsc", exifId, imageId));
                if (sharedExif) ir.writeBytes(ref("cdsc", sharedId, imageId));
                if (xmp != null) ir.writeBytes(ref("cdsc", xmpId, imageId));
                kids.add(box("iref", full(irefVersion, 0, ir.toByteArray())));
            }

            // iprp
            ByteArrayOutputStream pc = new ByteArrayOutputStream();
            List<Integer> idx = new ArrayList<Integer>();
            List<Boolean> ess = new ArrayList<Boolean>();
            int n = 0;
            for (int i = 0; i < img.props.size(); i++) {
                byte[] p = img.props.get(i);
                String t = cc(p, 4);
                if (icc != null && t.equals("colr") && !cc(p, 8).equals("nclx")) continue;
                pc.writeBytes(p);
                idx.add(++n);
                ess.add(img.ess.get(i));
            }
            if (icc != null) {
                pc.writeBytes(box("colr", "prof".getBytes(StandardCharsets.US_ASCII), icc));
                idx.add(++n);
                ess.add(false);
            }
            ByteArrayOutputStream pm = new ByteArrayOutputStream();
            pm.writeBytes(be(1, 4));
            pm.writeBytes(be(imageId, imageId > 0xFFFF ? 4 : 2));
            pm.write(idx.size());
            for (int i = 0; i < idx.size(); i++) pm.write((ess.get(i) ? 0x80 : 0) | idx.get(i));
            kids.add(box("iprp", box("ipco", pc.toByteArray()), box("ipma", full(imageId > 0xFFFF ? 1 : 0, 0, pm.toByteArray()))));
            if (exifCm == 1 && exif != null) kids.add(box("idat", idat));
            if (ilocLast) kids.add(iloc);
            return box("meta", full(0, 0, cat(kids.toArray(new byte[0][]))));
        }
    }

    // =====================================================================================
    // Fixture sources
    // =====================================================================================

    private static Img heic;
    private static Img avif;

    static synchronized Img heicImg() throws IOException {
        if (heic == null) {
            File png = T.file("heif-src.png");
            T.run("convert", "-size", "96x64", "gradient:red-blue", png.getPath());
            File f = T.file("heif-plain.heic");
            T.run("heif-enc", "-q", "60", "-o", f.getPath(), png.getPath());
            heic = extract(f);
        }
        return heic;
    }

    static synchronized Img avifImg() throws IOException {
        if (avif == null) {
            File png = T.file("heif-src.png");
            if (!png.exists()) heicImg();
            File f = T.file("heif-plain.avif");
            T.run("heif-enc", "-A", "-q", "60", "-o", f.getPath(), png.getPath());
            avif = extract(f);
        }
        return avif;
    }

    static ExifTiffTest.Opt huaweiOpt(boolean le) {
        ExifTiffTest.Opt o = ExifTiffTest.opt(le);
        return o;
    }

    static byte[] huaweiTiff(boolean le) throws IOException {
        return ExifTiffTest.build(huaweiOpt(le)).tiff;
    }

    /** Exif item payload: 4-byte offset + optional "Exif\0\0" + TIFF. */
    static byte[] exifPayload(byte[] tiff, boolean prefix) {
        if (prefix) return cat(be(6, 4), "Exif\0\0".getBytes(StandardCharsets.ISO_8859_1), tiff);
        return cat(be(0, 4), tiff);
    }

    static String xmpPacket(boolean padding) {
        return xmpPacket(padding, true);
    }

    /** Huawei-style XMP; without {@code wrapper} there is no xpacket PI, so edits may change the length. */
    static String xmpPacket(boolean padding, boolean wrapper) {
        StringBuilder s = new StringBuilder();
        if (wrapper) s.append("<?xpacket begin='﻿' id='W5M0MpCehiHzreSzNTczkc9d'?>\n");
        s.append("<x:xmpmeta xmlns:x='adobe:ns:meta/'>\n");
        s.append("<rdf:RDF xmlns:rdf='http://www.w3.org/1999/02/22-rdf-syntax-ns#'>\n");
        s.append(" <rdf:Description rdf:about=''\n");
        s.append("  xmlns:tiff='http://ns.adobe.com/tiff/1.0/'\n");
        s.append("  xmlns:xmp='http://ns.adobe.com/xap/1.0/'\n");
        s.append("  xmlns:exifEX='http://cipa.jp/exif/1.0/'\n");
        s.append("  tiff:Make='HUAWEI'\n");
        s.append("  tiff:Model='ALN-AL00'\n");
        s.append("  xmp:CreatorTool='ALN-AL00 4.0.0.120(C00E120R1P1)'\n");
        s.append("  exifEX:LensModel='HUAWEI REAR CAMERA'\n");
        s.append("  exifEX:BodySerialNumber='SN-HW-123456'/>\n");
        s.append("</rdf:RDF>\n</x:xmpmeta>\n");
        if (padding) {
            for (int i = 0; i < 20; i++) s.append("                                                                                                   \n");
        }
        if (wrapper) s.append("<?xpacket end='w'?>");
        return s.toString();
    }

    static byte[] huaweiIcc() {
        return IccProfileEditorTest.v2Profile("HUAWEI", "ALN-AL00", false);
    }

    /** Typical hand-built fixture: Huawei Exif (LE, prefix) + padded XMP + Huawei ICC. */
    static HB fixture(Img img) throws IOException {
        HB b = new HB();
        b.img = img;
        b.exif = exifPayload(huaweiTiff(true), true);
        b.xmp = xmpPacket(true).getBytes(StandardCharsets.UTF_8);
        b.icc = huaweiIcc();
        return b;
    }

    // =====================================================================================
    // Verification helpers
    // =====================================================================================

    static String md5(byte[] b) {
        try {
            MessageDigest d = MessageDigest.getInstance("MD5");
            StringBuilder sb = new StringBuilder();
            for (byte x : d.digest(b)) sb.append(String.format("%02x", x));
            return sb.toString();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static int pixCounter;

    /** md5 of the decoded RGBA pixels (heif-convert -> PNG -> ImageMagick raw RGBA). */
    static synchronized String pixels(File f) throws IOException {
        File png = T.file("heif-pix-" + (pixCounter++) + ".png");
        T.run("heif-convert", f.getPath(), png.getPath());
        byte[] raw = ExifTiffTest.execBytes("convert", png.getPath(), "-depth", "8", "rgba:-");
        T.check(raw.length > 0, "no pixels decoded from " + f);
        png.delete();
        return md5(raw);
    }

    static File write(String name, byte[] data) throws IOException {
        File f = T.file(name);
        T.write(f, data);
        return f;
    }

    static final class Out {
        File file;
        byte[] data;
        EditReport report;
    }

    static Out edit(File in, String outName, EditSpec spec) throws IOException {
        Out o = new Out();
        o.file = T.file(outName);
        o.report = T.edit(in, o.file, spec);
        o.data = T.read(o.file);
        T.eq((long) o.data.length, o.report.outputLength, "report.outputLength");
        return o;
    }

    static EditReport editDirect(byte[] in, ByteArrayOutputStream out, EditSpec spec) throws IOException {
        EditReport r = new EditReport();
        HeifEditor.edit(new ByteArraySource(in), out, spec, r, null);
        return r;
    }

    static boolean hasChange(EditReport r, String s) {
        return r.changes.contains(s);
    }

    static boolean hasChangeStarting(EditReport r, String s) {
        for (String c : r.changes) if (c.startsWith(s)) return true;
        return false;
    }

    static boolean hasWarning(EditReport r, String part) {
        for (String w : r.warnings) if (w.contains(part)) return true;
        return false;
    }

    static void heifInfoOk(File f) throws IOException {
        T.Result r = T.exec("heif-info", f.getPath());
        T.check(r.exit == 0, "heif-info failed on " + f.getName() + ":\n" + r.out);
    }

    static void absentAll(byte[] data, String what) {
        for (String v : VENDOR) ExifTiffTest.absent(data, v, what);
    }

    /** Full Apple-spoof verification of an edited file. */
    static void verifyApple(File in, Out out, Img img, boolean xmp, boolean icc, boolean exifViaExiftool) throws IOException {
        String n = out.file.getName();
        Map<String, String> t = T.exif(out.file, "-n");
        if (exifViaExiftool) checkAppleTiffTags(t, n);
        if (xmp) {
            T.eq("Apple", t.get("XMP-tiff:Make"), n + " XMP-tiff:Make");
            T.eq("iPhone 17 Pro Max", t.get("XMP-tiff:Model"), n + " XMP-tiff:Model");
            T.eq("26.0.1", t.get("XMP-xmp:CreatorTool"), n + " XMP CreatorTool");
            T.eq("iPhone 17 Pro Max back triple camera 6.765mm f/1.78", t.get("XMP-exifEX:LensModel"), n + " XMP LensModel");
            T.check(!t.containsKey("XMP-exifEX:SerialNumber"), n + " XMP serial must be removed");
        }
        if (icc) {
            T.eq("APPL", t.get("ICC-header:DeviceManufacturer"), n + " ICC manufacturer");
            T.eq("", t.get("ICC-header:DeviceModel"), n + " ICC model");
            T.eq("appl", t.get("ICC-header:ProfileCMMType"), n + " ICC CMM");
            T.eq("APPL", t.get("ICC-header:PrimaryPlatform"), n + " ICC platform");
            T.eq("appl", t.get("ICC-header:ProfileCreator"), n + " ICC creator");
            T.eq("Apple", t.get("ICC_Profile:DeviceMfgDesc"), n + " ICC dmnd");
            T.eq("iPhone 17 Pro Max", t.get("ICC_Profile:DeviceModelDesc"), n + " ICC dmdd");
        }
        ExifTiffTest.assertNoNewWarnings(in, out.file);
        heifInfoOk(out.file);
        T.eq(img.pixels, pixels(out.file), n + " decoded pixels");
        absentAll(out.data, n);
        // the edit is idempotent: a second run changes nothing (byte-identical output)
        Out again = edit(out.file, "again-" + n, T.appleSpec());
        T.check(Arrays.equals(out.data, again.data), n + ": second edit changed the file: " + again.report);
        T.check(again.report.changes.isEmpty(), n + ": second edit reported changes: " + again.report);
        MediaInfo mi = T.inspect(out.file);
        T.eq("Apple", mi.make, n + " inspect make");
        T.eq("iPhone 17 Pro Max", mi.model, n + " inspect model");
        T.eq(img.w, mi.width, n + " inspect width");
        T.eq(img.h, mi.height, n + " inspect height");
        if (icc) T.eq("APPL", mi.iccManufacturer, n + " inspect icc manufacturer");
    }

    static void checkAppleTiffTags(Map<String, String> t, String n) {
        T.eq("Apple", t.get("IFD0:Make"), n + " IFD0:Make");
        T.eq("iPhone 17 Pro Max", t.get("IFD0:Model"), n + " IFD0:Model");
        T.eq("26.0.1", t.get("IFD0:Software"), n + " IFD0:Software");
        T.eq("iPhone 17 Pro Max", t.get("IFD0:HostComputer"), n + " IFD0:HostComputer");
        T.eq("Apple", t.get("ExifIFD:LensMake"), n + " LensMake");
        T.eq("iPhone 17 Pro Max back triple camera 6.765mm f/1.78", t.get("ExifIFD:LensModel"), n + " LensModel");
        T.check(!t.containsKey("ExifIFD:SerialNumber"), n + ": BodySerialNumber must be removed");
        T.check(!t.containsKey("ExifIFD:LensSerialNumber"), n + ": LensSerialNumber must be removed");
        T.check(!t.containsKey("ExifIFD:OwnerName"), n + ": CameraOwnerName must be removed");
        T.check(!t.containsKey("ExifIFD:MakerNoteUnknownText") && !t.containsKey("ExifIFD:MakerNoteUnknownBinary")
                && !t.containsKey("ExifIFD:MakerNoteUnknown"), n + ": MakerNote must be removed");
    }

    /** Checks the input was built correctly (readable by every tool, Huawei values present). */
    static void sanity(File in, Img img, boolean exifViaExiftool) throws IOException {
        heifInfoOk(in);
        T.eq(img.pixels, pixels(in), in.getName() + " input pixels");
        if (exifViaExiftool) T.eq("HUAWEI", T.exif(in).get("IFD0:Make"), in.getName() + " input Make");
    }

    // =====================================================================================
    // Tests: real-world fixtures
    // =====================================================================================

    public static void testRealExiftoolWrittenHeic() throws Exception {
        if (!tools()) return;
        Img img = heicImg();
        File in = T.file("heif-real-et.heic");
        T.write(in, T.read(img.src));
        T.run("exiftool", "-q", "-overwrite_original", "-Make=HUAWEI", "-Model=ALN-AL00",
                "-Software=ALN-AL00 4.0.0.120(C00E120R1P1)", "-LensModel=HUAWEI REAR CAMERA",
                "-SerialNumber=SN-HW-123456", "-XMP-tiff:Make=HUAWEI", "-XMP-tiff:Model=ALN-AL00", in.getPath());
        Map<String, String> before = T.exif(in);
        T.eq("HUAWEI", before.get("IFD0:Make"), "fixture make");
        TM m0 = parse(T.read(in));
        long exifId = itemOfType(m0, "Exif");
        T.check(exifId > 0, "fixture has an Exif item");
        long[][] oldExif = absRanges(m0, exifId);

        Out out = edit(in, "heif-real-et-out.heic", T.appleSpec());
        T.check(hasChange(out.report, "EXIF Make: HUAWEI → Apple"), "report: " + out.report);
        T.check(hasChange(out.report, "EXIF Model: ALN-AL00 → iPhone 17 Pro Max"), "report: " + out.report);
        T.check(hasChange(out.report, "EXIF BodySerialNumber kaldırıldı"), "report: " + out.report);
        T.check(hasChange(out.report, "XMP tiff:Model: ALN-AL00 → iPhone 17 Pro Max"), "report: " + out.report);
        T.check(out.report.warnings.isEmpty(), "warnings: " + out.report.warnings);
        Map<String, String> t = T.exif(out.file, "-n");
        checkAppleTiffTags(t, "real-et");
        T.eq("Apple", t.get("XMP-tiff:Make"), "XMP make");
        ExifTiffTest.assertNoNewWarnings(in, out.file);
        heifInfoOk(out.file);
        T.eq(img.pixels, pixels(out.file), "pixels");
        absentAll(out.data, "real-et");
        // the grown Exif moved to a new mdat right after meta; its old bytes are zero-filled
        TM m1 = parse(out.data);
        TB2 added = newMdat(m1);
        long[][] newExif = absRanges(m1, exifId);
        T.check(newExif[0][0] >= added.pay() && newExif[0][1] <= added.end(), "Exif lives in the new mdat");
        for (long p = oldExif[0][0]; p < oldExif[0][1]; p++) {
            T.check(out.data[(int) mapOld(m0, p, out.data.length - m0.f.length)] == 0, "old Exif byte not zeroed at " + p);
        }
        Out again = edit(out.file, "heif-real-et-out2.heic", T.appleSpec());
        T.check(Arrays.equals(out.data, again.data), "idempotent: " + again.report);
    }

    public static void testRealHeifEncFromJpegWithIccXmpExif() throws Exception {
        if (!tools()) return;
        realFromJpeg(false);
    }

    public static void testRealAvifFromJpegWithIccXmpExif() throws Exception {
        if (!tools()) return;
        realFromJpeg(true);
    }

    private static void realFromJpeg(boolean avifOut) throws Exception {
        File icc = write("heif-huawei.icc", huaweiIcc());
        File jpg = T.file("heif-real-src.jpg");
        T.run("convert", "-size", "96x64", "gradient:red-blue", "-quality", "92", jpg.getPath());
        T.run("exiftool", "-q", "-overwrite_original", "-Make=HUAWEI", "-Model=ALN-AL00",
                "-Software=ALN-AL00 4.0.0.120(C00E120R1P1)", "-LensModel=HUAWEI REAR CAMERA",
                "-icc_profile<=" + icc.getPath(), "-XMP-tiff:Make=HUAWEI", "-XMP-tiff:Model=ALN-AL00",
                "-XMP-xmp:CreatorTool=ALN-AL00 4.0.0.120(C00E120R1P1)", jpg.getPath());
        File in = T.file(avifOut ? "heif-real-jpg.avif" : "heif-real-jpg.heic");
        if (avifOut) T.run("heif-enc", "-A", "-q", "60", "-o", in.getPath(), jpg.getPath());
        else T.run("heif-enc", "-q", "60", "-o", in.getPath(), jpg.getPath());
        Map<String, String> before = T.exif(in, "-n");
        T.eq("HUAWEI", before.get("IFD0:Make"), "fixture make");
        T.eq("HUAW", before.get("ICC-header:DeviceManufacturer"), "fixture ICC");
        T.eq("HUAWEI", before.get("ICC_Profile:DeviceMfgDesc"), "fixture dmnd");
        T.eq("HUAWEI", before.get("XMP-tiff:Make"), "fixture XMP");
        Img img = extract(in);
        T.eq(avifOut ? MediaFormat.AVIF : MediaFormat.HEIF, T.inspect(in).format, "detected format");

        Out out = edit(in, (avifOut ? "heif-real-jpg-out.avif" : "heif-real-jpg-out.heic"), T.appleSpec());
        T.eq(avifOut ? MediaFormat.AVIF : MediaFormat.HEIF, out.report.format, "report format");
        T.check(hasChange(out.report, "ICC üretici: HUAW → APPL"), "report: " + out.report);
        T.check(hasChangeStarting(out.report, "ICC üretici açıklaması (dmnd): HUAWEI → Apple"), "report: " + out.report);
        T.check(hasChange(out.report, "XMP tiff:Make: HUAWEI → Apple"), "report: " + out.report);
        T.check(hasChange(out.report, "EXIF Make: HUAWEI → Apple"), "report: " + out.report);
        Map<String, String> t = T.exif(out.file, "-n");
        checkAppleTiffTags(t, out.file.getName());
        T.eq("APPL", t.get("ICC-header:DeviceManufacturer"), "ICC manufacturer");
        T.eq("Apple", t.get("ICC_Profile:DeviceMfgDesc"), "dmnd");
        T.eq("iPhone 17 Pro Max", t.get("ICC_Profile:DeviceModelDesc"), "dmdd");
        T.eq("Apple", t.get("XMP-tiff:Make"), "XMP make");
        T.eq("26.0.1", t.get("XMP-xmp:CreatorTool"), "XMP creator tool");
        ExifTiffTest.assertNoNewWarnings(in, out.file);
        heifInfoOk(out.file);
        T.eq(img.pixels, pixels(out.file), "pixels");
        absentAll(out.data, out.file.getName());
        Out again = edit(out.file, "again-" + out.file.getName(), T.appleSpec());
        T.check(Arrays.equals(out.data, again.data), "idempotent: " + again.report);
        MediaInfo mi = T.inspect(out.file);
        T.eq("APPL", mi.iccManufacturer, "inspect icc");
        T.eq("Apple", mi.iccManufacturerText, "inspect dmnd");
        T.check(mi.hasXmp && mi.hasExif && mi.hasIcc, "inspect flags");
    }

    /**
     * GPAC (MP4Box) layout: iloc v0 with offset_size 0 (positions only in base_offset), a second
     * image item used as thumbnail ('thmb'), a trailing 'free' box.
     */
    public static void testRealMp4BoxHeifWithThumbnail() throws Exception {
        if (!tools() || !T.hasTool("MP4Box") || !T.hasTool("ffmpeg")) return;
        File hevc = T.file("heif-gpac.hevc");
        T.run("ffmpeg", "-y", "-v", "error", "-f", "lavfi", "-i", "testsrc=size=128x96", "-frames:v", "1",
                "-c:v", "libx265", "-x265-params", "log-level=error", hevc.getPath());
        File gp = T.file("heif-gpac.heic");
        gp.delete();
        T.run("MP4Box", "-add-image", hevc.getPath() + ":primary", "-add-image", hevc.getPath() + ":ref=thmb,1",
                "-ab", "heic", "-new", gp.getPath());
        byte[] plain = T.read(gp);
        TM m0 = parse(plain);
        T.eq(0, m0.ilocSizes[0], "GPAC writes offset_size 0");
        String pix = pixels(gp);

        // (a) no Exif yet: created, located through base_offset (offset_size stays 0)
        Out out = edit(gp, "out-heif-gpac.heic", T.appleSpec());
        T.check(hasChange(out.report, "HEIF Exif öğesi oluşturuldu"), "report " + out.report);
        TM m1 = parse(out.data);
        T.eq(0, m1.ilocSizes[0], "offset_size still 0");
        long id = itemOfType(m1, "Exif");
        T.eq(3L, id, "new item id");
        T.check(containsRef(m1, id, 1), "cdsc to primary");
        Map<String, String> t = T.exif(out.file);
        T.eq("Apple", t.get("IFD0:Make"), "make");
        T.eq("128", t.get("ExifIFD:ExifImageWidth"), "width");
        ExifTiffTest.assertNoNewWarnings(gp, out.file);
        heifInfoOk(out.file);
        T.eq(pix, pixels(out.file), "pixels");
        T.check(Arrays.equals(itemData(m0, 2), itemData(m1, 2)), "thumbnail item data");
        TB2 lastIn = m0.top.get(m0.top.size() - 1);
        T.eq("free", lastIn.type, "fixture ends with free");
        T.check(Arrays.equals(Arrays.copyOfRange(plain, lastIn.off, lastIn.end()),
                Arrays.copyOfRange(out.data, out.data.length - lastIn.size, out.data.length)), "free box kept last");

        // (b) Exif + XMP written by exiftool, then the Apple spoof
        File et = T.file("heif-gpac-et.heic");
        T.write(et, plain);
        T.run("exiftool", "-q", "-overwrite_original", "-Make=HUAWEI", "-Model=ALN-AL00",
                "-Software=ALN-AL00 4.0.0.120(C00E120R1P1)", "-LensModel=HUAWEI REAR CAMERA",
                "-XMP-tiff:Make=HUAWEI", "-XMP-tiff:Model=ALN-AL00", et.getPath());
        Out o2 = edit(et, "out-heif-gpac-et.heic", T.appleSpec());
        checkAppleTiffTags(T.exif(o2.file, "-n"), "gpac-et");
        T.eq("Apple", T.exif(o2.file).get("XMP-tiff:Make"), "XMP");
        ExifTiffTest.assertNoNewWarnings(et, o2.file);
        heifInfoOk(o2.file);
        T.eq(pix, pixels(o2.file), "pixels");
        absentAll(o2.data, "gpac-et");
        Out again = edit(o2.file, "again-heif-gpac-et.heic", T.appleSpec());
        T.check(Arrays.equals(o2.data, again.data), "idempotent");
    }

    public static void testCompressedXmpIsEditedAndRecompressed() throws Exception {
        if (!tools()) return;
        File jpg = T.file("heif-comp-src.jpg");
        T.run("convert", "-size", "96x64", "gradient:red-blue", "-quality", "92", jpg.getPath());
        T.run("exiftool", "-q", "-overwrite_original", "-Make=HUAWEI", "-XMP-tiff:Make=HUAWEI",
                "-XMP-tiff:Model=ALN-AL00", jpg.getPath());
        File in = T.file("heif-comp.heic");
        T.run("heif-enc", "-q", "60", "--enable-metadata-compression", "-o", in.getPath(), jpg.getPath());
        TM m0 = parse(T.read(in));
        long xid = itemOfType(m0, "mime:application/rdf+xml");
        T.eq("deflate", m0.contentEnc.get(xid), "fixture XMP is compressed");
        String x0 = new String(inflate(itemData(m0, xid)), StandardCharsets.UTF_8);
        T.contains(x0, "HUAWEI", "fixture XMP");
        MediaInfo mi0 = T.inspect(in);
        T.check(mi0.hasXmp, "inspect sees compressed XMP");

        Out out = edit(in, "heif-comp-out.heic", T.appleSpec());
        T.check(hasChange(out.report, "XMP tiff:Make: HUAWEI → Apple"), "report: " + out.report);
        TM m1 = parse(out.data);
        T.eq("deflate", m1.contentEnc.get(xid), "still compressed");
        String x1 = new String(inflate(itemData(m1, xid)), StandardCharsets.UTF_8);
        T.notContains(x1, "HUAWEI", "XMP");
        T.notContains(x1, "ALN-AL00", "XMP");
        T.contains(x1, "Apple", "XMP");
        T.contains(x1, "iPhone 17 Pro Max", "XMP");
        T.eq(x0.length(), x1.length(), "padding keeps the packet length");
        ExifTiffTest.assertNoNewWarnings(in, out.file);
        heifInfoOk(out.file);
        absentAll(out.data, "compressed");
    }

    static byte[] inflate(byte[] d) throws Exception {
        Inflater inf = new Inflater();
        inf.setInput(d);
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        while (!inf.finished()) {
            int n = inf.inflate(buf);
            if (n == 0 && inf.needsInput()) throw new AssertionError("truncated zlib stream");
            o.write(buf, 0, n);
        }
        inf.end();
        return o.toByteArray();
    }

    static byte[] deflate(byte[] d) {
        Deflater df = new Deflater();
        df.setInput(d);
        df.finish();
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        while (!df.finished()) o.write(buf, 0, df.deflate(buf));
        df.end();
        return o.toByteArray();
    }

    // =====================================================================================
    // Tests: hand-built layout matrix
    // =====================================================================================

    /** {ilocVersion, offSize, lenSize, baseSize, idxSize, mode, mdatFirst, littleEndian, exifPrefix, variant}. */
    static final int[][] MATRIX = {
            {0, 4, 4, 4, 0, 1, 0, 1, 1, 0},  // libheif style
            {0, 4, 4, 0, 0, 0, 0, 0, 1, 1},  // exiftool style, metadata before image, big-endian Exif
            {0, 4, 4, 4, 0, 0, 1, 1, 0, 0},  // mdat before meta, offset-0 Exif
            {1, 4, 4, 0, 0, 0, 0, 0, 1, 2},  // Apple style: iloc v1 last in meta, dinf
            {1, 0, 4, 4, 0, 1, 0, 1, 1, 0},  // offset_size 0 (base offsets only)
            {2, 8, 8, 8, 0, 2, 0, 0, 0, 3},  // iloc v2, 64-bit fields, free box in meta
            {1, 4, 4, 4, 4, 2, 1, 1, 1, 0},  // index_size 4, mdat first, mdat-relative base
            {2, 4, 8, 0, 0, 0, 0, 1, 1, 1},  // v2, metadata before image
            {0, 8, 4, 8, 0, 1, 1, 0, 1, 3},  // mdat first, free box in meta
            {1, 4, 4, 8, 0, 2, 0, 1, 0, 4},  // mdat extends to EOF (size 0)
            {0, 4, 4, 4, 0, 1, 0, 0, 1, 5},  // largesize mdat + uuid box + trailing garbage
    };

    static HB matrixFixture(Img img, int[] c) throws IOException {
        HB b = fixture(img);
        b.ilocVersion = c[0];
        b.offSize = c[1];
        b.lenSize = c[2];
        b.baseSize = c[3];
        b.idxSize = c[4];
        b.mode = c[5];
        b.mdatFirst = c[6] == 1;
        b.exif = exifPayload(huaweiTiff(c[7] == 1), c[8] == 1);
        switch (c[9]) {
            case 1:
                b.metaBeforeImage = true;
                break;
            case 2:
                b.ilocLast = true;
                b.dinf = true;
                break;
            case 3:
                b.freeInMeta = true;
                break;
            case 4:
                b.mdatToEof = true;
                break;
            case 5:
                b.mdatLarge = true;
                b.boxAfter = box("uuid", "0123456789abcdef".getBytes(StandardCharsets.US_ASCII),
                        "vendor trailer payload kept as is".getBytes(StandardCharsets.US_ASCII));
                b.trailer = new byte[]{0, 0, 7};
                break;
            default:
                break;
        }
        return b;
    }

    public static void testHandBuiltLayoutMatrixHeic() throws Exception {
        if (!tools()) return;
        matrix(heicImg(), "heic", 0, MATRIX.length);
    }

    public static void testHandBuiltLayoutMatrixAvif() throws Exception {
        if (!tools()) return;
        // a few representative layouts with AV1 data
        matrix(avifImg(), "avif", 0, 4);
    }

    private static void matrix(Img img, String ext, int from, int to) throws Exception {
        for (int i = from; i < to; i++) {
            int[] c = MATRIX[i];
            String name = "heif-mx" + i + "." + ext;
            HB b = matrixFixture(img, c);
            byte[] data = b.build();
            File in = write(name, data);
            sanity(in, img, true);
            Out out = edit(in, "out-" + name, T.appleSpec());
            T.check(hasChange(out.report, "EXIF Make: HUAWEI → Apple"), name + ": " + out.report);
            T.check(hasChangeStarting(out.report, "MakerNote temizlendi"), name + ": " + out.report);
            T.check(hasChange(out.report, "ICC üretici: HUAW → APPL"), name + ": " + out.report);
            T.check(out.report.warnings.isEmpty(), name + " warnings: " + out.report.warnings);
            verifyApple(in, out, img, true, true, true);
            // layout invariants: everything outside meta / metadata items / appended data is unchanged
            TM m0 = parse(data);
            TM m1 = parse(out.data);
            T.eq(md5(itemData(m0, b.imageId)), md5(itemData(m1, b.imageId)), name + " coded image bytes");
            T.eq(m0.ilocVersion, m1.ilocVersion, name + " iloc version kept");
            int tl = b.trailer == null ? 0 : b.trailer.length;
            if (b.boxAfter != null) {
                T.eq(out.data.length - tl - b.boxAfter.length, ExifTiffTest.indexOf(out.data, b.boxAfter),
                        name + ": trailing uuid box kept verbatim at the end");
            }
            if (b.trailer != null) {
                T.check(Arrays.equals(b.trailer, Arrays.copyOfRange(out.data, out.data.length - tl,
                        out.data.length)), name + ": trailer kept at the end");
            }
            if (b.mdatToEof) {
                TB2 last = m1.top.get(m1.top.size() - 1);
                T.check(last.type.equals("mdat") && u32(out.data, last.off) == 0, name + ": size-0 mdat still last");
            }
            // every byte outside meta and outside the old Exif/XMP items is unchanged (just shifted)
            long growth = out.data.length - data.length;
            List<long[]> md = new ArrayList<long[]>();
            md.addAll(Arrays.asList(absRanges(m0, b.exifId)));
            md.addAll(Arrays.asList(absRanges(m0, b.xmpId)));
            for (int p = 0; p < data.length; p++) {
                if (p >= m0.meta.off && p < m0.meta.end()) continue;
                boolean inItem = false;
                for (long[] r : md) if (p >= r[0] && p < r[1]) inItem = true;
                if (inItem) continue;
                T.check(out.data[(int) mapOld(m0, p, growth)] == data[p], name + ": byte changed at " + p);
            }
            // the unchanged-spec run is byte-identical
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            EditReport r0 = editDirect(data, bo, ExifTiffTest.nullSpec());
            T.check(Arrays.equals(data, bo.toByteArray()), name + ": null spec must be byte-identical");
            T.check(r0.changes.isEmpty() && r0.warnings.isEmpty(), name + ": null spec report " + r0);
        }
    }

    // =====================================================================================
    // Tests: idat items, multi-extent items, grow / shrink
    // =====================================================================================

    public static void testExifInIdatGrowsAndShrinks() throws Exception {
        if (!tools()) return;
        Img img = heicImg();
        for (int variant = 0; variant < 3; variant++) {
            HB b = fixture(img);
            b.ilocVersion = variant == 2 ? 2 : 1;
            b.exifCm = 1;
            b.exif = exifPayload(huaweiTiff(variant != 1), true);
            if (variant == 1) {
                b.ilocLast = true; // Apple order: idat before iloc
                b.dinf = true;
                b.baseSize = 0;
                b.mode = 0;
            }
            if (variant == 2) b.mdatFirst = true;
            String name = "heif-idat" + variant + ".heic";
            byte[] data = b.build();
            File in = write(name, data);
            sanity(in, img, false);
            TM m0 = parse(data);
            T.eq(1, m0.iloc.get(b.exifId).cm, "fixture Exif is in idat");

            // grow: the new payload is appended to idat, the old bytes are zeroed
            Out out = edit(in, "out-" + name, T.appleSpec());
            T.check(hasChange(out.report, "EXIF Make: HUAWEI → Apple"), name + ": " + out.report);
            TM m1 = parse(out.data);
            TItem ex = m1.iloc.get(b.exifId);
            T.eq(1, ex.cm, name + " still idat");
            T.check(m1.idatLen > m0.idatLen, name + " idat grew");
            T.check(ex.base + ex.ext.get(0)[0] >= m0.idatLen, name + " new Exif appended in idat");
            byte[] oldIdatNow = Arrays.copyOfRange(out.data, m1.idatStart, m1.idatStart + m0.idatLen);
            T.check(Arrays.equals("IDAT".getBytes(StandardCharsets.US_ASCII), Arrays.copyOf(oldIdatNow, 4)), name + " idat prefix kept");
            for (int i = 4; i < oldIdatNow.length; i++) T.check(oldIdatNow[i] == 0, name + " old idat Exif zeroed at " + i);
            checkAppleTiffTags(T.exif(ExifTiffTest.jpeg("idat-x" + variant + ".jpg", tiffOf(itemData(m1, b.exifId)))), name);
            T.eq(md5(itemData(m0, b.imageId)), md5(itemData(m1, b.imageId)), name + " image bytes");
            Map<String, String> t = T.exif(out.file, "-n");
            T.eq("APPL", t.get("ICC-header:DeviceManufacturer"), name + " ICC");
            T.eq("Apple", t.get("XMP-tiff:Make"), name + " XMP");
            ExifTiffTest.assertNoNewWarnings(in, out.file);
            heifInfoOk(out.file);
            T.eq(img.pixels, pixels(out.file), name + " pixels");
            absentAll(out.data, name);
            MediaInfo mi = T.inspect(out.file);
            T.eq("Apple", mi.make, name + " inspect make (idat Exif)");
            Out again = edit(out.file, "again-" + name, T.appleSpec());
            T.check(Arrays.equals(out.data, again.data), name + " idempotent: " + again.report);

            // same-size edit: in place inside idat, nothing moves
            EditSpec s = ExifTiffTest.nullSpec();
            s.make = "LG";
            s.model = "G7";
            Out small = edit(in, "small-" + name, s);
            T.eq(data.length, small.data.length, name + " in-place edit keeps the size");
            TM m2 = parse(small.data);
            T.eq(m0.idatLen, m2.idatLen, name + " idat size");
            Map<String, String> tx = T.exif(ExifTiffTest.jpeg("idat-s" + variant + ".jpg", tiffOf(itemData(m2, b.exifId))));
            T.eq("LG", tx.get("IFD0:Make"), name + " small make");
            T.eq("G7", tx.get("IFD0:Model"), name + " small model");
            // the old Make / Model values are gone from the Exif item (the ICC/XMP keep theirs:
            // editIcc / editXmp are off in this spec)
            byte[] smallExif = itemData(m2, b.exifId);
            ExifTiffTest.absent(smallExif, "HUAWEI\0", name);
            ExifTiffTest.absent(smallExif, "ALN-AL00\0", name);
            T.eq(md5(itemData(m0, b.imageId)), md5(itemData(m2, b.imageId)), name + " small: image bytes");
            heifInfoOk(small.file);
        }
    }

    public static void testMultiExtentExifIsConsolidated() throws Exception {
        if (!tools()) return;
        Img img = heicImg();
        HB b = fixture(img);
        b.exifSplit = 3;
        b.mode = 0;
        byte[] data = b.build();
        File in = write("heif-multi.heic", data);
        sanity(in, img, true);
        TM m0 = parse(data);
        T.eq(3, m0.iloc.get(b.exifId).ext.size(), "fixture has 3 extents");
        long[][] old = absRanges(m0, b.exifId);
        Out out = edit(in, "out-heif-multi.heic", T.appleSpec());
        verifyApple(in, out, img, true, true, true);
        TM m1 = parse(out.data);
        T.eq(1, m1.iloc.get(b.exifId).ext.size(), "rewritten as one extent");
        long growth = out.data.length - data.length;
        for (long[] r : old) {
            for (long p = r[0]; p < r[1]; p++) {
                T.check(out.data[(int) mapOld(m0, p, growth)] == 0, "old extent byte zeroed at " + p);
            }
        }
        // the junk between the parts is not item data and stays
        T.check(ExifTiffTest.indexOf(out.data, "gap".getBytes(StandardCharsets.US_ASCII)) > 0, "gaps kept");
    }

    public static void testExifValuesShrinkInPlace() throws Exception {
        if (!tools()) return;
        Img img = heicImg();
        for (boolean le : new boolean[]{true, false}) {
            HB b = fixture(img);
            b.exif = exifPayload(huaweiTiff(le), true);
            byte[] data = b.build();
            File in = write("heif-shrink-" + le + ".heic", data);
            TM m0 = parse(data);
            EditSpec s = ExifTiffTest.nullSpec();
            s.make = "LG";
            s.model = "G7";
            s.lensModel = "LG LENS";
            Out out = edit(in, "out-heif-shrink-" + le + ".heic", s);
            T.check(hasChange(out.report, "EXIF Make: HUAWEI → LG"), "report " + out.report);
            T.check(hasChange(out.report, "EXIF LensModel: HUAWEI REAR CAMERA → LG LENS"), "report " + out.report);
            T.eq(data.length, out.data.length, "same size");
            byte[] exifNow = itemData(parse(out.data), b.exifId);
            ExifTiffTest.absent(exifNow, "HUAWEI", "shrunk Exif");
            ExifTiffTest.absent(exifNow, "ALN-AL00\0", "shrunk Exif");
            TM m1 = parse(out.data);
            T.check(Arrays.equals(absRanges(m0, b.exifId)[0], absRanges(m1, b.exifId)[0]), "Exif stays in place");
            int diffs = 0;
            long[] r = absRanges(m0, b.exifId)[0];
            for (int i = 0; i < data.length; i++) {
                if (data[i] != out.data[i]) {
                    T.check(i >= r[0] && i < r[1], "byte changed outside the Exif item at " + i);
                    diffs++;
                }
            }
            T.check(diffs > 0, "something changed");
            Map<String, String> t = T.exif(out.file);
            T.eq("LG", t.get("IFD0:Make"), "make");
            T.eq("G7", t.get("IFD0:Model"), "model");
            T.eq("LG LENS", t.get("ExifIFD:LensModel"), "lens model");
            T.eq("HUAWEI", t.get("XMP-tiff:Make"), "XMP untouched (editXmp=false)");
            T.eq("HUAW", T.exif(out.file, "-n").get("ICC-header:DeviceManufacturer"), "ICC untouched (editIcc=false)");
            ExifTiffTest.assertNoNewWarnings(in, out.file);
            heifInfoOk(out.file);
            T.eq(img.pixels, pixels(out.file), "pixels");
        }
    }

    public static void testExifValuesRemovedAndAbsent() throws Exception {
        if (!tools()) return;
        Img img = heicImg();
        for (boolean le : new boolean[]{true, false}) {
            HB b = fixture(img);
            ExifTiffTest.Opt o = huaweiOpt(le);
            o.hostComputer = null; // absent in the input
            o.lensMake = null;
            b.exif = exifPayload(ExifTiffTest.build(o).tiff, true);
            String name = "heif-remove-" + le + ".heic";
            File in = write(name, b.build());
            EditSpec s = ExifTiffTest.nullSpec();
            s.make = "";
            s.software = "";
            s.lensModel = "";
            s.hostComputer = "";  // absent: nothing to remove, nothing created
            s.lensMake = "";
            s.model = "Pixel 10 Pro XL";
            s.removeMakerNote = true;
            s.clearSerials = true;
            Out out = edit(in, "out-" + name, s);
            T.check(hasChange(out.report, "EXIF Make kaldırıldı"), "report " + out.report);
            T.check(hasChange(out.report, "EXIF Software kaldırıldı"), "report " + out.report);
            T.check(hasChange(out.report, "EXIF LensModel kaldırıldı"), "report " + out.report);
            T.check(hasChange(out.report, "EXIF Model: ALN-AL00 → Pixel 10 Pro XL"), "report " + out.report);
            T.check(!hasChangeStarting(out.report, "EXIF HostComputer"), "absent HostComputer: " + out.report);
            T.check(!hasChangeStarting(out.report, "EXIF LensMake"), "absent LensMake: " + out.report);
            Map<String, String> t = T.exif(out.file);
            T.check(!t.containsKey("IFD0:Make") && !t.containsKey("IFD0:Software") && !t.containsKey("ExifIFD:LensModel"),
                    "removed tags: " + t);
            T.check(!t.containsKey("IFD0:HostComputer") && !t.containsKey("ExifIFD:LensMake"), "not created: " + t);
            T.eq("Pixel 10 Pro XL", t.get("IFD0:Model"), "model");
            T.check(!t.containsKey("ExifIFD:SerialNumber"), "serial removed");
            byte[] ex = itemData(parse(out.data), b.exifId);
            for (String v : VENDOR) ExifTiffTest.absent(ex, v, name + " Exif item");
            ExifTiffTest.assertNoNewWarnings(in, out.file);
            heifInfoOk(out.file);
            T.eq(img.pixels, pixels(out.file), "pixels");
        }
    }

    public static void testXmpPayloadShrinksInPlace() throws Exception {
        if (!tools()) return;
        Img img = heicImg();
        HB b = fixture(img);
        String packet = xmpPacket(false, false);
        b.xmp = packet.getBytes(StandardCharsets.UTF_8);
        byte[] data = b.build();
        File in = write("heif-xmp-shrink.heic", data);
        TM m0 = parse(data);
        long[] r0 = absRanges(m0, b.xmpId)[0];
        EditSpec s = ExifTiffTest.nullSpec();
        s.editXmp = true;
        s.make = "";
        s.model = "G7";
        Out out = edit(in, "out-heif-xmp-shrink.heic", s);
        T.check(hasChange(out.report, "XMP tiff:Make kaldırıldı"), "report " + out.report);
        T.check(hasChange(out.report, "XMP tiff:Model: ALN-AL00 → G7"), "report " + out.report);
        T.check(hasChange(out.report, "EXIF Make kaldırıldı"), "report " + out.report);
        TM m1 = parse(out.data);
        long[] r1 = absRanges(m1, b.xmpId)[0];
        // (the grown Exif went to a new mdat after meta, so everything after meta shifted by 'growth')
        long growth = out.data.length - data.length;
        T.eq(mapOld(m0, r0[0], growth), r1[0], "XMP stays in place");
        T.check(r1[1] - r1[0] < r0[1] - r0[0], "XMP length shrank");
        for (long p = r1[1]; p < mapOld(m0, r0[1], growth); p++) {
            T.check(out.data[(int) p] == 0, "freed XMP tail zeroed at " + p);
        }
        String x = new String(itemData(m1, b.xmpId), StandardCharsets.UTF_8);
        T.notContains(x, "tiff:Make", "XMP");
        T.contains(x, "tiff:Model='G7'", "XMP");
        T.check(x.endsWith("</x:xmpmeta>\n"), "packet intact");
        Map<String, String> t = T.exif(out.file);
        T.check(!t.containsKey("XMP-tiff:Make"), "XMP make removed");
        T.eq("G7", t.get("XMP-tiff:Model"), "XMP model");
        ExifTiffTest.assertNoNewWarnings(in, out.file);
        heifInfoOk(out.file);
    }

    public static void testExifGrowthInsertsNewMdatAndKeepsEverythingElse() throws Exception {
        if (!tools()) return;
        Img img = heicImg();
        for (boolean mdatFirst : new boolean[]{false, true}) {
            HB b = fixture(img);
            b.icc = null;
            b.xmp = null;
            b.mode = 0;
            b.baseSize = 0;
            b.mdatFirst = mdatFirst;
            byte[] data = b.build();
            String name = "heif-grow-" + mdatFirst + ".heic";
            File in = write(name, data);
            TM m0 = parse(data);
            long[] r0 = absRanges(m0, b.exifId)[0];
            Out out = edit(in, "out-" + name, T.appleSpec());
            TM m1 = parse(out.data);
            T.check(!Arrays.equals(Arrays.copyOfRange(data, m0.meta.off, m0.meta.end()),
                    Arrays.copyOfRange(out.data, m1.meta.off, m1.meta.end())), name + ": iloc updated");
            T.eq(m0.meta.size, m1.meta.size, name + ": meta size unchanged (no ICC/XMP edits)");
            TB2 added = newMdat(m1);
            long[] r1 = absRanges(m1, b.exifId)[0];
            T.eq((long) added.pay(), r1[0], name + ": Exif at the start of the new mdat");
            T.eq((long) added.end(), r1[1], name + ": new mdat holds just the Exif");
            T.eq((long) data.length + added.size, (long) out.data.length, name + ": only the new mdat was added");
            // every original byte outside meta is unchanged (shifted after meta) except the zeroed old Exif
            long growth = added.size;
            for (int i = 0; i < data.length; i++) {
                if (i >= m0.meta.off && i < m0.meta.end()) continue;
                byte nb = out.data[(int) mapOld(m0, i, growth)];
                if (i >= r0[0] && i < r0[1]) T.check(nb == 0, name + ": old Exif zeroed at " + i);
                else T.check(nb == data[i], name + ": byte changed at " + i);
            }
            T.eq(md5(itemData(m0, b.imageId)), md5(itemData(m1, b.imageId)), name + ": image");
            checkAppleTiffTags(T.exif(out.file), name);
            ExifTiffTest.assertNoNewWarnings(in, out.file);
            heifInfoOk(out.file);
            T.eq(img.pixels, pixels(out.file), name + ": pixels");
        }
    }

    public static void testMotionPhotoVideoBoxStaysLast() throws Exception {
        if (!tools()) return;
        Img img = heicImg();
        File mp4 = T.file("heif-motion.mp4");
        if (T.hasTool("ffmpeg")) {
            T.run("ffmpeg", "-y", "-v", "error", "-f", "lavfi", "-i", "testsrc=size=64x48:rate=5", "-t", "1",
                    "-pix_fmt", "yuv420p", mp4.getPath());
        } else {
            T.write(mp4, "not really a video".getBytes(StandardCharsets.US_ASCII));
        }
        byte[] video = T.read(mp4);
        byte[] mpvd = box("mpvd", video);
        for (boolean create : new boolean[]{false, true}) {
            HB b = fixture(img);
            if (create) b.exif = null;
            b.boxAfter = mpvd;
            byte[] data = b.build();
            String name = "heif-motion-" + create + ".heic";
            File in = write(name, data);
            Out out = edit(in, "out-" + name, T.appleSpec());
            T.check(out.data.length > data.length, name + ": grew");
            T.eq(out.data.length - mpvd.length, ExifTiffTest.indexOf(out.data, mpvd), name + ": video box is still last");
            TM m1 = parse(out.data);
            T.eq("mpvd", m1.top.get(m1.top.size() - 1).type, name + ": last box");
            T.eq("Apple", T.exif(out.file).get("IFD0:Make"), name + ": make");
            heifInfoOk(out.file);
            T.eq(img.pixels, pixels(out.file), name + ": pixels");
            ExifTiffTest.assertNoNewWarnings(in, out.file);
        }
    }

    public static void testZeroLengthExtentMeansToEndOfBox() throws Exception {
        if (!tools()) return;
        Img img = heicImg();
        HB b = fixture(img);
        b.exif = null;
        b.icc = null;
        byte[] data = b.build(); // mdat = image, XMP (XMP ends the mdat)
        TM m0 = parse(data);
        TB2 iloc = m0.kid("iloc");
        int q = findIlocItem(data, iloc, b.xmpId);
        long[] r0 = absRanges(m0, b.xmpId)[0];
        put32(data, q + 10 + 4, 0); // extent_length = 0: "to the end of the containing box"
        File in = write("heif-len0.heic", data);
        MediaInfo mi = T.inspect(in);
        T.eq("HUAWEI", mi.make, "inspect reads the length-0 XMP");
        Out out = edit(in, "out-heif-len0.heic", T.appleSpec());
        T.check(hasChange(out.report, "XMP tiff:Make: HUAWEI → Apple"), "report " + out.report);
        TM m1 = parse(out.data);
        long[] r1 = absRanges(m1, b.xmpId)[0];
        T.eq(r0[1] - r0[0], r1[1] - r1[0], "explicit length now");
        String x = new String(itemData(m1, b.xmpId), StandardCharsets.UTF_8);
        T.contains(x, "tiff:Make='Apple'", "XMP");
        T.check(x.endsWith("<?xpacket end='w'?>"), "whole packet");
        heifInfoOk(out.file);
        T.eq(img.pixels, pixels(out.file), "pixels");
    }

    public static void testLargesizeMetaHeaderKept() throws Exception {
        if (!tools()) return;
        Img img = heicImg();
        HB b = fixture(img);
        b.mdatFirst = true; // item offsets point before meta: unaffected by the longer header
        byte[] small = b.build();
        TM m0 = parse(small);
        T.eq(m0.top.size() - 1, m0.top.indexOf(m0.meta), "meta is last");
        byte[] large = cat(Arrays.copyOf(small, m0.meta.off), be(1, 4), "meta".getBytes(StandardCharsets.US_ASCII),
                be(m0.meta.size + 8, 8), Arrays.copyOfRange(small, m0.meta.off + 8, small.length));
        File in = write("heif-largemeta.heic", large);
        sanity(in, img, true);
        Out out = edit(in, "out-heif-largemeta.heic", T.appleSpec());
        TM m1 = parse(out.data);
        T.eq(1L, u32(out.data, m1.meta.off), "largesize header kept");
        T.eq(16, m1.meta.hs, "16-byte header");
        verifyApple(in, out, img, true, true, true);
    }

    public static void testExifOffsetFieldWrongButPrefixed() throws Exception {
        if (!tools()) return;
        Img img = heicImg();
        HB b = fixture(img);
        // offset says 0 but "Exif\0\0" precedes the TIFF header (seen in some writers)
        b.exif = cat(be(0, 4), "Exif\0\0".getBytes(StandardCharsets.ISO_8859_1), huaweiTiff(false));
        byte[] data = b.build();
        MediaInfo mi = new MediaInfo();
        HeifEditor.inspect(new ByteArraySource(data), mi);
        T.eq("HUAWEI", mi.make, "inspect tolerates the wrong offset");
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        EditReport r = editDirect(data, bo, T.appleSpec());
        T.check(hasChange(r, "EXIF Make: HUAWEI → Apple"), "edited: " + r);
        TM m1 = parse(bo.toByteArray());
        byte[] p = itemData(m1, b.exifId);
        T.eq(0L, u32(p, 0), "offset field kept verbatim");
        T.eq("Exif\0\0", new String(p, 4, 6, StandardCharsets.ISO_8859_1), "prefix kept");
        Map<String, String> t = T.exif(ExifTiffTest.jpeg("heif-wrongoff.jpg", Arrays.copyOfRange(p, 10, p.length)));
        T.eq("Apple", t.get("IFD0:Make"), "make");
    }

    // =====================================================================================
    // Tests: Exif creation, IDs, flags
    // =====================================================================================

    public static void testCreateExifWhenMissing() throws Exception {
        if (!tools()) return;
        Img img = heicImg();
        // (a) heif-enc output: no Exif, no iref at all
        File in = T.file("heif-noexif.heic");
        T.write(in, T.read(img.src));
        Out out = edit(in, "out-heif-noexif.heic", T.appleSpec());
        T.check(hasChange(out.report, "HEIF Exif öğesi oluşturuldu"), "report " + out.report);
        T.check(hasChange(out.report, "EXIF Make eklendi: Apple"), "report " + out.report);
        T.check(hasChange(out.report, "EXIF LensModel eklendi: iPhone 17 Pro Max back triple camera 6.765mm f/1.78"), "report " + out.report);
        checkCreated(in, out, img, 1);

        // (b) XMP only (iref exists, version 1)
        HB b = fixture(img);
        b.exif = null;
        b.irefVersion = 1;
        File in2 = write("heif-xmponly.heic", b.build());
        Out out2 = edit(in2, "out-heif-xmponly.heic", T.appleSpec());
        long id2 = checkCreated(in2, out2, img, 1);
        T.eq(4L, id2, "new id = max + 1");
        TM m2 = parse(out2.data);
        T.eq(1, m2.irefVersion, "iref version kept");
        T.check(containsRef(m2, b.xmpId, b.imageId), "old cdsc kept");
        T.eq("Apple", T.exif(out2.file).get("XMP-tiff:Make"), "XMP edited too");

        // (c) creation disabled / nothing to write
        EditSpec off = T.appleSpec();
        off.createExifIfMissing = false;
        off.editIcc = false;
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] plain = T.read(img.src);
        editDirect(plain, bo, off);
        T.check(Arrays.equals(plain, bo.toByteArray()), "createExifIfMissing=false: unchanged");
        EditSpec noName = T.appleSpec();
        noName.make = null;
        noName.model = "";
        bo.reset();
        editDirect(plain, bo, noName);
        T.check(Arrays.equals(plain, bo.toByteArray()), "no make/model: no Exif created");
    }

    static boolean containsRef(TM m, long from, long to) {
        for (long[] r : m.cdsc) if (r[0] == from && r[1] == to) return true;
        return false;
    }

    /** Verifies a created Exif item; returns its ID. */
    static long checkCreated(File in, Out out, Img img, long primary) throws IOException {
        String n = out.file.getName();
        TM m = parse(out.data);
        long id = itemOfType(m, "Exif");
        T.check(id > 0, n + ": Exif item created");
        T.eq(1, m.infeFlags.get(id) & 1, n + ": hidden");
        T.check(containsRef(m, id, primary), n + ": cdsc to the primary image");
        byte[] p = itemData(m, id);
        T.eq(6L, u32(p, 0), n + ": Apple/Android style offset");
        T.eq("Exif\0\0", new String(p, 4, 6, StandardCharsets.ISO_8859_1), n + ": Exif\\0\\0 prefix");
        Map<String, String> t = T.exif(out.file, "-n");
        T.eq("Apple", t.get("IFD0:Make"), n + " make");
        T.eq("iPhone 17 Pro Max", t.get("IFD0:Model"), n + " model");
        T.eq("26.0.1", t.get("IFD0:Software"), n + " software");
        T.eq("Apple", t.get("ExifIFD:LensMake"), n + " lens make");
        T.eq(String.valueOf(img.w), t.get("ExifIFD:ExifImageWidth"), n + " width");
        T.eq(String.valueOf(img.h), t.get("ExifIFD:ExifImageHeight"), n + " height");
        ExifTiffTest.assertNoNewWarnings(in, out.file);
        heifInfoOk(out.file);
        T.Result hi = T.exec("heif-info", out.file.getPath());
        T.contains(hi.out, "Exif:", n + " heif-info sees the Exif");
        T.eq(img.pixels, pixels(out.file), n + " pixels");
        Out again = edit(out.file, "again-" + n, T.appleSpec());
        T.check(Arrays.equals(out.data, again.data), n + " idempotent: " + again.report);
        T.check(again.report.changes.isEmpty(), n + " no second creation: " + again.report);
        return id;
    }

    public static void testLargeItemIdsAndIlocVersionUpgrade() throws Exception {
        if (!tools()) return;
        Img img = heicImg();
        // (a) 32-bit IDs everywhere (iloc v2, infe v3, iref v1, pitm v1, ipma v1)
        HB b = fixture(img);
        b.ilocVersion = 2;
        b.irefVersion = 1;
        b.imageId = 70000;
        b.exifId = 70001;
        b.xmpId = 70002;
        File in = write("heif-bigids.heic", b.build());
        sanity(in, img, true);
        Out out = edit(in, "out-heif-bigids.heic", T.appleSpec());
        verifyApple(in, out, img, true, true, true);

        // (b) iloc v0 / infe v2 / no iref, primary ID 65535: the new Exif gets ID 65536 which
        //     forces iloc v2, infe v3 and a version-1 iref
        HB c = new HB();
        c.img = img;
        c.imageId = 65535;
        c.icc = huaweiIcc();
        File in2 = write("heif-maxid.heic", c.build());
        sanity(in2, img, false);
        Out out2 = edit(in2, "out-heif-maxid.heic", T.appleSpec());
        TM m = parse(out2.data);
        T.eq(2, m.ilocVersion, "iloc upgraded to v2");
        T.eq(1, m.irefVersion, "iref v1 for 32-bit IDs");
        long id = checkCreated(in2, out2, img, 65535);
        T.eq(65536L, id, "new id");
    }

    public static void testSharedExifItemsEditedOnce() throws Exception {
        if (!tools()) return;
        Img img = heicImg();
        HB b = fixture(img);
        b.sharedExif = true;
        File in = write("heif-shared.heic", b.build());
        Out out = edit(in, "out-heif-shared.heic", T.appleSpec());
        int makes = 0;
        for (String c : out.report.changes) if (c.equals("EXIF Make: HUAWEI → Apple")) makes++;
        T.eq(1, makes, "edited once: " + out.report);
        TM m = parse(out.data);
        T.check(Arrays.deepEquals(absRanges(m, b.exifId), absRanges(m, b.sharedId)), "both items share the new data");
        verifyApple(in, out, img, true, true, true);
    }

    public static void testFlagsAreRespected() throws Exception {
        if (!tools()) return;
        Img img = heicImg();
        File in = write("heif-flags.heic", fixture(img).build());
        EditSpec s = T.appleSpec();
        s.editIcc = false;
        s.editXmp = false;
        s.removeMakerNote = false;
        s.clearSerials = false;
        Out out = edit(in, "out-heif-flags.heic", s);
        Map<String, String> t = T.exif(out.file, "-n");
        T.eq("Apple", t.get("IFD0:Make"), "make");
        T.eq("HUAW", t.get("ICC-header:DeviceManufacturer"), "ICC kept");
        T.eq("HUAWEI", t.get("ICC_Profile:DeviceMfgDesc"), "dmnd kept");
        T.eq("HUAWEI", t.get("XMP-tiff:Make"), "XMP kept");
        T.eq("SN-HW-123456", t.get("ExifIFD:SerialNumber"), "serial kept");
        ExifTiffTest.present(out.data, "VNDRNOTE", "MakerNote kept");
        for (String c : out.report.changes) {
            T.check(!c.startsWith("ICC") && !c.startsWith("XMP") && !c.startsWith("MakerNote"), "unexpected change " + c);
        }
        // and the opposite: only ICC
        EditSpec onlyIcc = ExifTiffTest.nullSpec();
        onlyIcc.editIcc = true;
        onlyIcc.icc = T.appleSpec().icc;
        Out o2 = edit(in, "out-heif-onlyicc.heic", onlyIcc);
        Map<String, String> t2 = T.exif(o2.file, "-n");
        T.eq("HUAWEI", t2.get("IFD0:Make"), "Exif kept");
        T.eq("APPL", t2.get("ICC-header:DeviceManufacturer"), "ICC edited");
        for (String c : o2.report.changes) T.check(c.startsWith("ICC"), "only ICC changes: " + c);
        ExifTiffTest.assertNoNewWarnings(in, o2.file);
        heifInfoOk(o2.file);
        T.eq(img.pixels, pixels(o2.file), "pixels");
    }

    public static void testIccWithProfileIdV4() throws Exception {
        if (!tools()) return;
        Img img = heicImg();
        HB b = fixture(img);
        b.icc = IccProfileEditorTest.v4Profile(true, true);
        b.xmp = null;
        File in = write("heif-iccv4.heic", b.build());
        Out out = edit(in, "out-heif-iccv4.heic", T.appleSpec());
        T.check(hasChange(out.report, "ICC profil kimliği (MD5) yeniden hesaplandı"), "report " + out.report);
        Map<String, String> t = T.exif(out.file, "-n");
        T.eq("APPL", t.get("ICC-header:DeviceManufacturer"), "ICC manufacturer");
        // the profile ID exiftool reads equals the MD5 of the extracted profile (with zeroed fields)
        byte[] prof = ExifTiffTest.execBytes("exiftool", "-b", "-ICC_Profile", out.file.getPath());
        T.check(prof.length > 128, "extracted ICC");
        T.check(Arrays.equals(IccProfileEditorTest.md5Id(prof), Arrays.copyOfRange(prof, 84, 100)), "valid MD5 profile ID");
        ExifTiffTest.assertNoNewWarnings(in, out.file);
        heifInfoOk(out.file);
        T.eq(img.pixels, pixels(out.file), "pixels");
    }

    // =====================================================================================
    // Tests: identity, inspect, progress
    // =====================================================================================

    public static void testUnchangedSpecIsByteIdentical() throws Exception {
        if (!tools()) return;
        List<byte[]> inputs = new ArrayList<byte[]>();
        inputs.add(T.read(heicImg().src));
        inputs.add(T.read(avifImg().src));
        for (int i = 0; i < MATRIX.length; i++) inputs.add(matrixFixture(heicImg(), MATRIX[i]).build());
        EditSpec allNull = ExifTiffTest.nullSpec();
        EditSpec defaultsNoValues = new EditSpec(); // flags on, but nothing applicable on a file without Exif
        for (int i = 0; i < inputs.size(); i++) {
            byte[] in = inputs.get(i);
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            EditReport r = editDirect(in, bo, allNull);
            T.check(Arrays.equals(in, bo.toByteArray()), "input " + i + " not byte-identical");
            T.check(r.changes.isEmpty(), "input " + i + " report " + r);
            bo.reset();
            HeifEditor.edit(new ByteArraySource(in), bo, null, null, null);
            T.check(Arrays.equals(in, bo.toByteArray()), "null spec, input " + i);
        }
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] plain = inputs.get(0);
        editDirect(plain, bo, defaultsNoValues);
        T.check(Arrays.equals(plain, bo.toByteArray()), "default spec on a metadata-free file");
    }

    public static void testInspect() throws Exception {
        if (!tools()) return;
        Img img = heicImg();
        File in = write("heif-inspect.heic", fixture(img).build());
        MediaInfo mi = T.inspect(in);
        T.eq(MediaFormat.HEIF, mi.format, "format");
        T.eq("HUAWEI", mi.make, "make");
        T.eq("ALN-AL00", mi.model, "model");
        T.eq("HUAWEI REAR CAMERA", mi.lensModel, "lens");
        T.eq("2025:06:01 12:00:00", mi.dateTimeOriginal, "date");
        T.check(mi.hasExif && mi.hasMakerNote && mi.hasXmp && mi.hasIcc, "flags");
        T.eq("var", mi.gpsStatus, "gps");
        T.eq("HUAW", mi.iccManufacturer, "icc manufacturer");
        T.eq("HUAWEI", mi.iccManufacturerText, "dmnd");
        T.eq("ALN-AL00", mi.iccModelText, "dmdd");
        T.eq(96, mi.width, "width");
        T.eq(64, mi.height, "height");
        T.eq("HUAWEI ALN-AL00", mi.deviceLabel(), "label");

        // XMP fills only what Exif lacks
        HB b = fixture(img);
        b.exif = null;
        File x = write("heif-inspect-xmp.heic", b.build());
        MediaInfo mx = T.inspect(x);
        T.check(!mx.hasExif && mx.hasXmp, "xmp only");
        T.eq("HUAWEI", mx.make, "make from XMP");
        T.eq("ALN-AL00 4.0.0.120(C00E120R1P1)", mx.software, "software from XMP");
        T.eq("yok", mx.gpsStatus, "no gps");

        MediaInfo mp = T.inspect(img.src);
        T.check(!mp.hasExif && !mp.hasXmp && !mp.hasIcc, "plain file");
        T.eq(96, mp.width, "plain width");
        MediaInfo ma = T.inspect(avifImg().src);
        T.eq(MediaFormat.AVIF, ma.format, "avif format");
        T.eq(96, ma.width, "avif width");
    }

    public static void testProgressIsReported() throws Exception {
        if (!tools()) return;
        final List<long[]> calls = new ArrayList<long[]>();
        ProgressListener l = new ProgressListener() {
            @Override
            public void onProgress(long done, long total) {
                calls.add(new long[]{done, total});
            }
        };
        byte[] in = fixture(heicImg()).build();
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        MetadataEditor.edit(new ByteArraySource(in), bo, T.appleSpec(), l);
        T.check(!calls.isEmpty(), "progress reported");
        long[] last = calls.get(calls.size() - 1);
        T.eq((long) bo.size(), last[0], "final progress = output size");
        T.eq((long) bo.size(), last[1], "total = output size");
        for (int i = 1; i < calls.size(); i++) T.check(calls.get(i)[0] >= calls.get(i - 1)[0], "monotonic");
    }

    // =====================================================================================
    // Tests: malformed input
    // =====================================================================================

    /** Runs edit + inspect; only UnsupportedFormatException may escape. Returns true if the edit worked. */
    static boolean robust(byte[] in, EditSpec spec, String what) {
        boolean ok = false;
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            EditReport r = new EditReport();
            HeifEditor.edit(new ByteArraySource(in), bo, spec, r, null);
            ok = true;
            // whatever we wrote must be editable again without internal errors
            byte[] out = bo.toByteArray();
            try {
                HeifEditor.edit(new ByteArraySource(out), new ByteArrayOutputStream(), spec, new EditReport(), null);
            } catch (UnsupportedFormatException e) {
                T.check(e.getCause() == null, what + ": internal error on re-edit: " + e.getCause());
            }
        } catch (UnsupportedFormatException e) {
            if (e.getCause() != null) {
                AssertionError ae = new AssertionError(what + ": internal error (" + e.getMessage() + ")");
                ae.initCause(e.getCause());
                throw ae;
            }
        } catch (IOException e) {
            throw new AssertionError(what + ": unexpected " + e, e);
        } catch (RuntimeException e) {
            AssertionError ae = new AssertionError(what + ": RuntimeException");
            ae.initCause(e);
            throw ae;
        }
        try {
            HeifEditor.inspect(new ByteArraySource(in), new MediaInfo());
        } catch (UnsupportedFormatException e) {
            // fine
        } catch (IOException e) {
            throw new AssertionError(what + ": inspect unexpected " + e, e);
        } catch (RuntimeException e) {
            AssertionError ae = new AssertionError(what + ": inspect RuntimeException");
            ae.initCause(e);
            throw ae;
        }
        return ok;
    }

    static byte[] copy(byte[] b) {
        return Arrays.copyOf(b, b.length);
    }

    static void put32(byte[] b, int o, long v) {
        System.arraycopy(be(v, 4), 0, b, o, 4);
    }

    static void expectUnsupported(byte[] in, String what) {
        try {
            HeifEditor.edit(new ByteArraySource(in), new ByteArrayOutputStream(), T.appleSpec(), new EditReport(), null);
            throw new AssertionError(what + ": expected UnsupportedFormatException");
        } catch (UnsupportedFormatException e) {
            T.check(e.getMessage() != null && !e.getMessage().isEmpty(), what + ": message");
            T.check(e.getCause() == null, what + ": internal error " + e.getCause());
        } catch (IOException e) {
            throw new AssertionError(what + ": " + e, e);
        }
    }

    public static void testHandCraftedCorruptInputs() throws Exception {
        if (!tools()) return;
        Img img = heicImg();
        HB b = fixture(img);
        byte[] good = b.build();
        TM m = parse(good);
        TB2 iloc = m.kid("iloc");
        TB2 iinf = m.kid("iinf");
        TB2 iref = m.kid("iref");
        TB2 iprp = m.kid("iprp");

        // no meta at all / only ftyp / empty / garbage
        expectUnsupported(Arrays.copyOf(good, 28), "ftyp only");
        expectUnsupported(new byte[0], "empty");
        byte[] noMeta = copy(good);
        System.arraycopy("free".getBytes(StandardCharsets.US_ASCII), 0, noMeta, m.meta.off + 4, 4);
        expectUnsupported(noMeta, "meta renamed");
        // meta size beyond the end of the file -> the meta is unparseable
        byte[] bigMeta = copy(good);
        put32(bigMeta, m.meta.off, 0x7FFFFFF0L);
        expectUnsupported(bigMeta, "meta too big");
        // 64-bit box size overflowing the position arithmetic
        byte[] ovf = copy(good);
        put32(ovf, m.meta.off, 1);
        robust(ovf, T.appleSpec(), "largesize meta");
        byte[] ovf2 = cat(Arrays.copyOf(good, 28), be(1, 4), "free".getBytes(StandardCharsets.US_ASCII),
                be(0x7FFFFFFFFFFFFFF0L, 8), Arrays.copyOfRange(good, 28, good.length));
        robust(ovf2, T.appleSpec(), "overflowing largesize");
        // iloc: unsupported version, huge counts, invalid field sizes
        byte[] v3 = copy(good);
        v3[iloc.pay()] = 3;
        expectUnsupported(v3, "iloc v3");
        byte[] cnt = copy(good);
        cnt[iloc.pay() + 6] = (byte) 0xFF;
        cnt[iloc.pay() + 7] = (byte) 0xFF;
        expectUnsupported(cnt, "iloc count");
        byte[] fs = copy(good);
        fs[iloc.pay() + 4] = (byte) 0x9F;
        expectUnsupported(fs, "iloc field size 9");
        // Exif extent beyond the end of the file: warned, the rest is still edited
        byte[] far = copy(good);
        TItem ex = m.iloc.get(b.exifId);
        int exItemPos = findIlocItem(good, iloc, b.exifId);
        put32(far, exItemPos + 2 + 2, 0x7FFFFF00L); // base_offset (v0: id u16, dri u16, base u32)
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        EditReport r = editDirect(far, bo, T.appleSpec());
        T.check(hasWarning(r, "HEIF Exif verisi okunamadı"), "far Exif warning: " + r);
        T.check(hasChange(r, "ICC üretici: HUAW → APPL"), "ICC still edited: " + r);
        T.check(hasChange(r, "XMP tiff:Make: HUAWEI → Apple"), "XMP still edited: " + r);
        T.check(ex != null, "exif item");
        // Exif payload that is not TIFF
        HB nt = fixture(img);
        nt.exif = cat(be(6, 4), "Exif\0\0".getBytes(StandardCharsets.ISO_8859_1), "this is not a TIFF header at all".getBytes(StandardCharsets.US_ASCII));
        r = editDirect(nt.build(), new ByteArrayOutputStream(), T.appleSpec());
        T.check(hasWarning(r, "TIFF başlığı yok"), "no-TIFF warning: " + r);
        // TIFF header but broken IFD
        HB bt = fixture(img);
        bt.exif = exifPayload(new byte[]{'M', 'M', 0, 42, 0x7F, 0, 0, 0, 1, 2, 3}, true);
        r = editDirect(bt.build(), new ByteArrayOutputStream(), T.appleSpec());
        T.check(hasWarning(r, "HEIF Exif verisi bozuk"), "broken TIFF warning: " + r);
        // XMP that is not UTF-8
        HB nx = fixture(img);
        nx.xmp = new byte[]{'<', '?', 'x', (byte) 0xC3, (byte) 0x28, 'a'};
        r = editDirect(nx.build(), new ByteArrayOutputStream(), T.appleSpec());
        T.check(hasWarning(r, "UTF-8"), "XMP warning: " + r);
        // XMP with an unknown content encoding / broken deflate data
        HB ux = fixture(img);
        ux.xmpEnc = "br";
        r = editDirect(ux.build(), new ByteArrayOutputStream(), T.appleSpec());
        T.check(hasWarning(r, "sıkıştırılmış"), "encoding warning: " + r);
        HB dx = fixture(img);
        dx.xmpEnc = "deflate";
        r = editDirect(dx.build(), new ByteArrayOutputStream(), T.appleSpec());
        T.check(hasWarning(r, "XMP verisi açılamadı"), "inflate warning: " + r);
        HB okx = fixture(img);
        okx.xmpEnc = "deflate";
        okx.xmp = deflate(xmpPacket(true).getBytes(StandardCharsets.UTF_8));
        r = editDirect(okx.build(), new ByteArrayOutputStream(), T.appleSpec());
        T.check(hasChange(r, "XMP tiff:Make: HUAWEI → Apple"), "deflate XMP edited: " + r);
        // broken iprp: ICC untouched with a warning, Exif still edited
        byte[] bp = copy(good);
        put32(bp, iprp.pay(), 0x0FFFFFFFL); // ipco size
        r = editDirect(bp, new ByteArrayOutputStream(), T.appleSpec());
        T.check(hasWarning(r, "iprp"), "iprp warning: " + r);
        T.check(hasChange(r, "EXIF Make: HUAWEI → Apple"), "Exif still edited: " + r);
        // broken iref + missing Exif: creation skipped with a warning
        HB ne = fixture(img);
        ne.exif = null;
        byte[] bi = ne.build();
        TM mi = parse(bi);
        TB2 ir = mi.kid("iref");
        put32(bi, ir.pay() + 4, 0xFFFF); // child box size
        r = editDirect(bi, new ByteArrayOutputStream(), T.appleSpec());
        T.check(hasWarning(r, "iref"), "iref warning: " + r);
        // encrypted Exif item
        byte[] enc = copy(good);
        int infePos = findInfe(good, iinf, b.exifId);
        enc[infePos + 12 + 2] = 0;
        enc[infePos + 12 + 3] = 1; // protection index
        r = editDirect(enc, new ByteArrayOutputStream(), T.appleSpec());
        T.check(hasWarning(r, "şifreli"), "protected warning: " + r);
        T.check(iref != null, "iref present");
        // moov present -> not a still image
        expectUnsupported(cat(good, box("moov", new byte[8])), "moov");
        // trailing bytes that do not parse as a box are kept verbatim
        byte[] tr = cat(good, new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9});
        bo = new ByteArrayOutputStream();
        editDirect(tr, bo, T.appleSpec());
        byte[] o = bo.toByteArray();
        T.check(Arrays.equals(Arrays.copyOfRange(o, o.length - 9, o.length), new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9}), "trailer at end");
    }

    static int findIlocItem(byte[] f, TB2 iloc, long id) {
        // v0 with 4/4/4 sizes: items start at pay+8, each: id(2) dri(2) base(4) count(2) extents(8 each)
        int q = iloc.pay() + 8;
        while (q < iloc.end()) {
            int ec = u16(f, q + 8);
            if (u16(f, q) == id) return q;
            q += 10 + ec * 8;
        }
        throw new AssertionError("iloc item " + id);
    }

    static int findInfe(byte[] f, TB2 iinf, long id) {
        for (TB2 e : boxes(f, iinf.pay() + 6, iinf.end())) {
            if (u16(f, e.pay() + 4) == id) return e.off;
        }
        throw new AssertionError("infe " + id);
    }

    public static void testFuzzNeverThrowsRuntimeException() throws Exception {
        if (!tools()) return;
        List<byte[]> seeds = new ArrayList<byte[]>();
        seeds.add(fixture(heicImg()).build());
        seeds.add(matrixFixture(heicImg(), MATRIX[3]).build());
        seeds.add(matrixFixture(heicImg(), MATRIX[5]).build());
        HB id = fixture(heicImg());
        id.exifCm = 1;
        id.ilocVersion = 1;
        seeds.add(id.build());
        HB noExif = fixture(heicImg());
        noExif.exif = null;
        seeds.add(noExif.build());
        // -Dheif.fuzz.iterations=N / -Dheif.fuzz.seed=S for longer local runs
        int per = Integer.getInteger("heif.fuzz.iterations", 300);
        Random rnd = new Random(Long.getLong("heif.fuzz.seed", 20261101L));
        EditSpec[] specs = {T.appleSpec(), ExifTiffTest.nullSpec()};
        int n = 0;
        int ok = 0;
        for (byte[] seed : seeds) {
            TM m = parse(seed);
            int ms = m.meta.off;
            int me = m.meta.end();
            for (int iter = 0; iter < per; iter++) {
                byte[] f = copy(seed);
                int kind = rnd.nextInt(4);
                if (kind == 0) {
                    f = Arrays.copyOf(f, rnd.nextInt(f.length + 1));
                } else if (kind == 1) {
                    int flips = 1 + rnd.nextInt(6);
                    for (int k = 0; k < flips; k++) f[ms + rnd.nextInt(me - ms)] = (byte) rnd.nextInt(256);
                } else if (kind == 2) {
                    // 32-bit fields inside meta set to interesting values
                    long[] vals = {0, 1, 7, 8, 0xFFFF, 0x10000, 0x7FFFFFFFL, 0xFFFFFFFFL, f.length, f.length - 1};
                    int p = ms + rnd.nextInt(me - ms - 4);
                    put32(f, p, vals[rnd.nextInt(vals.length)]);
                } else {
                    for (int k = 0; k < 3; k++) f[rnd.nextInt(f.length)] = (byte) rnd.nextInt(256);
                }
                if (robust(f, specs[iter % 2], "fuzz " + n)) ok++;
                n++;
            }
        }
        T.check(n == 5 * per, "iterations " + n);
        T.check(ok > n / 4, "too few edits succeeded: " + ok);
    }
}
