package com.metakamera.core.png;

import com.metakamera.core.ByteUtils;

import java.io.ByteArrayOutputStream;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/** Codec for PNG textual chunks (tEXt, zTXt, iTXt) and zlib helpers. */
final class PngText {

    /** Maximum inflated size accepted for a metadata chunk (zip-bomb guard). */
    static final int MAX_INFLATE = 64 * 1024 * 1024;

    final String type;
    final byte[] keywordBytes;
    final String keyword;
    /** iTXt only. */
    int compressionFlag;
    int compressionMethod;
    byte[] language = new byte[0];
    byte[] translatedKeyword = new byte[0];
    /** Uncompressed text bytes (Latin-1 for tEXt/zTXt, UTF-8 for iTXt). */
    byte[] text;

    private PngText(String type, byte[] keywordBytes) {
        this.type = type;
        this.keywordBytes = keywordBytes;
        this.keyword = new String(keywordBytes, ByteUtils.ISO_8859_1);
    }

    boolean isUtf8() {
        return "iTXt".equals(type);
    }

    boolean isCompressed() {
        return "zTXt".equals(type) || ("iTXt".equals(type) && compressionFlag == 1);
    }

    /** Text as a Java string (Latin-1 or UTF-8). */
    String textString() {
        return new String(text, isUtf8() ? ByteUtils.UTF_8 : ByteUtils.ISO_8859_1);
    }

