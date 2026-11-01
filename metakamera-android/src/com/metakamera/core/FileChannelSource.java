package com.metakamera.core;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

/** {@link ByteSource} backed by a seekable {@link FileChannel} (positional reads, thread-unsafe). */
public final class FileChannelSource implements ByteSource {

    private final FileChannel channel;
    private final java.io.Closeable owner;
    private final long length;

    public FileChannelSource(FileChannel channel, java.io.Closeable owner) throws IOException {
        this.channel = channel;
        this.owner = owner;
        this.length = channel.size();
    }

    public static FileChannelSource open(java.io.File file) throws IOException {
        RandomAccessFile raf = new RandomAccessFile(file, "r");
        try {
            return new FileChannelSource(raf.getChannel(), raf);
        } catch (IOException e) {
            raf.close();
            throw e;
        }
    }

    @Override
    public long length() {
        return length;
    }

    @Override
    public int read(long position, byte[] buf, int off, int len) throws IOException {
        if (position >= length) return -1;
        if (len == 0) return 0;
        ByteBuffer bb = ByteBuffer.wrap(buf, off, len);
        int total = 0;
        while (bb.hasRemaining()) {
            int n = channel.read(bb, position + total);
            if (n < 0) break;
            if (n == 0 && position + total >= length) break;
            total += n;
        }
        return total == 0 ? -1 : total;
    }

    @Override
    public void close() throws IOException {
        try {
            channel.close();
        } finally {
            if (owner != null) owner.close();
        }
    }
}
