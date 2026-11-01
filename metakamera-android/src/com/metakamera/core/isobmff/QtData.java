package com.metakamera.core.isobmff;

import com.metakamera.core.ByteUtils;

import java.nio.charset.Charset;
import java.util.List;

/**
 * Encoding helpers for QuickTime metadata values:
 * <ul>
 *   <li>'data' atoms (ilst items of 'mdta' Keys metadata and iTunes 'mdir' metadata):
 *       [uint32 type indicator][uint32 locale][value]</li>
 *   <li>QuickTime user-data text atoms ('©mak', '©mod', '©swr' in moov/udta):
 *       one or more [uint16 length][uint16 language][text]</li>
 *   <li>3GPP user-data string atoms ('auth' ...): [version/flags][uint16 language][text NUL]</li>
 *   <li>raw string atoms (Samsung udta/smta/'mdln')</li>
 * </ul>
 */
final class QtData {

    static final String DATA = "data";
    /** Packed ISO-639-2 "eng". */
    static final int LANG_ENG = 0x15C7;

    private static final Charset UTF_16BE = Charset.forName("UTF-16BE");
    private static final Charset UTF_16LE = Charset.forName("UTF-16LE");

    private QtData() {
    }

    // ------------------------------------------------------------------ 'data' atoms

    /** Decodes a 'data' atom payload (type indicator + locale + value); null if too short. */
    static String decodeDataPayload(byte[] p) {
        if (p == null || p.length < 8) return null;
        int type = (int) (ByteUtils.u32be(p, 0) & 0xFFFFFF);
        int n = p.length - 8;
        String s;
        if (type == 2 || type == 5) {
            s = new String(p, 8, n - (n % 2), UTF_16BE);
        } else {
            s = new String(p, 8, n, ByteUtils.UTF_8);
        }
        return stripNul(s);
    }

    /** Payload of a UTF-8 'data' atom (type indicator 1) with the given 4-byte locale. */
    static byte[] encodeDataPayload(String value, long locale) {
        byte[] v = value.getBytes(ByteUtils.UTF_8);
        byte[] p = new byte[8 + v.length];
        ByteUtils.put32be(p, 0, 1);
        ByteUtils.put32be(p, 4, locale);
        System.arraycopy(v, 0, p, 8, v.length);
        return p;
    }

    static long localeOf(byte[] dataPayload) {
        return dataPayload != null && dataPayload.length >= 8 ? ByteUtils.u32be(dataPayload, 4) : 0;
    }

    /** Value of an ilst item (first 'data' child), null if unreadable. */
    static String itemValue(Mp4Box item) {
        if (item.isContainer()) {
            Mp4Box d = item.child(DATA);
            return d == null ? null : decodeDataPayload(d.payloadBytes());
        }
        return null;
    }

    /** Replaces the value of every 'data' child of an ilst item (adds one when there is none). */
    static void setItemValue(Mp4Box item, String value) {
        if (!item.isContainer()) {
            // unreadable item content: replace it with a single fresh 'data' atom
            item.setPayload(Mp4Box.newLeaf(DATA, encodeDataPayload(value, 0)).toBytes());
            return;
        }
        List<Mp4Box> datas = item.childrenOf(DATA);
        if (datas.isEmpty()) {
            item.addChild(Mp4Box.newLeaf(DATA, encodeDataPayload(value, 0)));
            return;
        }
        for (Mp4Box d : datas) {
            d.setPayload(encodeDataPayload(value, localeOf(d.payloadBytes())));
        }
    }

    /** New ilst item box with the given type and a single UTF-8 'data' atom. */
    static Mp4Box newItem(String type, String value) {
        Mp4Box item = Mp4Box.newContainer(type, null);
        item.children.add(Mp4Box.newLeaf(DATA, encodeDataPayload(value, 0)));
        return item;
    }

    // ------------------------------------------------------------------ numeric 'data' atoms

