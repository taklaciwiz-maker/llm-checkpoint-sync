package com.metakamera.test;

import com.metakamera.core.ByteSource;
import com.metakamera.core.EditReport;
import com.metakamera.core.EditSpec;
import com.metakamera.core.FileChannelSource;
import com.metakamera.core.IccIdentity;
import com.metakamera.core.MediaFormat;
import com.metakamera.core.MediaInfo;
import com.metakamera.core.ProgressListener;
import com.metakamera.core.UnsupportedFormatException;
import com.metakamera.core.icc.IccProfileEditor;
import com.metakamera.core.webp.WebpEditor;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static com.metakamera.test.JpegEditorTest.Ed;
import static com.metakamera.test.JpegEditorTest.ascii;
import static com.metakamera.test.JpegEditorTest.assertNoNewWarnings;
import static com.metakamera.test.JpegEditorTest.concat;
import static com.metakamera.test.JpegEditorTest.exiftoolMentionsNone;
import static com.metakamera.test.JpegEditorTest.hasChange;
import static com.metakamera.test.JpegEditorTest.hasChangeStarting;
import static com.metakamera.test.JpegEditorTest.hasWarning;
import static com.metakamera.test.JpegEditorTest.indexOf;
import static com.metakamera.test.JpegEditorTest.inspect;
import static com.metakamera.test.JpegEditorTest.nullSpec;
import static com.metakamera.test.JpegEditorTest.removalSpec;
import static com.metakamera.test.JpegEditorTest.robust;
import static com.metakamera.test.JpegEditorTest.run;
import static com.metakamera.test.JpegEditorTest.write;

/** Tests for {@link WebpEditor}. */
public final class WebpEditorTest {

    static final Ed WEBP = new Ed() {
        @Override
        public void edit(ByteSource s, OutputStream o, EditSpec spec, EditReport r, ProgressListener l) throws IOException {
            WebpEditor.edit(s, o, spec, r, l);
        }

        @Override
        public void inspect(ByteSource s, MediaInfo i) throws IOException {
            WebpEditor.inspect(s, i);
        }
    };

    // =====================================================================================
    // helpers
    // =====================================================================================

    /** WebP from an ImageMagick gradient; alpha adds a semi-transparent alpha channel. */
    static File plainWebp(String name, String size, boolean lossless, boolean alpha) throws IOException {
        File png = T.file(name + ".src.png");
        if (alpha) {
            T.run("convert", "-size", size, "gradient:red-blue", "-alpha", "set", "-channel", "A",
                    "-evaluate", "set", "50%", "+channel", "png32:" + png.getPath());
        } else {
            T.run("convert", "-size", size, "gradient:red-blue", png.getPath());
        }
        File f = T.file(name);
        List<String> cmd = new ArrayList<String>(Arrays.asList("cwebp", "-quiet"));
        if (lossless) cmd.add("-lossless");
        cmd.add(png.getPath());
        cmd.add("-o");
        cmd.add(f.getPath());
        T.run(cmd.toArray(new String[0]));
        return f;
    }

    static File huaweiWebp(String name, String order, boolean icc, boolean xmp, boolean lossless) throws IOException {
        File f = plainWebp(name, "64x48", lossless, false);
        List<String> cmd = new ArrayList<String>(Arrays.asList("exiftool", "-q", "-overwrite_original",
                "-ExifByteOrder=" + order, "-EXIF:Make=HUAWEI", "-EXIF:Model=ALN-AL00",
                "-EXIF:Software=ALN-AL00 4.0.0.120(C00E120R1P1)", "-EXIF:LensModel=HUAWEI REAR CAMERA",
                "-EXIF:SerialNumber=SN123456", "-EXIF:DateTimeOriginal=2025:06:01 12:00:00",
                "-EXIF:ExifImageWidth=64", "-EXIF:ExifImageHeight=48"));
        if (icc) cmd.add("-icc_profile<=" + JpegEditorTest.SRGB);
        if (xmp) {
            cmd.add("-XMP-tiff:Make=HUAWEI");
            cmd.add("-XMP-tiff:Model=ALN-AL00");
        }
        cmd.add(f.getPath());
        T.run(cmd.toArray(new String[0]));
        return f;
    }

