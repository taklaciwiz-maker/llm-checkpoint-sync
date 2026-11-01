package com.metakamera.core.tiff;

import com.metakamera.core.ByteUtils;
import com.metakamera.core.UnsupportedFormatException;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Bounds-checked parser of an EXIF TIFF structure: IFD0, Exif IFD, GPS IFD, Interop IFD and IFD1.
 * Every offset/length is validated; loops and overlapping IFDs are rejected.
 */
final class TiffFile {

    static final int T_BYTE = 1;
    static final int T_ASCII = 2;
    static final int T_SHORT = 3;
    static final int T_LONG = 4;
    static final int T_RATIONAL = 5;
    static final int T_UNDEFINED = 7;
    static final int T_SLONG = 9;
    static final int T_IFD = 13;

    static final int TAG_MAKE = 0x010F;
    static final int TAG_MODEL = 0x0110;
    static final int TAG_STRIP_OFFSETS = 0x0111;
    static final int TAG_STRIP_BYTE_COUNTS = 0x0117;
    static final int TAG_SOFTWARE = 0x0131;
    static final int TAG_HOST_COMPUTER = 0x013C;
    static final int TAG_TILE_OFFSETS = 0x0144;
    static final int TAG_TILE_BYTE_COUNTS = 0x0145;
    static final int TAG_SUB_IFDS = 0x014A;
    static final int TAG_JPEG_IF = 0x0201;
    static final int TAG_JPEG_IF_LEN = 0x0202;
    static final int TAG_EXIF_IFD = 0x8769;
    static final int TAG_ICC = 0x8773;
    static final int TAG_GPS_IFD = 0x8825;
    static final int TAG_EXIF_VERSION = 0x9000;
    static final int TAG_DATE_TIME_ORIGINAL = 0x9003;
    static final int TAG_OFFSET_TIME_ORIGINAL = 0x9011;
    static final int TAG_COMPONENTS_CONFIGURATION = 0x9101;
    static final int TAG_MAKER_NOTE = 0x927C;
    static final int TAG_FLASHPIX_VERSION = 0xA000;
    static final int TAG_COLOR_SPACE = 0xA001;
    static final int TAG_PIXEL_X = 0xA002;
    static final int TAG_PIXEL_Y = 0xA003;
    static final int TAG_INTEROP_IFD = 0xA005;
    static final int TAG_IMAGE_UNIQUE_ID = 0xA420;
    static final int TAG_CAMERA_OWNER = 0xA430;
    static final int TAG_BODY_SERIAL = 0xA431;
    static final int TAG_LENS_MAKE = 0xA433;
    static final int TAG_LENS_MODEL = 0xA434;
    static final int TAG_LENS_SERIAL = 0xA435;

    static final int GPS_LATITUDE = 0x0002;
    static final int GPS_LONGITUDE = 0x0004;

    final byte[] d;
    final boolean le;

    TiffIfd ifd0;
    TiffIfd exif;
    TiffIfd gps;
    TiffIfd interop;
    TiffIfd ifd1;

    /** A pointer entry exists but the IFD it points to could not be parsed. */
    boolean exifBroken;
    boolean gpsBroken;
    boolean interopBroken;
    boolean ifd1Broken;

    /** Turkish, user-facing parse problems (non-fatal). */
    final List<String> warnings = new ArrayList<String>();

    /** Every entry parsed from the source (for overlap checks). */
    final List<TiffEntry> allEntries = new ArrayList<TiffEntry>();
    /** Source structure ranges [start, end) of every parsed IFD. */
    final List<int[]> ifdRanges = new ArrayList<int[]>();

    private TiffFile(byte[] d, boolean le) {
        this.d = d;
        this.le = le;
    }

    static int typeSize(int type) {
        switch (type) {
            case 1:
            case 2:
            case 6:
            case 7:
                return 1;
            case 3:
            case 8:
                return 2;
            case 4:
            case 9:
            case 11:
            case 13:
                return 4;
            case 5:
            case 10:
            case 12:
                return 8;
            default:
                return 0;
        }
    }

    // ------------------------------------------------------------------ parsing

