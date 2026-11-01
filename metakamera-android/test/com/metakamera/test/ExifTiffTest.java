package com.metakamera.test;

import com.metakamera.core.EditReport;
import com.metakamera.core.EditSpec;
import com.metakamera.core.MediaInfo;
import com.metakamera.core.UnsupportedFormatException;
import com.metakamera.core.tiff.ExifTiff;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;

/** Tests for {@link ExifTiff}: append-only editing, compaction fallback, createMinimal, inspect, robustness. */
public final class ExifTiffTest {

    private static final int APP1_MAX = 65527;

    // =====================================================================================
    // Independent TIFF builder (does not use production code)
    // =====================================================================================

    static final int BYTE = 1, ASCII = 2, SHORT = 3, LONG = 4, RATIONAL = 5, UNDEF = 7;

    static final class Ent {
        final int tag;
        int type;
        long count;
        byte[] data;
        int fieldPos;
        int dataPos;
        /** When set, the 4-byte field is written verbatim (no out-of-line data). */
        byte[] rawField;

        Ent(int tag, int type, long count, byte[] data) {
            this.tag = tag;
            this.type = type;
            this.count = count;
            this.data = data;
        }
    }

    static final class TB {
        final boolean le;
        byte[] b = new byte[4096];
        int len;
        int nextPos;

        TB(boolean le) {
            this.le = le;
            w8(le ? 'I' : 'M');
            w8(le ? 'I' : 'M');
            w16(42);
            w32(0);
        }

        void ensure(int n) {
            if (len + n > b.length) b = Arrays.copyOf(b, Math.max(b.length * 2, len + n + 16));
        }

        int align() {
            if ((len & 1) != 0) w8(0);
            return len;
        }

        void w8(int v) {
            ensure(1);
            b[len++] = (byte) v;
        }

        void w16(int v) {
            ensure(2);
            put16(len, v);
            len += 2;
        }

        void w32(long v) {
            ensure(4);
            put32(len, v);
            len += 4;
        }

        void put16(int p, int v) {
            if (le) {
                b[p] = (byte) v;
                b[p + 1] = (byte) (v >> 8);
            } else {
                b[p] = (byte) (v >> 8);
                b[p + 1] = (byte) v;
            }
        }

        void put32(int p, long v) {
            if (le) {
                for (int i = 0; i < 4; i++) b[p + i] = (byte) (v >> (8 * i));
            } else {
                for (int i = 0; i < 4; i++) b[p + i] = (byte) (v >> (24 - 8 * i));
            }
        }

        int wbytes(byte[] d) {
            ensure(d.length);
            int p = len;
            System.arraycopy(d, 0, b, len, d.length);
            len += d.length;
            return p;
        }

        int writeIfd(List<Ent> es) {
            int pos = align();
            w16(es.size());
            for (Ent e : es) {
                w16(e.tag);
                w16(e.type);
                w32(e.count);
                e.fieldPos = len;
                w32(0);
            }
            nextPos = len;
            w32(0);
            for (Ent e : es) {
                if (e.rawField != null) {
                    System.arraycopy(e.rawField, 0, b, e.fieldPos, 4);
                    e.dataPos = -1;
                } else if (e.data.length > 4) {
                    e.dataPos = align();
                    wbytes(e.data);
                    put32(e.fieldPos, e.dataPos);
                } else {
                    System.arraycopy(e.data, 0, b, e.fieldPos, e.data.length);
                    e.dataPos = e.fieldPos;
                }
            }
            return pos;
        }

        byte[] bytes() {
            return Arrays.copyOf(b, len);
        }

        // encoders
        byte[] sh(int... v) {
            byte[] r = new byte[v.length * 2];
            for (int i = 0; i < v.length; i++) {
                if (le) {
                    r[2 * i] = (byte) v[i];
                    r[2 * i + 1] = (byte) (v[i] >> 8);
                } else {
                    r[2 * i] = (byte) (v[i] >> 8);
                    r[2 * i + 1] = (byte) v[i];
                }
            }
            return r;
        }

        byte[] lg(long... v) {
            byte[] r = new byte[v.length * 4];
            for (int i = 0; i < v.length; i++) {
                for (int k = 0; k < 4; k++) {
                    r[4 * i + k] = (byte) (le ? (v[i] >> (8 * k)) : (v[i] >> (24 - 8 * k)));
                }
            }
            return r;
        }
    }

    static byte[] asc(String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        return Arrays.copyOf(b, b.length + 1);
    }

    static Ent ascii(int tag, String s) {
        byte[] b = asc(s);
        return new Ent(tag, ASCII, b.length, b);
    }

    static final Comparator<Ent> BY_TAG = new Comparator<Ent>() {
        @Override
        public int compare(Ent a, Ent b) {
            return Integer.compare(a.tag, b.tag);
        }
    };

    /** Fixture options. */
    static final class Opt {
        boolean le = true;
        String make = "HUAWEI";
        String model = "ALN-AL00";
        String software = "ALN-AL00 4.0.0.120(C00E120R1P1)";
        String hostComputer;
        String lensMake;
        String lensModel = "HUAWEI REAR CAMERA";
        String exifVersion = "0232";
        boolean exif = true;
        boolean gps = true;
        boolean gpsMasked;
        boolean interop = true;
        boolean makerNote = true;
        int makerNotePad = 0;
        boolean serials = true;
        boolean thumbnail = true;
        byte[] thumbData;
        boolean ifd1MakeModel;
        /** Make and LensMake share one value slot. */
        boolean shareMakeWithLensMake;
        byte[] icc;
        List<Ent> extraIfd0 = new ArrayList<Ent>();
    }

    /** Built fixture plus the offsets the tests check. */
    static final class Fx {
        byte[] tiff;
        int ifd0Pos;
        int makerNotePos = -1;
        int makerNoteLen;
        byte[] makerNote;
        int thumbPos = -1;
        byte[] thumb;
        int gpsPos = -1;
    }

    static final int MN_HEADER = 78;

    static Fx build(Opt o) throws IOException {
        Fx fx = new Fx();
        TB t = new TB(o.le);
        List<Ent> i0 = new ArrayList<Ent>();
        if (o.make != null) i0.add(ascii(0x010F, o.make));
        if (o.model != null) i0.add(ascii(0x0110, o.model));
        i0.add(new Ent(0x011A, RATIONAL, 1, t.lg(72, 1)));
        i0.add(new Ent(0x011B, RATIONAL, 1, t.lg(72, 1)));
        i0.add(new Ent(0x0128, SHORT, 1, t.sh(2)));
        if (o.software != null) i0.add(ascii(0x0131, o.software));
        i0.add(ascii(0x0132, "2025:06:01 12:00:00"));
        if (o.hostComputer != null) i0.add(ascii(0x013C, o.hostComputer));
        i0.add(new Ent(0x0213, SHORT, 1, t.sh(1)));
        if (o.icc != null) i0.add(new Ent(0x8773, UNDEF, o.icc.length, o.icc));
        Ent pExif = null;
        Ent pGps = null;
        if (o.exif) i0.add(pExif = new Ent(0x8769, LONG, 1, t.lg(0)));
        if (o.gps) i0.add(pGps = new Ent(0x8825, LONG, 1, t.lg(0)));
        i0.addAll(o.extraIfd0);
        Collections.sort(i0, BY_TAG);
        fx.ifd0Pos = t.writeIfd(i0);
        int ifd0Next = t.nextPos;
        t.put32(4, fx.ifd0Pos);

        if (o.exif) {
            List<Ent> ex = new ArrayList<Ent>();
            ex.add(new Ent(0x829A, RATIONAL, 1, t.lg(1, 100)));
            ex.add(new Ent(0x829D, RATIONAL, 1, t.lg(18, 10)));
            ex.add(new Ent(0x9000, UNDEF, 4, o.exifVersion.getBytes(StandardCharsets.US_ASCII)));
            ex.add(ascii(0x9003, "2025:06:01 12:00:00"));
            if (o.exifVersion.compareTo("0231") >= 0) ex.add(ascii(0x9011, "+03:00"));
            ex.add(new Ent(0x9101, UNDEF, 4, new byte[]{1, 2, 3, 0}));
            Ent mn = null;
            if (o.makerNote) {
                byte[] m = makerNoteTemplate(t, o.makerNotePad);
                ex.add(mn = new Ent(0x927C, UNDEF, m.length, m));
            }
            ex.add(new Ent(0xA000, UNDEF, 4, "0100".getBytes(StandardCharsets.US_ASCII)));
            ex.add(new Ent(0xA001, SHORT, 1, t.sh(1)));
            ex.add(new Ent(0xA002, LONG, 1, t.lg(64)));
            ex.add(new Ent(0xA003, LONG, 1, t.lg(48)));
            Ent pIop = null;
            if (o.interop) ex.add(pIop = new Ent(0xA005, LONG, 1, t.lg(0)));
            if (o.serials) {
                ex.add(ascii(0xA420, "0123456789ABCDEF0123456789ABCDEF"));
                ex.add(ascii(0xA430, "Ali Veli"));
                ex.add(ascii(0xA431, "SN-HW-123456"));
                ex.add(ascii(0xA435, "LSN-999"));
            }
            Ent lensMake = null;
            if (o.lensMake != null) ex.add(lensMake = ascii(0xA433, o.lensMake));
            if (o.lensModel != null) ex.add(ascii(0xA434, o.lensModel));
            Collections.sort(ex, BY_TAG);
            if (o.shareMakeWithLensMake && lensMake != null) {
                Ent mk = null;
                for (Ent e : i0) if (e.tag == 0x010F) mk = e;
                lensMake.rawField = new byte[4];
                lensMake.count = mk.count;
                TB tmp = new TB(o.le);
                tmp.put32(0, mk.dataPos);
                System.arraycopy(tmp.b, 0, lensMake.rawField, 0, 4);
            }
            int exPos = t.writeIfd(ex);
            t.put32(pExif.fieldPos, exPos);
            if (mn != null) {
                fx.makerNotePos = mn.dataPos;
                fx.makerNoteLen = mn.data.length;
                // absolute (TIFF-relative) offsets inside the vendor MakerNote
                t.put32(mn.dataPos + 18, mn.dataPos + 38);
                t.put32(mn.dataPos + 30, mn.dataPos + 70);
                fx.makerNote = Arrays.copyOfRange(t.b, mn.dataPos, mn.dataPos + mn.data.length);
            }
            if (o.interop) {
                List<Ent> io = new ArrayList<Ent>();
                io.add(ascii(0x0001, "R98"));
                io.add(new Ent(0x0002, UNDEF, 4, "0100".getBytes(StandardCharsets.US_ASCII)));
                int p = t.writeIfd(io);
                t.put32(pIop.fieldPos, p);
            }
        }
        if (o.gps) {
            List<Ent> g = new ArrayList<Ent>();
            g.add(new Ent(0x0000, BYTE, 4, new byte[]{2, 3, 0, 0}));
            g.add(ascii(0x0001, "N"));
            g.add(new Ent(0x0002, RATIONAL, 3, o.gpsMasked ? t.lg(0, 1, 0, 1, 0, 1) : t.lg(41, 1, 0, 1, 0, 1)));
            g.add(ascii(0x0003, "E"));
            g.add(new Ent(0x0004, RATIONAL, 3, o.gpsMasked ? t.lg(0, 1, 0, 1, 0, 1) : t.lg(29, 1, 0, 1, 0, 1)));
            fx.gpsPos = t.writeIfd(g);
            t.put32(pGps.fieldPos, fx.gpsPos);
        }
        if (o.thumbnail) {
            byte[] th = o.thumbData != null ? o.thumbData : thumbJpeg();
            List<Ent> i1 = new ArrayList<Ent>();
            i1.add(new Ent(0x0103, SHORT, 1, t.sh(6)));
            if (o.ifd1MakeModel) {
                i1.add(ascii(0x010F, o.make));
                i1.add(ascii(0x0110, o.model));
            }
            i1.add(new Ent(0x011A, RATIONAL, 1, t.lg(72, 1)));
            i1.add(new Ent(0x011B, RATIONAL, 1, t.lg(72, 1)));
            i1.add(new Ent(0x0128, SHORT, 1, t.sh(2)));
            Ent jif = new Ent(0x0201, LONG, 1, t.lg(0));
            i1.add(jif);
            i1.add(new Ent(0x0202, LONG, 1, t.lg(th.length)));
            int p = t.writeIfd(i1);
            t.put32(ifd0Next, p);
            fx.thumbPos = t.align();
            t.wbytes(th);
            t.put32(jif.fieldPos, fx.thumbPos);
            fx.thumb = th;
        }
        fx.tiff = t.bytes();
        return fx;
    }