    /** Test-side chunk walk: {fourcc, offset, payloadSize}; checks RIFF size and padding. */
    static List<Object[]> chunks(byte[] w, boolean expectExactRiff) {
        T.eq("RIFF", new String(w, 0, 4, java.nio.charset.StandardCharsets.ISO_8859_1), "RIFF");
        T.eq("WEBP", new String(w, 8, 4, java.nio.charset.StandardCharsets.ISO_8859_1), "WEBP");
        long riff = le32(w, 4);
        if (expectExactRiff) T.eq((long) w.length - 8, riff, "RIFF size");
        List<Object[]> l = new ArrayList<Object[]>();
        int p = 12;
        long end = Math.min(w.length, riff + 8);
        while (p + 8 <= end) {
            String f = new String(w, p, 4, java.nio.charset.StandardCharsets.ISO_8859_1);
            long sz = le32(w, p + 4);
            l.add(new Object[]{f, p, (int) sz});
            p += 8 + (int) sz + (int) (sz & 1);
        }
        T.eq(end, (long) p, "chunks end exactly at the RIFF end");
        return l;
    }

    static List<String> fourccs(byte[] w) {
        List<String> r = new ArrayList<String>();
        for (Object[] c : chunks(w, false)) r.add((String) c[0]);
        return r;
    }

    static byte[] chunkPayload(byte[] w, String fourcc) {
        for (Object[] c : chunks(w, false)) {
            if (c[0].equals(fourcc)) return Arrays.copyOfRange(w, (Integer) c[1] + 8, (Integer) c[1] + 8 + (Integer) c[2]);
        }
        return null;
    }

