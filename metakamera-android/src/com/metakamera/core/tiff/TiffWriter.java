package com.metakamera.core.tiff;

import com.metakamera.core.ByteUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Serializes complete TIFF structures from scratch (compacting fallback and createMinimal). */
final class TiffWriter {

    /** An entry to serialize: {@code data} holds the complete value in the target byte order. */
    static final class WEntry {
        final int tag;
        int type;
        long count;
        byte[] data;
        /** Output position of the 4-byte value field (set by {@link #writeIfd}). */
        int fieldPos = -1;
        /** Output position of the value (== fieldPos when inline). */
        int dataPos = -1;

        WEntry(int tag, int type, long count, byte[] data) {
            this.tag = tag;
            this.type = type;
            this.count = count;
            this.data = data;
        }
    }

    static final class WIfd {
        final List<WEntry> entries = new ArrayList<WEntry>();
        int pos = -1;
        int nextPos = -1;

        WEntry find(int tag) {
            for (int i = 0; i < entries.size(); i++) {
                if (entries.get(i).tag == tag) return entries.get(i);
            }
            return null;
        }

        void add(WEntry e) {
            entries.add(e);
        }
    }

    private static final Comparator<WEntry> BY_TAG = new Comparator<WEntry>() {
        @Override
        public int compare(WEntry x, WEntry y) {
            return x.tag < y.tag ? -1 : (x.tag == y.tag ? 0 : 1);
        }
    };

    final TiffBuf out;

    TiffWriter(boolean le, int capacity) {
        out = new TiffBuf(le, capacity);
        out.write(le ? new byte[]{'I', 'I'} : new byte[]{'M', 'M'});
        out.w16(42);
        out.w32(0); // IFD0 offset, patched by setIfd0()
    }

    void setIfd0(int pos) {
        out.put32(4, pos);
    }

    /** Writes the IFD (entries sorted by tag, next pointer 0) followed by its out-of-line values. */
    void writeIfd(WIfd ifd) {
        Collections.sort(ifd.entries, BY_TAG);
        ifd.pos = out.align2();
        out.w16(ifd.entries.size());
        for (int i = 0; i < ifd.entries.size(); i++) {
            WEntry e = ifd.entries.get(i);
            out.w16(e.tag);
            out.w16(e.type);
            out.w32(e.count);
            e.fieldPos = out.length();
            out.w32(0);
        }
        ifd.nextPos = out.length();
        out.w32(0);
        for (int i = 0; i < ifd.entries.size(); i++) {
            WEntry e = ifd.entries.get(i);
            if (e.data.length > 4) {
                int p = out.align2();
                out.write(e.data);
                out.put32(e.fieldPos, p);
                e.dataPos = p;
            } else {
                out.writeAt(e.fieldPos, e.data, 0, e.data.length);
                e.dataPos = e.fieldPos;
            }
        }
    }

    /** Appends raw data (word aligned) and returns its position. */
    int writeData(byte[] b, int off, int len) {
        int p = out.align2();
        out.write(b, off, len);
        return p;
    }

    void patch32(int pos, long v) {
        out.put32(pos, v);
    }

    int length() {
        return out.length();
    }

    byte[] toByteArray() {
        return out.toByteArray();
    }

    // ------------------------------------------------------------------ value encoders

    static byte[] ascii(String s) {
        byte[] b = s.getBytes(ByteUtils.UTF_8);
        byte[] r = new byte[b.length + 1];
        System.arraycopy(b, 0, r, 0, b.length);
        return r;
    }

    byte[] short1(int v) {
        byte[] b = new byte[2];
        if (out.le) ByteUtils.put16le(b, 0, v);
        else ByteUtils.put16be(b, 0, v);
        return b;
    }

    byte[] long1(long v) {
        byte[] b = new byte[4];
        if (out.le) ByteUtils.put32le(b, 0, v);
        else ByteUtils.put32be(b, 0, v);
        return b;
    }

    byte[] rational(long num, long den) {
        byte[] b = new byte[8];
        if (out.le) {
            ByteUtils.put32le(b, 0, num);
            ByteUtils.put32le(b, 4, den);
        } else {
            ByteUtils.put32be(b, 0, num);
            ByteUtils.put32be(b, 4, den);
        }
        return b;
    }
}
