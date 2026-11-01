package com.metakamera.core.isobmff;

import com.metakamera.core.ByteSource;
import com.metakamera.core.ByteUtils;
import com.metakamera.core.UnsupportedFormatException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Header of one ISO-BMFF / QuickTime box (atom), located in a {@link ByteSource} or byte array.
 * Shared foundation for {@code HeifEditor} and {@code Mp4Editor}.
 */
public final class BoxHeader {

    /** Absolute offset of the first header byte (size field). */
    public final long offset;
    /** Total size including header. */
    public final long size;
    /** 8, 16 (largesize) — plus 16 more for 'uuid' boxes (the usertype is counted in the header). */
    public final int headerSize;
    /** 4-char type, ISO-8859-1 decoded (so '©mak' works). */
    public final String type;
    /** True when the box used the 64-bit largesize field. */
    public final boolean largeSize;
    /** True when the on-disk size field was 0 ("extends to end of file/parent"). */
    public final boolean sizeToEnd;

    public BoxHeader(long offset, long size, int headerSize, String type, boolean largeSize, boolean sizeToEnd) {
        this.offset = offset;
        this.size = size;
        this.headerSize = headerSize;
        this.type = type;
        this.largeSize = largeSize;
        this.sizeToEnd = sizeToEnd;
    }

    public long end() {
        return offset + size;
    }

    public long payloadOffset() {
        return offset + headerSize;
    }

    public long payloadSize() {
        return size - headerSize;
    }

    @Override
    public String toString() {
        return "'" + type + "'@" + offset + "+" + size;
    }

    /**
     * Reads a box header at {@code pos} from a source. {@code limit} is the end of the enclosing
     * container (file length for top level). Returns null if fewer than 8 bytes remain.
     */
    public static BoxHeader read(ByteSource src, long pos, long limit) throws IOException {
        if (limit - pos < 8) return null;
        byte[] h = new byte[16];
        ByteUtils.readFully(src, pos, h, 0, 8);
        long size = ByteUtils.u32be(h, 0);
        String type = ByteUtils.fourcc(h, 4);
        int hs = 8;
        boolean large = false;
        boolean toEnd = false;
        if (size == 1) {
            if (limit - pos < 16) throw new UnsupportedFormatException("Bozuk kutu başlığı (" + type + ")");
            ByteUtils.readFully(src, pos + 8, h, 8, 8);
            size = ByteUtils.u64be(h, 8);
            hs = 16;
            large = true;
        } else if (size == 0) {
            size = limit - pos;
            toEnd = true;
        }
        if ("uuid".equals(type)) hs += 16;
        if (size < hs || size > limit - pos) {
            throw new UnsupportedFormatException("Bozuk kutu boyutu: '" + type + "' @" + pos);
        }
        return new BoxHeader(pos, size, hs, type, large, toEnd);
    }

    /** Same as {@link #read(ByteSource, long, long)} but for an in-memory buffer (offsets relative to buf). */
    public static BoxHeader read(byte[] buf, int pos, int limit) throws UnsupportedFormatException {
        if (limit - pos < 8) return null;
        long size = ByteUtils.u32be(buf, pos);
        String type = ByteUtils.fourcc(buf, pos + 4);
        int hs = 8;
        boolean large = false;
        boolean toEnd = false;
        if (size == 1) {
            if (limit - pos < 16) throw new UnsupportedFormatException("Bozuk kutu başlığı (" + type + ")");
            size = ByteUtils.u64be(buf, pos + 8);
            hs = 16;
            large = true;
        } else if (size == 0) {
            size = limit - pos;
            toEnd = true;
        }
        if ("uuid".equals(type)) hs += 16;
        if (size < hs || size > limit - pos) {
            throw new UnsupportedFormatException("Bozuk kutu boyutu: '" + type + "' @" + pos);
        }
        return new BoxHeader(pos, size, hs, type, large, toEnd);
    }

    /** Lists the top-level boxes of a file. */
    public static List<BoxHeader> topLevel(ByteSource src) throws IOException {
        List<BoxHeader> list = new ArrayList<BoxHeader>();
        long len = src.length();
        long pos = 0;
        while (pos < len) {
            BoxHeader b = read(src, pos, len);
            if (b == null) break; // trailing garbage < 8 bytes: callers copy it verbatim
            list.add(b);
            pos = b.end();
        }
        return list;
    }

    /** Lists child boxes inside [start, end) of an in-memory buffer. */
    public static List<BoxHeader> children(byte[] buf, int start, int end) throws UnsupportedFormatException {
        List<BoxHeader> list = new ArrayList<BoxHeader>();
        int pos = start;
        while (pos + 8 <= end) {
            BoxHeader b = read(buf, pos, end);
            if (b == null) break;
            list.add(b);
            pos = (int) b.end();
        }
        return list;
    }
}
