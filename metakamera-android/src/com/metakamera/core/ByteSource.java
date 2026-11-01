package com.metakamera.core;

import java.io.Closeable;
import java.io.IOException;

/**
 * Random-access, read-only view of an input file.
 *
 * Implementations: {@link FileChannelSource} (files, Android ParcelFileDescriptors)
 * and {@link ByteArraySource} (in-memory, mostly for tests).
 */
public interface ByteSource extends Closeable {

    /** Total length in bytes. */
    long length() throws IOException;

    /**
     * Reads up to {@code len} bytes starting at absolute {@code position}.
     *
     * @return number of bytes read, or -1 if {@code position >= length()}
     */
    int read(long position, byte[] buf, int off, int len) throws IOException;
}