    /** Parses the structure; throws only when the block is unusable (bad header or IFD0). */
    static TiffFile parse(byte[] d) throws UnsupportedFormatException {
        if (d == null || d.length < 8) throw new UnsupportedFormatException("EXIF verisi çok kısa");
        boolean le;
        if (d[0] == 'I' && d[1] == 'I') {
            le = true;
        } else if (d[0] == 'M' && d[1] == 'M') {
            le = false;
        } else {
            throw new UnsupportedFormatException("EXIF bayt sırası tanınmadı");
        }
        TiffFile t = new TiffFile(d, le);
        if (t.u16(2) != 42) throw new UnsupportedFormatException("EXIF başlığı geçersiz");
        t.ifd0 = t.parseIfd("IFD0", t.u32(4));
        if (t.ifd0 == null) throw new UnsupportedFormatException("EXIF IFD0 okunamadı (bozuk veri)");

        TiffEntry p = t.ifd0.find(TAG_EXIF_IFD);
        if (p != null) {
            t.exif = t.parseIfd("ExifIFD", t.pointerValue(p));
            if (t.exif == null) {
                t.exifBroken = true;
                t.warnings.add("Exif IFD bozuk");
            }
        }
        p = t.ifd0.find(TAG_GPS_IFD);
        if (p != null) {
            t.gps = t.parseIfd("GPS", t.pointerValue(p));
            if (t.gps == null) {
                t.gpsBroken = true;
                t.warnings.add("GPS IFD bozuk");
            }
        }
        if (t.exif != null) {
            p = t.exif.find(TAG_INTEROP_IFD);
            if (p != null) {
                t.interop = t.parseIfd("InteropIFD", t.pointerValue(p));
                if (t.interop == null) {
                    t.interopBroken = true;
                    t.warnings.add("Interop IFD bozuk");
                }
            }
        }
        if (t.ifd0.next != 0) {
            t.ifd1 = t.parseIfd("IFD1", t.ifd0.next);
            if (t.ifd1 == null) {
                t.ifd1Broken = true;
                t.warnings.add("IFD1 (küçük resim) bozuk");
            }
        }
        return t;
    }

    /** Value of a single-valued offset entry (LONG/IFD/SLONG/SHORT), -1 when not a pointer. */
    long pointerValue(TiffEntry e) {
        if (e == null || e.count != 1) return -1;
        switch (e.type) {
            case T_LONG:
            case T_IFD:
            case T_SLONG:
                return u32(e.field, 0);
            case T_SHORT:
                return u16(e.field, 0);
            default:
                return -1;
        }
    }

    private TiffIfd parseIfd(String name, long off) {
        if (off < 8 || off > d.length - 2L) return null;
        int o = (int) off;
        int n = u16(o);
        long structEnd = o + 2L + 12L * n;
        if (structEnd > d.length) return null;
        boolean hasNext = structEnd + 4 <= d.length;
        int end = (int) structEnd + (hasNext ? 4 : 0);
        for (int i = 0; i < ifdRanges.size(); i++) {
            int[] r = ifdRanges.get(i);
            if (o < r[1] && r[0] < end) return null; // loop or overlapping IFDs
        }
        ifdRanges.add(new int[]{o, end});
        TiffIfd ifd = new TiffIfd(name);
        ifd.pos = o;
        ifd.srcCount = n;
        ifd.hasNextField = hasNext;
        ifd.next = hasNext ? u32((int) structEnd) : 0;
        for (int i = 0; i < n; i++) {
            TiffEntry e = parseEntry(o + 2 + 12 * i);
            ifd.entries.add(e);
            allEntries.add(e);
        }
        return ifd;
    }

    private TiffEntry parseEntry(int p) {
        TiffEntry e = new TiffEntry();
        e.entryPos = p;
        e.tag = u16(p);
        e.type = u16(p + 2);
        e.count = u32(p + 4);
        System.arraycopy(d, p + 8, e.field, 0, 4);
        int sz = typeSize(e.type);
        if (sz > 0) {
            e.byteLen = e.count * sz;
            if (e.byteLen <= 4) {
                e.inline = true;
                e.valuePos = p + 8;
            } else {
                long vo = u32(p + 8);
                if (vo >= 8 && vo + e.byteLen <= d.length) e.valuePos = (int) vo;
            }
        }
        return e;
    }

    // ------------------------------------------------------------------ value access

