package com.metakamera.core.isobmff;

import com.metakamera.core.ByteUtils;
import com.metakamera.core.UnsupportedFormatException;

import java.io.ByteArrayOutputStream;

/**
 * Bounds-checked big-endian reader over a byte array window [pos, limit), plus a few writer
 * helpers used by the HEIF box serializers. Every read past the window throws an
 * {@link UnsupportedFormatException} (never a RuntimeException).
 */
final class HeifBuf {

    final byte[] b;
    int pos;
    final int limit;
    private final String what;

    HeifBuf(byte[] b, int start, int limit, String what) throws UnsupportedFormatException {
        if (b == null || start < 0 || limit < start || limit > b.length) throw corrupt(what);
        this.b = b;
        this.pos = start;
        this.limit = limit;
        this.what = what;
    }

    static UnsupportedFormatException corrupt(String what) {
        return new UnsupportedFormatException("HEIF yapısı bozuk (" + what + ")");
    }

    int remaining() {
        return limit - pos;
    }

    private void need(int n) throws UnsupportedFormatException {
        if (n < 0 || n > limit - pos) throw corrupt(what);
    }

    int u8() throws UnsupportedFormatException {
        need(1);
        return b[pos++] & 0xFF;
    }

    int u16() throws UnsupportedFormatException {
        need(2);
        int v = ByteUtils.u16be(b, pos);
        pos += 2;
        return v;
    }

    long u32() throws UnsupportedFormatException {
        need(4);
        long v = ByteUtils.u32be(b, pos);
        pos += 4;
        return v;
    }

    /** Unsigned big-endian integer of {@code size} bytes (0..8). Values above 2^63-1 are rejected. */
    long uN(int size) throws UnsupportedFormatException {
        if (size < 0 || size > 8) throw corrupt(what);
        need(size);
        long v = 0;
        for (int i = 0; i < size; i++) v = (v << 8) | (b[pos + i] & 0xFF);
        pos += size;
        if (v < 0) throw corrupt(what);
        return v;
    }

    String fourcc() throws UnsupportedFormatException {
        need(4);
        String s = ByteUtils.fourcc(b, pos);
        pos += 4;
        return s;
    }

    /** NUL-terminated UTF-8 string; a missing terminator at the end of the window is tolerated. */
    String cstring() {
        int start = pos;
        int p = pos;
        while (p < limit && b[p] != 0) p++;
        String s = new String(b, start, p - start, ByteUtils.UTF_8);
        pos = p < limit ? p + 1 : p;
        return s;
    }

    void skip(int n) throws UnsupportedFormatException {
        need(n);
        pos += n;
    }

    // ------------------------------------------------------------------ writing helpers

    static void w16(ByteArrayOutputStream o, int v) {
        o.write(v >>> 8);
        o.write(v);
    }

    static void w32(ByteArrayOutputStream o, long v) {
        o.write((int) (v >>> 24));
        o.write((int) (v >>> 16));
        o.write((int) (v >>> 8));
        o.write((int) v);
    }

    static void wN(ByteArrayOutputStream o, long v, int size) {
        for (int i = size - 1; i >= 0; i--) o.write((int) (v >>> (8 * i)));
    }

    /** True when {@code v} can be stored in an unsigned field of {@code size} bytes. */
    static boolean fits(long v, int size) {
        if (v < 0) return false;
        if (size >= 8) return true;
        if (size <= 0) return v == 0;
        return v < (1L << (8 * size));
    }

    /** Smallest standard ISO-BMFF field size (0, 4 or 8) able to hold {@code v}. */
    static int need(long v) {
        if (v == 0) return 0;
        return v <= 0xFFFFFFFFL ? 4 : 8;
    }

    /**
     * Builds a box: 32-bit size header (or a 64-bit largesize header when {@code large} is set or
     * the box does not fit 32 bits) followed by {@code payload}.
     */
    static byte[] box(String type, byte[] payload, boolean large) {
        long total = 8L + payload.length;
        if (!large && total > 0xFFFFFFFFL) large = true;
        ByteArrayOutputStream o = new ByteArrayOutputStream(payload.length + 16);
        if (large) {
            total += 8;
            w32(o, 1);
            o.write(ByteUtils.fourccBytes(type), 0, 4);
            wN(o, total, 8);
        } else {
            w32(o, total);
            o.write(ByteUtils.fourccBytes(type), 0, 4);
        }
        o.write(payload, 0, payload.length);
        return o.toByteArray();
    }

    /** Payload with a FullBox version/flags prefix. */
    static byte[] full(int version, int flags, byte[] rest) {
        byte[] r = new byte[4 + rest.length];
        r[0] = (byte) version;
        r[1] = (byte) (flags >>> 16);
        r[2] = (byte) (flags >>> 8);
        r[3] = (byte) flags;
        System.arraycopy(rest, 0, r, 4, rest.length);
        return r;
    }
}
