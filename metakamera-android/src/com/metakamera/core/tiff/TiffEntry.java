package com.metakamera.core.tiff;

/** One 12-byte IFD entry (parsed from the source, or created by an edit). */
final class TiffEntry {

    int tag;
    int type;
    long count;
    /** The raw 4-byte value/offset field, in the file's byte order. */
    final byte[] field = new byte[4];

    /** Position of the 12-byte entry in the source, -1 for a new entry. */
    int entryPos = -1;
    /** count * typeSize as found in the source; -1 when the type is unknown. */
    long byteLen = -1;
    /** Absolute position of the source value (inline: entryPos + 8); -1 when unknown or out of range. */
    int valuePos = -1;
    /** Source value is stored inside the 4-byte field. */
    boolean inline;

    // ------------------------------------------------------------- edit state

    /** Complete new value bytes (in file byte order) to be stored; null = keep the source value. */
    byte[] newValue;
    /** The 12 bytes of the entry differ from the source (must be re-written). */
    boolean dirty;
    /** Where an out-of-line new value was placed by the append commit (-1 = inline / not placed). */
    int outValuePos = -1;
    int outValueLen;

    /** True when the source value lives outside the entry and lies inside the TIFF. */
    boolean hasOutOfLineSourceValue() {
        return !inline && valuePos >= 0 && byteLen > 4;
    }

    static TiffEntry create(int tag, int type, long count, byte[] value) {
        TiffEntry e = new TiffEntry();
        e.tag = tag;
        e.type = type;
        e.count = count;
        e.newValue = value;
        e.dirty = true;
        return e;
    }
}
