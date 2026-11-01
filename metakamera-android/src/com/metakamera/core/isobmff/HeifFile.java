package com.metakamera.core.isobmff;

import com.metakamera.core.ByteSource;
import com.metakamera.core.ByteUtils;
import com.metakamera.core.UnsupportedFormatException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Top-level layout of a HEIF/AVIF still-image file plus its parsed 'meta' box, and helpers that
 * locate / read item data through 'iloc'.
 */
final class HeifFile {

    /** Largest 'meta' box loaded into memory. */
    static final int META_MAX = 64 << 20;
    /** Largest metadata item (Exif / XMP) loaded into memory. */
    static final int ITEM_MAX = 32 << 20;

    final ByteSource src;
    final long len;
    /**
     * Parseable top-level boxes, in file order. Bytes after the last one (an unparseable trailer,
     * usually absent) are treated as opaque data and copied verbatim.
     */
    final List<BoxHeader> top = new ArrayList<BoxHeader>();
    BoxHeader metaBox;
    HeifMeta meta;

    private HeifFile(ByteSource src, long len) {
        this.src = src;
        this.len = len;
    }

    static HeifFile open(ByteSource src) throws IOException {
        HeifFile f = new HeifFile(src, src.length());
        long pos = 0;
        while (f.len - pos >= 8) {
            BoxHeader b;
            try {
                b = BoxHeader.read(src, pos, f.len);
            } catch (UnsupportedFormatException e) {
                break; // unparseable rest: kept verbatim as a trailer
            }
            if (b == null || b.size < b.headerSize || b.size > f.len - pos) break;
            f.top.add(b);
            pos += b.size;
        }
        for (int i = 0; i < f.top.size(); i++) {
            BoxHeader b = f.top.get(i);
            if ("moov".equals(b.type)) {
                throw new UnsupportedFormatException("HEIF görüntü dizileri (moov içeren dosyalar) desteklenmiyor");
            }
            if ("meta".equals(b.type)) {
                // a second file-level meta would carry its own (unshifted) item offsets
                if (f.metaBox != null) throw new UnsupportedFormatException("HEIF dosyasında birden fazla meta kutusu var");
                f.metaBox = b;
            }
        }
        if (f.metaBox == null) throw new UnsupportedFormatException("HEIF dosyasında meta kutusu bulunamadı");
        if (f.metaBox.size > META_MAX) throw new UnsupportedFormatException("HEIF meta kutusu çok büyük");
        byte[] raw = ByteUtils.readBytes(src, f.metaBox.offset, (int) f.metaBox.size);
        if (f.metaBox.sizeToEnd) {
            // make the in-memory copy self-describing (the rewritten meta always gets an explicit size)
            ByteUtils.put32be(raw, 0, raw.length);
        }
        f.meta = HeifMeta.parse(raw);
        return f;
    }

    long metaStart() {
        return metaBox.offset;
    }

    long metaEnd() {
        return metaBox.end();
    }

    /** Top-level box containing absolute position {@code p}, or null. */
    BoxHeader topBoxAt(long p) {
        for (int i = 0; i < top.size(); i++) {
            BoxHeader b = top.get(i);
            if (p >= b.offset && p < b.end()) return b;
        }
        return null;
    }

    /**
     * Data ranges of an item: absolute file ranges for construction method 0, idat-relative ranges
     * for method 1; {start, end} pairs with extent_length 0 resolved ("to the end of the containing
     * box / idat"). Returns null when the item cannot be located inside this file.
     */
    long[][] ranges(HeifIloc.Item it) {
        int cm = it.constructionMethod();
        if (it.dataRefIndex != 0 || (cm != 0 && cm != 1)) return null;
        long limit;
        if (cm == 1) {
            int[] ir = meta.idatRange();
            if (ir == null) return null;
            limit = ir[1] - ir[0];
        } else {
            limit = len;
        }
        long[][] r = new long[it.extents.size()][];
        for (int i = 0; i < r.length; i++) {
            HeifIloc.Extent x = it.extents.get(i);
            if (it.baseOffset > Long.MAX_VALUE - x.offset) return null;
            long s = it.baseOffset + x.offset;
            if (s > limit) return null;
            long e;
            if (x.length == 0) {
                if (cm == 0) {
                    BoxHeader b = topBoxAt(s);
                    e = b != null ? b.end() : limit;
                } else {
                    e = limit;
                }
            } else {
                if (x.length > limit - s) return null;
                e = s + x.length;
            }
            r[i] = new long[]{s, e};
        }
        return r;
    }

    static long total(long[][] ranges) {
        long t = 0;
        for (int i = 0; i < ranges.length; i++) t += ranges[i][1] - ranges[i][0];
        return t;
    }

    /** Reads the bytes of resolved ranges (see {@link #ranges}); caller has checked the total size. */
    byte[] read(int cm, long[][] ranges) throws IOException {
        long t = total(ranges);
        if (t > ITEM_MAX) throw new UnsupportedFormatException("HEIF öğesi çok büyük");
        byte[] out = new byte[(int) t];
        int p = 0;
        int[] ir = cm == 1 ? meta.idatRange() : null;
        for (int i = 0; i < ranges.length; i++) {
            int n = (int) (ranges[i][1] - ranges[i][0]);
            if (cm == 1) {
                System.arraycopy(meta.raw, ir[0] + (int) ranges[i][0], out, p, n);
            } else if (n > 0) {
                ByteUtils.readFully(src, ranges[i][0], out, p, n);
            }
            p += n;
        }
        return out;
    }

    /** True when any construction-method-0 range intersects the 'meta' box. */
    boolean touchesMeta(long[][] ranges) {
        long ms = metaStart();
        long me = metaEnd();
        for (int i = 0; i < ranges.length; i++) {
            if (ranges[i][0] < me && ranges[i][1] > ms) return true;
            if (ranges[i][0] == ranges[i][1] && ranges[i][0] >= ms && ranges[i][0] < me) return true;
        }
        return false;
    }
}
