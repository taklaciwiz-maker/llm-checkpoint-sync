package com.metakamera.core.png;

import com.metakamera.core.ByteSource;
import com.metakamera.core.ByteUtils;
import com.metakamera.core.EditReport;
import com.metakamera.core.EditSpec;
import com.metakamera.core.MediaInfo;
import com.metakamera.core.ProgressListener;
import com.metakamera.core.UnsupportedFormatException;
import com.metakamera.core.jpeg.MetaOps;
import com.metakamera.core.jpeg.SpliceWriter;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.CRC32;

/**
 * PNG metadata editor.
 *
 * <p>Edits the 'eXIf' chunk (raw TIFF; created before the first IDAT when missing), the 'iCCP'
 * profile (inflate / edit / deflate, the profile name is kept), XMP in the "XML:com.adobe.xmp"
 * text chunk (compressed iTXt supported), ImageMagick "Raw profile type exif/APP1/xmp/icc/icm"
 * text chunks, and device-identity text chunks (Make, Model, exif:Make, tiff:Model, icc:model...;
 * only existing ones are changed). CRCs of rewritten chunks are recomputed; every other chunk
 * (IDAT, fdAT, unknown...) and data after IEND is copied verbatim.
 */
public final class PngEditor {

    static final byte[] SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
    /** Metadata chunks larger than this are not loaded (left untouched). */
    static final long MAX_META_CHUNK = 64L * 1024 * 1024;
    /** Total metadata bytes held in memory. */
    static final long MAX_LOADED = 128L * 1024 * 1024;

    static final String XMP_KEYWORD = "XML:com.adobe.xmp";
    static final String RAW_PROFILE = "Raw profile type ";

    private static final byte[] EXIF_PREFIX = {'E', 'x', 'i', 'f', 0, 0};
    private static final byte[] XMP_APP1_ID = "http://ns.adobe.com/xap/1.0/\0".getBytes(ByteUtils.ISO_8859_1);

    private static final int F_MAKE = 1;
    private static final int F_MODEL = 2;
    private static final int F_SOFTWARE = 3;
    private static final int F_HOST = 4;
    private static final int F_LENS_MAKE = 5;
    private static final int F_LENS_MODEL = 6;
    private static final int F_ICC_MFR = 7;
    private static final int F_ICC_MODEL = 8;
    private static final int F_SERIAL = 9;

    /**
     * Device-identity text keywords. exiftool writes "Make"/"Model"; ImageMagick copies EXIF, XMP
     * and ICC properties of converted images into "exif:*", "tiff:*", "exifEX:*", "icc:*"... chunks.
     */
    private static final String[] TEXT_KEYS = {
            "Make", "exif:Make", "tiff:Make",
            "Model", "exif:Model", "tiff:Model",
            "exif:Software", "tiff:Software", "xmp:CreatorTool",
            "exif:HostComputer", "tiff:HostComputer",
            "exif:LensMake", "exifEX:LensMake",
            "exif:LensModel", "exifEX:LensModel", "aux:Lens",
            "icc:manufacturer", "icc:model",
            "exif:BodySerialNumber", "exif:SerialNumber", "exif:LensSerialNumber",
            "exif:CameraOwnerName", "exif:ImageUniqueID",
            "exifEX:BodySerialNumber", "exifEX:LensSerialNumber", "exifEX:CameraOwnerName",
            "aux:SerialNumber", "aux:LensSerialNumber", "aux:OwnerName",
    };
    private static final int[] TEXT_FIELDS = {
            F_MAKE, F_MAKE, F_MAKE,
            F_MODEL, F_MODEL, F_MODEL,
            F_SOFTWARE, F_SOFTWARE, F_SOFTWARE,
            F_HOST, F_HOST,
            F_LENS_MAKE, F_LENS_MAKE,
            F_LENS_MODEL, F_LENS_MODEL, F_LENS_MODEL,
            F_ICC_MFR, F_ICC_MODEL,
            F_SERIAL, F_SERIAL, F_SERIAL,
            F_SERIAL, F_SERIAL,
            F_SERIAL, F_SERIAL, F_SERIAL,
            F_SERIAL, F_SERIAL, F_SERIAL,
    };

