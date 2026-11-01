package com.metakamera.test;

import com.metakamera.core.ByteArraySource;
import com.metakamera.core.ByteSource;
import com.metakamera.core.EditReport;
import com.metakamera.core.EditSpec;
import com.metakamera.core.FileChannelSource;
import com.metakamera.core.IccIdentity;
import com.metakamera.core.MediaInfo;
import com.metakamera.core.ProgressListener;
import com.metakamera.core.UnsupportedFormatException;
import com.metakamera.core.icc.IccProfileEditor;
import com.metakamera.core.jpeg.JpegEditor;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Tests for {@link JpegEditor}. Also hosts helpers shared with PngEditorTest / WebpEditorTest.
 */
public final class JpegEditorTest {

    static final String SRGB = "/usr/share/color/icc/sRGB.icc";

    // =====================================================================================
    // Shared helpers
    // =====================================================================================

    /** Format editor entry points (so the shared robustness helpers work for every format). */
    interface Ed {
        void edit(ByteSource s, OutputStream o, EditSpec spec, EditReport r, ProgressListener l) throws IOException;

        void inspect(ByteSource s, MediaInfo i) throws IOException;
    }

    static final Ed JPEG = new Ed() {
        @Override
        public void edit(ByteSource s, OutputStream o, EditSpec spec, EditReport r, ProgressListener l) throws IOException {
            JpegEditor.edit(s, o, spec, r, l);
        }

        @Override
        public void inspect(ByteSource s, MediaInfo i) throws IOException {
            JpegEditor.inspect(s, i);
        }
    };

    /** Spec that changes nothing at all. */
    static EditSpec nullSpec() {
        EditSpec s = new EditSpec();
        s.removeMakerNote = false;
        s.clearSerials = false;
        s.editIcc = false;
        s.editXmp = false;
        s.createExifIfMissing = false;
        return s;
    }

    /** Spec that removes every identity value (never creates anything). */
    static EditSpec removalSpec() {
        EditSpec s = nullSpec();
        s.make = "";
        s.model = "";
        s.software = "";
        s.hostComputer = "";
        s.lensMake = "";
        s.lensModel = "";
        s.removeMakerNote = true;
        s.clearSerials = true;
        s.editXmp = true;
        s.createExifIfMissing = true;
        return s;
    }