    static long le32(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8) | ((b[off + 2] & 0xFF) << 16) | ((long) (b[off + 3] & 0xFF) << 24);
    }

    static byte[] le32b(long v) {
        return new byte[]{(byte) v, (byte) (v >>> 8), (byte) (v >>> 16), (byte) (v >>> 24)};
    }

    static byte[] chunk(String fourcc, byte[] payload) {
        byte[] pad = (payload.length & 1) != 0 ? new byte[1] : new byte[0];
        return concat(ascii(fourcc), le32b(payload.length), payload, pad);
    }

    /** Rebuilds a WebP from chunks (RIFF size computed). */
    static byte[] riff(byte[]... chunks) {
        byte[] body = concat(chunks);
        return concat(ascii("RIFF"), le32b(body.length + 4), ascii("WEBP"), body);
    }

    static byte[] rawChunk(byte[] w, String fourcc) {
        for (Object[] c : chunks(w, false)) {
            if (c[0].equals(fourcc)) {
                int sz = (Integer) c[2];
                return Arrays.copyOfRange(w, (Integer) c[1], (Integer) c[1] + 8 + sz + (sz & 1));
            }
        }
        return null;
    }

    static void webpinfoOk(File f) throws IOException {
        T.Result r = T.exec("webpinfo", f.getPath());
        T.contains(r.out, "No error detected", "webpinfo " + f.getName());
    }

    /** Decoded RGBA (PAM) of both files must be identical. */
    static void sameDecoded(File a, File b, String what) throws IOException {
        File pa = T.file(a.getName() + ".pam");
        File pb = T.file(b.getName() + ".pam");
        T.run("dwebp", "-quiet", "-pam", a.getPath(), "-o", pa.getPath());
        T.run("dwebp", "-quiet", "-pam", b.getPath(), "-o", pb.getPath());
        T.check(Arrays.equals(T.read(pa), T.read(pb)), what + ": decoded RGBA identical");
    }

    // =====================================================================================
    // tests: identity change
    // =====================================================================================

    public static void testAppleSpecExtendedLossyBigEndian() throws Exception {
        appleOn("MM", false);
    }

    public static void testAppleSpecExtendedLosslessLittleEndian() throws Exception {
        appleOn("II", true);
    }

    private static void appleOn(String order, boolean lossless) throws Exception {
        String tag = order + (lossless ? "-ll" : "-ly");
        File in = huaweiWebp("webp-apple-" + tag + ".webp", order, true, true, lossless);
        byte[] a = T.read(in);
        T.eq(Arrays.asList("VP8X", "ICCP", lossless ? "VP8L" : "VP8 ", "EXIF", "XMP "), fourccs(a), "fixture layout");
        File out = T.file("webp-apple-" + tag + "-out.webp");
        EditReport rep = T.edit(in, out, T.appleSpec());
        T.eq(MediaFormat.WEBP, rep.format, "format");
        byte[] b = T.read(out);
        T.eq(fourccs(a), fourccs(b), "layout kept");
        chunks(b, true);
        Map<String, String> m = T.exif(out);
        T.eq("Apple", m.get("IFD0:Make"), "make");
        T.eq("iPhone 17 Pro Max", m.get("IFD0:Model"), "model");
        T.eq("26.0.1", m.get("IFD0:Software"), "software");
        T.eq("iPhone 17 Pro Max back triple camera 6.765mm f/1.78", m.get("ExifIFD:LensModel"), "lens");
        T.eq(null, m.get("ExifIFD:SerialNumber"), "serial");
        T.eq("2025:06:01 12:00:00", m.get("ExifIFD:DateTimeOriginal"), "date");
        T.eq("Apple", m.get("XMP-tiff:Make"), "xmp make");
        T.eq("iPhone 17 Pro Max", m.get("XMP-tiff:Model"), "xmp model");
        T.eq("APPL", T.exif(out, "-n").get("ICC-header:DeviceManufacturer"), "icc mfr");
        T.eq("Apple", m.get("ICC_Profile:DeviceMfgDesc"), "dmnd");
        T.eq("iPhone 17 Pro Max", m.get("ICC_Profile:DeviceModelDesc"), "dmdd");
        T.eq("XMP, EXIF, ICC Profile", m.get("RIFF:WebP_Flags"), "flags unchanged");
        exiftoolMentionsNone(out, "HUAWEI", "ALN-AL00", "SN123456", "lcms generated");
        byte[] expectedIcc = IccProfileEditor.edit(chunkPayload(a, "ICCP"), T.appleSpec().icc, new EditReport());
        T.check(Arrays.equals(expectedIcc, chunkPayload(b, "ICCP")), "ICCP = ICC editor result");
        T.check(Arrays.equals(rawChunk(a, lossless ? "VP8L" : "VP8 "), rawChunk(b, lossless ? "VP8L" : "VP8 ")), "bitstream identical");
        T.check(hasChange(rep, "EXIF Make: HUAWEI → Apple"), "report: " + rep);
        T.check(hasChange(rep, "XMP tiff:Make: HUAWEI → Apple"), "report xmp: " + rep);
        T.check(hasChangeStarting(rep, "ICC üretici"), "report icc: " + rep);
        T.eq(0, rep.warnings.size(), "warnings: " + rep);
        sameDecoded(in, out, tag);
        webpinfoOk(out);
        assertNoNewWarnings(in, out);

        MediaInfo info = T.inspect(out);
        T.eq("Apple", info.make, "inspect make");
        T.eq("iPhone 17 Pro Max", info.model, "inspect model");
        T.eq(64, info.width, "w");
        T.eq(48, info.height, "h");
        T.check(info.hasExif && info.hasIcc && info.hasXmp, "flags");
        T.eq("Apple", info.iccManufacturerText, "dmnd");

        EditReport rep2 = new EditReport();
        T.check(Arrays.equals(b, run(WEBP, b, T.appleSpec(), rep2)), "idempotent: " + rep2);
        T.eq(0, rep2.changes.size(), "idempotent changes: " + rep2);
    }

    public static void testRemoveValues() throws Exception {
        File in = huaweiWebp("webp-remove.webp", "MM", true, true, false);
        File out = T.file("webp-remove-out.webp");
        EditReport rep = T.edit(in, out, removalSpec());
        Map<String, String> m = T.exif(out);
        T.eq(null, m.get("IFD0:Make"), "make");
        T.eq(null, m.get("IFD0:Model"), "model");
        T.eq(null, m.get("XMP-tiff:Make"), "xmp make");
        T.eq("2025:06:01 12:00:00", m.get("ExifIFD:DateTimeOriginal"), "date kept");
        T.eq("lcms", T.exif(out, "-n").get("ICC-header:DeviceManufacturer").trim(), "icc untouched");
        T.check(hasChange(rep, "EXIF Make kaldırıldı"), "report: " + rep);
        chunks(T.read(out), true);
        webpinfoOk(out);
        sameDecoded(in, out, "remove");
        assertNoNewWarnings(in, out);
    }

    // =====================================================================================
    // tests: EXIF creation
    // =====================================================================================

    public static void testCreateExifInSimpleLossy() throws Exception {
        File in = plainWebp("webp-simple.webp", "64x48", false, false);
        byte[] a = T.read(in);
        T.eq(Arrays.asList("VP8 "), fourccs(a), "simple fixture");
        File out = T.file("webp-simple-out.webp");
        EditReport rep = T.edit(in, out, T.appleSpec());
        byte[] b = T.read(out);
        T.eq(Arrays.asList("VP8X", "VP8 ", "EXIF"), fourccs(b), "layout");
        chunks(b, true);
        byte[] x = chunkPayload(b, "VP8X");
        T.eq(10, x.length, "VP8X size");
        T.eq(0x08, x[0] & 0xFF, "EXIF flag only");
        T.eq(63, (x[4] & 0xFF) | ((x[5] & 0xFF) << 8) | ((x[6] & 0xFF) << 16), "canvas w-1");
        T.eq(47, (x[7] & 0xFF) | ((x[8] & 0xFF) << 8) | ((x[9] & 0xFF) << 16), "canvas h-1");
        T.check(Arrays.equals(rawChunk(a, "VP8 "), rawChunk(b, "VP8 ")), "bitstream identical");
        Map<String, String> m = T.exif(out);
        T.eq("Apple", m.get("IFD0:Make"), "make");
        T.eq("iPhone 17 Pro Max", m.get("IFD0:Model"), "model");
        T.eq("64", m.get("ExifIFD:ExifImageWidth"), "pixel x");
        T.eq("48", m.get("ExifIFD:ExifImageHeight"), "pixel y");
        T.eq("EXIF", m.get("RIFF:WebP_Flags"), "flags");
        T.check(hasChange(rep, "EXIF bloğu oluşturuldu"), "report: " + rep);
        T.check(hasChange(rep, "WebP genişletilmiş başlık (VP8X) eklendi"), "report vp8x: " + rep);
        webpinfoOk(out);
        sameDecoded(in, out, "simple");
        assertNoNewWarnings(in, out);
        MediaInfo info = T.inspect(out);
        T.eq(64, info.width, "inspect w");
        T.eq("Apple", info.make, "inspect make");
        // idempotent
        EditReport rep2 = new EditReport();
        T.check(Arrays.equals(b, run(WEBP, b, T.appleSpec(), rep2)), "idempotent: " + rep2);
        // flags
        EditSpec off = T.appleSpec();
        off.createExifIfMissing = false;
        T.check(Arrays.equals(a, run(WEBP, a, off, null)), "createExifIfMissing=false");
        EditSpec lens = nullSpec();
        lens.createExifIfMissing = true;
        lens.lensModel = "x";
        T.check(Arrays.equals(a, run(WEBP, a, lens, null)), "no make/model -> nothing");
    }

    public static void testCreateExifInSimpleLosslessWithAlpha() throws Exception {
        File in = plainWebp("webp-alpha.webp", "33x17", true, true);
        byte[] a = T.read(in);
        T.eq(Arrays.asList("VP8L"), fourccs(a), "simple lossless fixture");
        File out = T.file("webp-alpha-out.webp");
        T.edit(in, out, T.appleSpec());
        byte[] b = T.read(out);
        T.eq(Arrays.asList("VP8X", "VP8L", "EXIF"), fourccs(b), "layout");
        byte[] x = chunkPayload(b, "VP8X");
        T.eq(0x08 | 0x10, x[0] & 0xFF, "EXIF + ALPHA flags");
        T.eq(32, (x[4] & 0xFF) | ((x[5] & 0xFF) << 8), "canvas w-1");
        T.eq(16, (x[7] & 0xFF) | ((x[8] & 0xFF) << 8), "canvas h-1");
        T.contains(T.run("webpinfo", out.getPath()), "Alpha: 1", "alpha flag");
        webpinfoOk(out);
        sameDecoded(in, out, "alpha");
        T.eq("33", T.exif(out).get("ExifIFD:ExifImageWidth"), "w");
        assertNoNewWarnings(in, out);
        MediaInfo i = inspect(WEBP, a);
        T.eq(33, i.width, "inspect VP8L w");
        T.eq(17, i.height, "inspect VP8L h");
    }

    public static void testCreateExifInExtendedFile() throws Exception {
        // extended with ICC + XMP but no EXIF: EXIF goes after the image, before XMP
        File in = plainWebp("webp-ext.webp", "40x30", false, false);
        T.run("exiftool", "-q", "-overwrite_original", "-icc_profile<=" + JpegEditorTest.SRGB,
                "-XMP-tiff:Make=HUAWEI", in.getPath());
        byte[] a = T.read(in);
        T.eq(Arrays.asList("VP8X", "ICCP", "VP8 ", "XMP "), fourccs(a), "fixture");
        File out = T.file("webp-ext-out.webp");
        EditReport rep = T.edit(in, out, T.appleSpec());
        byte[] b = T.read(out);
        T.eq(Arrays.asList("VP8X", "ICCP", "VP8 ", "EXIF", "XMP "), fourccs(b), "layout");
        chunks(b, true);
        T.eq(0x20 | 0x08 | 0x04, chunkPayload(b, "VP8X")[0] & 0xFF, "ICC + EXIF + XMP flags");
        byte[] va = chunkPayload(a, "VP8X");
        byte[] vb = chunkPayload(b, "VP8X");
        T.check(Arrays.equals(Arrays.copyOfRange(va, 1, 10), Arrays.copyOfRange(vb, 1, 10)), "canvas unchanged");
        Map<String, String> m = T.exif(out);
        T.eq("Apple", m.get("IFD0:Make"), "make");
        T.eq("Apple", m.get("XMP-tiff:Make"), "xmp make");
        T.eq("40", m.get("ExifIFD:ExifImageWidth"), "w from canvas");
        T.check(!hasChange(rep, "WebP genişletilmiş başlık (VP8X) eklendi"), "no VP8X added: " + rep);
        webpinfoOk(out);
        sameDecoded(in, out, "ext");
        assertNoNewWarnings(in, out);
    }

    public static void testAnimatedWebp() throws Exception {
        File in = T.file("webp-anim.webp");
        T.run("ffmpeg", "-y", "-v", "error", "-f", "lavfi", "-i", "testsrc=size=32x32:rate=5", "-t", "1",
                "-c:v", "libwebp_anim", "-loop", "0", in.getPath());
        byte[] a = T.read(in);
        List<String> fa = fourccs(a);
        T.eq("ANIM", fa.get(1), "animated fixture");
        File out = T.file("webp-anim-out.webp");
        T.edit(in, out, T.appleSpec());
        byte[] b = T.read(out);
        List<String> fb = fourccs(b);
        List<String> expected = new ArrayList<String>(fa);
        expected.add("EXIF");
        T.eq(expected, fb, "EXIF after the last frame");
        chunks(b, true);
        T.eq(0x02 | 0x08, chunkPayload(b, "VP8X")[0] & 0x0E, "ANIM + EXIF flags");
        webpinfoOk(out);
        T.eq("Apple", T.exif(out).get("IFD0:Make"), "make");
        T.eq("32", T.exif(out).get("ExifIFD:ExifImageWidth"), "w");
        // all frames decode identically
        File da = new File(T.workDir(), "webp-anim-a");
        File db = new File(T.workDir(), "webp-anim-b");
        da.mkdirs();
        db.mkdirs();
        T.run("anim_dump", "-folder", da.getPath(), "-prefix", "f", "-pam", in.getPath());
        T.run("anim_dump", "-folder", db.getPath(), "-prefix", "f", "-pam", out.getPath());
        String[] names = da.list();
        T.check(names != null && names.length >= 2, "frames dumped");
        for (String n : names) {
            T.check(Arrays.equals(T.read(new File(da, n)), T.read(new File(db, n))), "frame " + n);
        }
        T.check(Arrays.equals(Arrays.copyOfRange(a, 30, a.length), Arrays.copyOfRange(b, 30, 30 + a.length - 30)),
                "ANIM/ANMF chunks byte-identical");
    }

    // =====================================================================================
    // tests: unusual structure
    // =====================================================================================

    public static void testExifPrefixOddPaddingUnknownChunks() throws Exception {
        byte[] a = T.read(huaweiWebp("webp-hand.webp", "II", false, true, false));
        byte[] tiff = chunkPayload(a, "EXIF");
        byte[] xmp = chunkPayload(a, "XMP ");
        // odd-sized XMP (one extra space before the trailer is fine for XML)
        byte[] xmpOdd = (xmp.length & 1) == 0 ? concat(xmp, ascii(" ")) : xmp;
        byte[] unknown = chunk("ZZZZ", ascii("hello"));
        byte[] in = riff(rawChunk(a, "VP8X"), rawChunk(a, "VP8 "), chunk("EXIF", concat(ascii("Exif\0\0"), tiff)),
                chunk("XMP ", xmpOdd), unknown);
        chunks(in, true);
        File fin = write("webp-hand-in.webp", in);
        T.eq("HUAWEI", T.inspect(fin).make, "inspect with Exif prefix");
        EditReport rep = new EditReport();
        byte[] out = run(WEBP, in, T.appleSpec(), rep);
        chunks(out, true);
        byte[] ex = chunkPayload(out, "EXIF");
        T.check(indexOf(ex, ascii("Exif\0\0II")) == 0, "Exif prefix kept");
        T.check(Arrays.equals(unknown, Arrays.copyOfRange(out, out.length - unknown.length, out.length)), "unknown chunk + pad kept");
        File fout = write("webp-hand-out.webp", out);
        Map<String, String> m = T.exif(fout);
        T.eq("Apple", m.get("IFD0:Make"), "make");
        T.eq("Apple", m.get("XMP-tiff:Make"), "xmp");
        T.eq("iPhone 17 Pro Max", m.get("XMP-tiff:Model"), "xmp model");
        sameDecoded(fin, fout, "hand");
        assertNoNewWarnings(fin, fout);
        T.check(Arrays.equals(in, run(WEBP, in, nullSpec(), null)), "noop identical");
    }

    public static void testTrailingDataAfterRiffKept() throws Exception {
        byte[] a = T.read(huaweiWebp("webp-trail.webp", "MM", true, false, false));
        byte[] trailer = new byte[1001];
        new Random(3).nextBytes(trailer);
        byte[] in = concat(a, trailer);
        EditReport rep = new EditReport();
        byte[] out = run(WEBP, in, T.appleSpec(), rep);
        T.check(Arrays.equals(trailer, Arrays.copyOfRange(out, out.length - trailer.length, out.length)), "trailer kept");
        byte[] body = Arrays.copyOf(out, out.length - trailer.length);
        chunks(body, true);
        T.eq((long) body.length - 8, le32(out, 4), "RIFF size covers only the RIFF payload");
        T.check(Arrays.equals(in, run(WEBP, in, nullSpec(), null)), "noop identical with trailer");
    }

    public static void testUnchangedSpecIsByteIdentical() throws Exception {
        List<byte[]> inputs = new ArrayList<byte[]>();
        inputs.add(T.read(huaweiWebp("webp-noop1.webp", "MM", true, true, false)));
        inputs.add(T.read(huaweiWebp("webp-noop2.webp", "II", false, false, true)));
        inputs.add(T.read(plainWebp("webp-noop3.webp", "20x10", false, false)));
        inputs.add(T.read(plainWebp("webp-noop4.webp", "21x11", true, true)));
        for (int k = 0; k < inputs.size(); k++) {
            byte[] in = inputs.get(k);
            EditReport rep = new EditReport();
            T.check(Arrays.equals(in, run(WEBP, in, nullSpec(), rep)), "null spec identical #" + k);
            T.eq(0, rep.changes.size(), "no changes #" + k);
            EditSpec same = nullSpec();
            MediaInfo i = inspect(WEBP, in);
            same.make = i.make;
            same.model = i.model;
            same.editXmp = true;
            same.editIcc = true;
            same.icc = new IccIdentity();
            EditReport rep2 = new EditReport();
            T.check(Arrays.equals(in, run(WEBP, in, same, rep2)), "same values identical #" + k + ": " + rep2);
        }
    }

    public static void testBrokenMetadataIsWarnedNotFatal() throws Exception {
        byte[] a = T.read(huaweiWebp("webp-broken.webp", "MM", true, true, false));
        // EXIF chunk with garbage, ICC without 'acsp'
        byte[] badExif = chunk("EXIF", ascii("garbage-not-tiff"));
        byte[] icc = chunkPayload(a, "ICCP").clone();
        icc[36] = 'x';
        byte[] in = riff(rawChunk(a, "VP8X"), chunk("ICCP", icc), rawChunk(a, "VP8 "), badExif, rawChunk(a, "XMP "));
        EditReport rep = new EditReport();
        byte[] out = run(WEBP, in, T.appleSpec(), rep);
        T.check(hasWarning(rep, "EXIF"), "exif warning: " + rep);
        T.check(!rep.warnings.isEmpty(), "warnings");
        T.check(Arrays.equals(badExif, rawChunk(out, "EXIF")), "bad EXIF untouched");
        T.check(hasChange(rep, "XMP tiff:Make: HUAWEI → Apple"), "XMP still edited: " + rep);
        chunks(out, true);
    }

    public static void testCorruptAndTruncatedInputs() throws Exception {
        byte[] a = T.read(huaweiWebp("webp-corrupt.webp", "MM", true, true, false));
        for (int cut = 0; cut < a.length; cut += (cut < 300 ? 1 : 37)) {
            robust(WEBP, Arrays.copyOf(a, cut), "cut " + cut);
        }
        // RIFF size smaller than the header / larger than the file
        byte[] s1 = a.clone();
        System.arraycopy(le32b(2), 0, s1, 4, 4);
        robust(WEBP, s1, "riff 2");
        byte[] s2 = a.clone();
        System.arraycopy(le32b(0xFFFFFFF0L), 0, s2, 4, 4);
        robust(WEBP, s2, "riff huge");
        // chunk size beyond the end
        byte[] s3 = a.clone();
        System.arraycopy(le32b(0x7FFFFFF0L), 0, s3, 16, 4);
        robust(WEBP, s3, "chunk huge");
        // short VP8X + create EXIF
        byte[] simple = T.read(plainWebp("webp-corrupt-simple.webp", "16x16", false, false));
        robust(WEBP, riff(chunk("VP8X", new byte[3]), rawChunk(simple, "VP8 ")), "short vp8x");
        robust(WEBP, riff(chunk("VP8 ", new byte[4])), "short vp8");
        robust(WEBP, riff(chunk("VP8L", new byte[]{0x2F})), "short vp8l");
        robust(WEBP, riff(chunk("ICCP", new byte[0])), "icc first");
        robust(WEBP, riff(), "no chunks");
        try {
            run(WEBP, riff(chunk("JUNK", new byte[4])), T.appleSpec(), null);
            throw new AssertionError("unknown first chunk must be rejected");
        } catch (UnsupportedFormatException e) {
            T.contains(e.getMessage(), "WebP", "message");
        }
        // simple file whose VP8 header is broken: EXIF cannot be created, warning
        byte[] vp8 = chunkPayload(simple, "VP8 ").clone();
        vp8[3] = 0;
        EditReport rep = new EditReport();
        byte[] out = run(WEBP, riff(chunk("VP8 ", vp8)), T.appleSpec(), rep);
        T.check(hasWarning(rep, "boyutu okunamadı"), "size warning: " + rep);
        T.eq(1, fourccs(out).size(), "nothing added");
        JpegEditorTest.fuzz(WEBP, a, a.length, 4242, 400, "webp fuzz");
        JpegEditorTest.fuzz(WEBP, simple, simple.length, 99, 200, "webp simple fuzz");
    }

    public static void testInspectAndProgress() throws Exception {
        File in = huaweiWebp("webp-inspect.webp", "II", true, true, true);
        MediaInfo i = T.inspect(in);
        T.eq(MediaFormat.WEBP, i.format, "format");
        T.eq("HUAWEI", i.make, "make");
        T.eq("ALN-AL00", i.model, "model");
        T.eq("ALN-AL00 4.0.0.120(C00E120R1P1)", i.software, "software");
        T.eq("2025:06:01 12:00:00", i.dateTimeOriginal, "date");
        T.check(i.hasExif && i.hasIcc && i.hasXmp, "flags");
        T.eq("lcms", i.iccManufacturer, "icc");
        T.eq(64, i.width, "w");
        T.eq(48, i.height, "h");
        MediaInfo p = inspect(WEBP, T.read(plainWebp("webp-inspect-plain.webp", "50x20", false, false)));
        T.eq(50, p.width, "plain w");
        T.eq(20, p.height, "plain h");
        T.check(!p.hasExif && !p.hasIcc && !p.hasXmp, "plain flags");

        JpegEditorTest.Prog prog = new JpegEditorTest.Prog();
        File out = T.file("webp-inspect-out.webp");
        FileChannelSource src = FileChannelSource.open(in);
        try (OutputStream os = new java.io.FileOutputStream(out)) {
            WebpEditor.edit(src, os, T.appleSpec(), new EditReport(), prog);
        } finally {
            src.close();
        }
        prog.check(out.length(), "progress");
    }
}
