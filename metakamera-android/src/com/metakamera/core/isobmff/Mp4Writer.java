package com.metakamera.core.isobmff;

import com.metakamera.core.ByteUtils;

/** Growable big-endian byte buffer used to build boxes. */
final class Mp4Writer {

    private byte[] buf;
    private int len;

    Mp4Writer(int initialCapacity) {
        buf = new byte[Math.max(16, initialCapacity)];
    }

    Mp4Writer() {
        this(64);
    }

    private void ensure(int extra) {
        int need = len + extra;
        if (need > buf.length) {
            int cap = buf.length;
            while (cap < need) cap = cap < (1 << 29) ? cap * 2 : need;
            byte[] n = new byte[cap];
            System.arraycopy(buf, 0, n, 0, len);
            buf = n;
        }
    }

    void write(byte[] b, int off, int n) {
        ensure(n);
        System.arraycopy(b, off, buf, len, n);
        len += n;
    }

    void write(byte[] b) {
        write(b, 0, b.length);
    }

    void u8(int v) {
        ensure(1);
        buf[len++] = (byte) v;
    }

    void u16(int v) {
        ensure(2);
        ByteUtils.put16be(buf, len, v);
        len += 2;
    }

    void u32(long v) {
        ensure(4);
        ByteUtils.put32be(buf, len, v);
        len += 4;
    }

    void u64(long v) {
        ensure(8);
        ByteUtils.put64be(buf, len, v);
        len += 8;
    }

    void fourcc(String t) {
        byte[] b = t.getBytes(ByteUtils.ISO_8859_1);
        if (b.length != 4) {
            byte[] p = new byte[4];
            System.arraycopy(b, 0, p, 0, Math.min(4, b.length));
            b = p;
        }
        write(b, 0, 4);
    }

    byte[] toByteArray() {
        if (len == buf.length) return buf;
        byte[] r = new byte[len];
        System.arraycopy(buf, 0, r, 0, len);
        return r;
    }
}
