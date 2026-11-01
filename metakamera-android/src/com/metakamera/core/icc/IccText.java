package com.metakamera.core.icc;

import com.metakamera.core.ByteUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads and writes the ICC text tag types used by 'desc', 'dmnd' and 'dmdd':
 * textDescriptionType ('desc', ICC v2), multiLocalizedUnicodeType ('mluc', ICC v4) and the
 * plain textType ('text').  Every read is bounds-checked; malformed data yields
 * {@code ok == false} instead of an exception.
 */
final class IccText {

    static final String DESC = "desc";
    static final String MLUC = "mluc";
    static final String TEXT = "text";

    /** Tag type signature (first 4 bytes of the tag data), null when the tag is shorter than 4 bytes. */
    String type;
    /** True when the tag is a known text type and every length/offset was inside the tag. */
    boolean ok;

    /** desc/text: ASCII part (up to the first NUL). */
    String ascii;
    /** desc: Unicode part (null when its count is 0 or it is missing). */
    String unicode;
    /** desc: ScriptCode part (null when its count is 0 or it is missing). */
    String script;

    /** mluc: record texts and their "llCC" language/country codes. */
    final List<String> records = new ArrayList<String>();
    final List<String> recordLocales = new ArrayList<String>();

    private IccText() {
    }

    // ------------------------------------------------------------------ decoding

    /** Decodes the tag at {@code [off, off+size)} of {@code p}. Never throws for in-range arguments. */
    static IccText decode(byte[] p, int off, int size) {
        IccText t = new IccText();
        if (off < 0 || size < 4 || (long) off + size > p.length) return t;
        t.type = ByteUtils.fourcc(p, off);
        if (DESC.equals(t.type)) {
            t.ok = decodeDesc(p, off, size, t);
        } else if (MLUC.equals(t.type)) {
            t.ok = decodeMluc(p, off, size, t);
        } else if (TEXT.equals(t.type)) {
            if (size >= 8) {
                t.ascii = latin1UntilNul(p, off + 8, size - 8);
                t.ok = true;
            }
        }
        return t;
    }

    private static boolean decodeDesc(byte[] p, int off, int size, IccText t) {
        if (size < 12) return false;
        long asciiCount = ByteUtils.u32be(p, off + 8);
        if (asciiCount > size - 12) return false;
        t.ascii = latin1UntilNul(p, off + 12, (int) asciiCount);
        int pos = 12 + (int) asciiCount;          // relative to off
        if (size - pos < 8) return true;            // Unicode/ScriptCode part missing: tolerated
        long ucCount = ByteUtils.u32be(p, off + pos + 4);
        pos += 8;
        if (ucCount > (size - pos) / 2) return false;
        if (ucCount > 0) {
            String u = utf16UntilNul(p, off + pos, (int) ucCount * 2);
            if (u.length() > 0) t.unicode = u;     // a lone NUL means "no Unicode text"
        }
        pos += (int) ucCount * 2;
        if (size - pos < 3) return true;
        int scCount = ByteUtils.u8(p, off + pos + 2);
        pos += 3;
        int avail = Math.min(Math.min(scCount, 67), size - pos);
        if (scCount > 0 && avail > 0) {
            String sc = latin1UntilNul(p, off + pos, avail);
            if (sc.length() > 0) t.script = sc;
        }
        return true;
    }

    private static boolean decodeMluc(byte[] p, int off, int size, IccText t) {
        if (size < 16) return false;
        long count = ByteUtils.u32be(p, off + 8);
        long recSize = ByteUtils.u32be(p, off + 12);
        if (recSize < 12 || recSize > size) return false;
        if (count > (size - 16) / recSize) return false;
        for (int i = 0; i < (int) count; i++) {
            int rec = off + 16 + (int) (i * recSize);
            long len = ByteUtils.u32be(p, rec + 4);
            long strOff = ByteUtils.u32be(p, rec + 8);
            if (strOff > size || len > size - strOff) return false;
            t.recordLocales.add(new String(p, rec, 4, ByteUtils.ISO_8859_1));
            t.records.add(stripNul(utf16(p, off + (int) strOff, (int) len)));
        }
        return true;
    }

    // ------------------------------------------------------------------ queries

    /** Best human-readable text of the tag (trimmed), or null when it is unreadable / not a text type. */
    String text() {
        String t = rawText();
        return t == null ? null : t.trim();
    }

    private String rawText() {
        if (!ok) return null;
        if (DESC.equals(type)) {
            if (ascii != null && ascii.length() > 0) return ascii;
            if (unicode != null && unicode.length() > 0) return unicode;
            return ascii != null ? ascii : "";
        }
        if (MLUC.equals(type)) {
            if (records.isEmpty()) return "";
            int best = 0;
            for (int i = recordLocales.size() - 1; i >= 0; i--) {
                String loc = recordLocales.get(i);
                if (loc.startsWith("en")) best = i;
            }
            for (int i = 0; i < recordLocales.size(); i++) {
                if ("enUS".equals(recordLocales.get(i))) {
                    best = i;
                    break;
                }
            }
            return records.get(best);
        }
        return ascii;
    }

