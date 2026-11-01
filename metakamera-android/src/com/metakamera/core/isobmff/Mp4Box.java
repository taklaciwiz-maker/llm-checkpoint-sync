package com.metakamera.core.isobmff;

import com.metakamera.core.ByteUtils;
import com.metakamera.core.UnsupportedFormatException;

import java.util.ArrayList;
import java.util.List;

/**
 * In-memory ISO-BMFF / QuickTime box tree node used by {@link Mp4Editor}.
 *
 * A node either keeps its original bytes (a slice of the loaded 'moov' buffer) or carries
 * replacement content. Untouched nodes are re-serialized byte-for-byte from the original slice,
 * so only the boxes on the path to an edit are rebuilt.
 *
 * Leaves keep their payload as raw bytes. Containers ("expanded" nodes) hold
 * {@code prefix} (e.g. FullBox version/flags of a 'meta'), {@code children} and {@code trailing}
 * (e.g. the 32-bit zero terminator QuickTime allows at the end of 'udta').
 */
final class Mp4Box {

    static final byte[] EMPTY = new byte[0];

    /** 4-char type (ISO-8859-1, so '©mak' and binary ilst indices round-trip). */
    String type;

    /** Original bytes (null for boxes created by the editor). */
    final byte[] src;
    final int srcOff;
    final int srcLen;
    final int headerSize;
    final boolean largeSize;
    final boolean sizeToEnd;

    /** Leaf: replacement payload (null = original payload). */
    byte[] payload;
    /** Container: child boxes (null = leaf / not expanded). */
    List<Mp4Box> children;
    byte[] prefix = EMPTY;
    byte[] trailing = EMPTY;

    /** This node's own header/payload/child list changed. */
    boolean modified;

    private Mp4Box(String type, byte[] src, int srcOff, int srcLen, int headerSize, boolean largeSize,
                   boolean sizeToEnd) {
        this.type = type;
        this.src = src;
        this.srcOff = srcOff;
        this.srcLen = srcLen;
        this.headerSize = headerSize;
        this.largeSize = largeSize;
        this.sizeToEnd = sizeToEnd;
    }

    /** Wraps a box located in {@code buf} (header {@code h}, offsets relative to buf). */
    static Mp4Box of(byte[] buf, BoxHeader h) {
        return new Mp4Box(h.type, buf, (int) h.offset, (int) h.size, h.headerSize, h.largeSize, h.sizeToEnd);
    }

    /** Parses the whole buffer as a single box. */
    static Mp4Box parseRoot(byte[] buf) throws UnsupportedFormatException {
        BoxHeader h = BoxHeader.read(buf, 0, buf.length);
        if (h == null || h.size != buf.length) throw new UnsupportedFormatException("Bozuk kutu");
        return of(buf, h);
    }

    /** New leaf box. */
    static Mp4Box newLeaf(String type, byte[] payload) {
        Mp4Box b = new Mp4Box(type, null, 0, 0, 8, false, false);
        b.payload = payload;
        b.modified = true;
        return b;
    }

    /** New (empty) container box. */
    static Mp4Box newContainer(String type, byte[] prefix) {
        Mp4Box b = new Mp4Box(type, null, 0, 0, 8, false, false);
        b.children = new ArrayList<Mp4Box>();
        b.prefix = prefix == null ? EMPTY : prefix;
        b.modified = true;
        return b;
    }

    boolean isContainer() {
        return children != null;
    }

    int origPayloadOffset() {
        return srcOff + headerSize;
    }

    int origPayloadLength() {
        return srcLen - headerSize;
    }

    /** Current payload of a leaf (copy-free when unchanged is not guaranteed: returns a new array). */
    byte[] payloadBytes() {
        if (payload != null) return payload;
        if (src == null) return EMPTY;
        byte[] p = new byte[origPayloadLength()];
        System.arraycopy(src, origPayloadOffset(), p, 0, p.length);
        return p;
    }

    void setPayload(byte[] p) {
        payload = p;
        modified = true;
    }

    /** Type as a big-endian uint32 (ilst item index in 'mdta' metadata). */
    long typeInt() {
        byte[] t = type.getBytes(ByteUtils.ISO_8859_1);
        if (t.length != 4) return -1;
        return ByteUtils.u32be(t, 0);
    }

    static String typeFromInt(long v) {
        byte[] t = new byte[4];
        ByteUtils.put32be(t, 0, v);
        return new String(t, ByteUtils.ISO_8859_1);
    }

    // ------------------------------------------------------------------ expansion