    /** Source value bytes, or null when unknown/out of range. */
    byte[] value(TiffEntry e) {
        if (e == null || e.valuePos < 0 || e.byteLen < 0) return null;
        return Arrays.copyOfRange(d, e.valuePos, e.valuePos + (int) e.byteLen);
    }

    /** ASCII-ish (ASCII/BYTE/UNDEFINED) source value up to the first NUL, or null. */
    byte[] rawString(TiffEntry e) {
        if (e == null) return null;
        if (e.type != T_ASCII && e.type != T_BYTE && e.type != T_UNDEFINED) return null;
        byte[] v = value(e);
        if (v == null) return null;
        int n = 0;
        while (n < v.length && v[n] != 0) n++;
        return n == v.length ? v : Arrays.copyOf(v, n);
    }

    /** Decoded source string, trailing spaces trimmed; null when absent/unreadable. */
    String string(TiffEntry e) {
        byte[] b = rawString(e);
        return b == null ? null : decode(b);
    }

    static String decode(byte[] b) {
        String s;
        try {
            CharsetDecoder dec = ByteUtils.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            s = dec.decode(ByteBuffer.wrap(b)).toString();
        } catch (CharacterCodingException ex) {
            s = new String(b, ByteUtils.ISO_8859_1);
        }
        int end = s.length();
        while (end > 0) {
            char c = s.charAt(end - 1);
            if (c == ' ' || c == '\0' || c == '\t' || c == '\r' || c == '\n') end--;
            else break;
        }
        return s.substring(0, end);
    }

    /** Unsigned integer array value (BYTE/SHORT/LONG), or null. */
    long[] uintArray(TiffEntry e) {
        if (e == null) return null;
        int sz;
        if (e.type == T_SHORT) sz = 2;
        else if (e.type == T_LONG || e.type == T_IFD) sz = 4;
        else if (e.type == T_BYTE) sz = 1;
        else return null;
        byte[] v = value(e);
        if (v == null || e.count > 1000000) return null;
        int n = (int) e.count;
        long[] r = new long[n];
        for (int i = 0; i < n; i++) {
            if (sz == 1) r[i] = v[i] & 0xFF;
            else if (sz == 2) r[i] = u16(v, i * 2);
            else r[i] = u32(v, i * 4);
        }
        return r;
    }

    /**
     * Image data ranges [start, end) referenced by this IFD (JPEG thumbnail, strips).
     * Invalid references are skipped. Returns an empty list when there are none.
     */
    List<int[]> dataRanges(TiffIfd ifd) {
        List<int[]> r = new ArrayList<int[]>();
        if (ifd == null) return r;
        long off = pointerValue(ifd.find(TAG_JPEG_IF));
        long len = pointerValue(ifd.find(TAG_JPEG_IF_LEN));
        if (off >= 8 && len > 0 && off + len <= d.length) r.add(new int[]{(int) off, (int) (off + len)});
        long[] so = uintArray(ifd.find(TAG_STRIP_OFFSETS));
        long[] sc = uintArray(ifd.find(TAG_STRIP_BYTE_COUNTS));
        if (so != null && sc != null && so.length == sc.length) {
            for (int i = 0; i < so.length; i++) {
                if (so[i] >= 8 && sc[i] > 0 && so[i] + sc[i] <= d.length) {
                    r.add(new int[]{(int) so[i], (int) (so[i] + sc[i])});
                }
            }
        }
        return r;
    }

    TiffIfd[] allIfds() {
        return new TiffIfd[]{ifd0, exif, gps, interop, ifd1};
    }

    // ------------------------------------------------------------------ endian helpers

    int u16(int off) {
        return u16(d, off);
    }

    long u32(int off) {
        return u32(d, off);
    }

    int u16(byte[] b, int off) {
        return le ? ByteUtils.u16le(b, off) : ByteUtils.u16be(b, off);
    }

    long u32(byte[] b, int off) {
        return le ? ByteUtils.u32le(b, off) : ByteUtils.u32be(b, off);
    }

    void put16(byte[] b, int off, int v) {
        if (le) ByteUtils.put16le(b, off, v);
        else ByteUtils.put16be(b, off, v);
    }

    void put32(byte[] b, int off, long v) {
        if (le) ByteUtils.put32le(b, off, v);
        else ByteUtils.put32be(b, off, v);
    }
}
