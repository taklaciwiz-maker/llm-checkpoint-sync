package com.metakamera.core;

/** In-memory {@link ByteSource}. */
public final class ByteArraySource implements ByteSource {

    private final byte[] data;

    public ByteArraySource(byte[] data) {
        this.data = data;
    }

    @Override
    public long length() {
        return data.length;
    }

    @Override
    public int read(long position, byte[] buf, int off, int len) {
        if (position >= data.length) return -1;
        int n = (int) Math.min(len, data.length - position);
        System.arraycopy(data, (int) position, buf, off, n);
        return n;
    }

    @Override
    public void close() {
    }
}
