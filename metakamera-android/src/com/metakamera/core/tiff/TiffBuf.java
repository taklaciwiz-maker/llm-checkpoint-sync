package com.metakamera.core.tiff;

import java.util.Arrays;

/** Growable byte buffer with TIFF byte-order aware helpers. */
final class TiffBuf {

    final boolean le;
    private byte[] a;
    private int len;

    TiffBuf(boolean le, int capacity) {
        this.le = le;
        this.a = new byte[Math.max(64, capacity)];
    }

    /** Starts with a copy of {@code init}. */
    TiffBuf(boolean le, byte[] init) {
        this.le = le;
        this.a = new byte[init.length + 1024];
        System.arraycopy(init, 0, a, 0, init.length);
        this.len = init.length;
    }

    int length() {
        return len;
    }

    private void ensure(int extra) {
        long need = (long) len + extra;
        if (need > Integer.MAX_VALUE - 64) throw new IllegalStateException("TIFF too large");
        if (need > a.length) {
            long cap = Math.max(need, (long) a.length * 2);
            if (cap > Integer.MAX_VALUE - 64) cap = Integer.MAX_VALUE - 64;
            a = Arrays.copyOf(a, (int) cap);
        }
    }

    /** Pads with one zero byte if the length is odd (TIFF word alignment); returns the new length. */
    int align2() {
        if ((len & 1) != 0) {
            ensure(1);
            a[len++] = 0;
        }
        return len;
    }

    /** Appends and returns the position of the first appended byte. */
    int write(byte[] b, int off, int n) {
        ensure(n);
        System.arraycopy(b, off, a, len, n);
        int p = len;
        len += n;
        return p;
    }

    int write(byte[] b) {
        return write(b, 0, b.length);
    }

    void w16(int v) {
        ensure(2);
        put16(len, v);
        len += 2;
    }

    void w32(long v) {
        ensure(4);
        put32(len, v);
        len += 4;
    }

    void put16(int pos, int v) {
        if (le) {
            a[pos] = (byte) v;
            a[pos + 1] = (byte) (v >>> 8);
        } else {
            a[pos] = (byte) (v >>> 8);
            a[pos + 1] = (byte) v;
        }
    }

    void put32(int pos, long v) {
        if (le) {
            a[pos] = (byte) v;
            a[pos + 1] = (byte) (v >>> 8);
            a[pos + 2] = (byte) (v >>> 16);
            a[pos + 3] = (byte) (v >>> 24);
        } else {
            a[pos] = (byte) (v >>> 24);
            a[pos + 1] = (byte) (v >>> 16);
            a[pos + 2] = (byte) (v >>> 8);
            a[pos + 3] = (byte) v;
        }
    }

    void writeAt(int pos, byte[] b, int off, int n) {
        System.arraycopy(b, off, a, pos, n);
    }

    void zero(int from, int to) {
        if (from < 0) from = 0;
        if (to > len) to = len;
        if (from < to) Arrays.fill(a, from, to, (byte) 0);
    }

    byte[] toByteArray() {
        return Arrays.copyOf(a, len);
    }
}