    private PngEditor() {
    }

    /** One chunk, or an unparseable remainder ({@link #raw}). */
    static final class Chunk {
        long start;
        long end;
        String type;
        long length;
        boolean raw;
        /** Chunk data for loaded (metadata) chunks, else null. */
        byte[] data;
        long storedCrc;
        boolean tooLarge;

        boolean crcOk() {
            return data != null && crc(type, data) == storedCrc;
        }
    }

    static final class Png {
        final List<Chunk> chunks = new ArrayList<Chunk>();
        long tailStart;
        long fileLen;
        String problem;
        int width;
        int height;

        int indexOf(String type) {
            for (int i = 0; i < chunks.size(); i++) {
                Chunk c = chunks.get(i);
                if (!c.raw && type.equals(c.type)) return i;
            }
            return -1;
        }
    }

    static boolean isLoaded(String t) {
        return t.equals("IHDR") || t.equals("eXIf") || t.equals("iCCP")
                || t.equals("tEXt") || t.equals("zTXt") || t.equals("iTXt");
    }

    static boolean validType(byte[] b, int off) {
        for (int i = 0; i < 4; i++) {
            int c = b[off + i] & 0xFF;
            if (!((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z'))) return false;
        }
        return true;
    }

    static Png parse(ByteSource src) throws IOException {
        long len = src.length();
        if (len < 8 + 12) throw new UnsupportedFormatException("PNG dosyası çok kısa");
        byte[] sig = ByteUtils.readBytes(src, 0, 8);
        if (!ByteUtils.startsWith(sig, 0, SIGNATURE)) throw new UnsupportedFormatException("Geçerli bir PNG dosyası değil");
        Png png = new Png();
        png.fileLen = len;
        long pos = 8;
        long loaded = 0;
        byte[] h = new byte[8];
        while (pos < len) {
            Chunk c = new Chunk();
            c.start = pos;
            if (len - pos < 12) {
                c.raw = true;
                c.end = len;
                png.chunks.add(c);
                png.problem = "PNG dosyası kesik veya sonunda tanınmayan veri var";
                pos = len;
                break;
            }
            ByteUtils.readFully(src, pos, h, 0, 8);
            long l = ByteUtils.u32be(h, 0);
            if (l > 0x7FFFFFFFL || !validType(h, 4) || pos + 12 + l > len) {
                c.raw = true;
                c.end = len;
                png.chunks.add(c);
                png.problem = pos + 12 + l > len ? "PNG dosyası kesik" : "PNG yapısı bozuk";
                pos = len;
                break;
            }
            c.type = ByteUtils.fourcc(h, 4);
            c.length = l;
            c.end = pos + 12 + l;
            if (isLoaded(c.type)) {
                if (l <= MAX_META_CHUNK && loaded + l <= MAX_LOADED) {
                    c.data = ByteUtils.readBytes(src, pos + 8, (int) l);
                    c.storedCrc = ByteUtils.u32be(ByteUtils.readBytes(src, pos + 8 + l, 4), 0);
                    loaded += l;
                } else {
                    c.tooLarge = true;
                }
            }
            png.chunks.add(c);
            pos = c.end;
            if (c.type.equals("IEND")) break;
        }
        png.tailStart = pos;
        if (png.chunks.isEmpty()) throw new UnsupportedFormatException("PNG başlığı (IHDR) geçersiz");
        Chunk ihdr = png.chunks.get(0);
        if (ihdr.raw || !"IHDR".equals(ihdr.type) || ihdr.data == null || ihdr.length != 13) {
            throw new UnsupportedFormatException("PNG başlığı (IHDR) geçersiz");
        }
        png.width = (int) Math.min(Integer.MAX_VALUE, ByteUtils.u32be(ihdr.data, 0));
        png.height = (int) Math.min(Integer.MAX_VALUE, ByteUtils.u32be(ihdr.data, 4));
        return png;
    }

    // ==================================================================== edit

    public static void edit(ByteSource src, OutputStream out, EditSpec spec, EditReport report,
                            ProgressListener listener) throws IOException {
        if (spec == null) throw new IllegalArgumentException("spec == null");
        if (report == null) report = new EditReport();
        Png png = parse(src);
        List<Chunk> chunks = png.chunks;
        int n = chunks.size();
        if (png.problem != null) report.warn(png.problem + "; kalan veri olduğu gibi kopyalandı");
        byte[][] repl = new byte[n][];
        boolean hasExif = false;

        for (int i = 0; i < n; i++) {
            Chunk c = chunks.get(i);
            if (c.raw) continue;
            String t = c.type;
            if (t.equals("eXIf")) {
                boolean first = !hasExif;
                hasExif = true;
                if (!first) continue;
                if (c.tooLarge) {
                    report.warn("PNG eXIf bloğu çok büyük; değiştirilmedi");
                } else if (!c.crcOk()) {
                    report.warn("PNG eXIf bloğunun CRC değeri hatalı; değiştirilmedi");
                } else {
                    byte[] nd = editExifPayload(c.data, spec, report);
                    if (nd != null) repl[i] = chunkBytes("eXIf", nd);
                }
            } else if (t.equals("iCCP")) {
                if (!MetaOps.wantsIcc(spec) || i != png.indexOf("iCCP")) continue;
                if (c.tooLarge) {
                    report.warn("PNG iCCP bloğu çok büyük; ICC değiştirilmedi");
                } else if (!c.crcOk()) {
                    report.warn("PNG iCCP bloğunun CRC değeri hatalı; ICC değiştirilmedi");
                } else {
                    byte[] nd = editIccp(c.data, spec, report);
                    if (nd != null) repl[i] = chunkBytes("iCCP", nd);
                }
            } else if (t.equals("tEXt") || t.equals("zTXt") || t.equals("iTXt")) {
                if (c.data == null) continue;
                String kw = PngText.peekKeyword(c.data);
                if (kw == null) continue;
                int kind = textKind(kw);
                if (kind == 0) continue;
                if (kind == 2 && isRawExif(c)) hasExif = true;
                if (!wantsText(kind, kw, spec)) continue;
                if (!c.crcOk()) {
                    report.warn("PNG " + t + " (" + kw + ") bloğunun CRC değeri hatalı; değiştirilmedi");
                    continue;
                }
                PngText pt = PngText.parse(t, c.data);
                if (pt == null) {
                    report.warn("PNG " + t + " (" + kw + ") bloğu okunamadı; değiştirilmedi");
                    continue;
                }
                byte[] nd;
                if (kind == 1) nd = editXmpText(pt, spec, report);
                else if (kind == 2) nd = editRawProfile(pt, spec, report);
                else nd = editIdentityText(pt, spec, report);
                if (nd != null) repl[i] = nd.length == 0 ? nd : chunkBytes(t, nd);
            }
        }

        // ---------------------------------------------------------------- create eXIf
        byte[] newExif = null;
        int insertAt = -1;
        if (!hasExif && MetaOps.wantsNewExif(spec)) {
            byte[] tiff = MetaOps.createExif(spec, png.width, png.height, report);
            if (tiff != null) {
                newExif = chunkBytes("eXIf", tiff);
                // before the image data; for APNG also before the first frame control chunk
                insertAt = png.indexOf("IDAT");
                int fctl = png.indexOf("fcTL");
                if (fctl >= 0 && (insertAt < 0 || fctl < insertAt)) insertAt = fctl;
                if (insertAt < 0) insertAt = png.indexOf("IEND");
                if (insertAt < 0) {
                    insertAt = n;
                    if (n > 1 && chunks.get(n - 1).raw) insertAt = n - 1;
                }
            }
        }

        // ---------------------------------------------------------------- write
        SpliceWriter w = new SpliceWriter();
        w.copy(0, 8);
        for (int i = 0; i < n; i++) {
            if (i == insertAt) w.add(newExif);
            Chunk c = chunks.get(i);
            if (repl[i] != null) w.add(repl[i]);
            else w.copy(c.start, c.end - c.start);
        }
        if (insertAt == n) w.add(newExif);
        w.copy(png.tailStart, png.fileLen - png.tailStart);
        w.writeTo(src, out, listener);
    }

    /** 0 = not interesting, 1 = XMP, 2 = raw profile, 3 = identity text. */
    private static int textKind(String kw) {
        if (kw.equals(XMP_KEYWORD)) return 1;
        if (kw.startsWith(RAW_PROFILE)) {
            String name = kw.substring(RAW_PROFILE.length()).toLowerCase(Locale.ROOT);
            if (name.equals("exif") || name.equals("app1") || name.equals("xmp")
                    || name.equals("icc") || name.equals("icm")) return 2;
            return 0;
        }
        return fieldOf(kw) != 0 ? 3 : 0;
    }

    private static int fieldOf(String kw) {
        for (int i = 0; i < TEXT_KEYS.length; i++) if (TEXT_KEYS[i].equals(kw)) return TEXT_FIELDS[i];
        return 0;
    }

    private static String rawName(String kw) {
        return kw.substring(RAW_PROFILE.length()).toLowerCase(Locale.ROOT);
    }

    /** Whether a text chunk can be affected by the spec at all (avoids needless decompression). */
    private static boolean wantsText(int kind, String kw, EditSpec spec) {
        if (kind == 1) return spec.editXmp;
        if (kind == 2) {
            String name = rawName(kw);
            if (name.equals("xmp")) return spec.editXmp;
            if (name.equals("icc") || name.equals("icm")) return MetaOps.wantsIcc(spec);
            return true; // exif / app1 (EXIF or XMP inside)
        }
        return valueFor(fieldOf(kw), spec) != null;
    }

    private static String valueFor(int field, EditSpec spec) {
        switch (field) {
            case F_MAKE:
                return spec.make;
            case F_MODEL:
                return spec.model;
            case F_SOFTWARE:
                return spec.software;
            case F_HOST:
                return spec.hostComputer;
            case F_LENS_MAKE:
                return spec.lensMake;
            case F_LENS_MODEL:
                return spec.lensModel;
            case F_ICC_MFR:
                return MetaOps.wantsIcc(spec) ? spec.icc.manufacturerText : null;
            case F_ICC_MODEL:
                return MetaOps.wantsIcc(spec) ? spec.icc.modelText : null;
            case F_SERIAL:
                return spec.clearSerials ? "" : null;
            default:
                return null;
        }
    }

    /** True when a "Raw profile type exif/APP1" chunk holds EXIF (cheap check, decodes the text). */
    private static boolean isRawExif(Chunk c) {
        String kw = PngText.peekKeyword(c.data);
        if (kw == null) return false;
        String name = rawName(kw);
        if (!name.equals("exif") && !name.equals("app1")) return false;
        PngText pt = PngText.parse(c.type, c.data);
        if (pt == null) return false;
        byte[] d = PngText.decodeRawProfile(pt.text);
        return d != null && exifOffset(d) >= 0;
    }

    /** Offset of the TIFF header in an EXIF payload ("Exif\0\0" prefix allowed), or -1. */
    static int exifOffset(byte[] d) {
        int p = ByteUtils.startsWith(d, 0, EXIF_PREFIX) ? EXIF_PREFIX.length : 0;
        if (d.length < p + 8) return -1;
        boolean ii = d[p] == 'I' && d[p + 1] == 'I' && d[p + 2] == 42 && d[p + 3] == 0;
        boolean mm = d[p] == 'M' && d[p + 1] == 'M' && d[p + 2] == 0 && d[p + 3] == 42;
        return ii || mm ? p : -1;
    }

    /** New eXIf data or null when unchanged. Keeps a non-standard "Exif\0\0" prefix. */
    private static byte[] editExifPayload(byte[] d, EditSpec spec, EditReport report) {
        int pre = ByteUtils.startsWith(d, 0, EXIF_PREFIX) ? EXIF_PREFIX.length : 0;
        byte[] tiff = pre == 0 ? d : MetaOps.slice(d, pre, d.length);
        byte[] nt = MetaOps.editExif(tiff, spec, MetaOps.UNLIMITED, report);
        if (nt == tiff) return null;
        return pre == 0 ? nt : MetaOps.concat(MetaOps.slice(d, 0, pre), nt);
    }

    /** New iCCP data or null. */
    private static byte[] editIccp(byte[] d, EditSpec spec, EditReport report) {
        int k = PngText.keywordLength(d);
        if (k < 0 || k + 2 > d.length || d[k + 1] != 0) {
            report.warn("PNG iCCP bloğu okunamadı; ICC değiştirilmedi");
            return null;
        }
        byte[] profile = PngText.inflate(d, k + 2, d.length - k - 2);
        if (profile == null) {
            report.warn("PNG iCCP profili açılamadı (zlib hatası); ICC değiştirilmedi");
            return null;
        }
        byte[] np = MetaOps.editIcc(profile, spec, report);
        if (np == profile) return null;
        return MetaOps.concat(MetaOps.slice(d, 0, k + 2), PngText.deflate(np));
    }

    /** New chunk data for an XMP text chunk, or null. */
    private static byte[] editXmpText(PngText pt, EditSpec spec, EditReport report) {
        byte[] nx = MetaOps.editXmp(pt.text, 0, pt.text.length, spec, report);
        return nx == null ? null : pt.serialize(nx);
    }

    /** New chunk data for an ImageMagick raw profile chunk, or null. */
    private static byte[] editRawProfile(PngText pt, EditSpec spec, EditReport report) {
        String name = rawName(pt.keyword);
        byte[] d = PngText.decodeRawProfile(pt.text);
        if (d == null) {
            report.warn("PNG \"" + pt.keyword + "\" profili okunamadı; değiştirilmedi");
            return null;
        }
        String label = PngText.rawProfileName(pt.text);
        if (label == null) label = name;
        byte[] nd = null;
        if (name.equals("xmp")) {
            nd = MetaOps.editXmp(d, 0, d.length, spec, report);
        } else if (name.equals("icc") || name.equals("icm")) {
            byte[] np = MetaOps.editIcc(d, spec, report);
            if (np != d) nd = np;
        } else {
            int off = exifOffset(d);
            if (off >= 0) {
                byte[] tiff = MetaOps.slice(d, off, d.length);
                byte[] nt = MetaOps.editExif(tiff, spec, MetaOps.UNLIMITED, report);
                if (nt != tiff) nd = MetaOps.concat(MetaOps.slice(d, 0, off), nt);
            } else if (ByteUtils.startsWith(d, 0, XMP_APP1_ID) && spec.editXmp) {
                int hl = XMP_APP1_ID.length;
                byte[] nx = MetaOps.editXmp(d, hl, d.length - hl, spec, report);
                if (nx != null) nd = MetaOps.concat(XMP_APP1_ID, nx);
            }
        }
        if (nd == null) return null;
        return pt.serialize(PngText.encodeRawProfile(label, nd));
    }

    /** New chunk data, an empty array (= remove the chunk), or null (unchanged). */
    private static byte[] editIdentityText(PngText pt, EditSpec spec, EditReport report) {
        String value = valueFor(fieldOf(pt.keyword), spec);
        if (value == null) return null;
        String old = pt.textString();
        if (value.length() == 0) {
            report.change("PNG metni " + pt.keyword + " kaldırıldı");
            return new byte[0];
        }
        byte[] enc = pt.encode(value);
        if (java.util.Arrays.equals(enc, pt.text)) return null;
        report.change("PNG metni " + pt.keyword + ": " + old + " → " + value);
        return pt.serialize(enc);
    }

    static long crc(String type, byte[] data) {
        CRC32 c = new CRC32();
        c.update(ByteUtils.fourccBytes(type));
        c.update(data, 0, data.length);
        return c.getValue();
    }

    static byte[] chunkBytes(String type, byte[] data) {
        byte[] b = new byte[12 + data.length];
        ByteUtils.put32be(b, 0, data.length);
        System.arraycopy(ByteUtils.fourccBytes(type), 0, b, 4, 4);
        System.arraycopy(data, 0, b, 8, data.length);
        ByteUtils.put32be(b, 8 + data.length, crc(type, data));
        return b;
    }

    // ==================================================================== inspect

    public static void inspect(ByteSource src, MediaInfo info) throws IOException {
        if (info == null) return;
        Png png;
        try {
            png = parse(src);
        } catch (UnsupportedFormatException e) {
            return;
        }
        if (png.width > 0 && png.height > 0) {
            info.width = png.width;
            info.height = png.height;
        }
        byte[] icc = null;
        byte[] exif = null;
        byte[] xmp = null;
        boolean iccSeen = false;
        boolean exifSeen = false;
        boolean xmpSeen = false;
        String[] texts = new String[F_SERIAL + 1];
        for (Chunk c : png.chunks) {
            if (c.raw) continue;
            String t = c.type;
            if (t.equals("iCCP") && !iccSeen) {
                iccSeen = true;
                if (c.data != null) {
                    int k = PngText.keywordLength(c.data);
                    if (k >= 0 && k + 2 <= c.data.length) icc = PngText.inflate(c.data, k + 2, c.data.length - k - 2);
                }
            } else if (t.equals("eXIf") && !exifSeen) {
                exifSeen = true;
                if (c.data != null) {
                    int off = ByteUtils.startsWith(c.data, 0, EXIF_PREFIX) ? EXIF_PREFIX.length : 0;
                    exif = MetaOps.slice(c.data, off, c.data.length);
                }
            } else if ((t.equals("tEXt") || t.equals("zTXt") || t.equals("iTXt")) && c.data != null) {
                String kw = PngText.peekKeyword(c.data);
                int kind = kw == null ? 0 : textKind(kw);
                if (kind == 0) continue;
                PngText pt = PngText.parse(t, c.data);
                if (pt == null) continue;
                if (kind == 1) {
                    if (xmp == null) xmp = pt.text;
                    xmpSeen = true;
                } else if (kind == 2) {
                    byte[] d = PngText.decodeRawProfile(pt.text);
                    if (d == null) continue;
                    String name = rawName(kw);
                    if (name.equals("xmp")) {
                        if (xmp == null) xmp = d;
                        xmpSeen = true;
                    } else if (name.equals("icc") || name.equals("icm")) {
                        if (icc == null) icc = d;
                        iccSeen = true;
                    } else {
                        int off = exifOffset(d);
                        if (off >= 0) {
                            if (exif == null) exif = MetaOps.slice(d, off, d.length);
                            exifSeen = true;
                        }
                    }
                } else {
                    int f = fieldOf(kw);
                    if (texts[f] == null) texts[f] = pt.textString();
                }
            }
        }
        if (icc != null) MetaOps.inspectIcc(icc, info);
        else if (iccSeen) info.hasIcc = true;
        if (exif != null) MetaOps.inspectExif(exif, info);
        else if (exifSeen) info.hasExif = true;
        if (xmp != null) MetaOps.inspectXmp(xmp, 0, xmp.length, info);
        else if (xmpSeen) info.hasXmp = true;
        if (info.make == null) info.make = texts[F_MAKE];
        if (info.model == null) info.model = texts[F_MODEL];
        if (info.software == null) info.software = texts[F_SOFTWARE];
        if (info.hostComputer == null) info.hostComputer = texts[F_HOST];
        if (info.lensMake == null) info.lensMake = texts[F_LENS_MAKE];
        if (info.lensModel == null) info.lensModel = texts[F_LENS_MODEL];
    }
}