    /** Well-known QuickTime 'data' types. */
    static final int TYPE_UTF8 = 1;
    static final int TYPE_UTF16 = 2;
    static final int TYPE_BE_SIGNED = 21;
    static final int TYPE_BE_UNSIGNED = 22;
    static final int TYPE_FLOAT32 = 23;
    static final int TYPE_FLOAT64 = 24;

    /** Byte width of a fixed-size integer type (65..67/74 signed, 75..77/78 unsigned), 0 = variable/none. */
    private static int fixedIntWidth(int type) {
        switch (type) {
            case 65:
            case 75:
                return 1;
            case 66:
            case 76:
                return 2;
            case 67:
            case 77:
                return 4;
            case 74:
            case 78:
                return 8;
            default:
                return 0;
        }
    }

    private static boolean isIntType(int type) {
        return type == TYPE_BE_SIGNED || type == TYPE_BE_UNSIGNED || fixedIntWidth(type) > 0;
    }

    private static boolean isSignedIntType(int type) {
        return type == TYPE_BE_SIGNED || (type >= 65 && type <= 67) || type == 74;
    }

    /** True when {@code v} is representable in {@code width} bytes (signed or unsigned). */
    private static boolean fits(long v, int width, boolean signed) {
        if (width >= 8) return signed || v >= 0;
        long bits = 8L * width;
        if (signed) return v >= -(1L << (bits - 1)) && v < (1L << (bits - 1));
        return v >= 0 && v < (1L << bits);
    }