    static byte[] run(Ed ed, byte[] in, EditSpec spec, EditReport rep) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        ed.edit(new ByteArraySource(in), bo, spec, rep == null ? new EditReport() : rep, null);
        return bo.toByteArray();
    }

    static MediaInfo inspect(Ed ed, byte[] in) throws IOException {
        MediaInfo i = new MediaInfo();
        ed.inspect(new ByteArraySource(in), i);
        return i;
    }

    static File write(String name, byte[] data) throws IOException {
        File f = T.file(name);
        T.write(f, data);
        return f;
    }

    /** exiftool -validate warnings/errors, without the summary line and file-specific text. */
    static Set<String> validateWarnings(File f) throws IOException {
        Set<String> s = new LinkedHashSet<String>();
        for (String line : T.validate(f).split("\n")) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("========") || line.contains("image files read")) continue;
            int c = line.indexOf(':');
            String key = c > 0 ? line.substring(0, c) : line;
            if (key.contains("Validate")) continue;
            if (!key.contains("Warning") && !key.contains("Error")) continue;
            s.add(c > 0 ? line.substring(c + 1).trim() : line);
        }
        return s;
    }

    static void assertNoNewWarnings(File before, File after) throws IOException {
        Set<String> a = validateWarnings(before);
        Set<String> b = validateWarnings(after);
        b.removeAll(a);
        T.check(b.isEmpty(), "new exiftool -validate warnings in " + after.getName() + ": " + b + " (before: " + a + ")");
    }

    /** ImageMagick absolute-error pixel comparison ("0" = identical). */
    static void samePixels(File a, File b, String what) throws IOException {
        T.Result r = T.exec("compare", "-metric", "AE", a.getPath(), b.getPath(), "null:");
        T.eq("0", r.out.trim(), what + ": decoded pixels must be identical");
    }

    static boolean hasChange(EditReport r, String exact) {
        for (String c : r.changes) if (c.equals(exact)) return true;
        return false;
    }

    static boolean hasChangeStarting(EditReport r, String prefix) {
        for (String c : r.changes) if (c.startsWith(prefix)) return true;
        return false;
    }

    static boolean hasWarning(EditReport r, String part) {
        for (String c : r.warnings) if (c.contains(part)) return true;
        return false;
    }

    static int indexOf(byte[] hay, byte[] needle) {
        return indexOf(hay, needle, 0);
    }

    static int indexOf(byte[] hay, byte[] needle, int from) {
        outer:
        for (int i = Math.max(0, from); i + needle.length <= hay.length; i++) {
            for (int j = 0; j < needle.length; j++) if (hay[i + j] != needle[j]) continue outer;
            return i;
        }
        return -1;
    }

    static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.ISO_8859_1);
    }

    /** exiftool -a -G1 -s output (all groups) must not mention any of the strings. */
    static void exiftoolMentionsNone(File f, String... strings) throws IOException {
        String all = T.run("exiftool", "-a", "-G1", "-s", "-u", f.getPath());
        for (String s : strings) T.notContains(all, s, "exiftool output of " + f.getName());
    }

    /** Runs a command and returns its raw stdout bytes (stderr discarded). */
    static byte[] execBytes(String... cmd) throws IOException {
        File out = File.createTempFile("mk-exec", ".bin", T.workDir());
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectOutput(out);
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            Process p = pb.start();
            try {
                p.waitFor();
            } catch (InterruptedException e) {
                throw new IOException(e);
            }
            return Files.readAllBytes(out.toPath());
        } finally {
            out.delete();
        }
    }

    /** Edit + inspect must never throw anything but UnsupportedFormatException. Returns #successful edits. */
    static int robust(Ed ed, byte[] in, String what) throws IOException {
        EditSpec[] specs = {T.appleSpec(), nullSpec(), removalSpec()};
        int ok = 0;
        for (EditSpec s : specs) {
            try {
                EditReport rep = new EditReport();
                byte[] out = run(ed, in, s, rep);
                T.check(out != null, what + ": output");
                ok++;
            } catch (UnsupportedFormatException e) {
                T.check(e.getMessage() != null && !e.getMessage().isEmpty(), what + ": message");
            } catch (RuntimeException e) {
                AssertionError ae = new AssertionError(what + ": RuntimeException " + e);
                ae.initCause(e);
                throw ae;
            }
        }
        try {
            inspect(ed, in);
        } catch (UnsupportedFormatException e) {
            // acceptable
        } catch (RuntimeException e) {
            AssertionError ae = new AssertionError(what + ": inspect RuntimeException " + e);
            ae.initCause(e);
            throw ae;
        }
        return ok;
    }

    /** Random mutations (byte flips, small insertions/deletions) of {@code base} within {@code [0, limit)}. */
    static void fuzz(Ed ed, byte[] base, int limit, long seed, int iterations, String what) throws IOException {
        Random rnd = new Random(seed);
        limit = Math.min(limit, base.length);
        for (int it = 0; it < iterations; it++) {
            byte[] b = base.clone();
            int kind = rnd.nextInt(4);
            if (kind == 0 || kind == 1) {
                int k = 1 + rnd.nextInt(kind == 0 ? 2 : 12);
                for (int j = 0; j < k; j++) {
                    int p = rnd.nextInt(limit);
                    b[p] = rnd.nextInt(3) == 0 ? (byte) 0xFF : (byte) rnd.nextInt(256);
                }
            } else if (kind == 2) {
                int p = rnd.nextInt(limit);
                b = Arrays.copyOf(b, p + rnd.nextInt(Math.max(1, base.length - p)));
            } else {
                int p = rnd.nextInt(limit);
                int n = 1 + rnd.nextInt(16);
                byte[] c = new byte[b.length + n];
                System.arraycopy(b, 0, c, 0, p);
                for (int j = 0; j < n; j++) c[p + j] = (byte) rnd.nextInt(256);
                System.arraycopy(b, p, c, p + n, b.length - p);
                b = c;
            }
            robust(ed, b, what + " #" + it);
        }
    }

    /** Progress recorder. */
    static final class Prog implements ProgressListener {
        final List<long[]> calls = new ArrayList<long[]>();

        @Override
        public void onProgress(long done, long total) {
            calls.add(new long[]{done, total});
        }

        void check(long expectedTotal, String what) {
            T.check(!calls.isEmpty(), what + ": progress reported");
            long prev = -1;
            for (long[] c : calls) {
                T.check(c[0] >= prev, what + ": progress monotonic");
                T.eq(expectedTotal, c[1], what + ": progress total");
                prev = c[0];
            }
            T.eq(expectedTotal, calls.get(calls.size() - 1)[0], what + ": final progress");
        }
    }

    // =====================================================================================
    // JPEG helpers
    // =====================================================================================

    /** Test-side JPEG header walker: {marker, offset, segmentLength(incl. marker)} up to SOS. */
    static List<int[]> segments(byte[] j) {
        List<int[]> l = new ArrayList<int[]>();
        int p = 2;
        while (p + 4 <= j.length) {
            if ((j[p] & 0xFF) != 0xFF) throw new AssertionError("no marker at " + p);
            int m = j[p + 1] & 0xFF;
            if (m == 0xFF) {
                p++;
                continue;
            }
            if (m == 0xDA || m == 0xD9) {
                l.add(new int[]{m, p, 0});
                break;
            }
            if (m == 0x01 || (m >= 0xD0 && m <= 0xD7)) {
                l.add(new int[]{m, p, 2});
                p += 2;
                continue;
            }
            int len = ((j[p + 2] & 0xFF) << 8) | (j[p + 3] & 0xFF);
            l.add(new int[]{m, p, len + 2});
            p += len + 2;
        }
        return l;
    }

    static int sosOffset(byte[] j) {
        for (int[] s : segments(j)) if (s[0] == 0xDA) return s[1];
        throw new AssertionError("no SOS");
    }

    /** Payload (after the length field) of segment s. */
    static byte[] payload(byte[] j, int[] s) {
        return Arrays.copyOfRange(j, s[1] + 4, s[1] + s[2]);
    }

    static List<byte[]> app(byte[] j, int marker, String id) {
        List<byte[]> r = new ArrayList<byte[]>();
        for (int[] s : segments(j)) {
            if (s[0] != marker) continue;
            byte[] p = payload(j, s);
            if (indexOf(p, ascii(id)) == 0) r.add(p);
        }
        return r;
    }

    /** Inserts raw bytes at {@code pos}. */
    static byte[] insert(byte[] b, int pos, byte[] ins) {
        byte[] r = new byte[b.length + ins.length];
        System.arraycopy(b, 0, r, 0, pos);
        System.arraycopy(ins, 0, r, pos, ins.length);
        System.arraycopy(b, pos, r, pos + ins.length, b.length - pos);
        return r;
    }

    static byte[] segment(int marker, byte[] payload) {
        byte[] s = new byte[4 + payload.length];
        s[0] = (byte) 0xFF;
        s[1] = (byte) marker;
        s[2] = (byte) ((payload.length + 2) >> 8);
        s[3] = (byte) (payload.length + 2);
        System.arraycopy(payload, 0, s, 4, payload.length);
        return s;
    }

    /** Offset right after the segment with the given marker+id (first match), or after SOI. */
    static int afterSegment(byte[] j, int marker, String id) {
        for (int[] s : segments(j)) {
            if (s[0] == marker && (id == null || indexOf(payload(j, s), ascii(id)) == 0)) return s[1] + s[2];
        }
        return 2;
    }

    static File plainJpeg(String name, String size) throws IOException {
        File f = T.file(name);
        T.run("convert", "-size", size, "gradient:red-blue", "-quality", "90", f.getPath());
        return f;
    }

    /** JPEG with Huawei EXIF (+ ICC / XMP) written by exiftool. order = "MM" or "II". */
    static File huaweiJpeg(String name, String order, boolean icc, boolean xmp) throws IOException {
        File f = plainJpeg(name, "64x48");
        List<String> cmd = new ArrayList<String>(Arrays.asList("exiftool", "-q", "-overwrite_original",
                "-ExifByteOrder=" + order, "-Make=HUAWEI", "-Model=ALN-AL00",
                "-Software=ALN-AL00 4.0.0.120(C00E120R1P1)", "-LensModel=HUAWEI REAR CAMERA",
                "-SerialNumber=SN123456", "-ImageUniqueID=ABCDEF0123456789",
                "-DateTimeOriginal=2025:06:01 12:00:00", "-OffsetTimeOriginal=+03:00",
                "-ExifImageWidth=64", "-ExifImageHeight=48", "-XResolution=72", "-YResolution=72",
                "-ResolutionUnit=inches"));
        if (icc) cmd.add("-icc_profile<=" + SRGB);
        if (xmp) {
            cmd.add("-XMP-tiff:Make=HUAWEI");
            cmd.add("-XMP-tiff:Model=ALN-AL00");
            cmd.add("-XMP-exifEX:LensModel=HUAWEI REAR CAMERA");
        }
        cmd.add(f.getPath());
        T.run(cmd.toArray(new String[0]));
        return f;
    }

    /** Adds a private 'data' tag of {@code size} bytes to an ICC profile (makes it > 64 KB). */
    static byte[] addPrivateTag(byte[] p, String sig, int size) {
        int count = (int) u32(p, 128);
        int oldSize = (int) u32(p, 0);
        int tableEnd = 132 + 12 * count;
        int dataStart = align4(oldSize + 12);
        byte[] out = new byte[dataStart + 12 + size];
        System.arraycopy(p, 0, out, 0, 128);
        put32(out, 128, count + 1);
        for (int i = 0; i < count; i++) {
            int e = 132 + 12 * i;
            System.arraycopy(p, e, out, e, 12);
            put32(out, e + 4, u32(p, e + 4) + 12);
        }
        int ne = 132 + 12 * count;
        System.arraycopy(ascii(sig), 0, out, ne, 4);
        put32(out, ne + 4, dataStart);
        put32(out, ne + 8, 12 + size);
        System.arraycopy(p, tableEnd, out, tableEnd + 12, oldSize - tableEnd);
        System.arraycopy(ascii("data"), 0, out, dataStart, 4);
        put32(out, dataStart + 8, 1); // binary
        for (int i = 0; i < size; i++) out[dataStart + 12 + i] = (byte) (i * 31 + 7);
        put32(out, 0, out.length);
        return out;
    }

    static int align4(int v) {
        return (v + 3) & ~3;
    }

    static long u32(byte[] b, int off) {
        return ((long) (b[off] & 0xFF) << 24) | ((b[off + 1] & 0xFF) << 16) | ((b[off + 2] & 0xFF) << 8) | (b[off + 3] & 0xFF);
    }

    static void put32(byte[] b, int off, long v) {
        b[off] = (byte) (v >>> 24);
        b[off + 1] = (byte) (v >>> 16);
        b[off + 2] = (byte) (v >>> 8);
        b[off + 3] = (byte) v;
    }

    static void put16(byte[] b, int off, int v, boolean le) {
        if (le) {
            b[off] = (byte) v;
            b[off + 1] = (byte) (v >>> 8);
        } else {
            b[off] = (byte) (v >>> 8);
            b[off + 1] = (byte) v;
        }
    }

    static void put32(byte[] b, int off, long v, boolean le) {
        if (le) {
            b[off] = (byte) v;
            b[off + 1] = (byte) (v >>> 8);
            b[off + 2] = (byte) (v >>> 16);
            b[off + 3] = (byte) (v >>> 24);
        } else {
            put32(b, off, v);
        }
    }

    static long get32(byte[] b, int off, boolean le) {
        if (le) {
            return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8) | ((b[off + 2] & 0xFF) << 16) | ((long) (b[off + 3] & 0xFF) << 24);
        }
        return u32(b, off);
    }

    /** Concatenated ICC data of all APP2 ICC_PROFILE chunks (in sequence order) + {seq,count} list. */
    static byte[] iccFromJpeg(byte[] j, List<int[]> seqCount) {
        List<byte[]> chunks = app(j, 0xE2, "ICC_PROFILE\0");
        byte[][] bySeq = new byte[256][];
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        for (byte[] c : chunks) {
            int seq = c[12] & 0xFF;
            int cnt = c[13] & 0xFF;
            if (seqCount != null) seqCount.add(new int[]{seq, cnt, c.length - 14});
            bySeq[seq] = Arrays.copyOfRange(c, 14, c.length);
        }
        for (byte[] b : bySeq) if (b != null) bo.write(b, 0, b.length);
        return bo.toByteArray();
    }

    // =====================================================================================
    // Tests: full identity change on exiftool-written files
    // =====================================================================================

    public static void testAppleSpecBigEndian() throws Exception {
        appleOnRealFile("MM");
    }

    public static void testAppleSpecLittleEndian() throws Exception {
        appleOnRealFile("II");
    }

    private static void appleOnRealFile(String order) throws Exception {
        File in = huaweiJpeg("jpg-apple-" + order + ".jpg", order, true, true);
        File out = T.file("jpg-apple-" + order + "-out.jpg");
        EditReport rep = T.edit(in, out, T.appleSpec());
        T.eq(com.metakamera.core.MediaFormat.JPEG, rep.format, "format");
        T.eq(out.length(), rep.outputLength, "outputLength");

        Map<String, String> m = T.exif(out);
        T.eq("Apple", m.get("IFD0:Make"), "make");
        T.eq("iPhone 17 Pro Max", m.get("IFD0:Model"), "model");
        T.eq("26.0.1", m.get("IFD0:Software"), "software");
        T.eq("iPhone 17 Pro Max", m.get("IFD0:HostComputer"), "host");
        T.eq("Apple", m.get("ExifIFD:LensMake"), "lens make");
        T.eq("iPhone 17 Pro Max back triple camera 6.765mm f/1.78", m.get("ExifIFD:LensModel"), "lens model");
        T.eq(null, m.get("ExifIFD:SerialNumber"), "serial removed");
        T.eq(null, m.get("ExifIFD:ImageUniqueID"), "uid removed");
        T.eq("2025:06:01 12:00:00", m.get("ExifIFD:DateTimeOriginal"), "date kept");
        T.eq("+03:00", m.get("ExifIFD:OffsetTimeOriginal"), "offset kept");
        T.eq(order.equals("MM") ? "Big-endian (Motorola, MM)" : "Little-endian (Intel, II)",
                m.get("File:ExifByteOrder"), "byte order kept");
        // XMP
        T.eq("Apple", m.get("XMP-tiff:Make"), "xmp make");
        T.eq("iPhone 17 Pro Max", m.get("XMP-tiff:Model"), "xmp model");
        T.eq("iPhone 17 Pro Max back triple camera 6.765mm f/1.78", m.get("XMP-exifEX:LensModel"), "xmp lens");
        // ICC
        T.eq("APPL", T.exif(out, "-n").get("ICC-header:DeviceManufacturer"), "icc manufacturer");
        T.eq("APPL", T.exif(out, "-n").get("ICC-header:PrimaryPlatform"), "icc platform");
        T.eq("Apple", m.get("ICC_Profile:DeviceMfgDesc"), "dmnd");
        T.eq("iPhone 17 Pro Max", m.get("ICC_Profile:DeviceModelDesc"), "dmdd");
        T.eq("sRGB", m.get("ICC_Profile:ProfileDescription"), "desc kept");
        exiftoolMentionsNone(out, "HUAWEI", "ALN-AL00", "SN123456", "ABCDEF0123456789", "lcms generated");

        // report
        T.check(hasChange(rep, "EXIF Make: HUAWEI → Apple"), "report make: " + rep);
        T.check(hasChange(rep, "EXIF Model: ALN-AL00 → iPhone 17 Pro Max"), "report model: " + rep);
        T.check(hasChange(rep, "XMP tiff:Model: ALN-AL00 → iPhone 17 Pro Max"), "report xmp: " + rep);
        T.check(hasChangeStarting(rep, "ICC üretici"), "report icc: " + rep);
        T.eq(0, rep.warnings.size(), "warnings: " + rep);

        // structure: image data and everything after SOS untouched, pixels identical, no new warnings
        byte[] a = T.read(in);
        byte[] b = T.read(out);
        int sa = sosOffset(a);
        int sb = sosOffset(b);
        T.check(Arrays.equals(Arrays.copyOfRange(a, sa, a.length), Arrays.copyOfRange(b, sb, b.length)), "tail identical");
        samePixels(in, out, "apple " + order);
        assertNoNewWarnings(in, out);
        // non-metadata segments kept byte for byte and in order
        List<String> segA = new ArrayList<String>();
        List<String> segB = new ArrayList<String>();
        for (int[] s : segments(a)) if (s[0] != 0xE1 && s[0] != 0xE2) segA.add(Arrays.toString(Arrays.copyOfRange(a, s[1], s[1] + s[2])));
        for (int[] s : segments(b)) if (s[0] != 0xE1 && s[0] != 0xE2) segB.add(Arrays.toString(Arrays.copyOfRange(b, s[1], s[1] + s[2])));
        T.eq(segA, segB, "other segments");

        // inspect
        MediaInfo info = T.inspect(out);
        T.eq("Apple", info.make, "inspect make");
        T.eq("iPhone 17 Pro Max", info.model, "inspect model");
        T.eq("Apple iPhone 17 Pro Max", info.deviceLabel(), "label");
        T.eq(64, info.width, "width");
        T.eq(48, info.height, "height");
        T.check(info.hasExif && info.hasIcc && info.hasXmp, "has exif/icc/xmp");
        T.eq("APPL", info.iccManufacturer, "inspect icc mfr");
        T.eq("Apple", info.iccManufacturerText, "inspect dmnd");
        T.eq("iPhone 17 Pro Max", info.iccModelText, "inspect dmdd");
        T.eq("2025:06:01 12:00:00", info.dateTimeOriginal, "inspect date");

        // idempotent: a second edit changes nothing
        File out2 = T.file("jpg-apple-" + order + "-out2.jpg");
        EditReport rep2 = T.edit(out, out2, T.appleSpec());
        T.check(Arrays.equals(b, T.read(out2)), "second edit must be byte-identical: " + rep2);
        T.eq(0, rep2.changes.size(), "second edit changes: " + rep2);

        // exiftool can still rewrite the result
        File again = T.file("jpg-apple-" + order + "-rewrite.jpg");
        T.write(again, b);
        T.run("exiftool", "-q", "-overwrite_original", "-Artist=Test", again.getPath());
        T.eq("Apple", T.exif(again).get("IFD0:Make"), "exiftool rewrite keeps make");
    }

    public static void testInspectOriginal() throws Exception {
        File in = huaweiJpeg("jpg-inspect.jpg", "MM", true, true);
        MediaInfo i = T.inspect(in);
        T.eq(com.metakamera.core.MediaFormat.JPEG, i.format, "format");
        T.eq(in.length(), i.length, "length");
        T.eq("HUAWEI", i.make, "make");
        T.eq("ALN-AL00", i.model, "model");
        T.eq("ALN-AL00 4.0.0.120(C00E120R1P1)", i.software, "software");
        T.eq("HUAWEI REAR CAMERA", i.lensModel, "lens");
        T.eq("+03:00", i.offsetTimeOriginal, "offset");
        T.check(i.hasExif && i.hasIcc && i.hasXmp, "flags");
        T.check(!i.hasMakerNote, "no makernote");
        T.eq("yok", i.gpsStatus, "gps");
        T.eq("lcms", i.iccManufacturer, "icc mfr");
        T.eq("sRGB", i.iccDescription, "icc desc");
        T.eq("lcms generated", i.iccManufacturerText, "dmnd");
        T.eq("sRGB", i.iccModelText, "dmdd");
        T.eq(64, i.width, "w");
        T.eq(48, i.height, "h");

        // plain JPEG: nothing but the size
        MediaInfo p = T.inspect(plainJpeg("jpg-inspect-plain.jpg", "40x30"));
        T.check(!p.hasExif && !p.hasIcc && !p.hasXmp, "plain flags");
        T.eq(null, p.make, "plain make");
        T.eq(40, p.width, "plain w");
        T.eq(30, p.height, "plain h");

        // progressive (SOF2)
        File prog = T.file("jpg-inspect-prog.jpg");
        T.run("convert", "-size", "33x17", "gradient:red-blue", "-interlace", "Plane", prog.getPath());
        MediaInfo pi = T.inspect(prog);
        T.eq(33, pi.width, "prog w");
        T.eq(17, pi.height, "prog h");
    }

    // =====================================================================================
    // Tests: no-op / byte identity
    // =====================================================================================

    public static void testUnchangedSpecIsByteIdentical() throws Exception {
        List<byte[]> inputs = new ArrayList<byte[]>();
        inputs.add(T.read(huaweiJpeg("jpg-noop-1.jpg", "MM", true, true)));
        inputs.add(T.read(huaweiJpeg("jpg-noop-2.jpg", "II", false, false)));
        inputs.add(T.read(plainJpeg("jpg-noop-3.jpg", "20x20")));
        byte[] trailer = new byte[5000];
        new Random(1).nextBytes(trailer);
        inputs.add(concat(inputs.get(0), trailer));
        inputs.add(mpfFile(true, 2, 0).file);
        for (int k = 0; k < inputs.size(); k++) {
            byte[] in = inputs.get(k);
            EditReport rep = new EditReport();
            byte[] out = run(JPEG, in, nullSpec(), rep);
            T.check(Arrays.equals(in, out), "null spec must be byte-identical #" + k);
            T.eq(0, rep.changes.size(), "no changes #" + k + ": " + rep);
            // a spec that only re-sets the current values is a no-op too
            EditReport rep2 = new EditReport();
            EditSpec same = nullSpec();
            same.make = inspect(JPEG, in).make;
            same.model = inspect(JPEG, in).model;
            same.editXmp = true;
            same.editIcc = true;
            same.icc = new IccIdentity();
            T.check(Arrays.equals(in, run(JPEG, in, same, rep2)), "same values must be byte-identical #" + k + ": " + rep2);
            T.eq(0, rep2.changes.size(), "same-value changes #" + k + ": " + rep2);
        }
    }

    // =====================================================================================
    // Tests: EXIF creation
    // =====================================================================================

    public static void testCreateExifAfterJfif() throws Exception {
        File in = plainJpeg("jpg-create-jfif.jpg", "64x48");
        byte[] a = T.read(in);
        T.eq(0xE0, segments(a).get(0)[0], "fixture starts with APP0");
        File out = T.file("jpg-create-jfif-out.jpg");
        EditReport rep = T.edit(in, out, T.appleSpec());
        byte[] b = T.read(out);
        List<int[]> sb = segments(b);
        T.eq(0xE0, sb.get(0)[0], "APP0 stays first");
        T.eq(0xE1, sb.get(1)[0], "EXIF APP1 right after APP0");
        T.check(indexOf(payload(b, sb.get(1)), ascii("Exif\0\0")) == 0, "Exif id");
        Map<String, String> m = T.exif(out);
        T.eq("Apple", m.get("IFD0:Make"), "make");
        T.eq("iPhone 17 Pro Max", m.get("IFD0:Model"), "model");
        T.eq("iPhone 17 Pro Max back triple camera 6.765mm f/1.78", m.get("ExifIFD:LensModel"), "lens");
        T.eq("64", m.get("ExifIFD:ExifImageWidth"), "pixel x from SOF");
        T.eq("48", m.get("ExifIFD:ExifImageHeight"), "pixel y from SOF");
        T.eq("1.01", m.get("JFIF:JFIFVersion"), "JFIF kept");
        T.check(hasChange(rep, "EXIF bloğu oluşturuldu"), "report: " + rep);
        T.check(hasChange(rep, "EXIF Make eklendi: Apple"), "report make: " + rep);
        T.check(hasChange(rep, "EXIF Model eklendi: iPhone 17 Pro Max"), "report model: " + rep);
        samePixels(in, out, "create");
        assertNoNewWarnings(in, out);
        // the rest of the file is the original after APP0
        int app0End = segments(a).get(0)[2] + 2;
        int exifLen = sb.get(1)[2];
        T.check(Arrays.equals(Arrays.copyOfRange(a, app0End, a.length),
                Arrays.copyOfRange(b, app0End + exifLen, b.length)), "rest identical");
        // idempotent
        EditReport rep2 = new EditReport();
        T.check(Arrays.equals(b, run(JPEG, b, T.appleSpec(), rep2)), "second edit identical: " + rep2);
    }

    public static void testCreateExifWithoutJfifAndFlags() throws Exception {
        File in = plainJpeg("jpg-create-nojfif.jpg", "30x20");
        T.run("exiftool", "-q", "-overwrite_original", "-JFIF:all=", in.getPath());
        byte[] a = T.read(in);
        T.check(segments(a).get(0)[0] != 0xE0, "fixture has no APP0");
        byte[] b = run(JPEG, a, T.appleSpec(), null);
        T.eq(0xE1, segments(b).get(0)[0], "EXIF right after SOI");
        T.check(Arrays.equals(Arrays.copyOfRange(a, 2, a.length),
                Arrays.copyOfRange(b, 2 + segments(b).get(0)[2], b.length)), "rest identical");
        File out = write("jpg-create-nojfif-out.jpg", b);
        T.eq("30", T.exif(out).get("ExifIFD:ExifImageWidth"), "w");
        assertNoNewWarnings(in, out);

        // createExifIfMissing = false: nothing is created
        EditSpec s = T.appleSpec();
        s.createExifIfMissing = false;
        EditReport r = new EditReport();
        T.check(Arrays.equals(a, run(JPEG, a, s, r)), "no creation when disabled");
        T.eq(0, r.changes.size(), "no changes: " + r);

        // only lens / software fields: no EXIF is created ("make or model non-empty")
        EditSpec lensOnly = nullSpec();
        lensOnly.createExifIfMissing = true;
        lensOnly.lensModel = "X";
        lensOnly.software = "Y";
        T.check(Arrays.equals(a, run(JPEG, a, lensOnly, null)), "no creation without make/model");
        // "" never creates
        EditSpec empty = nullSpec();
        empty.createExifIfMissing = true;
        empty.make = "";
        empty.model = "";
        T.check(Arrays.equals(a, run(JPEG, a, empty, null)), "no creation for empty values");

        // model only
        EditSpec modelOnly = nullSpec();
        modelOnly.createExifIfMissing = true;
        modelOnly.model = "Pixel 10 Pro XL";
        File mo = write("jpg-create-model-only.jpg", run(JPEG, a, modelOnly, null));
        Map<String, String> m = T.exif(mo);
        T.eq("Pixel 10 Pro XL", m.get("IFD0:Model"), "model only");
        T.eq(null, m.get("IFD0:Make"), "no make");
    }

    // =====================================================================================
    // Tests: grow / shrink / remove
    // =====================================================================================

    public static void testRemoveAndShrinkValues() throws Exception {
        for (String order : new String[]{"MM", "II"}) {
            File in = huaweiJpeg("jpg-remove-" + order + ".jpg", order, true, true);
            File out = T.file("jpg-remove-" + order + "-out.jpg");
            EditReport rep = T.edit(in, out, removalSpec());
            Map<String, String> m = T.exif(out);
            T.eq(null, m.get("IFD0:Make"), "make removed");
            T.eq(null, m.get("IFD0:Model"), "model removed");
            T.eq(null, m.get("IFD0:Software"), "software removed");
            T.eq(null, m.get("ExifIFD:LensModel"), "lens removed");
            T.eq(null, m.get("XMP-tiff:Make"), "xmp make removed");
            T.eq(null, m.get("XMP-tiff:Model"), "xmp model removed");
            T.eq("2025:06:01 12:00:00", m.get("ExifIFD:DateTimeOriginal"), "date kept");
            T.eq("lcms", T.exif(out, "-n").get("ICC-header:DeviceManufacturer").trim(), "icc untouched (editIcc=false)");
            T.check(hasChange(rep, "EXIF Make kaldırıldı"), "report: " + rep);
            samePixels(in, out, "remove");
            assertNoNewWarnings(in, out);

            // shrink
            EditSpec s = nullSpec();
            s.make = "LG";
            s.model = "G8";
            File out2 = T.file("jpg-shrink-" + order + "-out.jpg");
            T.edit(in, out2, s);
            Map<String, String> m2 = T.exif(out2);
            T.eq("LG", m2.get("IFD0:Make"), "shrunk make");
            T.eq("G8", m2.get("IFD0:Model"), "shrunk model");
            T.eq("HUAWEI", m2.get("XMP-tiff:Make"), "xmp untouched (editXmp=false)");
            assertNoNewWarnings(in, out2);

            // grow far beyond the old size
            EditSpec g = nullSpec();
            g.model = repeat("Very Long Model Name ", 40).trim();
            File out3 = T.file("jpg-grow-" + order + "-out.jpg");
            T.edit(in, out3, g);
            T.eq(g.model, T.exif(out3).get("IFD0:Model"), "grown model");
            assertNoNewWarnings(in, out3);
            samePixels(in, out3, "grow");
        }
    }

    static String repeat(String s, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(s);
        return sb.toString();
    }

    // =====================================================================================
    // Tests: ICC
    // =====================================================================================

    public static void testMultiChunkIccResplit() throws Exception {
        byte[] base = T.read(new File(SRGB));
        byte[] big = addPrivateTag(base, "mkpT", 150000);
        File iccFile = write("jpg-big.icc", big);
        T.check(T.exif(iccFile).get("ICC-header:ProfileFileSignature") != null, "valid big profile");
        File in = huaweiJpeg("jpg-bigicc.jpg", "MM", false, true);
        T.run("exiftool", "-q", "-overwrite_original", "-icc_profile<=" + iccFile.getPath(), in.getPath());
        byte[] a = T.read(in);
        List<int[]> sc = new ArrayList<int[]>();
        T.check(Arrays.equals(big, iccFromJpeg(a, sc)), "fixture holds the big profile");
        T.eq(3, sc.size(), "fixture has 3 ICC chunks");

        // full identity edit: dmnd/dmdd change the profile size -> re-split
        EditSpec spec = T.appleSpec();
        File out = T.file("jpg-bigicc-out.jpg");
        EditReport rep = T.edit(in, out, spec);
        byte[] b = T.read(out);
        List<int[]> sc2 = new ArrayList<int[]>();
        byte[] np = iccFromJpeg(b, sc2);
        byte[] expected = IccProfileEditor.edit(big, spec.icc, new EditReport());
        T.check(expected.length != big.length, "profile size changed");
        T.check(Arrays.equals(expected, np), "re-assembled profile equals the ICC editor result");
        T.eq(3, sc2.size(), "3 chunks");
        for (int k = 0; k < sc2.size(); k++) {
            T.eq(k + 1, sc2.get(k)[0], "seq " + k);
            T.eq(3, sc2.get(k)[1], "count " + k);
            if (k < 2) T.eq(65519, sc2.get(k)[2], "full chunk " + k);
        }
        // the chunks are contiguous, where the first one was
        T.eq(iccSegmentIndexes(a).get(0), iccSegmentIndexes(b).get(0), "first chunk position");
        List<Integer> ib = iccSegmentIndexes(b);
        for (int k = 1; k < ib.size(); k++) T.eq(ib.get(0) + k, ib.get(k), "contiguous chunk " + k);
        Map<String, String> m = T.exif(out, "-n");
        T.eq("APPL", m.get("ICC-header:DeviceManufacturer"), "icc mfr");
        T.eq("Apple", m.get("ICC_Profile:DeviceMfgDesc"), "dmnd");
        T.eq("iPhone 17 Pro Max", m.get("ICC_Profile:DeviceModelDesc"), "dmdd");
        byte[] extracted = execBytes("exiftool", "-b", "-icc_profile", out.getPath());
        T.check(Arrays.equals(expected, extracted), "exiftool extracts the same profile");
        // the private tag data survived
        int tagPos = indexOf(extracted, Arrays.copyOfRange(big, big.length - 150000, big.length - 149000));
        T.check(tagPos > 0, "private tag data kept");
        samePixels(in, out, "big icc");
        assertNoNewWarnings(in, out);
        T.check(hasChangeStarting(rep, "ICC"), "report: " + rep);

        // header-only identity change: same size -> same chunk boundaries and positions
        EditSpec hdr = nullSpec();
        hdr.editIcc = true;
        hdr.icc = new IccIdentity();
        hdr.icc.manufacturerSig = "SECO";
        hdr.icc.modelSig = "";
        byte[] c = run(JPEG, a, hdr, null);
        T.eq(a.length, c.length, "same length");
        int diffFrom = -1;
        int diffTo = -1;
        for (int i = 0; i < a.length; i++) {
            if (a[i] != c[i]) {
                if (diffFrom < 0) diffFrom = i;
                diffTo = i;
            }
        }
        int iccHdr = indexOf(a, ascii("ICC_PROFILE\0")) + 14;
        T.check(diffFrom >= iccHdr + 48 && diffTo < iccHdr + 56, "only ICC header bytes 48..55 changed: " + diffFrom + ".." + diffTo);
        File cf = write("jpg-bigicc-hdr.jpg", c);
        T.eq("SECO", T.exif(cf, "-n").get("ICC-header:DeviceManufacturer"), "hdr-only mfr");
    }

    static List<Integer> iccSegmentIndexes(byte[] j) {
        List<Integer> r = new ArrayList<Integer>();
        List<int[]> segs = segments(j);
        for (int i = 0; i < segs.size(); i++) {
            if (segs.get(i)[0] == 0xE2 && indexOf(payload(j, segs.get(i)), ascii("ICC_PROFILE\0")) == 0) r.add(i);
        }
        return r;
    }

    public static void testInconsistentIccChunksLeftUntouched() throws Exception {
        byte[] big = addPrivateTag(T.read(new File(SRGB)), "mkpT", 100000);
        File iccFile = write("jpg-badicc.icc", big);
        File in = huaweiJpeg("jpg-badicc.jpg", "II", false, false);
        T.run("exiftool", "-q", "-overwrite_original", "-icc_profile<=" + iccFile.getPath(), in.getPath());
        byte[] a = T.read(in);
        int c1 = indexOf(a, ascii("ICC_PROFILE\0"));
        int c2 = indexOf(a, ascii("ICC_PROFILE\0"), c1 + 1);
        T.check(c1 > 0 && c2 > c1, "two chunks");

        byte[][] variants = new byte[3][];
        variants[0] = a.clone();
        variants[0][c2 + 12] = 1; // duplicate sequence number
        variants[1] = a.clone();
        variants[1][c2 + 13] = 3; // count mismatch
        variants[2] = a.clone();
        variants[2][c1 + 12] = 0; // sequence 0
        for (int v = 0; v < variants.length; v++) {
            byte[] bad = variants[v];
            EditReport rep = new EditReport();
            byte[] out = run(JPEG, bad, T.appleSpec(), rep);
            T.check(hasWarning(rep, "ICC profili parçaları tutarsız"), "warning #" + v + ": " + rep);
            T.check(!hasChangeStarting(rep, "ICC"), "no ICC change #" + v + ": " + rep);
            T.check(hasChange(rep, "EXIF Make: HUAWEI → Apple"), "EXIF still edited #" + v);
            // all ICC chunks are byte-identical
            List<byte[]> ia = app(bad, 0xE2, "ICC_PROFILE");
            List<byte[]> ib = app(out, 0xE2, "ICC_PROFILE");
            T.eq(ia.size(), ib.size(), "chunk count #" + v);
            for (int k = 0; k < ia.size(); k++) T.check(Arrays.equals(ia.get(k), ib.get(k)), "chunk " + k + " #" + v);
        }
        // a malformed (non-acsp) single-chunk profile: ICC editor warns, file still edited
        File in2 = huaweiJpeg("jpg-badicc2.jpg", "MM", true, false);
        byte[] b = T.read(in2);
        int p = indexOf(b, ascii("acsp"));
        b[p] = 'x';
        EditReport rep = new EditReport();
        byte[] out = run(JPEG, b, T.appleSpec(), rep);
        T.check(!rep.warnings.isEmpty(), "warning for broken ICC: " + rep);
        T.check(Arrays.equals(app(b, 0xE2, "ICC_PROFILE").get(0), app(out, 0xE2, "ICC_PROFILE").get(0)), "broken ICC untouched");
        T.check(hasChange(rep, "EXIF Make: HUAWEI → Apple"), "EXIF edited");
    }

    // =====================================================================================
    // Tests: XMP
    // =====================================================================================

    static String xmpPacket(String attrs, String body) {
        return "<?xpacket begin=\"﻿\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>\n"
                + "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">"
                + "<rdf:Description rdf:about=\"\" xmlns:tiff=\"http://ns.adobe.com/tiff/1.0/\" "
                + "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" " + attrs + ">" + body
                + "</rdf:Description></rdf:RDF></x:xmpmeta>\n<?xpacket end=\"w\"?>";
    }

    public static void testXmpLimitAndExtendedXmp() throws Exception {
        byte[] a = T.read(huaweiJpeg("jpg-xmplimit.jpg", "MM", false, false));
        String attrs = "tiff:Make=\"HUAWEI\" tiff:Model=\"X\"";
        int fill = 65500 - xmpPacket(attrs, "<dc:source></dc:source>").getBytes(StandardCharsets.UTF_8).length;
        String packet = xmpPacket(attrs, "<dc:source>" + repeat("A", fill) + "</dc:source>");
        byte[] px = packet.getBytes(StandardCharsets.UTF_8);
        T.eq(65500, px.length, "packet near the limit");
        byte[] xmpSeg = segment(0xE1, concat(ascii("http://ns.adobe.com/xap/1.0/\0"), px));
        // extended XMP segment (must never be touched)
        byte[] ext = concat(ascii("http://ns.adobe.com/xmp/extension/\0"), ascii("0123456789ABCDEF0123456789ABCDEF"),
                new byte[]{0, 0, 0, 40, 0, 0, 0, 0}, ascii("<x tiff:Make=\"HUAWEI\" tiff:Model=\"X\"/>  "));
        byte[] extSeg = segment(0xE1, ext);
        int pos = afterSegment(a, 0xE1, "Exif");
        byte[] in = insert(a, pos, concat(xmpSeg, extSeg));

        // model grows by > 2 bytes: packet would exceed 65502 -> XMP kept, warning, EXIF still edited
        EditReport rep = new EditReport();
        byte[] out = run(JPEG, in, T.appleSpec(), rep);
        T.check(hasWarning(rep, "XMP düzenlenince JPEG sınırını"), "limit warning: " + rep);
        T.check(!hasChangeStarting(rep, "XMP"), "no XMP change reported: " + rep);
        T.check(hasChange(rep, "EXIF Make: HUAWEI → Apple"), "EXIF edited: " + rep);
        T.check(Arrays.equals(app(in, 0xE1, "http://ns.adobe.com/xap/1.0/\0").get(0),
                app(out, 0xE1, "http://ns.adobe.com/xap/1.0/\0").get(0)), "XMP untouched");
        T.check(Arrays.equals(ext, app(out, 0xE1, "http://ns.adobe.com/xmp/extension/").get(0)), "extended XMP untouched");

        // a short value fits: XMP edited, extended XMP still untouched
        EditSpec s = nullSpec();
        s.editXmp = true;
        s.make = "LG";
        s.model = "Q";
        EditReport rep2 = new EditReport();
        byte[] out2 = run(JPEG, in, s, rep2);
        T.check(hasChange(rep2, "XMP tiff:Make: HUAWEI → LG"), "xmp make: " + rep2);
        T.check(Arrays.equals(ext, app(out2, 0xE1, "http://ns.adobe.com/xmp/extension/").get(0)), "extended XMP untouched (2)");
        File f2 = write("jpg-xmplimit-out.jpg", out2);
        T.eq("LG", T.exif(f2).get("XMP-tiff:Make"), "exiftool xmp make");
        T.eq("Q", T.exif(f2).get("XMP-tiff:Model"), "exiftool xmp model");

        // editXmp = false: XMP untouched even though make changes
        EditSpec noXmp = T.appleSpec();
        noXmp.editXmp = false;
        byte[] out3 = run(JPEG, in, noXmp, null);
        T.check(Arrays.equals(app(in, 0xE1, "http://ns.adobe.com/xap/1.0/\0").get(0),
                app(out3, 0xE1, "http://ns.adobe.com/xap/1.0/\0").get(0)), "XMP untouched when disabled");

        // invalid UTF-8 inside the packet: warning, untouched
        byte[] badPx = xmpPacket("tiff:Make=\"HUAWEI\"", "").getBytes(StandardCharsets.UTF_8);
        badPx[badPx.length - 30] = (byte) 0xC3;
        byte[] in4 = insert(a, pos, segment(0xE1, concat(ascii("http://ns.adobe.com/xap/1.0/\0"), badPx)));
        EditReport rep4 = new EditReport();
        byte[] out4 = run(JPEG, in4, T.appleSpec(), rep4);
        T.check(hasWarning(rep4, "UTF-8"), "utf8 warning: " + rep4);
        T.check(Arrays.equals(app(in4, 0xE1, "http://ns.adobe.com/xap/1.0/\0").get(0),
                app(out4, 0xE1, "http://ns.adobe.com/xap/1.0/\0").get(0)), "bad XMP untouched");
    }

    // =====================================================================================
    // Tests: trailers and MPF
    // =====================================================================================

    public static void testTrailerAfterEoiPreserved() throws Exception {
        File base = huaweiJpeg("jpg-motion.jpg", "MM", true, true);
        File mp4 = T.file("jpg-motion-video.mp4");
        T.run("ffmpeg", "-y", "-v", "error", "-f", "lavfi", "-i", "testsrc=size=64x48:rate=10", "-t", "1",
                "-pix_fmt", "yuv420p", mp4.getPath());
        byte[] video = T.read(mp4);
        byte[] in = concat(T.read(base), video);
        File inF = write("jpg-motion-in.jpg", in);
        File out = T.file("jpg-motion-out.jpg");
        Prog prog = new Prog();
        FileChannelSource src = FileChannelSource.open(inF);
        EditReport rep = new EditReport();
        try (OutputStream os = new java.io.BufferedOutputStream(new java.io.FileOutputStream(out))) {
            JpegEditor.edit(src, os, T.appleSpec(), rep, prog);
        } finally {
            src.close();
        }
        byte[] b = T.read(out);
        prog.check(b.length, "progress");
        T.check(Arrays.equals(video, Arrays.copyOfRange(b, b.length - video.length, b.length)), "video trailer byte-identical");
        T.eq("Apple", T.exif(out).get("IFD0:Make"), "make");
        samePixels(base, out, "motion");
        // the trailer still decodes
        File tail = write("jpg-motion-tail.mp4", Arrays.copyOfRange(b, b.length - video.length, b.length));
        T.eq("", T.ffmpegDecodeErrors(tail), "trailer video decodes");
        T.check(rep.changes.size() > 5, "changes: " + rep);
    }

    /** Result of {@link #mpfFile}. */
    static final class Mpf {
        byte[] file;
        byte[] secondary;
    }

    /**
     * Builds a 2-image MPF file: primary (Huawei EXIF + ICC + XMP) with a hand-written MPF APP2
     * (inserted at segment position {@code where}: 0 = right after SOI, 1 = after EXIF APP1,
     * 2 = after the last header metadata segment) and a second JPEG appended after EOI.
     */
    static Mpf mpfFile(boolean le, int where, int seed) throws IOException {
        byte[] primary = T.read(huaweiJpeg("jpg-mpf-primary-" + seed + ".jpg", "MM", true, true));
        File sec = T.file("jpg-mpf-secondary-" + seed + ".jpg");
        T.run("convert", "-size", "32x24", "gradient:green-yellow", sec.getPath());
        byte[] secondary = T.read(sec);

        byte[] tiff = new byte[8 + 2 + 3 * 12 + 4 + 32];
        if (le) {
            tiff[0] = 'I';
            tiff[1] = 'I';
            tiff[2] = 42;
        } else {
            tiff[0] = 'M';
            tiff[1] = 'M';
            tiff[3] = 42;
        }
        put32(tiff, 4, 8, le);
        put16(tiff, 8, 3, le);
        int e = 10;
        put16(tiff, e, 0xB000, le);
        put16(tiff, e + 2, 7, le);
        put32(tiff, e + 4, 4, le);
        System.arraycopy(ascii("0100"), 0, tiff, e + 8, 4);
        e += 12;
        put16(tiff, e, 0xB001, le);
        put16(tiff, e + 2, 4, le);
        put32(tiff, e + 4, 1, le);
        put32(tiff, e + 8, 2, le);
        e += 12;
        put16(tiff, e, 0xB002, le);
        put16(tiff, e + 2, 7, le);
        put32(tiff, e + 4, 32, le);
        put32(tiff, e + 8, 50, le);
        // next IFD = 0 at 46..49; entries at 50
        byte[] seg = segment(0xE2, concat(ascii("MPF\0"), tiff));

        int pos;
        List<int[]> segs = segments(primary);
        if (where == 0) {
            pos = 2;
        } else if (where == 1) {
            pos = afterSegment(primary, 0xE1, "Exif");
        } else {
            pos = 2;
            for (int[] s : segs) if (s[0] >= 0xE0 && s[0] <= 0xEF) pos = s[1] + s[2];
        }
        int total = primary.length + seg.length;
        int hdr = pos + 8;
        int entries = 4 + 50; // offset of the MP entries inside the segment payload
        byte[] segFilled = seg.clone();
        int ep = 4 + entries; // inside the segment bytes (marker + length = 4)
        put32(segFilled, ep, 0x20030000L, le);
        put32(segFilled, ep + 4, total, le);
        put32(segFilled, ep + 8, 0, le);
        put32(segFilled, ep + 16, 0x00020002L, le); // multi-frame disparity image -> exiftool "MPImage2"
        put32(segFilled, ep + 20, secondary.length, le);
        put32(segFilled, ep + 24, total - hdr, le);
        Mpf m = new Mpf();
        m.file = concat(insert(primary, pos, segFilled), secondary);
        m.secondary = secondary;
        return m;
    }

    public static void testMpfOffsetsFixedWhenLaterSegmentsChange() throws Exception {
        int[][] variants = {{0, 1}, {1, 1}, {0, 0}, {1, 0}, {1, 2}}; // {le, where}
        int seed = 10;
        for (int[] v : variants) {
            boolean le = v[0] == 1;
            Mpf mpf = mpfFile(le, v[1], seed++);
            String tag = "le=" + le + " where=" + v[1];
            File in = write("jpg-mpf-in-" + seed + ".jpg", mpf.file);
            T.check(Arrays.equals(mpf.secondary, execBytes("exiftool", "-b", "-MPImage2", in.getPath())),
                    "fixture: exiftool extracts MPImage2 " + tag);
            File out = T.file("jpg-mpf-out-" + seed + ".jpg");
            EditReport rep = T.edit(in, out, T.appleSpec());
            byte[] b = T.read(out);
            byte[] img2 = execBytes("exiftool", "-b", "-MPImage2", out.getPath());
            T.check(Arrays.equals(mpf.secondary, img2), "MPImage2 still points at the 2nd JPEG " + tag + ": " + rep);
            File img2f = write("jpg-mpf-img2-" + seed + ".jpg", img2);
            T.run("convert", img2f.getPath(), "null:");
            boolean shouldPatch = v[1] != 2 && b.length != mpf.file.length;
            T.eq(shouldPatch, hasChangeStarting(rep, "MPF ek görüntü konumları güncellendi"), "MPF report " + tag + ": " + rep);
            T.eq("Apple", T.exif(out).get("IFD0:Make"), "make " + tag);
            samePixels(in, out, "mpf " + tag);
            assertNoNewWarnings(in, out);
            T.check(Arrays.equals(mpf.secondary, Arrays.copyOfRange(b, b.length - mpf.secondary.length, b.length)), "trailer " + tag);
        }
    }

    public static void testMpfCorruptIsReportedNotFatal() throws Exception {
        Mpf mpf = mpfFile(false, 0, 30);
        byte[] in = mpf.file.clone();
        int p = indexOf(in, ascii("MPF\0MM"));
        in[p + 4] = 'X'; // byte order destroyed
        EditReport rep = new EditReport();
        byte[] out = run(JPEG, in, T.appleSpec(), rep);
        T.check(hasWarning(rep, "MPF"), "MPF warning: " + rep);
        T.check(hasChange(rep, "EXIF Make: HUAWEI → Apple"), "EXIF edited");
        T.check(Arrays.equals(app(in, 0xE2, "MPF").get(0), app(out, 0xE2, "MPF").get(0)), "MPF untouched");
        // MP entry list pointing out of the segment
        byte[] in2 = mpf.file.clone();
        int q = indexOf(in2, ascii("MPF\0MM")) + 4;
        put32(in2, q + 8 + 2 + 24 + 8, 60000); // MPEntry value offset
        EditReport rep2 = new EditReport();
        run(JPEG, in2, T.appleSpec(), rep2);
        T.check(hasWarning(rep2, "MPF"), "MPF warning 2: " + rep2);
    }

    // =====================================================================================
    // Tests: EXIF problems are warnings, not failures
    // =====================================================================================

    public static void testBrokenExifKeptWithWarning() throws Exception {
        File in = huaweiJpeg("jpg-badexif.jpg", "MM", true, false);
        byte[] a = T.read(in);
        int p = indexOf(a, ascii("Exif\0\0MM"));
        byte[] bad = a.clone();
        bad[p + 6] = 'Q'; // byte order mark destroyed
        EditReport rep = new EditReport();
        byte[] out = run(JPEG, bad, T.appleSpec(), rep);
        T.check(hasWarning(rep, "EXIF"), "warning: " + rep);
        T.check(Arrays.equals(app(bad, 0xE1, "Exif").get(0), app(out, 0xE1, "Exif").get(0)), "broken EXIF untouched");
        T.check(hasChangeStarting(rep, "ICC"), "ICC still edited: " + rep);
        T.check(!hasChange(rep, "EXIF bloğu oluşturuldu"), "no second EXIF created");
        T.eq(1, app(out, 0xE1, "Exif").size(), "still one EXIF");

        // EXIF that cannot fit 64 KB after the edit (huge UserComment, no droppable data)
        File in2 = huaweiJpeg("jpg-fullexif.jpg", "II", false, false);
        T.run("exiftool", "-q", "-overwrite_original", "-UserComment=" + repeat("x", 64900), in2.getPath());
        byte[] c = T.read(in2);
        T.check(app(c, 0xE1, "Exif").get(0).length > 65000, "fixture EXIF is almost 64 KB");
        EditSpec s = nullSpec();
        s.model = repeat("M", 2000);
        EditReport rep2 = new EditReport();
        byte[] out2 = run(JPEG, c, s, rep2);
        T.check(hasWarning(rep2, "EXIF"), "size warning: " + rep2);
        T.check(Arrays.equals(c, out2), "file unchanged");
        T.eq(0, rep2.changes.size(), "no changes: " + rep2);
    }

    public static void testSecondExifBlockAlsoEdited() throws Exception {
        File in = huaweiJpeg("jpg-dupexif.jpg", "MM", false, false);
        byte[] a = T.read(in);
        byte[] exifSeg = segment(0xE1, app(a, 0xE1, "Exif").get(0));
        int after = afterSegment(a, 0xE1, "Exif");
        byte[] dup = insert(a, after, exifSeg);
        T.eq(2, app(dup, 0xE1, "Exif").size(), "fixture has two EXIF blocks");
        EditReport rep = new EditReport();
        byte[] out = run(JPEG, dup, T.appleSpec(), rep);
        List<byte[]> ex = app(out, 0xE1, "Exif");
        T.eq(2, ex.size(), "still two EXIF blocks");
        for (byte[] e : ex) {
            T.check(indexOf(e, ascii("HUAWEI")) < 0, "no HUAWEI left in any EXIF block");
            T.check(indexOf(e, ascii("iPhone 17 Pro Max")) > 0, "new model in every EXIF block");
        }
        T.eq("Apple", inspect(JPEG, out).make, "inspect uses the first block");
        File fin = write("jpg-dupexif-in.jpg", dup);
        File fout = write("jpg-dupexif-out.jpg", out);
        samePixels(fin, fout, "dup exif");
        assertNoNewWarnings(fin, fout);
    }

    // =====================================================================================
    // Tests: unusual but valid structure
    // =====================================================================================

    public static void testFillBytesAndStandaloneMarkers() throws Exception {
        File in = huaweiJpeg("jpg-fill.jpg", "MM", false, false);
        byte[] a = T.read(in);
        int exifPos = afterSegment(a, 0xE0, null);
        // fill bytes before the EXIF APP1 marker and before DQT
        byte[] f1 = insert(a, exifPos, new byte[]{(byte) 0xFF, (byte) 0xFF, (byte) 0xFF});
        int dqt = -1;
        for (int[] s : segments(f1)) if (s[0] == 0xDB) {
            dqt = s[1];
            break;
        }
        byte[] f2 = insert(f1, dqt, new byte[]{(byte) 0xFF, (byte) 0xFF});
        File fin = write("jpg-fill-in.jpg", f2);
        T.eq("HUAWEI", T.inspect(fin).make, "inspect through fill bytes");
        EditReport rep = new EditReport();
        byte[] out = run(JPEG, f2, T.appleSpec(), rep);
        File fout = write("jpg-fill-out.jpg", out);
        T.eq("Apple", T.exif(fout).get("IFD0:Make"), "make");
        samePixels(in, fout, "fill");
        T.check(indexOf(out, new byte[]{(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xE1}) > 0, "fill bytes before APP1 kept");
        T.eq(0, rep.warnings.size(), "no warnings: " + rep);
        T.check(Arrays.equals(f2, run(JPEG, f2, nullSpec(), null)), "noop identical with fill bytes");

        // standalone TEM marker and garbage between segments: kept verbatim
        byte[] g = insert(a, dqtOf(a), new byte[]{(byte) 0xFF, 0x01, 0x00, 0x00, 0x12});
        EditReport rg = new EditReport();
        byte[] go = run(JPEG, g, T.appleSpec(), rg);
        T.check(hasWarning(rg, "tanınmayan veri"), "garbage warning: " + rg);
        T.check(hasChange(rg, "EXIF Make: HUAWEI → Apple"), "edited despite garbage");
        T.check(indexOf(go, new byte[]{(byte) 0xFF, 0x01, 0x00, 0x00, 0x12, (byte) 0xFF, (byte) 0xDB}) > 0, "TEM + garbage kept in place");
        T.check(Arrays.equals(g, run(JPEG, g, nullSpec(), null)), "noop identical with garbage");

        // tables-only JPEG (EOI before any SOS)
        byte[] t = concat(Arrays.copyOfRange(a, 0, dqtOf(a)), new byte[]{(byte) 0xFF, (byte) 0xD9});
        EditReport rt = new EditReport();
        byte[] to = run(JPEG, t, T.appleSpec(), rt);
        T.check(hasChange(rt, "EXIF Make: HUAWEI → Apple"), "tables-only edited");
        T.eq((byte) 0xD9, to[to.length - 1], "EOI kept");
    }

    static int dqtOf(byte[] j) {
        for (int[] s : segments(j)) if (s[0] == 0xDB) return s[1];
        throw new AssertionError("no DQT");
    }

    // =====================================================================================
    // Tests: corrupt input never crashes
    // =====================================================================================

    public static void testCorruptAndTruncatedInputs() throws Exception {
        byte[] a = T.read(huaweiJpeg("jpg-corrupt.jpg", "MM", true, true));
        int sos = sosOffset(a);
        // every truncation point inside the header (+ a few after SOS)
        for (int cut = 0; cut < sos + 40; cut += (cut < 400 ? 1 : 7)) {
            robust(JPEG, Arrays.copyOf(a, cut), "cut " + cut);
        }
        // truncated header -> UnsupportedFormatException with a Turkish message
        try {
            run(JPEG, Arrays.copyOf(a, 300), T.appleSpec(), null);
            throw new AssertionError("truncated header must be rejected");
        } catch (UnsupportedFormatException e) {
            T.contains(e.getMessage(), "JPEG", "message");
        }
        // segment length < 2
        byte[] l = a.clone();
        int app1 = afterSegment(a, 0xE0, null);
        l[app1 + 2] = 0;
        l[app1 + 3] = 1;
        robust(JPEG, l, "length 1");
        // length beyond EOF
        byte[] l2 = a.clone();
        l2[app1 + 2] = (byte) 0xFF;
        l2[app1 + 3] = (byte) 0xFF;
        robust(JPEG, Arrays.copyOf(l2, app1 + 2000), "length beyond EOF");
        // marker 0x00 in the header
        byte[] z = a.clone();
        z[app1 + 1] = 0;
        robust(JPEG, z, "marker 00");
        // header of only fill bytes
        byte[] ff = new byte[5000];
        Arrays.fill(ff, (byte) 0xFF);
        ff[1] = (byte) 0xD8;
        robust(JPEG, ff, "all fill");
        // EXIF APP1 that is just "Exif\0\0"
        robust(JPEG, insert(Arrays.copyOf(a, a.length), 2, segment(0xE1, ascii("Exif\0\0"))), "empty exif");
        robust(JPEG, insert(a, 2, segment(0xE2, ascii("ICC_PROFILE\0"))), "short icc");
        robust(JPEG, insert(a, 2, segment(0xE2, ascii("MPF\0"))), "short mpf");
        robust(JPEG, insert(a, 2, segment(0xE1, ascii("http://ns.adobe.com/xap/1.0/\0"))), "empty xmp");
        robust(JPEG, insert(a, 2, segment(0xC0, new byte[]{8})), "short SOF");
        // random mutations in the header
        fuzz(JPEG, a, sos + 16, 12345, 400, "jpeg fuzz");
        Mpf mpf = mpfFile(true, 1, 40);
        fuzz(JPEG, mpf.file, sosOffset(mpf.file) + 16, 777, 150, "mpf fuzz");
    }

    public static void testProgressOnLargeFile() throws Exception {
        byte[] a = T.read(huaweiJpeg("jpg-large.jpg", "II", true, true));
        byte[] big = new byte[9 * 1024 * 1024];
        new Random(5).nextBytes(big);
        File in = write("jpg-large-in.jpg", concat(a, big));
        File out = T.file("jpg-large-out.jpg");
        Prog prog = new Prog();
        FileChannelSource src = FileChannelSource.open(in);
        try (OutputStream os = new java.io.BufferedOutputStream(new java.io.FileOutputStream(out))) {
            JpegEditor.edit(src, os, T.appleSpec(), new EditReport(), prog);
        } finally {
            src.close();
        }
        prog.check(out.length(), "large progress");
        T.check(prog.calls.size() >= 3, "several progress calls: " + prog.calls.size());
        byte[] b = T.read(out);
        T.check(Arrays.equals(big, Arrays.copyOfRange(b, b.length - big.length, b.length)), "trailer identical");
    }

    static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        for (byte[] p : parts) bo.write(p, 0, p.length);
        return bo.toByteArray();
    }
}