    /** Encodes a string for this chunk type (characters outside Latin-1 become '?' in tEXt/zTXt). */
    byte[] encode(String s) {
        if (isUtf8()) return s.getBytes(ByteUtils.UTF_8);
        byte[] b = new byte[s.length()];
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            b[i] = c <= 0xFF ? (byte) c : (byte) '?';
        }
        return b;
    }

    /** Length of the keyword (1..79 bytes before a NUL), or -1. */
    static int keywordLength(byte[] data) {
        int lim = Math.min(data.length, 80);
        for (int i = 0; i < lim; i++) {
            if (data[i] == 0) return i >= 1 ? i : -1;
        }
        return -1;
    }

    /** Only the keyword (cheap; no decompression), or null. */
    static String peekKeyword(byte[] data) {
        int k = keywordLength(data);
        return k < 0 ? null : new String(data, 0, k, ByteUtils.ISO_8859_1);
    }

    /** Parses a tEXt / zTXt / iTXt chunk body; null when malformed. */
    static PngText parse(String type, byte[] data) {
        int k = keywordLength(data);
        if (k < 0) return null;
        PngText t = new PngText(type, copy(data, 0, k));
        int p = k + 1;
        if ("tEXt".equals(type)) {
            t.text = copy(data, p, data.length);
            return t;
        }
        if ("zTXt".equals(type)) {
            if (p >= data.length || data[p] != 0) return null;
            t.compressionMethod = 0;
            t.text = inflate(data, p + 1, data.length - p - 1);
            return t.text == null ? null : t;
        }
        if ("iTXt".equals(type)) {
            if (p + 2 > data.length) return null;
            t.compressionFlag = data[p] & 0xFF;
            t.compressionMethod = data[p + 1] & 0xFF;
            if (t.compressionFlag > 1 || (t.compressionFlag == 1 && t.compressionMethod != 0)) return null;
            p += 2;
            int z = indexOf0(data, p);
            if (z < 0) return null;
            t.language = copy(data, p, z);
            p = z + 1;
            z = indexOf0(data, p);
            if (z < 0) return null;
            t.translatedKeyword = copy(data, p, z);
            p = z + 1;
            if (t.compressionFlag == 1) {
                t.text = inflate(data, p, data.length - p);
                if (t.text == null) return null;
            } else {
                t.text = copy(data, p, data.length);
            }
            return t;
        }
        return null;
    }

    /** Chunk body with {@code newText} (re-compressed when the original was compressed). */
    byte[] serialize(byte[] newText) {
        ByteArrayOutputStream bo = new ByteArrayOutputStream(newText.length + 64);
        bo.write(keywordBytes, 0, keywordBytes.length);
        bo.write(0);
        if ("tEXt".equals(type)) {
            bo.write(newText, 0, newText.length);
        } else if ("zTXt".equals(type)) {
            bo.write(0);
            byte[] z = deflate(newText);
            bo.write(z, 0, z.length);
        } else {
            bo.write(compressionFlag);
            bo.write(compressionMethod);
            bo.write(language, 0, language.length);
            bo.write(0);
            bo.write(translatedKeyword, 0, translatedKeyword.length);
            bo.write(0);
            byte[] body = compressionFlag == 1 ? deflate(newText) : newText;
            bo.write(body, 0, body.length);
        }
        return bo.toByteArray();
    }

    static int indexOf0(byte[] b, int from) {
        for (int i = from; i < b.length; i++) if (b[i] == 0) return i;
        return -1;
    }

    static byte[] copy(byte[] b, int from, int to) {
        byte[] r = new byte[to - from];
        System.arraycopy(b, from, r, 0, r.length);
        return r;
    }

    /** Inflates a zlib stream; null when it is corrupt, truncated or larger than MAX_INFLATE. */
    static byte[] inflate(byte[] b, int off, int len) {
        if (off < 0 || len < 0 || off > b.length || len > b.length - off) return null;
        Inflater inf = new Inflater();
        try {
            inf.setInput(b, off, len);
            ByteArrayOutputStream bo = new ByteArrayOutputStream(Math.max(64, Math.min(len * 4, 1 << 20)));
            byte[] buf = new byte[16 * 1024];
            int stalls = 0;
            while (!inf.finished()) {
                int n = inf.inflate(buf);
                if (n == 0) {
                    if (inf.finished()) break;
                    if (inf.needsInput() || inf.needsDictionary() || ++stalls > 4) return null;
                    continue;
                }
                stalls = 0;
                bo.write(buf, 0, n);
                if (bo.size() > MAX_INFLATE) return null;
            }
            return bo.toByteArray();
        } catch (DataFormatException e) {
            return null;
        } catch (RuntimeException e) {
            return null;
        } finally {
            inf.end();
        }
    }

    static byte[] deflate(byte[] data) {
        Deflater d = new Deflater(Deflater.BEST_COMPRESSION);
        try {
            d.setInput(data);
            d.finish();
            ByteArrayOutputStream bo = new ByteArrayOutputStream(Math.max(64, data.length / 2));
            byte[] buf = new byte[16 * 1024];
            while (!d.finished()) {
                int n = d.deflate(buf);
                bo.write(buf, 0, n);
            }
            return bo.toByteArray();
        } finally {
            d.end();
        }
    }

    // ------------------------------------------------------------------ ImageMagick raw profiles

    /** Decoded "Raw profile type xxx" text: "\n" name "\n" length "\n" hex... ; null when malformed. */
    static byte[] decodeRawProfile(byte[] text) {
        int p = 0;
        int n = text.length;
        while (p < n && (text[p] == '\n' || text[p] == '\r' || text[p] == ' ')) p++;
        // profile name line
        while (p < n && text[p] != '\n') p++;
        if (p >= n) return null;
        p++;
        while (p < n && (text[p] == ' ' || text[p] == '\t' || text[p] == '\r' || text[p] == '\n')) p++;
        long len = 0;
        int digits = 0;
        while (p < n && text[p] >= '0' && text[p] <= '9') {
            len = len * 10 + (text[p] - '0');
            if (len > MAX_INFLATE) return null;
            p++;
            digits++;
        }
        if (digits == 0) return null;
        if (len > (n - p) / 2) return null; // the hex text cannot hold that many bytes
        byte[] out = new byte[(int) len];
        int o = 0;
        int hi = -1;
        while (p < n && o < out.length) {
            int c = text[p++] & 0xFF;
            int v = Character.digit((char) c, 16);
            if (v < 0) {
                if (c == ' ' || c == '\n' || c == '\r' || c == '\t') continue;
                return null;
            }
            if (hi < 0) {
                hi = v;
            } else {
                out[o++] = (byte) ((hi << 4) | v);
                hi = -1;
            }
        }
        return o == out.length ? out : null;
    }

    /** Profile name of a raw profile text ("exif", "xmp", "icc"...), or null. */
    static String rawProfileName(byte[] text) {
        int p = 0;
        int n = text.length;
        while (p < n && (text[p] == '\n' || text[p] == '\r' || text[p] == ' ')) p++;
        int s = p;
        while (p < n && text[p] != '\n') p++;
        if (p >= n || p == s) return null;
        return new String(text, s, p - s, ByteUtils.ISO_8859_1).trim();
    }

    /** ImageMagick layout: "\n" name "\n" "%8d" then 36 bytes (72 hex digits) per line, final "\n". */
    static byte[] encodeRawProfile(String name, byte[] data) {
        StringBuilder sb = new StringBuilder(data.length * 2 + data.length / 36 + 32);
        sb.append('\n').append(name).append('\n');
        String l = Integer.toString(data.length);
        for (int i = l.length(); i < 8; i++) sb.append(' ');
        sb.append(l);
        final char[] hex = "0123456789abcdef".toCharArray();
        for (int i = 0; i < data.length; i++) {
            if (i % 36 == 0) sb.append('\n');
            sb.append(hex[(data[i] >> 4) & 0xF]).append(hex[data[i] & 0xF]);
        }
        sb.append('\n');
        return sb.toString().getBytes(ByteUtils.ISO_8859_1);
    }
}