    /**
     * Turns this leaf into a container by parsing its payload (after {@code prefixLen} bytes) as
     * child boxes. Fewer than 8 remaining bytes, or a run of zero bytes, at the end become
     * {@code trailing}. Throws (leaving the node untouched) when a child header is invalid.
     */
    void expand(int prefixLen) throws UnsupportedFormatException {
        if (children != null) return;
        if (src == null || payload != null) throw new UnsupportedFormatException("Kutu genişletilemedi");
        int start = origPayloadOffset();
        int end = srcOff + srcLen;
        if (prefixLen < 0 || start + prefixLen > end) throw new UnsupportedFormatException("Bozuk kutu: " + type);
        List<Mp4Box> list = new ArrayList<Mp4Box>();
        int pos = start + prefixLen;
        while (pos < end) {
            if (end - pos < 8 || allZero(src, pos, end)) break;
            BoxHeader h = BoxHeader.read(src, pos, end);
            if (h == null) break;
            list.add(of(src, h));
            pos = (int) h.end();
        }
        byte[] pre = new byte[prefixLen];
        System.arraycopy(src, start, pre, 0, prefixLen);
        byte[] tr = new byte[end - pos];
        System.arraycopy(src, pos, tr, 0, tr.length);
        prefix = pre;
        trailing = tr;
        children = list;
    }

    /** {@link #expand(int)} that returns false instead of throwing. */
    boolean tryExpand(int prefixLen) {
        try {
            expand(prefixLen);
            return true;
        } catch (UnsupportedFormatException e) {
            return false;
        }
    }

    private static boolean allZero(byte[] b, int from, int to) {
        for (int i = from; i < to; i++) {
            if (b[i] != 0) return false;
        }
        return true;
    }

    // ------------------------------------------------------------------ tree helpers

    Mp4Box child(String t) {
        if (children == null) return null;
        for (Mp4Box c : children) {
            if (c.type.equals(t)) return c;
        }
        return null;
    }

    List<Mp4Box> childrenOf(String t) {
        List<Mp4Box> r = new ArrayList<Mp4Box>();
        if (children == null) return r;
        for (Mp4Box c : children) {
            if (c.type.equals(t)) r.add(c);
        }
        return r;
    }

    void addChild(Mp4Box c) {
        children.add(c);
        modified = true;
    }

    void addChild(int index, Mp4Box c) {
        children.add(index, c);
        modified = true;
    }

    void removeChild(Mp4Box c) {
        if (children.remove(c)) modified = true;
    }

    // ------------------------------------------------------------------ serialization

    boolean isDirty() {
        if (src == null || modified) return true;
        if (children != null) {
            for (Mp4Box c : children) {
                if (c.isDirty()) return true;
            }
        }
        return false;
    }

    /** Serialized size. {@code fresh} forces a rebuilt header (needed for size-0 boxes in a rebuilt parent). */
    int size(boolean fresh) {
        if (!fresh && !isDirty()) return srcLen;
        int c = contentSize();
        return headerLen() + c;
    }

    int size() {
        return size(false);
    }

    private int contentSize() {
        if (children == null) return payload != null ? payload.length : (src == null ? 0 : origPayloadLength());
        long s = (long) prefix.length + trailing.length;
        for (Mp4Box c : children) s += c.size(c.sizeToEnd);
        if (s > Integer.MAX_VALUE - 64) throw new IllegalStateException("box too large");
        return (int) s;
    }

    private int baseHeaderLen() {
        return largeSize ? 16 : 8;
    }

    private int headerLen() {
        int extra = src != null ? headerSize - baseHeaderLen() : 0;
        return baseHeaderLen() + Math.max(0, extra);
    }

    byte[] toBytes() {
        Mp4Writer w = new Mp4Writer(size());
        write(w, false);
        return w.toByteArray();
    }

    void write(Mp4Writer w, boolean fresh) {
        if (!fresh && !isDirty()) {
            w.write(src, srcOff, srcLen);
            return;
        }
        int content = contentSize();
        int total = headerLen() + content;
        if (largeSize) {
            w.u32(1);
            w.fourcc(type);
            w.u64(total);
        } else {
            w.u32(total);
            w.fourcc(type);
        }
        int extra = headerLen() - baseHeaderLen();
        if (extra > 0) w.write(src, srcOff + baseHeaderLen(), extra);
        if (children == null) {
            if (payload != null) w.write(payload, 0, payload.length);
            else if (src != null) w.write(src, origPayloadOffset(), origPayloadLength());
            return;
        }
        w.write(prefix, 0, prefix.length);
        for (Mp4Box c : children) c.write(w, c.sizeToEnd);
        w.write(trailing, 0, trailing.length);
    }

    @Override
    public String toString() {
        return "'" + type + "'" + (children != null ? children.toString() : "");
    }
}
