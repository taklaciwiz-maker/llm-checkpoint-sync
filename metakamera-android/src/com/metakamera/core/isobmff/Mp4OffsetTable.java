package com.metakamera.core.isobmff;

import com.metakamera.core.ByteUtils;
import com.metakamera.core.UnsupportedFormatException;

/**
 * Absolute file offsets stored inside 'moov' that must follow the media data when 'moov' changes
 * size: 'stco' (32-bit chunk offsets), 'co64' (64-bit chunk offsets) and 'saio' in a sample table
 * (sample auxiliary information offsets, absolute outside movie fragments).
 *
 * Offsets are always shifted from the ORIGINAL values, so {@link #apply} can be called repeatedly
 * while the final 'moov' size settles (32-bit tables may have to grow to 64-bit).
 */
final class Mp4OffsetTable {

    static final long MAX_U32 = 0xFFFFFFFFL;

    private static final int STCO = 0;
    private static final int CO64 = 1;
    private static final int SAIO = 2;

    final Mp4Box box;
    private final int kind;
    /** Original payload. */
    private final byte[] orig;
    /** saio: offset of aux_info_type (when flags & 1), else -1. */
    private final int auxOff;
    private final int entriesOff;
    private final int count;
    private final boolean origWide;
    /** Current entry width: 64-bit. */
    private boolean wide;

    private Mp4OffsetTable(Mp4Box box, int kind, byte[] orig, int auxOff, int entriesOff, int count, boolean wide) {
        this.box = box;
        this.kind = kind;
        this.orig = orig;
        this.auxOff = auxOff;
        this.entriesOff = entriesOff;
        this.count = count;
        this.origWide = wide;
        this.wide = wide;
    }

    static boolean isOffsetBox(String type) {
        return "stco".equals(type) || "co64".equals(type) || "saio".equals(type);
    }

    /** Parses an 'stco' / 'co64' / 'saio' leaf. Throws when the table is inconsistent. */
    static Mp4OffsetTable parse(Mp4Box b) throws UnsupportedFormatException {
        byte[] p = b.payloadBytes();
        int kind = "stco".equals(b.type) ? STCO : "co64".equals(b.type) ? CO64 : SAIO;
        if (p.length < 8) throw new UnsupportedFormatException("Bozuk '" + b.type + "' tablosu");
        int pos = 4;
        int auxOff = -1;
        boolean wide;
        if (kind == SAIO) {
            int version = p[0] & 0xFF;
            int flags = (int) (ByteUtils.u32be(p, 0) & 0xFFFFFF);
            if (version > 1) throw new UnsupportedFormatException("Bilinmeyen 'saio' sürümü");
            if ((flags & 1) != 0) {
                auxOff = pos;
                pos += 8;
            }
            wide = version == 1;
        } else {
            wide = kind == CO64;
        }
        if (pos + 4 > p.length) throw new UnsupportedFormatException("Bozuk '" + b.type + "' tablosu");
        long count = ByteUtils.u32be(p, pos);
        pos += 4;
        int w = wide ? 8 : 4;
        if (count > (p.length - pos) / w) throw new UnsupportedFormatException("Bozuk '" + b.type + "' tablosu");
        return new Mp4OffsetTable(b, kind, p, auxOff, pos, (int) count, wide);
    }

    private long original(int i) {
        return origWide ? ByteUtils.u64be(orig, entriesOff + i * 8) : ByteUtils.u32be(orig, entriesOff + i * 4);
    }

    /** True if at least one offset points at or after {@code threshold}. */
    boolean affected(long threshold) {
        for (int i = 0; i < count; i++) {
            if (original(i) >= threshold) return true;
        }
        return false;
    }

    /** True if a shifted value no longer fits the current 32-bit entries. */
    boolean needsWiden(long threshold, long delta) {
        if (wide) return false;
        for (int i = 0; i < count; i++) {
            long o = original(i);
            if (o >= threshold && (o + delta > MAX_U32 || o + delta < 0)) return true;
        }
        return false;
    }

    /** Switches to 64-bit entries ('stco' becomes 'co64', 'saio' version 1). */
    void widen() {
        wide = true;
    }

    boolean wasWidened() {
        return wide && !origWide;
    }

    /** Rewrites the box with every offset {@code >= threshold} shifted by {@code delta}. */
    void apply(long threshold, long delta) {
        int origEntryEnd = entriesOff + count * (origWide ? 8 : 4);
        int extra = orig.length - origEntryEnd;
        Mp4Writer out = new Mp4Writer(entriesOff + count * 8 + extra);
        if (kind == SAIO) {
            out.u8(wide ? 1 : orig[0] & 0xFF);
            out.write(orig, 1, 3);
            if (auxOff >= 0) out.write(orig, auxOff, 8);
        } else if (wide && kind == STCO) {
            out.u32(0); // co64 version/flags
        } else {
            out.write(orig, 0, 4);
        }
        out.u32(count);
        for (int i = 0; i < count; i++) {
            long o = original(i);
            long v = o >= threshold ? o + delta : o;
            if (wide) out.u64(v);
            else out.u32(v);
        }
        out.write(orig, origEntryEnd, extra);
        if (kind == STCO && wide) box.type = "co64";
        box.setPayload(out.toByteArray());
    }
}
