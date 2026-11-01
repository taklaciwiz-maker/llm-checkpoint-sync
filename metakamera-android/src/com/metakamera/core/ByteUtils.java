package com.metakamera.core;

import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.Charset;

/** Small byte/IO helpers shared by all format editors. */
public final class ByteUtils {

    public static final Charset US_ASCII = Charset.forName("US-ASCII");
    public static final Charset UTF_8 = Charset.forName("UTF-8");
    public static final Charset ISO_8859_1 = Charset.forName("ISO-8859-1");

    private static final int COPY_BUF = 256 * 1024;

    private ByteUtils() {
    }

    // ---------------------------------------------------------------- big endian

    public static int u8(byte[] b, int off) {
        return b[off] & 0xFF;
    }

    public static int u16be(byte[] b, int off) {
        return ((b[off] & 0xFF) << 8) | (b[off + 1] & 0xFF);
    }

    public static long u32be(byte[] b, int off) {
        return ((long) (b[off] & 0xFF) << 24) | ((b[off + 1] & 0xFF) << 16)
                | ((b[off + 2] & 0xFF) << 8) | (b[off + 3] & 0xFF);
    }

    public static long u64be(byte[] b, int off) {
        return (u32be(b, off) << 32) | u32be(b, off + 4);
    }

    public static void put16be(byte[] b, int off, int v) {
        b[off] = (byte) (v >>> 8);
        b[off + 1] = (byte) v;
    }

    public static void put32be(byte[] b, int off, long v) {
        b[off] = (byte) (v >>> 24);
        b[off + 1] = (byte) (v >>> 16);
        b[off + 2] = (byte) (v >>> 8);
        b[off + 3] = (byte) v;
    }

    public static void put64be(byte[] b, int off, long v) {
        put32be(b, off, v >>> 32);
        put32be(b, off + 4, v);
    }

    // ---------------------------------------------------------------- little endian

    public static int u16le(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8);
    }

    public static long u32le(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8)
                | ((b[off + 2] & 0xFF) << 16) | ((long) (b[off + 3] & 0xFF) << 24);
    }

    public static void put16le(byte[] b, int off, int v) {
        b[off] = (byte) v;
        b[off + 1] = (byte) (v >>> 8);
    }

    public static void put32le(byte[] b, int off, long v) {
        b[off] = (byte) v;
        b[off + 1] = (byte) (v >>> 8);
        b[off + 2] = (byte) (v >>> 16);
        b[off + 3] = (byte) (v >>> 24);
    }

    // ---------------------------------------------------------------- strings

    /** 4-character code at offset (ISO-8859-1, so '©' stays one char). */
    public static String fourcc(byte[] b, int off) {
        return new String(b, off, 4, ISO_8859_1);
    }

    /** Encodes a 4-character code (ISO-8859-1). */
    public static byte[] fourccBytes(String s) {
        byte[] r = s.getBytes(ISO_8859_1);
        if (r.length != 4) throw new IllegalArgumentException("fourcc must be 4 chars: " + s);
        return r;
    }

    public static boolean startsWith(byte[] b, int off, byte[] prefix) {
        if (off < 0 || off + prefix.length > b.length) return false;
        for (int i = 0; i < prefix.length; i++) {
            if (b[off + i] != prefix[i]) return false;
        }
        return true;
    }

    // ---------------------------------------------------------------- source IO

    /** Reads exactly {@code len} bytes at {@code pos} or throws EOFException. */
    public static void readFully(ByteSource src, long pos, byte[] buf, int off, int len) throws IOException {
        int done = 0;
        while (done < len) {
            int n = src.read(pos + done, buf, off + done, len - done);
            if (n <= 0) throw new EOFException("Unexpected end of file at " + (pos + done));
            done += n;
        }
    }

    public static byte[] readBytes(ByteSource src, long pos, int len) throws IOException {
        byte[] b = new byte[len];
        readFully(src, pos, b, 0, len);
        return b;
    }

    /**
     * Copies {@code len} bytes from {@code src} at {@code pos} to {@code out}.
     * Reports progress as (progressBase + copied) / progressTotal when a listener is given.
     */
    public static void copyRange(ByteSource src, long pos, long len, OutputStream out,
                                 ProgressListener listener, long progressBase, long progressTotal) throws IOException {
        byte[] buf = new byte[(int) Math.min(COPY_BUF, Math.max(1, len))];
        long done = 0;
        long lastReport = 0;
        while (done < len) {
            int want = (int) Math.min(buf.length, len - done);
            int n = src.read(pos + done, buf, 0, want);
            if (n <= 0) throw new EOFException("Unexpected end of file at " + (pos + done));
            out.write(buf, 0, n);
            done += n;
            if (listener != null && (done - lastReport >= 4L * 1024 * 1024 || done == len)) {
                lastReport = done;
                listener.onProgress(progressBase + done, progressTotal);
            }
        }
    }

    public static void copyRange(ByteSource src, long pos, long len, OutputStream out) throws IOException {
        copyRange(src, pos, len, out, null, 0, 0);
    }

    /** Reads the whole source into memory (only for small inputs!). */
    public static byte[] readAll(ByteSource src) throws IOException {
        long len = src.length();
        if (len > Integer.MAX_VALUE - 16) throw new IOException("File too large to load in memory");
        return readBytes(src, 0, (int) len);
    }

    public static String hex(byte[] b, int off, int len) {
        StringBuilder sb = new StringBuilder(len * 2);
        for (int i = 0; i < len; i++) {
            sb.append(Character.forDigit((b[off + i] >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b[off + i] & 0xF, 16));
        }
        return sb.toString();
    }
}
