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
import com.metakamera.core.png.PngEditor;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

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
import static com.metakamera.test.JpegEditorTest.samePixels;
import static com.metakamera.test.JpegEditorTest.write;

/** Tests for {@link PngEditor}. */
public final class PngEditorTest {

    static final Ed PNG = new Ed() {
        @Override
        public void edit(ByteSource s, OutputStream o, EditSpec spec, EditReport r, ProgressListener l) throws IOException {
            PngEditor.edit(s, o, spec, r, l);
        }

        @Override
        public void inspect(ByteSource s, MediaInfo i) throws IOException {
            PngEditor.inspect(s, i);
        }
    };

    static final byte[] SIG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};

    // =====================================================================================
    // helpers
    // =====================================================================================

    static File plainPng(String name, String size) throws IOException {
        File f = T.file(name);
        T.run("convert", "-size", size, "gradient:red-blue", f.getPath());
        return f;
    }

    static File huaweiPng(String name, String order, boolean icc, boolean xmp) throws IOException {
        File f = plainPng(name, "64x48");
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

    /** {type, offset, length} of every chunk up to IEND; asserts valid CRCs. */
    static List<Object[]> pngChunks(byte[] p) {
        T.check(indexOf(p, SIG) == 0, "PNG signature");
        List<Object[]> l = new ArrayList<Object[]>();
        int pos = 8;
        while (pos + 12 <= p.length) {
            int len = (int) JpegEditorTest.u32(p, pos);
            String t = new String(p, pos + 4, 4, StandardCharsets.ISO_8859_1);
            CRC32 c = new CRC32();
            c.update(p, pos + 4, 4 + len);
            T.eq(c.getValue(), JpegEditorTest.u32(p, pos + 8 + len), "CRC of " + t + " at " + pos);
            l.add(new Object[]{t, pos, len});
            pos += 12 + len;
            if (t.equals("IEND")) break;
        }
        return l;
    }

    static List<String> types(byte[] p) {
        List<String> r = new ArrayList<String>();
        for (Object[] c : pngChunks(p)) r.add((String) c[0]);
        return r;
    }

    static byte[] data(byte[] p, String type) {
        for (Object[] c : pngChunks(p)) {
            if (c[0].equals(type)) return Arrays.copyOfRange(p, (Integer) c[1] + 8, (Integer) c[1] + 8 + (Integer) c[2]);
        }
        return null;
    }

    static byte[] rawChunk(byte[] p, String type) {
        for (Object[] c : pngChunks(p)) {
            if (c[0].equals(type)) return Arrays.copyOfRange(p, (Integer) c[1], (Integer) c[1] + 12 + (Integer) c[2]);
        }
        return null;
    }

    /** All chunks of a type, raw. */
    static List<byte[]> rawChunks(byte[] p, String type) {
        List<byte[]> r = new ArrayList<byte[]>();
        for (Object[] c : pngChunks(p)) {
            if (c[0].equals(type)) r.add(Arrays.copyOfRange(p, (Integer) c[1], (Integer) c[1] + 12 + (Integer) c[2]));
        }
        return r;
    }

    static byte[] chunk(String type, byte[] d) {
        byte[] b = new byte[12 + d.length];
        JpegEditorTest.put32(b, 0, d.length);
        System.arraycopy(ascii(type), 0, b, 4, 4);
        System.arraycopy(d, 0, b, 8, d.length);
        CRC32 c = new CRC32();
        c.update(b, 4, 4 + d.length);
        JpegEditorTest.put32(b, 8 + d.length, c.getValue());
        return b;
    }

    /** Inserts chunks right before the first IDAT. */
    static byte[] beforeIdat(byte[] p, byte[]... chunks) {
        for (Object[] c : pngChunks(p)) {
            if (c[0].equals("IDAT")) return JpegEditorTest.insert(p, (Integer) c[1], concat(chunks));
        }
        throw new AssertionError("no IDAT");
    }

    static byte[] zlib(byte[] d) {
        Deflater df = new Deflater();
        df.setInput(d);
        df.finish();
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        while (!df.finished()) bo.write(buf, 0, df.deflate(buf));
        df.end();
        return bo.toByteArray();
    }

    static byte[] unzlib(byte[] d, int off) throws Exception {
        Inflater inf = new Inflater();
        inf.setInput(d, off, d.length - off);
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        while (!inf.finished()) {
            int n = inf.inflate(buf);
            if (n == 0 && inf.needsInput()) throw new AssertionError("truncated zlib");
            bo.write(buf, 0, n);
        }
        inf.end();
        return bo.toByteArray();
    }

    /** ImageMagick "Raw profile type" text, written independently of the editor's encoder. */
    static String rawProfileText(String name, byte[] d) {
        StringBuilder sb = new StringBuilder("\n" + name + "\n" + String.format("%8d", d.length));
        for (int i = 0; i < d.length; i++) {
            if (i % 36 == 0) sb.append('\n');
            sb.append(String.format("%02x", d[i] & 0xFF));
        }
        return sb.append('\n').toString();
    }

    /** "Exif\0\0" + TIFF of the EXIF block of a Huawei JPEG. */
    static byte[] huaweiExifApp1Payload(String order) throws IOException {
        byte[] j = T.read(JpegEditorTest.huaweiJpeg("png-exifsrc-" + order + ".jpg", order, false, false));
        return JpegEditorTest.app(j, 0xE1, "Exif").get(0);
    }

    // =====================================================================================
    // tests: identity change
    // =====================================================================================

    public static void testAppleSpecBigEndian() throws Exception {
        appleOn("MM");
    }

    public static void testAppleSpecLittleEndian() throws Exception {
        appleOn("II");
    }

    private static void appleOn(String order) throws Exception {
        File in = huaweiPng("png-apple-" + order + ".png", order, true, true);
        byte[] a = T.read(in);
        File out = T.file("png-apple-" + order + "-out.png");
        EditReport rep = T.edit(in, out, T.appleSpec());
        T.eq(MediaFormat.PNG, rep.format, "format");
        byte[] b = T.read(out);
        T.eq(types(a), types(b), "chunk order kept");
        Map<String, String> m = T.exif(out);
        T.eq("Apple", m.get("IFD0:Make"), "make");
        T.eq("iPhone 17 Pro Max", m.get("IFD0:Model"), "model");
        T.eq("26.0.1", m.get("IFD0:Software"), "software");
        T.eq("Apple", m.get("ExifIFD:LensMake"), "lens make");
        T.eq(null, m.get("ExifIFD:SerialNumber"), "serial");
        T.eq("2025:06:01 12:00:00", m.get("ExifIFD:DateTimeOriginal"), "date");
        T.eq(order.equals("MM") ? "Big-endian (Motorola, MM)" : "Little-endian (Intel, II)", m.get("File:ExifByteOrder"), "order");
        T.eq("Apple", m.get("XMP-tiff:Make"), "xmp make");
        T.eq("iPhone 17 Pro Max", m.get("XMP-tiff:Model"), "xmp model");
        T.eq("APPL", T.exif(out, "-n").get("ICC-header:DeviceManufacturer"), "icc");
        T.eq("Apple", m.get("ICC_Profile:DeviceMfgDesc"), "dmnd");
        T.eq("iPhone 17 Pro Max", m.get("ICC_Profile:DeviceModelDesc"), "dmdd");
        exiftoolMentionsNone(out, "HUAWEI", "ALN-AL00", "SN123456", "lcms generated");
        // iCCP: name and method kept, profile = ICC editor result
        byte[] ia = data(a, "iCCP");
        byte[] ib = data(b, "iCCP");
        int k = JpegEditorTest.indexOf(ia, new byte[]{0});
        T.check(Arrays.equals(Arrays.copyOf(ia, k + 2), Arrays.copyOf(ib, k + 2)), "iCCP name + method kept");
        byte[] expected = IccProfileEditor.edit(unzlib(ia, k + 2), T.appleSpec().icc, new EditReport());
        T.check(Arrays.equals(expected, unzlib(ib, k + 2)), "iCCP profile = ICC editor result");
        T.check(Arrays.equals(rawChunk(a, "IDAT"), rawChunk(b, "IDAT")), "IDAT identical");
        T.check(hasChange(rep, "EXIF Make: HUAWEI → Apple"), "report: " + rep);
        T.check(hasChange(rep, "XMP tiff:Make: HUAWEI → Apple"), "report xmp: " + rep);
        T.check(hasChangeStarting(rep, "ICC üretici"), "report icc: " + rep);
        T.eq(0, rep.warnings.size(), "warnings: " + rep);
        samePixels(in, out, "png " + order);
        assertNoNewWarnings(in, out);

        MediaInfo info = T.inspect(out);
        T.eq("Apple", info.make, "inspect make");
        T.eq("iPhone 17 Pro Max", info.model, "inspect model");
        T.eq(64, info.width, "w");
        T.eq(48, info.height, "h");
        T.check(info.hasExif && info.hasIcc && info.hasXmp, "flags");
        T.eq("APPL", info.iccManufacturer, "icc mfr");

        EditReport rep2 = new EditReport();
        T.check(Arrays.equals(b, run(PNG, b, T.appleSpec(), rep2)), "idempotent: " + rep2);
        T.eq(0, rep2.changes.size(), "idempotent changes: " + rep2);
    }

    public static void testRemoveValues() throws Exception {
        File in = huaweiPng("png-remove.png", "II", true, true);
        File out = T.file("png-remove-out.png");
        EditReport rep = T.edit(in, out, removalSpec());
        Map<String, String> m = T.exif(out);
        T.eq(null, m.get("IFD0:Make"), "make");
        T.eq(null, m.get("IFD0:Model"), "model");
        T.eq(null, m.get("XMP-tiff:Model"), "xmp model");
        T.eq("2025:06:01 12:00:00", m.get("ExifIFD:DateTimeOriginal"), "date kept");
        T.check(hasChange(rep, "EXIF Make kaldırıldı"), "report: " + rep);
        pngChunks(T.read(out));
        samePixels(in, out, "remove");
        assertNoNewWarnings(in, out);
    }

    // =====================================================================================
    // tests: EXIF creation
    // =====================================================================================

    public static void testCreateExifBeforeIdat() throws Exception {
        File in = plainPng("png-create.png", "37x21");
        byte[] a = T.read(in);
        T.check(data(a, "eXIf") == null, "fixture has no EXIF");
        File out = T.file("png-create-out.png");
        EditReport rep = T.edit(in, out, T.appleSpec());
        byte[] b = T.read(out);
        List<String> ta = types(a);
        List<String> tb = types(b);
        int idat = ta.indexOf("IDAT");
        List<String> expected = new ArrayList<String>(ta);
        expected.add(idat, "eXIf");
        T.eq(expected, tb, "eXIf right before the first IDAT");
        Map<String, String> m = T.exif(out);
        T.eq("Apple", m.get("IFD0:Make"), "make");
        T.eq("iPhone 17 Pro Max", m.get("IFD0:Model"), "model");
        T.eq("37", m.get("ExifIFD:ExifImageWidth"), "w from IHDR");
        T.eq("21", m.get("ExifIFD:ExifImageHeight"), "h from IHDR");
        T.check(hasChange(rep, "EXIF bloğu oluşturuldu"), "report: " + rep);
        samePixels(in, out, "create");
        assertNoNewWarnings(in, out);
        EditReport rep2 = new EditReport();
        T.check(Arrays.equals(b, run(PNG, b, T.appleSpec(), rep2)), "idempotent: " + rep2);

        EditSpec off = T.appleSpec();
        off.createExifIfMissing = false;
        T.check(Arrays.equals(a, run(PNG, a, off, null)), "createExifIfMissing=false");
        EditSpec soft = nullSpec();
        soft.createExifIfMissing = true;
        soft.software = "x";
        T.check(Arrays.equals(a, run(PNG, a, soft, null)), "no make/model -> no EXIF");
    }

    public static void testApngCreateExif() throws Exception {
        File in = T.file("png-apng.png");
        T.run("ffmpeg", "-y", "-v", "error", "-f", "lavfi", "-i", "testsrc=size=32x24:rate=5", "-t", "1",
                "-plays", "0", "-f", "apng", in.getPath());
        byte[] a = T.read(in);
        List<String> ta = types(a);
        T.check(ta.contains("acTL") && ta.contains("fdAT"), "APNG fixture: " + ta);
        File out = T.file("png-apng-out.png");
        T.edit(in, out, T.appleSpec());
        byte[] b = T.read(out);
        List<String> tb = types(b);
        int fctl = ta.indexOf("fcTL");
        int idat = ta.indexOf("IDAT");
        List<String> expected = new ArrayList<String>(ta);
        expected.add(Math.min(fctl, idat), "eXIf");
        T.eq(expected, tb, "eXIf before the first fcTL/IDAT");
        T.eq("Apple", T.exif(out).get("IFD0:Make"), "make");
        String fa = T.run("ffmpeg", "-v", "error", "-i", in.getPath(), "-f", "framemd5", "-");
        String fb = T.run("ffmpeg", "-v", "error", "-i", out.getPath(), "-f", "framemd5", "-");
        T.eq(fa, fb, "all frames decode identically");
        T.check(fa.split("\n").length > 5, "several frames");
    }

    // =====================================================================================
    // tests: XMP / text chunks / ImageMagick profiles
    // =====================================================================================

    public static void testCompressedAndInternationalXmp() throws Exception {
        byte[] base = T.read(plainPng("png-itxt.png", "16x16"));
        String xmp = JpegEditorTest.xmpPacket("tiff:Make=\"HUAWEI\" tiff:Model=\"ALN-AL00\"", "<dc:title>Çiçek ğüşıöç</dc:title>");
        byte[] xb = xmp.getBytes(StandardCharsets.UTF_8);
        // compressed iTXt with a translated keyword (a language tag would make exiftool read it as
        // a localized text instead of XMP)
        byte[] itxt = concat(ascii("XML:com.adobe.xmp\0"), new byte[]{1, 0}, ascii("\0"),
                "Başlık".getBytes(StandardCharsets.UTF_8), new byte[]{0}, zlib(xb));
        byte[] in = beforeIdat(base, chunk("iTXt", itxt));
        File fin = write("png-itxt-in.png", in);
        T.eq("HUAWEI", T.exif(fin).get("XMP-tiff:Make"), "fixture readable");
        EditReport rep = new EditReport();
        byte[] out = run(PNG, in, T.appleSpec(), rep);
        File fout = write("png-itxt-out.png", out);
        Map<String, String> m = T.exif(fout);
        T.eq("Apple", m.get("XMP-tiff:Make"), "xmp make");
        T.eq("iPhone 17 Pro Max", m.get("XMP-tiff:Model"), "xmp model");
        T.eq("Çiçek ğüşıöç", m.get("XMP-dc:Title"), "unicode kept");
        byte[] d = data(out, "iTXt");
        int prefix = ("XML:com.adobe.xmp\0").length() + 2 + 1 + "Başlık".getBytes(StandardCharsets.UTF_8).length + 1;
        T.check(Arrays.equals(Arrays.copyOf(itxt, prefix), Arrays.copyOf(d, prefix)), "header fields kept, still compressed");
        String text = new String(unzlib(d, prefix), StandardCharsets.UTF_8);
        T.contains(text, "tiff:Make=\"Apple\"", "deflated text");
        T.check(hasChange(rep, "XMP tiff:Model: ALN-AL00 → iPhone 17 Pro Max"), "report: " + rep);
        samePixels(fin, fout, "itxt");
        assertNoNewWarnings(fin, fout);

        // corrupt zlib stream -> warning, untouched
        byte[] bad = itxt.clone();
        bad[bad.length - 5] ^= 0x55;
        bad[bad.length - 9] ^= 0x55;
        byte[] in2 = beforeIdat(base, chunk("iTXt", bad));
        EditReport rep2 = new EditReport();
        byte[] out2 = run(PNG, in2, T.appleSpec(), rep2);
        T.check(Arrays.equals(chunk("iTXt", bad), rawChunk(out2, "iTXt")), "corrupt iTXt untouched");
        T.check(hasWarning(rep2, "iTXt"), "warning: " + rep2);
    }

    public static void testImageMagickConvertedPng() throws Exception {
        File jpg = JpegEditorTest.huaweiJpeg("png-im-src.jpg", "MM", true, true);
        File in = T.file("png-im.png");
        T.run("convert", jpg.getPath(), in.getPath());
        byte[] a = T.read(in);
        List<String> ta = types(a);
        T.check(ta.contains("zTXt") && ta.contains("tEXt") && ta.contains("eXIf"), "IM fixture: " + ta);
        String before = T.run("exiftool", "-a", "-G1", "-s", in.getPath());
        T.contains(before, "ExifMake", "IM text chunk present");
        File out = T.file("png-im-out.png");
        EditReport rep = T.edit(in, out, T.appleSpec());
        exiftoolMentionsNone(out, "HUAWEI", "ALN-AL00", "SN123456", "ABCDEF0123456789", "lcms generated");
        Map<String, String> m = T.exif(out);
        T.eq("Apple", m.get("IFD0:Make"), "eXIf make");
        T.eq("Apple", m.get("PNG:ExifMake"), "exif:Make text");
        T.eq("iPhone 17 Pro Max", m.get("PNG:TiffModel"), "tiff:Model text");
        T.eq("iPhone 17 Pro Max back triple camera 6.765mm f/1.78", m.get("PNG:ExifLensModel"), "exif:LensModel text");
        T.eq(null, m.get("PNG:ExifBodySerialNumber"), "serial text removed");
        T.eq(null, m.get("PNG:ExifImageUniqueID"), "uid text removed");
        T.eq("Apple", m.get("PNG:Iccmanufacturer"), "icc:manufacturer text");
        T.eq("Apple", m.get("XMP-tiff:Make"), "raw xmp profile edited");
        T.eq("2025:06:01 12:00:00", m.get("PNG:ExifDateTimeOriginal"), "unrelated text kept");
        T.check(hasChange(rep, "PNG metni exif:Make: HUAWEI → Apple"), "report: " + rep);
        T.check(hasChange(rep, "PNG metni exif:BodySerialNumber kaldırıldı"), "report serial: " + rep);
        pngChunks(T.read(out));
        samePixels(in, out, "im");
        assertNoNewWarnings(in, out);
        EditReport rep2 = new EditReport();
        byte[] b = T.read(out);
        T.check(Arrays.equals(b, run(PNG, b, T.appleSpec(), rep2)), "idempotent: " + rep2);
        MediaInfo i = T.inspect(in);
        T.eq("HUAWEI", i.make, "inspect");
        T.check(i.hasXmp && i.hasIcc && i.hasExif, "inspect flags");
    }

    public static void testRawProfileExifOnly() throws Exception {
        byte[] base = T.read(plainPng("png-raw.png", "24x24"));
        for (String order : new String[]{"MM", "II"}) {
            byte[] app1 = huaweiExifApp1Payload(order);
            byte[] z = concat(ascii("Raw profile type exif\0"), new byte[]{0},
                    zlib(rawProfileText("exif", app1).getBytes(StandardCharsets.ISO_8859_1)));
            byte[] in = beforeIdat(base, chunk("zTXt", z));
            File fin = write("png-raw-" + order + ".png", in);
            T.eq("HUAWEI", T.exif(fin).get("IFD0:Make"), "exiftool reads the raw profile");
            T.eq("HUAWEI", T.inspect(fin).make, "inspect raw profile");
            EditReport rep = new EditReport();
            byte[] out = run(PNG, in, T.appleSpec(), rep);
            File fout = write("png-raw-" + order + "-out.png", out);
            T.eq("Apple", T.exif(fout).get("IFD0:Make"), "raw profile edited");
            T.eq("iPhone 17 Pro Max", T.exif(fout).get("IFD0:Model"), "model");
            T.check(data(out, "eXIf") == null, "no eXIf created when the raw profile holds EXIF: " + types(out));
            T.check(!hasChange(rep, "EXIF bloğu oluşturuldu"), "report: " + rep);
            exiftoolMentionsNone(fout, "HUAWEI", "ALN-AL00");
            samePixels(fin, fout, "raw");
            assertNoNewWarnings(fin, fout);
            // tEXt variant with the "APP1" name
            byte[] t = concat(ascii("Raw profile type APP1\0"), rawProfileText("APP1", app1).getBytes(StandardCharsets.ISO_8859_1));
            byte[] in2 = beforeIdat(base, chunk("tEXt", t));
            byte[] out2 = run(PNG, in2, T.appleSpec(), null);
            File f2 = write("png-raw2-" + order + "-out.png", out2);
            T.eq("Apple", T.exif(f2).get("IFD0:Make"), "APP1 raw profile edited");
            T.check(data(out2, "eXIf") == null, "no eXIf created (APP1)");
        }
        // malformed hex: warning, untouched
        byte[] bad = concat(ascii("Raw profile type exif\0"), ascii("\nexif\n     100\nzz00\n"));
        byte[] in3 = beforeIdat(base, chunk("tEXt", bad));
        EditReport rep3 = new EditReport();
        byte[] out3 = run(PNG, in3, T.appleSpec(), rep3);
        T.check(hasWarning(rep3, "Raw profile type exif"), "warning: " + rep3);
        T.check(Arrays.equals(chunk("tEXt", bad), rawChunk(out3, "tEXt")), "bad raw profile untouched");
    }

    public static void testIdentityTextChunksSemantics() throws Exception {
        byte[] base = T.read(plainPng("png-text.png", "16x16"));
        byte[] in = beforeIdat(base,
                chunk("tEXt", ascii("Make\0HUAWEI")),
                chunk("iTXt", concat(ascii("Model\0\0\0\0\0"), "ALN-AL00 ü".getBytes(StandardCharsets.UTF_8))),
                chunk("zTXt", concat(ascii("exif:Software\0\0"), zlib(ascii("ALN-AL00 4.0")))),
                chunk("tEXt", ascii("Comment\0HUAWEI keeps this")),
                chunk("tEXt", ascii("Software\0GIMP")));
        EditSpec s = nullSpec();
        s.make = "";
        s.model = "Galaxy S25 Ultra ü";
        s.software = "S938BXXU1AYA1";
        s.lensModel = "Created?";
        EditReport rep = new EditReport();
        byte[] out = run(PNG, in, s, rep);
        File fo = write("png-text-out.png", out);
        Map<String, String> m = T.exif(fo);
        T.eq(null, m.get("PNG:Make"), "Make removed");
        T.eq("Galaxy S25 Ultra ü", m.get("PNG:Model"), "Model (UTF-8 iTXt)");
        T.eq("S938BXXU1AYA1", m.get("PNG:ExifSoftware"), "zTXt exif:Software");
        T.eq("HUAWEI keeps this", m.get("PNG:Comment"), "unrelated text untouched");
        T.eq("GIMP", m.get("PNG:Software"), "PNG Software keyword untouched");
        T.check(!T.run("exiftool", "-a", "-G1", "-s", fo.getPath()).contains("Created?"), "nothing created");
        T.check(hasChange(rep, "PNG metni Make kaldırıldı"), "report: " + rep);
        T.check(hasChange(rep, "PNG metni Model: ALN-AL00 ü → Galaxy S25 Ultra ü"), "report model: " + rep);
        T.eq(3, rep.changes.size(), "changes: " + rep);
        byte[] z = null;
        for (byte[] c : rawChunks(out, "zTXt")) z = c;
        T.check(z != null && z[8 + "exif:Software".length() + 1] == 0, "zTXt still compressed");
        // non-Latin-1 in tEXt becomes '?'
        EditSpec s2 = nullSpec();
        s2.make = "Xiaomi 小米";
        File f2 = write("png-text-out2.png", run(PNG, in, s2, null));
        T.eq("Xiaomi ??", T.exif(f2).get("PNG:Make"), "latin-1 fallback");
    }

    // =====================================================================================
    // tests: byte identity, trailers, CRC problems
    // =====================================================================================

    public static void testUnchangedSpecIsByteIdentical() throws Exception {
        List<byte[]> inputs = new ArrayList<byte[]>();
        inputs.add(T.read(huaweiPng("png-noop1.png", "MM", true, true)));
        inputs.add(T.read(plainPng("png-noop2.png", "8x8")));
        File jpg = JpegEditorTest.huaweiJpeg("png-noop-src.jpg", "II", true, true);
        File im = T.file("png-noop3.png");
        T.run("convert", jpg.getPath(), im.getPath());
        inputs.add(T.read(im));
        byte[] trailer = new byte[777];
        new Random(9).nextBytes(trailer);
        inputs.add(concat(inputs.get(0), trailer));
        for (int k = 0; k < inputs.size(); k++) {
            byte[] in = inputs.get(k);
            EditReport rep = new EditReport();
            T.check(Arrays.equals(in, run(PNG, in, nullSpec(), rep)), "null spec identical #" + k);
            T.eq(0, rep.changes.size(), "no changes #" + k);
            EditSpec same = nullSpec();
            MediaInfo i = inspect(PNG, in);
            same.make = i.make;
            same.editXmp = true;
            same.editIcc = true;
            same.icc = new IccIdentity();
            EditReport rep2 = new EditReport();
            T.check(Arrays.equals(in, run(PNG, in, same, rep2)), "same values identical #" + k + ": " + rep2);
        }
    }

    public static void testTrailingDataAfterIendKept() throws Exception {
        byte[] a = T.read(huaweiPng("png-trail.png", "MM", false, false));
        byte[] trailer = ascii("TRAILER-DATA-AFTER-IEND\0\1\2\3");
        byte[] in = concat(a, trailer);
        EditReport rep = new EditReport();
        byte[] out = run(PNG, in, T.appleSpec(), rep);
        T.check(Arrays.equals(trailer, Arrays.copyOfRange(out, out.length - trailer.length, out.length)), "trailer kept");
        T.check(hasChange(rep, "EXIF Make: HUAWEI → Apple"), "edited");
        T.eq(0, rep.warnings.size(), "no warnings: " + rep);
    }

    public static void testCrcMismatchLeftUntouched() throws Exception {
        byte[] a = T.read(huaweiPng("png-crc.png", "MM", true, true));
        byte[] in = a.clone();
        int e = indexOf(in, ascii("eXIf"));
        int len = (int) JpegEditorTest.u32(in, e - 4);
        in[e + 4 + len] ^= 0x01; // flip a CRC bit
        EditReport rep = new EditReport();
        byte[] out = run(PNG, in, T.appleSpec(), rep);
        T.check(hasWarning(rep, "CRC"), "CRC warning: " + rep);
        T.check(indexOf(out, Arrays.copyOfRange(in, e - 4, e + 8 + len)) > 0, "eXIf chunk untouched (bad CRC kept)");
        T.check(hasChangeStarting(rep, "ICC"), "ICC still edited: " + rep);
        T.check(!hasChange(rep, "EXIF bloğu oluşturuldu"), "no second EXIF");
    }

    public static void testCorruptAndTruncatedInputs() throws Exception {
        byte[] a = T.read(huaweiPng("png-corrupt.png", "II", true, true));
        for (int cut = 0; cut < a.length; cut += (cut < 200 ? 1 : 29)) {
            robust(PNG, Arrays.copyOf(a, cut), "cut " + cut);
        }
        // truncated in the image data: edited, rest copied, warning
        EditReport rep = new EditReport();
        int idat = indexOf(a, ascii("IDAT"));
        byte[] trunc = Arrays.copyOf(a, idat + 20);
        byte[] out = run(PNG, trunc, T.appleSpec(), rep);
        T.check(hasWarning(rep, "kesik"), "truncation warning: " + rep);
        T.check(Arrays.equals(Arrays.copyOfRange(trunc, idat - 4, trunc.length), Arrays.copyOfRange(out, out.length - (trunc.length - idat + 4), out.length)), "rest copied");
        // invalid IHDR
        try {
            run(PNG, concat(SIG, chunk("IDAT", new byte[10]), chunk("IEND", new byte[0])), T.appleSpec(), null);
            throw new AssertionError("missing IHDR must be rejected");
        } catch (UnsupportedFormatException ex) {
            T.contains(ex.getMessage(), "PNG", "message");
        }
        byte[] ihdr = data(a, "IHDR");
        byte[] min = concat(SIG, chunk("IHDR", ihdr));
        robust(PNG, concat(min, chunk("iCCP", ascii("x\0\0garbage"))), "bad iccp");
        robust(PNG, concat(min, chunk("iCCP", ascii("\0\0"))), "empty iccp name");
        robust(PNG, concat(min, chunk("iCCP", new byte[0])), "empty iccp");
        robust(PNG, concat(min, chunk("eXIf", new byte[0])), "empty exif");
        robust(PNG, concat(min, chunk("eXIf", ascii("Exif\0\0"))), "exif prefix only");
        robust(PNG, concat(min, chunk("iTXt", ascii("XML:com.adobe.xmp\0\1\0"))), "short itxt");
        robust(PNG, concat(min, chunk("iTXt", ascii("XML:com.adobe.xmp\0\2\0\0\0x"))), "bad itxt flag");
        robust(PNG, concat(min, chunk("zTXt", ascii("Make\0\1x"))), "bad ztxt method");
        robust(PNG, concat(min, chunk("tEXt", ascii("\0nokeyword"))), "empty keyword");
        robust(PNG, concat(min, chunk("tEXt", ascii("Raw profile type exif\0\nexif\n99999999999\n00"))), "huge raw length");
        robust(PNG, concat(min, chunk("ab#d", new byte[3])), "invalid chunk type");
        JpegEditorTest.fuzz(PNG, a, a.length, 2024, 400, "png fuzz");
    }

    public static void testInspectAndProgress() throws Exception {
        File in = huaweiPng("png-inspect.png", "MM", true, true);
        MediaInfo i = T.inspect(in);
        T.eq(MediaFormat.PNG, i.format, "format");
        T.eq("HUAWEI", i.make, "make");
        T.eq("ALN-AL00", i.model, "model");
        T.eq("HUAWEI REAR CAMERA", i.lensModel, "lens");
        T.eq("2025:06:01 12:00:00", i.dateTimeOriginal, "date");
        T.check(i.hasExif && i.hasIcc && i.hasXmp, "flags");
        T.eq("sRGB", i.iccDescription, "icc desc");
        T.eq(64, i.width, "w");
        T.eq(48, i.height, "h");
        MediaInfo p = inspect(PNG, T.read(plainPng("png-inspect-plain.png", "9x7")));
        T.eq(9, p.width, "plain w");
        T.eq(7, p.height, "plain h");
        T.check(!p.hasExif && !p.hasIcc && !p.hasXmp, "plain flags");
        T.eq(null, p.make, "plain make");

        JpegEditorTest.Prog prog = new JpegEditorTest.Prog();
        File out = T.file("png-inspect-out.png");
        FileChannelSource src = FileChannelSource.open(in);
        try (OutputStream os = new java.io.FileOutputStream(out)) {
            PngEditor.edit(src, os, T.appleSpec(), new EditReport(), prog);
        } finally {
            src.close();
        }
        prog.check(out.length(), "progress");
    }
}
