package com.metakamera.core.jpeg;

import com.metakamera.core.ByteSource;
import com.metakamera.core.ByteUtils;
import com.metakamera.core.ProgressListener;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Output plan made of verbatim source ranges and in-memory byte arrays.
 *
 * <p>Adjacent source ranges are merged so an unchanged file is written with a single streamed
 * copy. Source ranges are streamed with {@link ByteUtils#copyRange} (progress reported); only the
 * replaced metadata blocks live in memory. Shared by the JPEG, PNG and WebP editors.
 */
public final class SpliceWriter {

    private static final class Piece {
        long srcPos;
        long len;
        byte[] data;
    }

    private final List<Piece> pieces = new ArrayList<Piece>();
    private long length;

    /** Appends {@code len} bytes of the source starting at {@code pos} (no-op for len == 0). */
    public void copy(long pos, long len) {
        if (len <= 0) return;
        if (pos < 0) throw new IllegalArgumentException("negative position");
        if (!pieces.isEmpty()) {
            Piece last = pieces.get(pieces.size() - 1);
            if (last.data == null && last.srcPos + last.len == pos) {
                last.len += len;
                length += len;
                return;
            }
        }
        Piece p = new Piece();
        p.srcPos = pos;
        p.len = len;
        pieces.add(p);
        length += len;
    }

    /** Appends new bytes (the array is not copied; do not modify it afterwards). */
    public void add(byte[] data) {
        if (data == null || data.length == 0) return;
        Piece p = new Piece();
        p.data = data;
        p.len = data.length;
        pieces.add(p);
        length += data.length;
    }

    /** Total number of bytes {@link #writeTo} will produce. */
    public long length() {
        return length;
    }

    /** Writes the plan; progress is reported as bytes written / {@link #length()}. */
    public void writeTo(ByteSource src, OutputStream out, ProgressListener listener) throws IOException {
        long done = 0;
        for (int i = 0; i < pieces.size(); i++) {
            Piece p = pieces.get(i);
            if (p.data != null) {
                out.write(p.data, 0, p.data.length);
                done += p.data.length;
                if (listener != null) listener.onProgress(done, length);
            } else {
                ByteUtils.copyRange(src, p.srcPos, p.len, out, listener, done, length);
                done += p.len;
            }
        }
        if (listener != null && pieces.isEmpty()) listener.onProgress(0, 0);
    }
}