    /** True when every text representation of this tag already equals {@code target}. */
    boolean matches(String target) {
        if (!ok) return false;
        String a = toAscii(target);
        if (DESC.equals(type)) {
            if (!a.equals(ascii)) return false;
            if (unicode != null && !unicode.equals(target)) return false;
            return script == null || script.length() == 0 || script.equals(a);
        }
        if (MLUC.equals(type)) {
            if (records.isEmpty()) return false;
            for (String r : records) {
                if (!r.equals(target)) return false;
            }
            return true;
        }
        if (TEXT.equals(type)) return a.equals(ascii);
        return false;
    }

    // ------------------------------------------------------------------ encoding

    /** Encodes {@code text} as a complete tag of the given type ('desc', 'mluc' or 'text'). */
    static byte[] encode(String type, String text) {
        if (MLUC.equals(type)) return encodeMluc(text);
        if (TEXT.equals(type)) return encodeTextType(text);
        return encodeDesc(text);
    }

    /** textDescriptionType: ASCII + Unicode (UTF-16BE) + empty ScriptCode, like lcms/Apple write it. */
    static byte[] encodeDesc(String text) {
        byte[] ascii = toAscii(text).getBytes(ByteUtils.ISO_8859_1);
        int asciiCount = ascii.length + 1;
        int ucCount = text.length() + 1;
        int len = 8 + 4 + asciiCount + 4 + 4 + ucCount * 2 + 2 + 1 + 67;
        byte[] b = new byte[len];
        System.arraycopy(ByteUtils.fourccBytes(DESC), 0, b, 0, 4);
        ByteUtils.put32be(b, 8, asciiCount);
        System.arraycopy(ascii, 0, b, 12, ascii.length);
        int pos = 12 + asciiCount;
        ByteUtils.put32be(b, pos, 0);               // Unicode language code
        ByteUtils.put32be(b, pos + 4, ucCount);
        pos += 8;
        for (int i = 0; i < text.length(); i++) {
            ByteUtils.put16be(b, pos, text.charAt(i));
            pos += 2;
        }
        // terminating NUL (2 bytes), ScriptCode code (2) + count (1) + 67 bytes: all zero
        return b;
    }

    /** multiLocalizedUnicodeType with a single en-US record (UTF-16BE, no terminator). */
    static byte[] encodeMluc(String text) {
        int strLen = text.length() * 2;
        byte[] b = new byte[28 + strLen];
        System.arraycopy(ByteUtils.fourccBytes(MLUC), 0, b, 0, 4);
        ByteUtils.put32be(b, 8, 1);
        ByteUtils.put32be(b, 12, 12);
        b[16] = 'e';
        b[17] = 'n';
        b[18] = 'U';
        b[19] = 'S';
        ByteUtils.put32be(b, 20, strLen);
        ByteUtils.put32be(b, 24, 28);
        for (int i = 0; i < text.length(); i++) ByteUtils.put16be(b, 28 + i * 2, text.charAt(i));
        return b;
    }

    /** textType: NUL-terminated 7-bit ASCII. */
    static byte[] encodeTextType(String text) {
        byte[] ascii = toAscii(text).getBytes(ByteUtils.ISO_8859_1);
        byte[] b = new byte[8 + ascii.length + 1];
        System.arraycopy(ByteUtils.fourccBytes(TEXT), 0, b, 0, 4);
        System.arraycopy(ascii, 0, b, 8, ascii.length);
        return b;
    }

    // ------------------------------------------------------------------ helpers

    /** 7-bit printable ASCII version of {@code s} (other characters become '?'). */
    static String toAscii(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isHighSurrogate(c) && i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1))) {
                i++;
                sb.append('?');
            } else {
                sb.append(c >= 0x20 && c < 0x7F ? c : '?');
            }
        }
        return sb.toString();
    }

    private static String latin1UntilNul(byte[] p, int off, int len) {
        int end = off;
        int max = off + len;
        while (end < max && p[end] != 0) end++;
        return new String(p, off, end - off, ByteUtils.ISO_8859_1);
    }

    private static String utf16(byte[] p, int off, int byteLen) {
        int n = byteLen / 2;
        char[] c = new char[n];
        for (int i = 0; i < n; i++) c[i] = (char) ByteUtils.u16be(p, off + i * 2);
        return new String(c);
    }

    private static String utf16UntilNul(byte[] p, int off, int byteLen) {
        String s = utf16(p, off, byteLen);
        int nul = s.indexOf('\0');
        return nul >= 0 ? s.substring(0, nul) : s;
    }

    private static String stripNul(String s) {
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == '\0') end--;
        return s.substring(0, end);
    }
}