    /**
     * Number held by a 'data' payload: big-endian signed (21) / unsigned (22) integer of 1..8 bytes,
     * the fixed-size integer types, float32/float64 (rounded) or decimal UTF-8/UTF-16 text. Null when
     * the payload holds no readable number.
     */
    static Long decodeDataNumber(byte[] p) {
        if (p == null || p.length < 8) return null;
        int type = (int) (ByteUtils.u32be(p, 0) & 0xFFFFFF);
        int n = p.length - 8;
        if (isIntType(type)) {
            int fixed = fixedIntWidth(type);
            if (n < 1 || n > 8 || (fixed > 0 && n != fixed)) return null;
            long v = 0;
            for (int i = 0; i < n; i++) v = (v << 8) | (p[8 + i] & 0xFF);
            boolean signed = isSignedIntType(type);
            if (signed && n < 8 && (p[8] & 0x80) != 0) v -= 1L << (8 * n);
            if (!signed && n == 8 && v < 0) return null;   // beyond Long.MAX_VALUE
            return Long.valueOf(v);
        }
        if (type == TYPE_FLOAT32 || type == TYPE_FLOAT64) {
            double d;
            if (type == TYPE_FLOAT32 && n == 4) d = Float.intBitsToFloat((int) ByteUtils.u32be(p, 8));
            else if (type == TYPE_FLOAT64 && n == 8) d = Double.longBitsToDouble(ByteUtils.u64be(p, 8));
            else return null;
            if (Double.isNaN(d) || Double.isInfinite(d) || Math.abs(d) > 1e15) return null;
            return Long.valueOf(Math.round(d));
        }
        if (type == TYPE_UTF8 || type == TYPE_UTF16) {
            String s = decodeDataPayload(p);
            if (s == null) return null;
            s = s.trim();
            if (s.length() == 0 || s.length() > 18) return null;
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (!(c >= '0' && c <= '9') && !(i == 0 && c == '-' && s.length() > 1)) return null;
            }
            return Long.valueOf(Long.parseLong(s));
        }
        return null;
    }

    /**
     * New 'data' payload holding {@code value}, keeping the old payload's type (and integer width
     * when the value fits) and locale. Integers that no longer fit, and unknown or missing old
     * payloads, become a big-endian signed integer (type 21) of 4 bytes (8 when needed), as iPhones
     * write their numeric keys.
     */
    static byte[] encodeDataNumber(byte[] old, long value) {
        boolean hasOld = old != null && old.length >= 8;
        int type = hasOld ? (int) (ByteUtils.u32be(old, 0) & 0xFFFFFF) : -1;
        long locale = hasOld ? ByteUtils.u32be(old, 4) : 0;
        int n = hasOld ? old.length - 8 : 0;
        if (type == TYPE_UTF8 || type == TYPE_UTF16) {
            String s = String.valueOf(value);
            byte[] v = type == TYPE_UTF16 ? s.getBytes(UTF_16BE) : s.getBytes(ByteUtils.UTF_8);
            return dataPayload(old, type, locale, v);
        }
        if (type == TYPE_FLOAT32 && n == 4) {
            byte[] v = new byte[4];
            ByteUtils.put32be(v, 0, Float.floatToIntBits((float) value) & 0xFFFFFFFFL);
            return dataPayload(old, type, locale, v);
        }
        if (type == TYPE_FLOAT64 && n == 8) {
            byte[] v = new byte[8];
            ByteUtils.put64be(v, 0, Double.doubleToLongBits((double) value));
            return dataPayload(old, type, locale, v);
        }
        if (isIntType(type)) {
            int fixed = fixedIntWidth(type);
            int width = fixed > 0 ? fixed : n;
            boolean okWidth = width == 1 || width == 2 || width == 3 || width == 4 || width == 8;
            if (okWidth && fits(value, width, isSignedIntType(type))) {
                return dataPayload(old, type, locale, intBytes(value, width));
            }
        } else {
            hasOld = false;   // unknown type: a fresh signed integer replaces it
        }
        int width = fits(value, 4, true) ? 4 : 8;
        return dataPayload(hasOld ? old : null, TYPE_BE_SIGNED, locale, intBytes(value, width));
    }

    private static byte[] intBytes(long v, int width) {
        byte[] b = new byte[width];
        for (int i = width - 1; i >= 0; i--) {
            b[i] = (byte) v;
            v >>= 8;
        }
        return b;
    }

    /** [type indicator (old high byte kept)][locale][value]. */
    private static byte[] dataPayload(byte[] old, int type, long locale, byte[] value) {
        byte[] p = new byte[8 + value.length];
        ByteUtils.put32be(p, 0, type & 0xFFFFFFL);
        if (old != null && old.length >= 8) p[0] = old[0];
        ByteUtils.put32be(p, 4, locale);
        System.arraycopy(value, 0, p, 8, value.length);
        return p;
    }

    /** Number of an ilst item (first 'data' child holding one), null if none. */
    static Long itemNumber(Mp4Box item) {
        if (!item.isContainer()) return null;
        for (Mp4Box d : item.childrenOf(DATA)) {
            Long v = decodeDataNumber(d.payloadBytes());
            if (v != null) return v;
        }
        return null;
    }

    /** Replaces the number of every 'data' child of an ilst item (adds one when there is none). */
    static void setItemNumber(Mp4Box item, long value) {
        if (!item.isContainer()) {
            item.setPayload(Mp4Box.newLeaf(DATA, encodeDataNumber(null, value)).toBytes());
            return;
        }
        List<Mp4Box> datas = item.childrenOf(DATA);
        if (datas.isEmpty()) {
            item.addChild(Mp4Box.newLeaf(DATA, encodeDataNumber(null, value)));
            return;
        }
        for (Mp4Box d : datas) d.setPayload(encodeDataNumber(d.payloadBytes(), value));
    }

    /** New ilst item box with a single big-endian signed integer 'data' atom. */
    static Mp4Box newNumberItem(String type, long value) {
        Mp4Box item = Mp4Box.newContainer(type, null);
        item.children.add(Mp4Box.newLeaf(DATA, encodeDataNumber(null, value)));
        return item;
    }

    // ------------------------------------------------------------------ user-data text atoms

    /** True when a user-data atom payload holds an iTunes-style 'data' atom instead of QuickTime text. */
    static boolean isDataStyle(byte[] p) {
        if (p.length < 16) return false;
        long sz = ByteUtils.u32be(p, 0);
        return sz >= 16 && sz <= p.length && "data".equals(ByteUtils.fourcc(p, 4));
    }

    /** First string of a QuickTime user-data text atom payload (null if malformed). */
    static String decodeText(byte[] p) {
        if (p == null) return null;
        if (isDataStyle(p)) {
            int sz = (int) ByteUtils.u32be(p, 0);
            byte[] dp = new byte[sz - 8];
            System.arraycopy(p, 8, dp, 0, dp.length);
            return decodeDataPayload(dp);
        }
        if (p.length < 4) return null;
        int len = ByteUtils.u16be(p, 0);
        int lang = ByteUtils.u16be(p, 2);
        if (4 + len > p.length) len = p.length - 4;
        String s;
        if (len >= 2 && (p[4] & 0xFF) == 0xFE && (p[5] & 0xFF) == 0xFF) {
            s = new String(p, 6, len - 2 - ((len - 2) % 2), UTF_16BE);
        } else if (lang < 0x400 && !isUtf8(p, 4, len)) {
            s = new String(p, 4, len, ByteUtils.ISO_8859_1); // Mac Roman approximation
        } else {
            s = new String(p, 4, len, ByteUtils.UTF_8);
        }
        return stripNul(s);
    }

    /** Language code of the first text entry, or -1. */
    static int textLang(byte[] p) {
        if (p == null || p.length < 4 || isDataStyle(p)) return -1;
        return ByteUtils.u16be(p, 2);
    }

    /**
     * New payload for a user-data text atom. Keeps the existing language code when it can
     * represent the value (packed ISO codes are UTF-8; Mac codes only for ASCII), else "eng".
     * An iTunes-style payload ('data' atom inside) keeps that style.
     */
    static byte[] encodeText(byte[] oldPayload, String value) {
        if (oldPayload != null && isDataStyle(oldPayload)) {
            int sz = (int) ByteUtils.u32be(oldPayload, 0);
            byte[] dp = new byte[sz - 8];
            System.arraycopy(oldPayload, 8, dp, 0, dp.length);
            return Mp4Box.newLeaf(DATA, encodeDataPayload(value, localeOf(dp))).toBytes();
        }
        byte[] v = value.getBytes(ByteUtils.UTF_8);
        if (v.length > 0xFFFF) {
            byte[] t = new byte[0xFFFF];
            System.arraycopy(v, 0, t, 0, t.length);
            v = t;
        }
        int lang = textLang(oldPayload);
        boolean ascii = isAscii(v);
        if (lang < 0 || lang == 0x7FFF || (lang < 0x400 && !ascii)) lang = LANG_ENG;
        byte[] p = new byte[4 + v.length];
        ByteUtils.put16be(p, 0, v.length);
        ByteUtils.put16be(p, 2, lang);
        System.arraycopy(v, 0, p, 4, v.length);
        return p;
    }

    // ------------------------------------------------------------------ 3GPP text atoms ('auth', 'cprt', ...)

    /** Bytes before the text of a 3GPP user-data string: FullBox version/flags + packed language. */
    static final int GPP_HEADER = 6;

    /**
     * Text of a 3GPP user-data string atom payload (3GPP TS 26.244: version/flags, 2-byte packed
     * language, NUL-terminated UTF-8 or BOM-prefixed UTF-16), null if too short.
     */
    static String decode3gppText(byte[] p) {
        if (p == null || p.length < GPP_HEADER) return null;
        int n = p.length - GPP_HEADER;
        String s;
        if (n >= 2 && (p[GPP_HEADER] & 0xFF) == 0xFE && (p[GPP_HEADER + 1] & 0xFF) == 0xFF) {
            s = new String(p, GPP_HEADER + 2, (n - 2) - ((n - 2) % 2), UTF_16BE);
        } else if (n >= 2 && (p[GPP_HEADER] & 0xFF) == 0xFF && (p[GPP_HEADER + 1] & 0xFF) == 0xFE) {
            s = new String(p, GPP_HEADER + 2, (n - 2) - ((n - 2) % 2), UTF_16LE);
        } else {
            s = new String(p, GPP_HEADER, n, ByteUtils.UTF_8);
        }
        return stripNul(cutAtNul(s));
    }

    /**
     * New payload for a 3GPP user-data string atom: keeps version/flags and language of the old
     * payload ("eng" when there is none), writes NUL-terminated UTF-8 (or UTF-16BE with BOM when the
     * old text used UTF-16).
     */
    static byte[] encode3gppText(byte[] oldPayload, String value) {
        Mp4Writer w = new Mp4Writer();
        boolean utf16 = false;
        if (oldPayload != null && oldPayload.length >= GPP_HEADER) {
            w.write(oldPayload, 0, GPP_HEADER);
            int n = oldPayload.length - GPP_HEADER;
            int b0 = n >= 2 ? oldPayload[GPP_HEADER] & 0xFF : 0;
            int b1 = n >= 2 ? oldPayload[GPP_HEADER + 1] & 0xFF : 0;
            utf16 = (b0 == 0xFE && b1 == 0xFF) || (b0 == 0xFF && b1 == 0xFE);
        } else {
            w.u32(0);
            w.u16(LANG_ENG);
        }
        if (utf16) {
            w.u8(0xFE);
            w.u8(0xFF);
            w.write(value.getBytes(UTF_16BE));
            w.u16(0);
        } else {
            w.write(value.getBytes(ByteUtils.UTF_8));
            w.u8(0);
        }
        return w.toByteArray();
    }

    // ------------------------------------------------------------------ raw string atoms (Samsung 'mdln')

    /** A raw (optionally NUL-terminated) UTF-8 string atom payload. */
    static String decodeRawText(byte[] p) {
        if (p == null) return null;
        return stripNul(cutAtNul(new String(p, ByteUtils.UTF_8)));
    }

    /** New raw string payload; keeps a NUL terminator when the old payload had one. */
    static byte[] encodeRawText(byte[] oldPayload, String value) {
        byte[] v = value.getBytes(ByteUtils.UTF_8);
        boolean nul = oldPayload != null && oldPayload.length > 0 && oldPayload[oldPayload.length - 1] == 0;
        byte[] r = new byte[v.length + (nul ? 1 : 0)];
        System.arraycopy(v, 0, r, 0, v.length);
        return r;
    }

    private static String cutAtNul(String s) {
        int z = s.indexOf('\0');
        return z < 0 ? s : s.substring(0, z);
    }

    // ------------------------------------------------------------------ misc

    static boolean isAscii(byte[] b) {
        for (byte x : b) {
            if ((x & 0x80) != 0) return false;
        }
        return true;
    }

    private static boolean isUtf8(byte[] b, int off, int len) {
        int i = off;
        int end = off + len;
        while (i < end) {
            int c = b[i] & 0xFF;
            int n;
            if (c < 0x80) n = 0;
            else if (c >= 0xC2 && c <= 0xDF) n = 1;
            else if (c >= 0xE0 && c <= 0xEF) n = 2;
            else if (c >= 0xF0 && c <= 0xF4) n = 3;
            else return false;
            if (n > 0 && i + n >= end) return false;
            for (int k = 1; k <= n; k++) {
                if ((b[i + k] & 0xC0) != 0x80) return false;
            }
            i += n + 1;
        }
        return true;
    }

    static String stripNul(String s) {
        int e = s.length();
        while (e > 0 && s.charAt(e - 1) == 0) e--;
        return s.substring(0, e);
    }
}
