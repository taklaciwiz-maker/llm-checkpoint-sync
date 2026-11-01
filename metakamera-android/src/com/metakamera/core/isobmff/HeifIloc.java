package com.metakamera.core.isobmff;

import com.metakamera.core.UnsupportedFormatException;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Item location box ('iloc', versions 0, 1 and 2): parsed model and serializer.
 *
 * <p>Serialization keeps the original version, flags and field sizes unless a value no longer
 * fits; field sizes then grow (to 4 or 8 bytes) and the version is raised to 2 when an item ID
 * needs 32 bits. The serialized size depends only on version, field sizes and entry counts, never
 * on the values, which lets the caller compute layouts before the final offsets are known.
 */
final class HeifIloc {

    static final class Extent {
        long index;
        long offset;
        long length;

        Extent(long index, long offset, long length) {
            this.index = index;
            this.offset = offset;
            this.length = length;
        }
    }

    static final class Item {
        long id;
        /** Raw 16-bit field of version 1/2 (12 reserved bits + construction_method). */
        int cmRaw;
        int dataRefIndex;
        long baseOffset;
        final List<Extent> extents = new ArrayList<Extent>();

        int constructionMethod() {
            return cmRaw & 0xF;
        }
    }

    int version;
    int flags;
    int offsetSize;
    int lengthSize;
    int baseOffsetSize;
    /** index_size for versions 1/2; the reserved nibble for version 0 (kept verbatim). */
    int indexSize;
    final List<Item> items = new ArrayList<Item>();

    static HeifIloc parse(byte[] b, int payloadStart, int end) throws UnsupportedFormatException {
        HeifBuf r = new HeifBuf(b, payloadStart, end, "iloc");
        HeifIloc l = new HeifIloc();
        l.version = r.u8();
        l.flags = (r.u8() << 16) | r.u16();
        if (l.version > 2) throw new UnsupportedFormatException("Desteklenmeyen HEIF iloc sürümü: " + l.version);
        int s1 = r.u8();
        int s2 = r.u8();
        l.offsetSize = s1 >>> 4;
        l.lengthSize = s1 & 0xF;
        l.baseOffsetSize = s2 >>> 4;
        l.indexSize = s2 & 0xF;
        if (l.offsetSize > 8 || l.lengthSize > 8 || l.baseOffsetSize > 8
                || (l.version > 0 && l.indexSize > 8)) {
            throw HeifBuf.corrupt("iloc alan boyutu");
        }
        long count = l.version < 2 ? r.u16() : r.u32();
        boolean hasCm = l.version == 1 || l.version == 2;
        int idx = hasCm ? l.indexSize : 0;
        // every item needs at least id + dri + extent_count bytes: reject absurd counts early
        int minItem = (l.version < 2 ? 2 : 4) + (hasCm ? 2 : 0) + 2 + l.baseOffsetSize + 2;
        if (count > r.remaining() / minItem) throw HeifBuf.corrupt("iloc öğe sayısı");
        for (long i = 0; i < count; i++) {
            Item it = new Item();
            it.id = l.version < 2 ? r.u16() : r.u32();
            if (hasCm) it.cmRaw = r.u16();
            it.dataRefIndex = r.u16();
            it.baseOffset = r.uN(l.baseOffsetSize);
            int ec = r.u16();
            int perExtent = idx + l.offsetSize + l.lengthSize;
            if (perExtent > 0 && ec > r.remaining() / perExtent) throw HeifBuf.corrupt("iloc kapsam sayısı");
            for (int e = 0; e < ec; e++) {
                long ei = idx > 0 ? r.uN(idx) : 0;
                long eo = r.uN(l.offsetSize);
                long el = r.uN(l.lengthSize);
                it.extents.add(new Extent(ei, eo, el));
            }
            l.items.add(it);
        }
        return l;
    }

    Item find(long id) {
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).id == id) return items.get(i);
        }
        return null;
    }

    /** Grows field sizes / version so that every current value fits. Returns true if anything grew. */
    boolean growToFit() {
        boolean grew = false;
        long maxId = 0;
        for (int i = 0; i < items.size(); i++) {
            Item it = items.get(i);
            if (it.id > maxId) maxId = it.id;
            if (!HeifBuf.fits(it.baseOffset, baseOffsetSize)) {
                baseOffsetSize = Math.max(baseOffsetSize, HeifBuf.need(it.baseOffset));
                grew = true;
            }
            for (int e = 0; e < it.extents.size(); e++) {
                Extent x = it.extents.get(e);
                if (!HeifBuf.fits(x.offset, offsetSize)) {
                    offsetSize = Math.max(offsetSize, HeifBuf.need(x.offset));
                    grew = true;
                }
                if (!HeifBuf.fits(x.length, lengthSize)) {
                    lengthSize = Math.max(lengthSize, HeifBuf.need(x.length));
                    grew = true;
                }
            }
        }
        if (maxId > 0xFFFF && version < 2) {
            if (version == 0) indexSize = 0; // reserved nibble becomes index_size
            version = 2;
            grew = true;
        }
        return grew;
    }

    /** Serialized size of the whole box (header included); independent of the values. */
    int boxSize() {
        return serialize().length;
    }

    byte[] serialize() {
        ByteArrayOutputStream o = new ByteArrayOutputStream(64 + items.size() * 24);
        o.write(version);
        o.write(flags >>> 16);
        HeifBuf.w16(o, flags);
        o.write((offsetSize << 4) | lengthSize);
        o.write((baseOffsetSize << 4) | indexSize);
        if (version < 2) HeifBuf.w16(o, items.size());
        else HeifBuf.w32(o, items.size());
        boolean hasCm = version == 1 || version == 2;
        int idx = hasCm ? indexSize : 0;
        for (int i = 0; i < items.size(); i++) {
            Item it = items.get(i);
            if (version < 2) HeifBuf.w16(o, (int) it.id);
            else HeifBuf.w32(o, it.id);
            if (hasCm) HeifBuf.w16(o, it.cmRaw);
            HeifBuf.w16(o, it.dataRefIndex);
            HeifBuf.wN(o, it.baseOffset, baseOffsetSize);
            HeifBuf.w16(o, it.extents.size());
            for (int e = 0; e < it.extents.size(); e++) {
                Extent x = it.extents.get(e);
                if (idx > 0) HeifBuf.wN(o, x.index, idx);
                HeifBuf.wN(o, x.offset, offsetSize);
                HeifBuf.wN(o, x.length, lengthSize);
            }
        }
        return HeifBuf.box("iloc", o.toByteArray(), false);
    }
}