    /** Fake vendor MakerNote whose internal pointers are absolute TIFF offsets (patched by build()). */
    static byte[] makerNoteTemplate(TB t, int pad) {
        byte[] m = new byte[MN_HEADER + pad];
        System.arraycopy("VNDRNOTE".getBytes(StandardCharsets.US_ASCII), 0, m, 0, 8);
        TB x = new TB(t.le);
        x.len = 0;
        x.w16(2);
        x.w16(1);
        x.w16(ASCII);
        x.w32(32);
        x.w32(0);
        x.w16(2);
        x.w16(LONG);
        x.w32(1);
        x.w32(0);
        x.w32(0);
        System.arraycopy(x.b, 0, m, 8, x.len);
        System.arraycopy("SECRETVENDORSERIAL-0123456789AB\0".getBytes(StandardCharsets.US_ASCII), 0, m, 38, 32);
        System.arraycopy("VNDRBLOB".getBytes(StandardCharsets.US_ASCII), 0, m, 70, 8);
        Arrays.fill(m, MN_HEADER, m.length, (byte) 0x5A);
        return m;
    }

    // =====================================================================================
    // Independent mini TIFF reader for offset checks
    // =====================================================================================

    static final class TR {
        final byte[] d;
        final boolean le;

        TR(byte[] d) {
            this.d = d;
            this.le = d[0] == 'I';
        }

        int u16(int o) {
            return le ? (d[o] & 0xFF) | ((d[o + 1] & 0xFF) << 8) : ((d[o] & 0xFF) << 8) | (d[o + 1] & 0xFF);
        }

        long u32(int o) {
            long a = d[o] & 0xFF, b = d[o + 1] & 0xFF, c = d[o + 2] & 0xFF, e = d[o + 3] & 0xFF;
            return le ? a | (b << 8) | (c << 16) | (e << 24) : (a << 24) | (b << 16) | (c << 8) | e;
        }

        int ifd0() {
            return (int) u32(4);
        }

        int next(int ifd) {
            return (int) u32(ifd + 2 + 12 * u16(ifd));
        }

        /** Entry position of {@code tag} in the IFD, -1 if absent. */
        int entry(int ifd, int tag) {
            int n = u16(ifd);
            for (int i = 0; i < n; i++) {
                int p = ifd + 2 + 12 * i;
                if (u16(p) == tag) return p;
            }
            return -1;
        }

        int tags(int ifd) {
            return u16(ifd);
        }

        long field(int ifd, int tag) {
            int p = entry(ifd, tag);
            return p < 0 ? -1 : u32(p + 8);
        }

        int exif() {
            return (int) field(ifd0(), 0x8769);
        }

        int ifd1() {
            return next(ifd0());
        }

        String str(int ifd, int tag) {
            int p = entry(ifd, tag);
            if (p < 0) return null;
            int cnt = (int) u32(p + 4);
            int off = cnt <= 4 ? p + 8 : (int) u32(p + 8);
            int n = 0;
            while (n < cnt && d[off + n] != 0) n++;
            return new String(d, off, n, StandardCharsets.UTF_8);
        }
    }

    // =====================================================================================
    // JPEG wrapping + exiftool helpers
    // =====================================================================================

    private static byte[] baseJpeg;
    private static byte[] thumbJpeg;

    static synchronized byte[] baseJpeg() throws IOException {
        if (baseJpeg == null) {
            File f = T.file("exiftiff-base.jpg");
            T.run("convert", "-size", "64x48", "gradient:red-blue", "-strip", f.getPath());
            baseJpeg = T.read(f);
        }
        return baseJpeg;
    }

    static synchronized byte[] thumbJpeg() throws IOException {
        if (thumbJpeg == null) {
            File f = T.file("exiftiff-thumb.jpg");
            T.run("convert", "-size", "16x12", "gradient:blue-yellow", "-strip", f.getPath());
            thumbJpeg = T.read(f);
        }
        return thumbJpeg;
    }

    static int u16be(byte[] b, int o) {
        return ((b[o] & 0xFF) << 8) | (b[o + 1] & 0xFF);
    }

    /** Returns {start, end} of the Exif APP1 segment (marker included), or null. */
    static int[] findExifSegment(byte[] j) {
        int p = 2;
        while (p + 4 <= j.length && (j[p] & 0xFF) == 0xFF) {
            int m = j[p + 1] & 0xFF;
            if (m == 0xDA || m == 0xD9) break;
            int len = u16be(j, p + 2);
            if (m == 0xE1 && p + 10 <= j.length && new String(j, p + 4, 6, StandardCharsets.ISO_8859_1).equals("Exif\0\0")) {
                return new int[]{p, p + 2 + len};
            }
            p += 2 + len;
        }
        return null;
    }

    static byte[] extractTiff(byte[] jpeg) {
        int[] s = findExifSegment(jpeg);
        if (s == null) return null;
        return Arrays.copyOfRange(jpeg, s[0] + 10, s[1]);
    }

    /** Puts {@code tiff} into {@code jpeg} as the Exif APP1 (replacing an existing one, else after SOI/APP0). */
    static byte[] withExif(byte[] jpeg, byte[] tiff) {
        T.check(tiff.length + 8 <= 65535, "APP1 too large: " + tiff.length);
        int[] s = findExifSegment(jpeg);
        int cut0;
        int cut1;
        if (s != null) {
            cut0 = s[0];
            cut1 = s[1];
        } else {
            cut0 = 2;
            if ((jpeg[2] & 0xFF) == 0xFF && (jpeg[3] & 0xFF) == 0xE0) cut0 = 4 + u16be(jpeg, 4);
            cut1 = cut0;
        }
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        bo.write(jpeg, 0, cut0);
        int len = 2 + 6 + tiff.length;
        bo.write(0xFF);
        bo.write(0xE1);
        bo.write(len >> 8);
        bo.write(len);
        bo.write("Exif\0\0".getBytes(StandardCharsets.ISO_8859_1), 0, 6);
        bo.write(tiff, 0, tiff.length);
        bo.write(jpeg, cut1, jpeg.length - cut1);
        return bo.toByteArray();
    }

    static File jpeg(String name, byte[] tiff) throws IOException {
        File f = T.file(name);
        T.write(f, withExif(baseJpeg(), tiff));
        return f;
    }

    /** exiftool -validate warnings/errors (messages only). */
    static Set<String> warnings(File f) throws IOException {
        Set<String> s = new TreeSet<String>();
        for (String line : T.validate(f).split("\n")) {
            String l = line.trim();
            if (l.startsWith("[")) l = l.substring(l.indexOf(']') + 1).trim();
            int c = l.indexOf(':');
            if (c < 0) continue;
            String tag = l.substring(0, c).trim();
            if (tag.startsWith("Warning") || tag.startsWith("Error")) s.add(l.substring(c + 1).trim());
        }
        return s;
    }

    static void assertNoNewWarnings(File before, File after, String... allowed) throws IOException {
        Set<String> a = warnings(before);
        Set<String> b = warnings(after);
        b.removeAll(a);
        outer:
        for (String w : b) {
            for (String ok : allowed) if (w.contains(ok)) continue outer;
            throw new AssertionError("new exiftool warning in " + after.getName() + ": " + w + "\nbefore=" + a + "\nafter=" + warnings(after));
        }
    }

    static byte[] execBytes(String... cmd) throws IOException {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(false).start();
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        InputStream in = p.getInputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        try {
            p.waitFor();
        } catch (InterruptedException e) {
            throw new IOException(e);
        }
        return bo.toByteArray();
    }

    static byte[] exiftoolThumbnail(File f) throws IOException {
        return execBytes("exiftool", "-b", "-ThumbnailImage", f.getAbsolutePath());
    }

    static int indexOf(byte[] hay, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= hay.length; i++) {
            for (int k = 0; k < needle.length; k++) if (hay[i + k] != needle[k]) continue outer;
            return i;
        }
        return -1;
    }

    static void absent(byte[] data, String s, String what) {
        int i = indexOf(data, s.getBytes(StandardCharsets.UTF_8));
        T.check(i < 0, what + ": \"" + s + "\" must not survive in the output (found at " + i + ")");
    }

    static void present(byte[] data, String s, String what) {
        T.check(indexOf(data, s.getBytes(StandardCharsets.UTF_8)) >= 0, what + ": \"" + s + "\" expected in output");
    }

    static EditSpec nullSpec() {
        EditSpec s = new EditSpec();
        s.removeMakerNote = false;
        s.clearSerials = false;
        s.editIcc = false;
        s.editXmp = false;
        s.createExifIfMissing = false;
        return s;
    }

    static byte[] edit(byte[] tiff, EditSpec s, EditReport r) throws IOException {
        return ExifTiff.edit(tiff, s, APP1_MAX, r);
    }

    static boolean hasChange(EditReport r, String text) {
        for (String c : r.changes) if (c.equals(text)) return true;
        return false;
    }

    static boolean hasWarningContaining(EditReport r, String text) {
        for (String c : r.warnings) if (c.contains(text)) return true;
        return false;
    }

    static Opt opt(boolean le) {
        Opt o = new Opt();
        o.le = le;
        return o;
    }

    // =====================================================================================
    // Tests: full Apple spoof on hand-built fixtures
    // =====================================================================================

    public static void testAppleSpecLittleEndian() throws Exception {
        appleSpecOnFixture(true);
    }

    public static void testAppleSpecBigEndian() throws Exception {
        appleSpecOnFixture(false);
    }

    private static void appleSpecOnFixture(boolean le) throws Exception {
        String tag = le ? "le" : "be";
        Fx fx = build(opt(le));
        EditSpec spec = T.appleSpec();
        EditReport rep = new EditReport();
        byte[] out = edit(fx.tiff, spec, rep);
        T.check(out != fx.tiff, "must change");
        T.check(out.length <= APP1_MAX, "size");
        T.check(out[0] == fx.tiff[0] && out[1] == fx.tiff[1], "byte order kept");

        File before = jpeg("apple-" + tag + "-in.jpg", fx.tiff);
        File after = jpeg("apple-" + tag + "-out.jpg", out);
        Map<String, String> m = T.exif(after);
        T.eq("Apple", m.get("IFD0:Make"), "make");
        T.eq("iPhone 17 Pro Max", m.get("IFD0:Model"), "model");
        T.eq("26.0.1", m.get("IFD0:Software"), "software");
        T.eq("iPhone 17 Pro Max", m.get("IFD0:HostComputer"), "host");
        T.eq("Apple", m.get("ExifIFD:LensMake"), "lensMake");
        T.eq("iPhone 17 Pro Max back triple camera 6.765mm f/1.78", m.get("ExifIFD:LensModel"), "lensModel");
        T.eq(null, m.get("ExifIFD:SerialNumber"), "serial removed");
        T.eq(null, m.get("ExifIFD:LensSerialNumber"), "lens serial removed");
        T.eq(null, m.get("ExifIFD:OwnerName"), "owner removed");
        T.eq(null, m.get("ExifIFD:ImageUniqueID"), "unique id removed");
        for (String k : m.keySet()) T.check(!k.contains("MakerNote") && !k.startsWith("MakerUnknown"), "makernote gone: " + k);
        // untouched data
        T.eq("2025:06:01 12:00:00", m.get("ExifIFD:DateTimeOriginal"), "dto");
        T.eq("+03:00", m.get("ExifIFD:OffsetTimeOriginal"), "oto");
        T.eq("1/100", m.get("ExifIFD:ExposureTime"), "exposure");
        T.eq("R98 - DCF basic file (sRGB)", m.get("InteropIFD:InteropIndex"), "interop");
        T.eq("41 deg 0' 0.00\"", m.get("GPS:GPSLatitude"), "gps lat");
        T.eq("29 deg 0' 0.00\"", m.get("GPS:GPSLongitude"), "gps lon");
        T.eq("2025:06:01 12:00:00", m.get("IFD0:ModifyDate"), "modify date");
        T.check(Arrays.equals(fx.thumb, exiftoolThumbnail(after)), "thumbnail bytes preserved");
        assertNoNewWarnings(before, after);

        // vendor/device strings are gone from every byte of the block
        absent(out, "HUAWEI", "old make / lens");
        absent(out, "ALN-AL00", "old model / software");
        absent(out, "SECRETVENDOR", "makernote content");
        absent(out, "VNDRNOTE", "makernote header");
        absent(out, "VNDRBLOB", "makernote blob");
        absent(out, "SN-HW-123456", "serial");
        absent(out, "LSN-999", "lens serial");
        absent(out, "Ali Veli", "owner");
        absent(out, "0123456789ABCDEF0123456789ABCDEF", "unique id");

        // append-only: the thumbnail and GPS IFD did not move
        TR tr = new TR(out);
        T.eq((long) fx.thumbPos, tr.field(tr.ifd1(), 0x0201), "thumbnail offset unchanged");
        T.eq((long) fx.gpsPos, tr.field(tr.ifd0(), 0x8825), "gps ifd offset unchanged");
        // makernote region zero-filled
        for (int i = 0; i < fx.makerNoteLen; i++) T.check(out[fx.makerNotePos + i] == 0, "makernote zero-filled at " + i);

        // report
        T.check(hasChange(rep, "EXIF Make: HUAWEI → Apple"), "report make: " + rep);
        T.check(hasChange(rep, "EXIF Model: ALN-AL00 → iPhone 17 Pro Max"), "report model: " + rep);
        T.check(hasChange(rep, "EXIF Software: ALN-AL00 4.0.0.120(C00E120R1P1) → 26.0.1"), "report software: " + rep);
        T.check(hasChange(rep, "EXIF HostComputer eklendi: iPhone 17 Pro Max"), "report host: " + rep);
        T.check(hasChange(rep, "EXIF LensMake eklendi: Apple"), "report lensmake: " + rep);
        T.check(hasChange(rep, "EXIF LensModel: HUAWEI REAR CAMERA → iPhone 17 Pro Max back triple camera 6.765mm f/1.78"), "report lensmodel: " + rep);
        T.check(hasChange(rep, "MakerNote temizlendi (VNDRNOTE)"), "report makernote: " + rep);
        T.check(hasChange(rep, "EXIF BodySerialNumber kaldırıldı"), "report serial: " + rep);
        T.check(hasChange(rep, "EXIF LensSerialNumber kaldırıldı"), "report lens serial: " + rep);
        T.check(hasChange(rep, "EXIF CameraOwnerName kaldırıldı"), "report owner: " + rep);
        T.check(hasChange(rep, "EXIF ImageUniqueID kaldırıldı"), "report uid: " + rep);
        T.eq(11, rep.changes.size(), "number of changes: " + rep);
        T.eq(0, rep.warnings.size(), "warnings: " + rep);

        // inspect sees the new identity
        MediaInfo info = new MediaInfo();
        ExifTiff.inspect(out, info);
        T.eq("Apple", info.make, "inspect make");
        T.eq("iPhone 17 Pro Max", info.model, "inspect model");
        T.eq("Apple iPhone 17 Pro Max", info.deviceLabel(), "label");
        T.check(!info.hasMakerNote, "no makernote");
        T.eq("var", info.gpsStatus, "gps");

        // idempotent: a second run changes nothing
        EditReport rep2 = new EditReport();
        byte[] out2 = edit(out, T.appleSpec(), rep2);
        T.check(out2 == out, "second edit must return the input array");
        T.eq(0, rep2.changes.size(), "second edit changes: " + rep2);
    }

    public static void testKeepMakerNoteOffsetsStableLE() throws Exception {
        keepMakerNote(true);
    }

    public static void testKeepMakerNoteOffsetsStableBE() throws Exception {
        keepMakerNote(false);
    }

    private static void keepMakerNote(boolean le) throws Exception {
        String tag = le ? "le" : "be";
        Fx fx = build(opt(le));
        EditSpec spec = T.appleSpec();
        spec.removeMakerNote = false;
        spec.clearSerials = false;
        EditReport rep = new EditReport();
        byte[] out = edit(fx.tiff, spec, rep);
        // the MakerNote is byte-identical at the same absolute offset ...
        T.check(Arrays.equals(fx.makerNote, Arrays.copyOfRange(out, fx.makerNotePos, fx.makerNotePos + fx.makerNoteLen)),
                "makernote bytes identical at same offset");
        TR tr = new TR(out);
        int exif = tr.exif();
        T.eq((long) fx.makerNotePos, tr.field(exif, 0x927C), "makernote pointer unchanged");
        // ... so its internal absolute pointers still resolve
        long p1 = tr.u32(fx.makerNotePos + 18);
        T.eq("SECRETVENDORSERIAL-0123456789AB", new String(out, (int) p1, 31, StandardCharsets.US_ASCII), "makernote abs ptr 1");
        long p2 = tr.u32(fx.makerNotePos + 30);
        T.eq("VNDRBLOB", new String(out, (int) p2, 8, StandardCharsets.US_ASCII), "makernote abs ptr 2");
        // thumbnail unchanged at the same offset
        int ifd1 = tr.ifd1();
        T.eq((long) fx.thumbPos, tr.field(ifd1, 0x0201), "thumb offset");
        T.check(Arrays.equals(fx.thumb, Arrays.copyOfRange(out, fx.thumbPos, fx.thumbPos + fx.thumb.length)), "thumb bytes");
        // serials kept
        T.eq("SN-HW-123456", tr.str(exif, 0xA431), "serial kept");
        // nothing that was not edited moved: the source prefix is unchanged outside edited slots
        T.check(out.length > fx.tiff.length, "appended");
        File before = jpeg("keepmn-" + tag + "-in.jpg", fx.tiff);
        File after = jpeg("keepmn-" + tag + "-out.jpg", out);
        Map<String, String> m = T.exif(after);
        T.eq("Apple", m.get("IFD0:Make"), "make");
        T.eq("SN-HW-123456", m.get("ExifIFD:SerialNumber"), "serial via exiftool");
        assertNoNewWarnings(before, after);
        T.check(!hasChange(rep, "MakerNote temizlendi (VNDRNOTE)"), "no makernote change reported");
        T.eq(0, rep.warnings.size(), "warnings " + rep);
    }

    public static void testOldValuesDoNotSurviveWhenGrown() throws Exception {
        for (boolean le : new boolean[]{true, false}) {
            Opt o = opt(le);
            o.make = "LGE";            // inline (4 bytes incl. NUL)
            o.model = "LM-G900N";      // out-of-line, grows
            o.software = "G90010f";
            o.lensModel = "LGLENS1";
            Fx fx = build(o);
            EditSpec s = nullSpec();
            s.make = "Google";             // inline -> appended
            s.model = "Pixel 10 Pro XL Ultra Long Name";  // grows -> appended, old slot zeroed
            s.software = "BP2A.250605.031.A3";             // grows
            s.lensModel = "Pixel 10 Pro XL back camera 6.9mm f/1.68";
            byte[] out = edit(fx.tiff, s, new EditReport());
            absent(out, "LM-G900N", "old model");
            absent(out, "G90010f", "old software");
            absent(out, "LGLENS1", "old lens");
            absent(out, "LGE", "old make");
            TR tr = new TR(out);
            T.eq("Google", tr.str(tr.ifd0(), 0x010F), "make");
            T.eq("Pixel 10 Pro XL Ultra Long Name", tr.str(tr.ifd0(), 0x0110), "model");
            T.eq("Pixel 10 Pro XL back camera 6.9mm f/1.68", tr.str(tr.exif(), 0xA434), "lens");
            // IFD structure did not need re-writing: IFD0 and Exif IFD stay in place
            T.eq(fx.ifd0Pos, tr.ifd0(), "ifd0 not moved when only values change");
            // every appended value is word aligned
            T.eq(0L, tr.field(tr.ifd0(), 0x0110) & 1, "aligned");
            // makernote untouched (removeMakerNote=false)
            T.check(Arrays.equals(fx.makerNote, Arrays.copyOfRange(out, fx.makerNotePos, fx.makerNotePos + fx.makerNoteLen)), "mn");
            File after = jpeg("grow-" + le + ".jpg", out);
            Map<String, String> m = T.exif(after);
            T.eq("Google", m.get("IFD0:Make"), "exiftool make");
            T.eq("BP2A.250605.031.A3", m.get("IFD0:Software"), "exiftool sw");
            assertNoNewWarnings(jpeg("grow-" + le + "-in.jpg", fx.tiff), after);
        }
    }

    public static void testShrinkInPlaceAndToInline() throws Exception {
        for (boolean le : new boolean[]{true, false}) {
            Fx fx = build(opt(le));
            EditSpec s = nullSpec();
            s.make = "LG";                // 7 bytes -> 3 bytes inline, old slot zeroed
            s.software = "1.0";           // shrinks to inline
            s.model = "SM-S938B";         // ALN-AL00 (9 bytes) -> 9 bytes: in place
            EditReport rep = new EditReport();
            byte[] out = edit(fx.tiff, s, rep);
            T.eq(fx.tiff.length, out.length, "nothing appended: values fit in place / inline");
            absent(out, "HUAWEI\0", "old make");
            absent(out, "ALN-AL00", "old model/software");
            TR tr = new TR(out);
            T.eq("LG", tr.str(tr.ifd0(), 0x010F), "make");
            T.eq("SM-S938B", tr.str(tr.ifd0(), 0x0110), "model");
            T.eq("1.0", tr.str(tr.ifd0(), 0x0131), "software");
            int e = tr.entry(tr.ifd0(), 0x010F);
            T.eq(3L, tr.u32(e + 4), "count includes NUL");
            T.eq(ASCII, tr.u16(e + 2), "type ascii");
            File after = jpeg("shrink-" + le + ".jpg", out);
            Map<String, String> m = T.exif(after);
            T.eq("LG", m.get("IFD0:Make"), "exiftool make");
            T.eq("SM-S938B", m.get("IFD0:Model"), "exiftool model");
            assertNoNewWarnings(jpeg("shrink-" + le + "-in.jpg", fx.tiff), after);
            T.check(hasChange(rep, "EXIF Make: HUAWEI → LG"), "rep " + rep);
        }
    }

    public static void testRemoveAndAbsentValues() throws Exception {
        for (boolean le : new boolean[]{true, false}) {
            Fx fx = build(opt(le));
            EditSpec s = nullSpec();
            s.make = "";                 // remove existing
            s.hostComputer = "";         // absent: nothing happens
            s.lensMake = "";             // absent: nothing happens
            s.lensModel = "";            // remove existing
            s.software = "";             // remove existing
            EditReport rep = new EditReport();
            byte[] out = edit(fx.tiff, s, rep);
            absent(out, "HUAWEI", "removed make + lens model");
            present(out, "ALN-AL00", "model kept");
            TR tr = new TR(out);
            T.eq(-1, tr.entry(tr.ifd0(), 0x010F), "make entry gone");
            T.eq(-1, tr.entry(tr.ifd0(), 0x0131), "software entry gone");
            T.eq(-1, tr.entry(tr.ifd0(), 0x013C), "host never created");
            T.eq(-1, tr.entry(tr.exif(), 0xA433), "lensmake never created");
            T.eq(-1, tr.entry(tr.exif(), 0xA434), "lensmodel gone");
            T.check(tr.ifd0() != fx.ifd0Pos, "ifd0 re-written");
            // the old IFD0 bytes were zero-filled
            int oldIfd0End = fx.ifd0Pos + 2 + 12 * new TR(fx.tiff).tags(fx.ifd0Pos) + 4;
            for (int i = fx.ifd0Pos; i < oldIfd0End; i++) T.check(out[i] == 0, "old ifd0 zeroed at " + i);
            T.eq(3, rep.changes.size(), "changes " + rep);
            T.check(hasChange(rep, "EXIF Make kaldırıldı"), "rep");
            T.check(hasChange(rep, "EXIF Software kaldırıldı"), "rep");
            T.check(hasChange(rep, "EXIF LensModel kaldırıldı"), "rep");
            Map<String, String> m = T.exif(jpeg("remove-" + le + ".jpg", out));
            T.eq(null, m.get("IFD0:Make"), "make removed");
            T.eq("ALN-AL00", m.get("IFD0:Model"), "model kept");
            T.eq("41 deg 0' 0.00\"", m.get("GPS:GPSLatitude"), "gps kept");
            assertNoNewWarnings(jpeg("remove-" + le + "-in.jpg", fx.tiff), jpeg("remove-" + le + ".jpg", out));
            // idempotent
            EditReport rep2 = new EditReport();
            T.check(edit(out, s, rep2) == out, "idempotent removal");
            T.eq(0, rep2.changes.size(), "no changes");
        }
    }

    public static void testMakerNoteVendorLabel() throws Exception {
        // ASCII signature at the start of the MakerNote ("Apple iOS\0" style)
        Opt o = opt(true);
        Fx fx = build(o);
        byte[] t = fx.tiff.clone();
        System.arraycopy("Apple iOS\0".getBytes(StandardCharsets.US_ASCII), 0, t, fx.makerNotePos, 10);
        EditReport rep = new EditReport();
        byte[] out = edit(t, nullSpecWithMakerNoteRemoval(), rep);
        T.check(hasChange(rep, "MakerNote temizlendi (Apple iOS)"), "apple label " + rep);
        absent(out, "Apple iOS", "makernote header");
        // binary MakerNote (Samsung style: starts with the IFD entry count) -> the old Make is used
        o = opt(false);
        o.make = "samsung";
        fx = build(o);
        t = fx.tiff.clone();
        t[fx.makerNotePos] = 0x00;
        t[fx.makerNotePos + 1] = 0x2B;
        rep = new EditReport();
        edit(t, nullSpecWithMakerNoteRemoval(), rep);
        T.check(hasChange(rep, "MakerNote temizlendi (samsung)"), "samsung label " + rep);
        T.eq(1, rep.changes.size(), "only the makernote changed " + rep);
    }

    static EditSpec nullSpecWithMakerNoteRemoval() {
        EditSpec s = nullSpec();
        s.removeMakerNote = true;
        return s;
    }

    public static void testSameValuesAreNotReported() throws Exception {
        Fx fx = build(opt(true));
        EditSpec s = nullSpec();
        s.make = "HUAWEI";
        s.model = "ALN-AL00";
        s.lensModel = "HUAWEI REAR CAMERA";
        EditReport rep = new EditReport();
        byte[] out = edit(fx.tiff, s, rep);
        T.check(out == fx.tiff, "identical values: input returned");
        T.eq(0, rep.changes.size(), "no changes " + rep);
    }

    public static void testUnchangedSpecIsIdentity() throws Exception {
        for (boolean le : new boolean[]{true, false}) {
            Fx fx = build(opt(le));
            byte[] copy = fx.tiff.clone();
            EditReport rep = new EditReport();
            byte[] out = edit(fx.tiff, nullSpec(), rep);
            T.check(out == fx.tiff, "all-null spec returns the input itself");
            T.check(Arrays.equals(copy, fx.tiff), "input not modified");
            T.eq(0, rep.changes.size(), "changes");
            T.eq(0, rep.warnings.size(), "warnings");
            // default flags but nothing to remove
            Opt o = opt(le);
            o.makerNote = false;
            o.serials = false;
            Fx clean = build(o);
            EditSpec d = new EditSpec();
            d.editIcc = false;
            T.check(edit(clean.tiff, d, new EditReport()) == clean.tiff, "default flags, nothing to do");
            // null spec
            T.check(ExifTiff.edit(fx.tiff, null, APP1_MAX, null) == fx.tiff, "null spec");
        }
    }

    public static void testDoesNotModifyInputArray() throws Exception {
        Fx fx = build(opt(false));
        byte[] copy = fx.tiff.clone();
        edit(fx.tiff, T.appleSpec(), new EditReport());
        T.check(Arrays.equals(copy, fx.tiff), "input array must not be modified");
    }

    // =====================================================================================
    // Tests: special structures
    // =====================================================================================

    public static void testIfd1MakeModelUpdated() throws Exception {
        for (boolean le : new boolean[]{true, false}) {
            Opt o = opt(le);
            o.ifd1MakeModel = true;
            Fx fx = build(o);
            EditReport rep = new EditReport();
            byte[] out = edit(fx.tiff, T.appleSpec(), rep);
            Map<String, String> m = T.exif(jpeg("ifd1mm-" + le + ".jpg", out));
            T.eq("Apple", m.get("IFD1:Make"), "ifd1 make");
            T.eq("iPhone 17 Pro Max", m.get("IFD1:Model"), "ifd1 model");
            T.eq(null, m.get("IFD1:HostComputer"), "ifd1 host not created");
            T.check(Arrays.equals(fx.thumb, exiftoolThumbnail(T.file("ifd1mm-" + le + ".jpg"))), "thumb");
            absent(out, "HUAWEI", "old");
            absent(out, "ALN-AL00", "old");
            T.check(hasChange(rep, "EXIF IFD1 Make: HUAWEI → Apple"), "rep " + rep);
            // removing in IFD1 forces an IFD1 re-write; IFD0.next must follow
            EditSpec s = nullSpec();
            s.model = "";
            byte[] out2 = edit(fx.tiff, s, new EditReport());
            TR tr = new TR(out2);
            T.eq(-1, tr.entry(tr.ifd1(), 0x0110), "ifd1 model removed");
            T.eq((long) fx.thumbPos, tr.field(tr.ifd1(), 0x0201), "thumbnail not moved");
            File f2 = jpeg("ifd1rm-" + le + ".jpg", out2);
            T.check(Arrays.equals(fx.thumb, exiftoolThumbnail(f2)), "thumb after ifd1 rewrite");
            assertNoNewWarnings(jpeg("ifd1-" + le + "-in.jpg", fx.tiff), f2);
        }
    }

    public static void testSharedValueSlotIsNotOverwritten() throws Exception {
        for (boolean le : new boolean[]{true, false}) {
            Opt o = opt(le);
            o.lensMake = "HUAWEI";
            o.shareMakeWithLensMake = true;
            Fx fx = build(o);
            TR in = new TR(fx.tiff);
            T.eq(in.field(in.ifd0(), 0x010F), in.field(in.exif(), 0xA433), "fixture shares the slot");
            EditSpec s = nullSpec();
            s.make = "Apple"; // fits in the 7-byte slot, but the slot is shared -> must append
            byte[] out = edit(fx.tiff, s, new EditReport());
            TR tr = new TR(out);
            T.eq("Apple", tr.str(tr.ifd0(), 0x010F), "make");
            T.eq("HUAWEI", tr.str(tr.exif(), 0xA433), "lens make intact");
            Map<String, String> m = T.exif(jpeg("shared-" + le + ".jpg", out));
            T.eq("HUAWEI", m.get("ExifIFD:LensMake"), "exiftool lensmake");
            T.eq("Apple", m.get("IFD0:Make"), "exiftool make");
        }
    }

    public static void testExifIfdCreatedWhenMissing() throws Exception {
        for (boolean le : new boolean[]{true, false}) {
            Opt o = opt(le);
            o.exif = false;
            Fx fx = build(o);
            EditReport rep = new EditReport();
            byte[] out = edit(fx.tiff, T.appleSpec(), rep);
            T.check(hasChange(rep, "Exif IFD oluşturuldu"), "rep " + rep);
            File before = jpeg("noexif-" + le + "-in.jpg", fx.tiff);
            File after = jpeg("noexif-" + le + ".jpg", out);
            Map<String, String> m = T.exif(after);
            T.eq("Apple", m.get("ExifIFD:LensMake"), "lensmake");
            T.eq("iPhone 17 Pro Max back triple camera 6.765mm f/1.78", m.get("ExifIFD:LensModel"), "lensmodel");
            T.eq("0232", m.get("ExifIFD:ExifVersion"), "exif version");
            T.eq("41 deg 0' 0.00\"", m.get("GPS:GPSLatitude"), "gps kept");
            T.check(Arrays.equals(fx.thumb, exiftoolThumbnail(after)), "thumb");
            // only the image size is unknown to a TIFF-level editor
            assertNoNewWarnings(before, after, "ExifImageWidth", "ExifImageHeight");
            EditReport rep2 = new EditReport();
            T.check(edit(out, T.appleSpec(), rep2) == out, "idempotent " + rep2);
            // no lens values -> no Exif IFD is created
            EditSpec s = nullSpec();
            s.make = "Apple";
            s.lensModel = "";
            byte[] out3 = edit(fx.tiff, s, new EditReport());
            T.eq(-1, new TR(out3).entry(new TR(out3).ifd0(), 0x8769), "no exif ifd created");
        }
    }

    public static void testExifVersionBumpedForLensTags() throws Exception {
        for (boolean le : new boolean[]{true, false}) {
            Opt o = opt(le);
            o.exifVersion = "0220";
            o.lensModel = null;
            Fx fx = build(o);
            EditReport rep = new EditReport();
            byte[] out = edit(fx.tiff, T.appleSpec(), rep);
            T.check(hasChange(rep, "EXIF ExifVersion: 0220 → 0232"), "rep " + rep);
            File before = jpeg("ver-" + le + "-in.jpg", fx.tiff);
            File after = jpeg("ver-" + le + ".jpg", out);
            T.eq("0232", T.exif(after).get("ExifIFD:ExifVersion"), "version");
            assertNoNewWarnings(before, after);
            // no lens edit -> version untouched
            EditSpec s = nullSpec();
            s.make = "Apple";
            EditReport r2 = new EditReport();
            byte[] o2 = edit(fx.tiff, s, r2);
            T.eq("0220", T.exif(jpeg("ver2-" + le + ".jpg", o2)).get("ExifIFD:ExifVersion"), "version kept");
        }
    }

    public static void testUnknownTypesAndBrokenOffsetsPreservedVerbatim() throws Exception {
        for (boolean le : new boolean[]{true, false}) {
            Opt o = opt(le);
            Ent unknown = new Ent(0xC000, 99, 5, new byte[4]);
            unknown.rawField = new byte[]{1, 2, 3, 4};
            Ent dangling = new Ent(0xC001, UNDEF, 100, new byte[4]);
            dangling.rawField = new byte[]{(byte) 0x7F, (byte) 0xFF, (byte) 0xFF, 0x00};
            o.extraIfd0.add(unknown);
            o.extraIfd0.add(dangling);
            Fx fx = build(o);
            EditReport rep = new EditReport();
            byte[] out = edit(fx.tiff, T.appleSpec(), rep); // HostComputer is created -> IFD0 re-written
            TR tr = new TR(out);
            T.check(tr.ifd0() != fx.ifd0Pos, "ifd0 moved");
            int e1 = tr.entry(tr.ifd0(), 0xC000);
            int e2 = tr.entry(tr.ifd0(), 0xC001);
            T.check(e1 > 0, "unknown-type entry kept");
            T.eq(99, tr.u16(e1 + 2), "type");
            T.eq(5L, tr.u32(e1 + 4), "count");
            T.check(Arrays.equals(new byte[]{1, 2, 3, 4}, Arrays.copyOfRange(out, e1 + 8, e1 + 12)), "raw field verbatim");
            // a value stored beyond the end would read the appended bytes: it is dropped
            T.eq(-1, e2, "dangling entry removed");
            T.check(hasWarningContaining(rep, "1 bozuk EXIF girdisi"), "warned " + rep);
            // ... but only when the block is re-written at all
            EditReport r0 = new EditReport();
            T.check(edit(fx.tiff, nullSpec(), r0) == fx.tiff, "untouched without changes");
            // entries are sorted in the re-written IFD
            int n = tr.tags(tr.ifd0());
            for (int i = 1; i < n; i++) {
                T.check(tr.u16(tr.ifd0() + 2 + 12 * i) > tr.u16(tr.ifd0() + 2 + 12 * (i - 1)), "sorted");
            }
            T.eq("Apple", tr.str(tr.ifd0(), 0x010F), "make");
        }
    }

    public static void testEmbeddedIccProfileHandled() throws Exception {
        // A minimal ICC header (128 bytes + empty tag table) with manufacturer "HUAW"
        byte[] icc = new byte[132];
        icc[3] = (byte) 132;
        System.arraycopy("mntr".getBytes(StandardCharsets.US_ASCII), 0, icc, 12, 4);
        System.arraycopy("RGB XYZ ".getBytes(StandardCharsets.US_ASCII), 0, icc, 16, 8);
        icc[8] = 4;
        icc[9] = 0x30;
        System.arraycopy("acsp".getBytes(StandardCharsets.US_ASCII), 0, icc, 36, 4);
        System.arraycopy("HUAW".getBytes(StandardCharsets.US_ASCII), 0, icc, 48, 4);
        Opt o = opt(true);
        o.icc = icc;
        Fx fx = build(o);
        EditReport rep = new EditReport();
        byte[] out = edit(fx.tiff, T.appleSpec(), rep);
        TR tr = new TR(out);
        int e = tr.entry(tr.ifd0(), 0x8773);
        T.check(e > 0, "icc entry kept");
        int off = (int) tr.u32(e + 8);
        String mfg = new String(out, off + 48, 4, StandardCharsets.US_ASCII);
        // either the ICC editor rewrote the device manufacturer, or a warning explains why not
        T.check(mfg.equals("APPL") || hasWarningContaining(rep, "ICC"), "icc handled: mfg=" + mfg + " " + rep);
        T.eq("Apple", tr.str(tr.ifd0(), 0x010F), "rest of the edit still applied");
        MediaInfo info = new MediaInfo();
        ExifTiff.inspect(out, info); // must not throw
    }

    // =====================================================================================
    // Tests: real exiftool-written EXIF
    // =====================================================================================

    public static void testRealExiftoolExifLittleEndian() throws Exception {
        realExiftool("II", false);
    }

    public static void testRealExiftoolExifBigEndian() throws Exception {
        realExiftool("MM", false);
    }

    public static void testRealExiftoolExifWithThumbnailLE() throws Exception {
        realExiftool("II", true);
    }

    public static void testRealExiftoolExifWithThumbnailBE() throws Exception {
        realExiftool("MM", true);
    }

    private static void realExiftool(String order, boolean thumb) throws Exception {
        File src = T.file("real-" + order + "-" + thumb + ".jpg");
        T.write(src, baseJpeg());
        File th = T.file("real-thumb.jpg");
        T.write(th, thumbJpeg());
        List<String> cmd = new ArrayList<String>(Arrays.asList("exiftool", "-q", "-overwrite_original",
                "-ExifByteOrder=" + order, "-Make=HUAWEI", "-Model=ALN-AL00",
                "-Software=ALN-AL00 4.0.0.120(C00E120R1P1)", "-LensModel=HUAWEI REAR CAMERA",
                "-SerialNumber=SN123456", "-LensSerialNumber=LSN999", "-OwnerName=Ali Veli",
                "-ImageUniqueID=ABCDEF0123456789", "-DateTimeOriginal=2025:06:01 12:00:00",
                "-OffsetTimeOriginal=+03:00", "-ExifImageWidth=64", "-ExifImageHeight=48",
                "-XResolution=72", "-YResolution=72", "-ResolutionUnit=inches",
                "-GPSLatitude=41.0", "-GPSLatitudeRef=N", "-GPSLongitude=29.0", "-GPSLongitudeRef=E"));
        if (thumb) cmd.add("-ThumbnailImage<=" + th.getPath());
        cmd.add(src.getPath());
        T.run(cmd.toArray(new String[0]));
        byte[] jpg = T.read(src);
        byte[] tiff = extractTiff(jpg);
        T.check(tiff != null, "exif extracted");
        T.eq(order, new String(tiff, 0, 2, StandardCharsets.US_ASCII), "byte order");

        EditReport rep = new EditReport();
        byte[] out = edit(tiff, T.appleSpec(), rep);
        File dst = T.file("real-" + order + "-" + thumb + "-out.jpg");
        T.write(dst, withExif(jpg, out));
        Map<String, String> m = T.exif(dst);
        T.eq("Apple", m.get("IFD0:Make"), "make");
        T.eq("iPhone 17 Pro Max", m.get("IFD0:Model"), "model");
        T.eq("26.0.1", m.get("IFD0:Software"), "software");
        T.eq("iPhone 17 Pro Max", m.get("IFD0:HostComputer"), "host");
        T.eq("Apple", m.get("ExifIFD:LensMake"), "lensmake");
        T.eq("iPhone 17 Pro Max back triple camera 6.765mm f/1.78", m.get("ExifIFD:LensModel"), "lensmodel");
        T.eq(null, m.get("ExifIFD:SerialNumber"), "serial");
        T.eq(null, m.get("ExifIFD:LensSerialNumber"), "lens serial");
        T.eq(null, m.get("ExifIFD:OwnerName"), "owner");
        T.eq(null, m.get("ExifIFD:ImageUniqueID"), "uid");
        T.eq("2025:06:01 12:00:00", m.get("ExifIFD:DateTimeOriginal"), "dto");
        T.eq("+03:00", m.get("ExifIFD:OffsetTimeOriginal"), "oto");
        T.eq("64", m.get("ExifIFD:ExifImageWidth"), "width");
        T.eq("41 deg 0' 0.00\"", m.get("GPS:GPSLatitude"), "gps");
        T.eq(m.get("File:ExifByteOrder"), T.exif(src).get("File:ExifByteOrder"), "byte order");
        if (thumb) T.check(Arrays.equals(thumbJpeg(), exiftoolThumbnail(dst)), "thumbnail preserved");
        assertNoNewWarnings(src, dst);
        absent(out, "HUAWEI", "old make");
        absent(out, "ALN-AL00", "old model");
        absent(out, "SN123456", "serial");
        absent(out, "Ali Veli", "owner");
        // pixels are untouched
        T.eq("0", T.exec("compare", "-metric", "AE", src.getPath(), dst.getPath(), "null:").out.trim(), "pixels identical");

        // inspect of the source
        MediaInfo info = new MediaInfo();
        ExifTiff.inspect(tiff, info);
        T.eq("HUAWEI", info.make, "inspect make");
        T.eq("ALN-AL00", info.model, "inspect model");
        T.eq("HUAWEI REAR CAMERA", info.lensModel, "inspect lens");
        T.eq("2025:06:01 12:00:00", info.dateTimeOriginal, "inspect dto");
        T.eq("+03:00", info.offsetTimeOriginal, "inspect oto");
        T.eq("var", info.gpsStatus, "inspect gps");
        T.check(info.hasExif, "hasExif");

        // idempotent
        EditReport rep2 = new EditReport();
        T.check(edit(out, T.appleSpec(), rep2) == out, "idempotent: " + rep2);
        // exiftool itself can still rewrite our output (structure is sane)
        File again = T.file("real-" + order + "-" + thumb + "-rewrite.jpg");
        T.write(again, T.read(dst));
        T.run("exiftool", "-q", "-overwrite_original", "-Artist=Test", again.getPath());
        T.eq("Apple", T.exif(again).get("IFD0:Make"), "exiftool rewrite keeps make");
    }

    // =====================================================================================
    // Tests: maxSize / compacting fallback
    // =====================================================================================

    /** Builds a fixture whose total size is close to {@code target} by padding the MakerNote. */
    static Fx fixtureOfSize(boolean le, int target, byte[] thumb) throws IOException {
        Opt o = opt(le);
        o.thumbData = thumb;
        Fx probe = build(o);
        o.makerNotePad = Math.max(0, target - probe.tiff.length);
        return build(o);
    }

    public static void testCompactionKeepsMakerNoteAsBlob() throws Exception {
        for (boolean le : new boolean[]{true, false}) {
            Fx fx = fixtureOfSize(le, APP1_MAX - 40, null);
            T.check(fx.tiff.length <= APP1_MAX, "fixture fits");
            EditSpec s = T.appleSpec();
            s.removeMakerNote = false;
            EditReport rep = new EditReport();
            byte[] out = edit(fx.tiff, s, rep);
            T.check(out.length <= APP1_MAX, "fits: " + out.length);
            T.check(hasWarningContaining(rep, "yeniden düzenlendi"), "compaction warned " + rep);
            T.check(hasWarningContaining(rep, "MakerNote taşındı"), "makernote moved warned " + rep);
            T.check(!hasWarningContaining(rep, "Küçük resim kaldırıldı"), "thumbnail kept " + rep);
            File after = jpeg("compact-mn-" + le + ".jpg", out);
            Map<String, String> m = T.exif(after);
            T.eq("Apple", m.get("IFD0:Make"), "make");
            T.eq("iPhone 17 Pro Max back triple camera 6.765mm f/1.78", m.get("ExifIFD:LensModel"), "lens");
            T.eq("41 deg 0' 0.00\"", m.get("GPS:GPSLatitude"), "gps");
            T.eq("R98 - DCF basic file (sRGB)", m.get("InteropIFD:InteropIndex"), "interop");
            T.eq(null, m.get("ExifIFD:SerialNumber"), "serial removed");
            T.check(Arrays.equals(fx.thumb, exiftoolThumbnail(after)), "thumb kept");
            // the makernote bytes survive as an opaque blob
            TR tr = new TR(out);
            long mn = tr.field(tr.exif(), 0x927C);
            T.check(Arrays.equals(Arrays.copyOfRange(fx.makerNote, 0, 8), Arrays.copyOfRange(out, (int) mn, (int) mn + 8)), "mn blob");
            absent(out, "HUAWEI", "old make");
            assertNoNewWarnings(jpeg("compact-mn-" + le + "-in.jpg", fx.tiff), after);
            EditReport rep2 = new EditReport();
            T.check(edit(out, s, rep2) == out, "idempotent after compaction " + rep2);
        }
    }

    public static void testCompactionDropsRemovedMakerNote() throws Exception {
        for (boolean le : new boolean[]{true, false}) {
            Fx fx = fixtureOfSize(le, APP1_MAX - 10, null);
            EditReport rep = new EditReport();
            byte[] out = edit(fx.tiff, T.appleSpec(), rep);
            T.check(out.length <= APP1_MAX, "fits");
            T.check(out.length < 4096, "makernote really dropped: " + out.length);
            T.check(!hasWarningContaining(rep, "MakerNote taşındı"), "no moved warning " + rep);
            absent(out, "VNDRNOTE", "mn");
            absent(out, "SECRETVENDOR", "mn");
            File after = jpeg("compact-nomn-" + le + ".jpg", out);
            Map<String, String> m = T.exif(after);
            T.eq("Apple", m.get("IFD0:Make"), "make");
            T.check(Arrays.equals(fx.thumb, exiftoolThumbnail(after)), "thumb kept");
            assertNoNewWarnings(jpeg("compact-nomn-" + le + "-in.jpg", fx.tiff), after);
        }
    }

    public static void testCompactionDropsThumbnailWhenStillTooLarge() throws Exception {
        File big = T.file("exiftiff-bigthumb.jpg");
        T.run("convert", "-seed", "7", "-size", "160x120", "plasma:fractal", "-quality", "100", big.getPath());
        byte[] thumb = T.read(big);
        T.check(thumb.length > 20000 && thumb.length < 60000, "thumb size " + thumb.length);
        for (boolean le : new boolean[]{true, false}) {
            Fx fx = fixtureOfSize(le, APP1_MAX - 5, thumb);
            T.check(fx.makerNoteLen > 1000, "makernote is large");
            EditSpec s = T.appleSpec();
            s.removeMakerNote = false;
            s.clearSerials = false;
            s.lensModel = "iPhone 17 Pro Max back triple camera 6.765mm f/1.78 with a deliberately long lens description "
                    + "so that even the compacted block no longer fits";
            EditReport rep = new EditReport();
            byte[] out = edit(fx.tiff, s, rep);
            T.check(out.length <= APP1_MAX, "fits");
            T.check(hasWarningContaining(rep, "Küçük resim kaldırıldı"), "thumbnail dropped warning " + rep);
            TR tr = new TR(out);
            T.eq(0, tr.ifd1(), "no IFD1");
            File after = jpeg("compact-nothumb-" + le + ".jpg", out);
            Map<String, String> m = T.exif(after);
            T.eq("Apple", m.get("IFD0:Make"), "make");
            T.eq(null, m.get("IFD1:ThumbnailLength"), "thumb gone");
            assertNoNewWarnings(jpeg("compact-nothumb-" + le + "-in.jpg", fx.tiff), after);
        }
    }

    public static void testCompactionImpossibleThrows() throws Exception {
        Fx fx = build(opt(true));
        EditReport rep = new EditReport();
        rep.change("önceki");
        try {
            ExifTiff.edit(fx.tiff, T.appleSpec(), 100, rep);
            throw new AssertionError("expected UnsupportedFormatException");
        } catch (UnsupportedFormatException e) {
            T.check(e.getMessage() != null && e.getMessage().length() > 0, "message");
            T.check(e.getCause() == null, "no internal error");
        }
        T.eq(1, rep.changes.size(), "report untouched on failure");
        // large maxSize: append result returned as is
        byte[] out = ExifTiff.edit(fx.tiff, T.appleSpec(), Integer.MAX_VALUE, new EditReport());
        T.check(out.length > 0, "ok");
    }

    /** IFD0 with many small entries + a big opaque blob, IFD1 with a 2-strip uncompressed thumbnail. */
    static byte[] stripFixture(boolean le, int blobLen) {
        TB t = new TB(le);
        List<Ent> i0 = new ArrayList<Ent>();
        i0.add(ascii(0x010F, "HUAWEI"));
        i0.add(ascii(0x0110, "ALN-AL00 " + "x".repeat(80)));
        for (int k = 0; k < 10; k++) i0.add(new Ent(0xC700 + k, SHORT, 1, t.sh(k)));
        byte[] blob = new byte[blobLen];
        Arrays.fill(blob, (byte) 0x33);
        i0.add(new Ent(0xC6F0, UNDEF, blob.length, blob));
        Collections.sort(i0, BY_TAG);
        int ifd0 = t.writeIfd(i0);
        int next = t.nextPos;
        t.put32(4, ifd0);
        List<Ent> i1 = new ArrayList<Ent>();
        i1.add(new Ent(0x0100, SHORT, 1, t.sh(4)));
        i1.add(new Ent(0x0101, SHORT, 1, t.sh(2)));
        Ent so = new Ent(0x0111, LONG, 2, t.lg(0, 0));
        i1.add(so);
        i1.add(new Ent(0x0117, SHORT, 2, t.sh(12, 12)));
        int p1 = t.writeIfd(i1);
        t.put32(next, p1);
        int s1 = t.align();
        t.wbytes("STRIP-ONE-AB".getBytes(StandardCharsets.US_ASCII));
        int s2 = t.align();
        t.wbytes("STRIP-TWO-CD".getBytes(StandardCharsets.US_ASCII));
        t.put32(so.dataPos, s1);
        t.put32(so.dataPos + 4, s2);
        return t.bytes();
    }

    public static void testCompactionWithStripThumbnail() throws Exception {
        for (boolean le : new boolean[]{true, false}) {
            int probe = stripFixture(le, 1000).length;
            int blobLen = 1000 + (APP1_MAX - 10 - probe);
            byte[] tiff = stripFixture(le, blobLen);
            T.check(Math.abs(tiff.length - (APP1_MAX - 10)) <= 2, "fixture size " + tiff.length);
            EditSpec s = nullSpec();
            s.make = "Apple";
            s.model = "X";
            s.hostComputer = "iPhone 17 Pro Max with a rather long host computer name";
            T.check(ExifTiff.edit(tiff, s, Integer.MAX_VALUE, new EditReport()).length > APP1_MAX, "append would not fit");
            EditReport rep = new EditReport();
            byte[] out = edit(tiff, s, rep);
            T.check(out.length <= APP1_MAX, "fits");
            T.check(hasWarningContaining(rep, "yeniden düzenlendi"), "compacted " + rep);
            T.check(!hasWarningContaining(rep, "Küçük resim"), "strips kept " + rep);
            TR tr = new TR(out);
            int ifd1 = tr.ifd1();
            T.check(ifd1 > 0, "ifd1 kept");
            int e = tr.entry(ifd1, 0x0111);
            T.eq(LONG, tr.u16(e + 2), "strip offsets as LONG");
            int arr = (int) tr.u32(e + 8);
            T.eq("STRIP-ONE-AB", new String(out, (int) tr.u32(arr), 12, StandardCharsets.US_ASCII), "strip 1");
            T.eq("STRIP-TWO-CD", new String(out, (int) tr.u32(arr + 4), 12, StandardCharsets.US_ASCII), "strip 2");
            T.eq("Apple", tr.str(tr.ifd0(), 0x010F), "make");
            T.eq("X", tr.str(tr.ifd0(), 0x0110), "model");
            T.eq(blobLen, (int) tr.u32(tr.entry(tr.ifd0(), 0xC6F0) + 4), "blob kept");
            T.eq(9, tr.u16(tr.entry(tr.ifd0(), 0xC709) + 8), "small inline entry kept");
            absent(out, "ALN-AL00", "old model");
            EditReport rep2 = new EditReport();
            T.check(edit(out, s, rep2) == out, "idempotent " + rep2);
        }
    }

    // =====================================================================================
    // Tests: createMinimal
    // =====================================================================================

    public static void testCreateMinimal() throws Exception {
        byte[] t = ExifTiff.createMinimal(T.appleSpec(), 4032, 3024);
        T.eq("II", new String(t, 0, 2, StandardCharsets.US_ASCII), "little endian");
        File f = jpeg("minimal.jpg", t);
        Map<String, String> m = T.exif(f);
        T.eq("Apple", m.get("IFD0:Make"), "make");
        T.eq("iPhone 17 Pro Max", m.get("IFD0:Model"), "model");
        T.eq("26.0.1", m.get("IFD0:Software"), "software");
        T.eq("iPhone 17 Pro Max", m.get("IFD0:HostComputer"), "host");
        T.eq("72", m.get("IFD0:XResolution"), "xres");
        T.eq("72", m.get("IFD0:YResolution"), "yres");
        T.eq("inches", m.get("IFD0:ResolutionUnit"), "unit");
        T.eq("Centered", m.get("IFD0:YCbCrPositioning"), "ycbcr");
        T.eq("0232", m.get("ExifIFD:ExifVersion"), "version");
        T.eq("0100", m.get("ExifIFD:FlashpixVersion"), "flashpix");
        T.eq("sRGB", m.get("ExifIFD:ColorSpace"), "colorspace");
        T.eq("Y, Cb, Cr, -", m.get("ExifIFD:ComponentsConfiguration"), "components");
        T.eq("4032", m.get("ExifIFD:ExifImageWidth"), "w");
        T.eq("3024", m.get("ExifIFD:ExifImageHeight"), "h");
        T.eq("Apple", m.get("ExifIFD:LensMake"), "lensmake");
        T.eq("iPhone 17 Pro Max back triple camera 6.765mm f/1.78", m.get("ExifIFD:LensModel"), "lensmodel");
        Set<String> w = warnings(f);
        T.check(w.isEmpty(), "createMinimal must validate cleanly: " + w);
        // an edit with the same spec is a no-op
        EditReport rep = new EditReport();
        T.check(edit(t, T.appleSpec(), rep) == t, "no-op edit " + rep);
        MediaInfo info = new MediaInfo();
        ExifTiff.inspect(t, info);
        T.eq("Apple", info.make, "inspect");
        T.eq("yok", info.gpsStatus, "gps");
        T.check(!info.hasMakerNote, "mn");
    }

    public static void testCreateMinimalPartialSpec() throws Exception {
        EditSpec s = nullSpec();
        s.make = "samsung";
        s.model = "";       // empty: not written
        s.lensModel = null;
        byte[] t = ExifTiff.createMinimal(s, 0, 0);
        File f = jpeg("minimal-partial.jpg", t);
        Map<String, String> m = T.exif(f);
        T.eq("samsung", m.get("IFD0:Make"), "make");
        T.eq(null, m.get("IFD0:Model"), "model");
        T.eq(null, m.get("IFD0:Software"), "software");
        T.eq(null, m.get("ExifIFD:LensModel"), "lens");
        T.eq(null, m.get("ExifIFD:ExifImageWidth"), "no width");
        T.eq("0232", m.get("ExifIFD:ExifVersion"), "version");
        for (String w : warnings(f)) {
            T.check(w.contains("ExifImageWidth") || w.contains("ExifImageHeight"), "unexpected warning " + w);
        }
        // null spec is accepted
        byte[] t2 = ExifTiff.createMinimal(null, 10, 10);
        T.eq("0232", T.exif(jpeg("minimal-null.jpg", t2)).get("ExifIFD:ExifVersion"), "null spec");
        // edits on a created block work (append + re-write)
        EditReport rep = new EditReport();
        byte[] e = edit(t, T.appleSpec(), rep);
        Map<String, String> m2 = T.exif(jpeg("minimal-edited.jpg", e));
        T.eq("Apple", m2.get("IFD0:Make"), "edited make");
        T.eq("iPhone 17 Pro Max", m2.get("IFD0:Model"), "edited model");
        T.eq("Apple", m2.get("ExifIFD:LensMake"), "edited lens make");
    }

    // =====================================================================================
    // Tests: inspect
    // =====================================================================================

    public static void testInspect() throws Exception {
        for (boolean le : new boolean[]{true, false}) {
            Opt o = opt(le);
            o.make = "HUAWEI   ";
            o.lensMake = "HUAWEI";
            o.hostComputer = "Host";
            Fx fx = build(o);
            MediaInfo info = new MediaInfo();
            ExifTiff.inspect(fx.tiff, info);
            T.check(info.hasExif, "hasExif");
            T.eq("HUAWEI", info.make, "trailing spaces trimmed");
            T.eq("ALN-AL00", info.model, "model");
            T.eq("ALN-AL00 4.0.0.120(C00E120R1P1)", info.software, "software");
            T.eq("Host", info.hostComputer, "host");
            T.eq("HUAWEI", info.lensMake, "lensMake");
            T.eq("HUAWEI REAR CAMERA", info.lensModel, "lensModel");
            T.eq("2025:06:01 12:00:00", info.dateTimeOriginal, "dto");
            T.eq("+03:00", info.offsetTimeOriginal, "oto");
            T.check(info.hasMakerNote, "makernote");
            T.eq("var", info.gpsStatus, "gps");
            T.eq("HUAWEI ALN-AL00", info.deviceLabel(), "label");

            o = opt(le);
            o.gpsMasked = true;
            info = new MediaInfo();
            ExifTiff.inspect(build(o).tiff, info);
            T.eq("maskelenmiş", info.gpsStatus, "masked gps");

            o = opt(le);
            o.gps = false;
            o.makerNote = false;
            o.exif = false;
            info = new MediaInfo();
            ExifTiff.inspect(build(o).tiff, info);
            T.eq("yok", info.gpsStatus, "no gps");
            T.check(!info.hasMakerNote, "no makernote");
            T.eq(null, info.lensModel, "no exif ifd -> no lens");
            T.eq(null, info.dateTimeOriginal, "no dto");
        }
        // garbage
        MediaInfo info = new MediaInfo();
        ExifTiff.inspect(new byte[]{1, 2, 3}, info);
        T.check(info.hasExif, "hasExif even for garbage");
        T.eq("yok", info.gpsStatus, "gps default");
        ExifTiff.inspect(null, new MediaInfo());
        ExifTiff.inspect(new byte[0], null);
    }

    public static void testInspectNonUtf8AndPadding() throws Exception {
        Opt o = opt(true);
        o.make = "Café";               // UTF-8 encoded by the builder
        o.model = "Model\0\0\0";             // embedded NUL padding
        Fx fx = build(o);
        MediaInfo info = new MediaInfo();
        ExifTiff.inspect(fx.tiff, info);
        T.eq("Café", info.make, "utf8 make");
        T.eq("Model", info.model, "model cut at NUL");
        // Latin-1 byte that is not valid UTF-8
        byte[] t = fx.tiff.clone();
        int i = indexOf(t, "Caf".getBytes(StandardCharsets.US_ASCII));
        t[i + 3] = (byte) 0xE9;
        t[i + 4] = 0;
        info = new MediaInfo();
        ExifTiff.inspect(t, info);
        T.eq("Café", info.make, "latin-1 fallback");
    }

    // =====================================================================================
    // Tests: corrupt input
    // =====================================================================================

    public static void testHandCraftedCorruptInputs() throws Exception {
        List<byte[]> cases = new ArrayList<byte[]>();
        cases.add(null);
        cases.add(new byte[0]);
        cases.add(new byte[7]);
        cases.add("II*\0\0\0\0\0".getBytes(StandardCharsets.ISO_8859_1));          // IFD0 offset 0
        cases.add("MM\0*\0\0\0\u0008".getBytes(StandardCharsets.ISO_8859_1));       // IFD0 at EOF
        cases.add("XX*\0\u0008\0\0\0\0\0".getBytes(StandardCharsets.ISO_8859_1));   // bad byte order
        cases.add("II+\0\u0008\0\0\0\0\0".getBytes(StandardCharsets.ISO_8859_1));   // bad magic
        cases.add("Exif\0\0II*\0\u0008\0\0\0".getBytes(StandardCharsets.ISO_8859_1)); // JPEG prefix not stripped
        {   // IFD0 entry count larger than the data
            byte[] b = {'I', 'I', 42, 0, 8, 0, 0, 0, (byte) 0xFF, (byte) 0xFF, 0, 0};
            cases.add(b);
        }
        for (boolean le : new boolean[]{true, false}) {
            Fx fx = build(opt(le));
            TR tr = new TR(fx.tiff);
            TB w = new TB(le);
            // Exif pointer -> IFD0 (loop)
            byte[] b = fx.tiff.clone();
            putField(w, b, tr.entry(tr.ifd0(), 0x8769) + 8, tr.ifd0());
            cases.add(b);
            // IFD1 pointer -> IFD0 (loop)
            b = fx.tiff.clone();
            putField(w, b, tr.ifd0() + 2 + 12 * tr.tags(tr.ifd0()), tr.ifd0());
            cases.add(b);
            // GPS pointer -> Exif IFD (shared IFD)
            b = fx.tiff.clone();
            putField(w, b, tr.entry(tr.ifd0(), 0x8825) + 8, tr.exif());
            cases.add(b);
            // Exif pointer out of range / huge
            b = fx.tiff.clone();
            putField(w, b, tr.entry(tr.ifd0(), 0x8769) + 8, 0xFFFFFFF0L);
            cases.add(b);
            // Make value offset out of range
            b = fx.tiff.clone();
            putField(w, b, tr.entry(tr.ifd0(), 0x010F) + 8, fx.tiff.length - 2);
            cases.add(b);
            // Make count huge
            b = fx.tiff.clone();
            putField(w, b, tr.entry(tr.ifd0(), 0x010F) + 4, 0xFFFFFFFFL);
            cases.add(b);
            // MakerNote count huge
            b = fx.tiff.clone();
            putField(w, b, tr.entry(tr.exif(), 0x927C) + 4, 0x7FFFFFFFL);
            cases.add(b);
            // thumbnail length beyond EOF
            b = fx.tiff.clone();
            putField(w, b, tr.entry(tr.ifd1(), 0x0202) + 8, 0x10000000L);
            cases.add(b);
            // Exif IFD entry count huge
            b = fx.tiff.clone();
            w.b = b;
            w.put16(tr.exif(), 0xFFFF);
            cases.add(b);
            // truncated in the middle of the Exif IFD
            cases.add(Arrays.copyOf(fx.tiff, tr.exif() + 20));
            // truncated right after IFD0 (no next pointer)
            cases.add(Arrays.copyOf(fx.tiff, tr.ifd0() + 2 + 12 * tr.tags(tr.ifd0())));
            // duplicate Make entries
            b = fx.tiff.clone();
            int mk = tr.entry(tr.ifd0(), 0x010F);
            int md = tr.entry(tr.ifd0(), 0x0110);
            System.arraycopy(b, mk, b, md, 2); // model entry now also tagged as Make
            cases.add(b);
        }
        int i = 0;
        for (byte[] c : cases) {
            robust(c, "case " + (i++));
        }
        // specific expectations
        expectUnsupported(null);
        expectUnsupported(new byte[0]);
        expectUnsupported("XX*\0\u0008\0\0\0\0\0".getBytes(StandardCharsets.ISO_8859_1));
        expectUnsupported("Exif\0\0II*\0\u0008\0\0\0".getBytes(StandardCharsets.ISO_8859_1));

        // a broken Exif pointer: IFD0 edits still work; the dead link is replaced by a fresh Exif IFD
        Fx fx = build(opt(true));
        TR tr = new TR(fx.tiff);
        byte[] b = fx.tiff.clone();
        putField(new TB(true), b, tr.entry(tr.ifd0(), 0x8769) + 8, 0xFFFFFFF0L);
        EditReport rep = new EditReport();
        byte[] out = edit(b, T.appleSpec(), rep);
        T.check(hasWarningContaining(rep, "Exif IFD bozuk"), "warned " + rep);
        T.check(hasWarningContaining(rep, "Bozuk Exif IFD bağlantısı kaldırıldı"), "link dropped " + rep);
        TR o = new TR(out);
        T.eq("Apple", o.str(o.ifd0(), 0x010F), "make still edited");
        T.eq("Apple", o.str(o.exif(), 0xA433), "lens make in the new exif ifd");
        Map<String, String> m = T.exif(jpeg("broken-exif-ptr.jpg", out));
        T.eq("iPhone 17 Pro Max back triple camera 6.765mm f/1.78", m.get("ExifIFD:LensModel"), "exiftool lens");
        T.eq("41 deg 0' 0.00\"", m.get("GPS:GPSLatitude"), "gps intact");
        // without any change the broken block is returned untouched
        EditReport rep0 = new EditReport();
        T.check(edit(b, nullSpec(), rep0) == b, "no change -> untouched");
        T.check(hasWarningContaining(rep0, "Exif IFD bozuk"), "still reported " + rep0);

        // GPS pointer beyond EOF: dropped once the block is re-written (it would point into appended data)
        byte[] g = fx.tiff.clone();
        putField(new TB(true), g, tr.entry(tr.ifd0(), 0x8825) + 8, fx.tiff.length + 40);
        EditReport rg = new EditReport();
        byte[] go = edit(g, T.appleSpec(), rg);
        T.eq(-1, new TR(go).entry(new TR(go).ifd0(), 0x8825), "dangling gps link dropped");
        T.check(hasWarningContaining(rg, "Bozuk GPS IFD bağlantısı kaldırıldı"), "warned " + rg);

        // duplicate Make: both copies replaced by one
        byte[] dup = fx.tiff.clone();
        System.arraycopy(dup, tr.entry(tr.ifd0(), 0x010F), dup, tr.entry(tr.ifd0(), 0x0110), 2);
        byte[] dupOut = edit(dup, T.appleSpec(), new EditReport());
        TR d = new TR(dupOut);
        int count = 0;
        for (int k = 0; k < d.tags(d.ifd0()); k++) if (d.u16(d.ifd0() + 2 + 12 * k) == 0x010F) count++;
        T.eq(1, count, "duplicates collapsed");
        absent(dupOut, "HUAWEI", "old make");
        absent(dupOut, "ALN-AL00\0", "old model (dup make) zeroed");
    }

    private static void putField(TB w, byte[] b, int pos, long v) {
        w.b = b;
        w.put32(pos, v);
    }

    private static void expectUnsupported(byte[] in) throws IOException {
        try {
            ExifTiff.edit(in, T.appleSpec(), APP1_MAX, new EditReport());
            throw new AssertionError("expected UnsupportedFormatException");
        } catch (UnsupportedFormatException e) {
            T.check(e.getCause() == null, "not an internal error: " + e.getCause());
        }
    }

    /**
     * Edits/inspects {@code in} with several specs: only UnsupportedFormatException (non-internal) is allowed.
     * Returns the number of edits that succeeded.
     */
    private static int robust(byte[] in, String what) throws IOException {
        int ok = 0;
        byte[] snapshot = in == null ? null : in.clone();
        EditSpec removal = nullSpec();
        removal.make = "";
        removal.model = "";
        removal.software = "";
        removal.lensModel = "";
        removal.removeMakerNote = true;
        removal.clearSerials = true;
        EditSpec[] specs = {T.appleSpec(), nullSpec(), removal};
        int[] sizes = {APP1_MAX, Integer.MAX_VALUE, 300};
        for (EditSpec s : specs) {
            for (int max : sizes) {
                try {
                    EditReport rep = new EditReport();
                    byte[] out = ExifTiff.edit(in, s, max, rep);
                    T.check(out != null, what + ": null output");
                    T.check(out == in || out.length <= max, what + ": exceeds max");
                    // the output is a sane TIFF again: a second identical edit is a no-op
                    EditReport rep2 = new EditReport();
                    byte[] out2 = ExifTiff.edit(out, s, max, rep2);
                    T.check(out2 == out, what + ": not idempotent: " + rep2 + " first=" + rep);
                    ExifTiff.inspect(out, new MediaInfo());
                    ok++;
                } catch (UnsupportedFormatException e) {
                    if (e.getCause() != null) {
                        AssertionError ae = new AssertionError(what + ": internal error");
                        ae.initCause(e.getCause());
                        throw ae;
                    }
                }
            }
        }
        MediaInfo info = new MediaInfo();
        ExifTiff.inspect(in, info);
        T.check(info.hasExif, what + ": hasExif");
        if (in != null) T.check(Arrays.equals(snapshot, in), what + ": input modified");
        return ok;
    }

    public static void testFuzzedInputsNeverCrash() throws Exception {
        List<byte[]> seeds = new ArrayList<byte[]>();
        for (boolean le : new boolean[]{true, false}) {
            seeds.add(build(opt(le)).tiff);
            Opt o = opt(le);
            o.ifd1MakeModel = true;
            o.lensMake = "HUAWEI";
            o.shareMakeWithLensMake = true;
            seeds.add(build(o).tiff);
        }
        seeds.add(ExifTiff.createMinimal(T.appleSpec(), 100, 100));
        Random rnd = new Random(20261101L);
        long[] interesting = {0, 1, 2, 4, 7, 8, 0xFFFF, 0x7FFFFFFFL, 0x80000000L, 0xFFFFFFFFL, 0xFFFFFFF0L};
        int n = 0;
        int ok = 0;
        for (byte[] seed : seeds) {
            TR tr = new TR(seed);
            List<Integer> entryPos = new ArrayList<Integer>();
            int[] ifds = {tr.ifd0(), tr.exif(), tr.ifd1()};
            for (int ifd : ifds) {
                if (ifd <= 0 || ifd >= seed.length) continue;
                for (int k = 0; k < tr.tags(ifd); k++) entryPos.add(ifd + 2 + 12 * k);
            }
            TB w = new TB(seed[0] == 'I');
            for (int iter = 0; iter < 600; iter++) {
                byte[] b = seed.clone();
                int kind = rnd.nextInt(5);
                if (kind == 0) {
                    int flips = 1 + rnd.nextInt(8);
                    for (int f = 0; f < flips; f++) b[rnd.nextInt(b.length)] = (byte) rnd.nextInt(256);
                } else if (kind == 1) {
                    b = Arrays.copyOf(b, rnd.nextInt(b.length + 1));
                } else if (!entryPos.isEmpty()) {
                    int e = entryPos.get(rnd.nextInt(entryPos.size()));
                    long v = rnd.nextBoolean() ? interesting[rnd.nextInt(interesting.length)] : rnd.nextInt(seed.length + 16);
                    w.b = b;
                    if (kind == 2) w.put16(e + 2, rnd.nextInt(16));            // type
                    else if (kind == 3) w.put32(e + 4, v);                   // count
                    else w.put32(e + 8, v);                                   // value / offset
                }
                ok += robust(b, "fuzz seed#" + seeds.indexOf(seed) + " iter " + iter + " kind " + kind);
                n++;
            }
        }
        T.check(n >= 3000, "fuzz iterations " + n);
        // 9 edits per input; most mutated inputs must still be editable (not just rejected)
        T.check(ok > n * 9 / 3, "too few successful fuzz edits: " + ok + " of " + (n * 9));
    }
}
