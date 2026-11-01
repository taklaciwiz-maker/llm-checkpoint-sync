package com.metakamera.core.jpeg;

/**
 * Patches the MP Entry offsets of a Multi-Picture Format (CIPA DC-007) APP2 segment.
 *
 * <p>Payload layout: "MPF\0" + TIFF header + MP Index IFD. The MPEntry tag (0xB002, UNDEFINED,
 * 16 bytes per image: attribute, size, offset, dependent1, dependent2) holds data offsets
 * relative to the start of the TIFF header; the first image has offset 0.
 */
final class MpfPatcher {

    static final int TAG_MP_ENTRY = 0xB002;

    /** Result of {@link #patch}. */
    static final class Result {
        /** Patched copy of the payload, or null when nothing had to change. */
        byte[] payload;
        /** Number of entry offsets changed. */
        int patched;
        /** User-facing problem, or null. */
        String problem;
    }

    private MpfPatcher() {
    }

    /**
     * Adds {@code delta} to every non-zero MP entry offset that points at or after
     * {@code minOffset} (offsets are relative to the MPF TIFF header).
     */
    static Result patch(byte[] payload, long minOffset, long delta) {
        Result res = new Result();
        if (payload == null || payload.length < 4 + 8) {
            res.problem = "MPF verisi okunamadı";
            return res;
        }
        final int base = 4;
        boolean le;
        if (payload[base] == 'I' && payload[base + 1] == 'I' && payload[base + 2] == 42 && payload[base + 3] == 0) {
            le = true;
        } else if (payload[base] == 'M' && payload[base + 1] == 'M' && payload[base + 2] == 0 && payload[base + 3] == 42) {
            le = false;
        } else {
            res.problem = "MPF verisi okunamadı (bayt sırası tanınmadı)";
            return res;
        }
        int tlen = payload.length - base;
        long ifd = u32(payload, base + 4, le);
        if (ifd < 8 || ifd > tlen - 2) {
            res.problem = "MPF dizini okunamadı";
            return res;
        }
        int n = u16(payload, base + (int) ifd, le);
        long entriesEnd = ifd + 2 + 12L * n;
        if (entriesEnd > tlen) {
            res.problem = "MPF dizini okunamadı";
            return res;
        }
        int dataPos = -1;
        long count = 0;
        for (int i = 0; i < n; i++) {
            int e = base + (int) ifd + 2 + 12 * i;
            int tag = u16(payload, e, le);
            if (tag != TAG_MP_ENTRY) continue;
            int type = u16(payload, e + 2, le);
            count = u32(payload, e + 4, le);
            if (type != 7 || count < 16 || count % 16 != 0) {
                res.problem = "MPF görüntü listesi okunamadı";
                return res;
            }
            long off = u32(payload, e + 8, le);
            if (off > tlen || count > tlen - off) {
                res.problem = "MPF görüntü listesi okunamadı";
                return res;
            }
            dataPos = base + (int) off;
            break;
        }
        if (dataPos < 0) {
            res.problem = "MPF görüntü listesi bulunamadı";
            return res;
        }
        byte[] out = payload.clone();
        int entries = (int) (count / 16);
        for (int k = 0; k < entries; k++) {
            int fp = dataPos + 16 * k + 8;
            long v = u32(out, fp, le);
            if (v == 0 || v < minOffset) continue;
            long nv = v + delta;
            if (nv < 0 || nv > 0xFFFFFFFFL) {
                res.problem = "MPF görüntü konumu sınır dışı";
                res.payload = null;
                res.patched = 0;
                return res;
            }
            put32(out, fp, nv, le);
            res.patched++;
        }
        if (res.patched > 0) res.payload = out;
        return res;
    }

    private static int u16(byte[] b, int off, boolean le) {
        return le ? ((b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8)) : (((b[off] & 0xFF) << 8) | (b[off + 1] & 0xFF));
    }

    private static long u32(byte[] b, int off, boolean le) {
        if (le) {
            return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8) | ((b[off + 2] & 0xFF) << 16)
                    | ((long) (b[off + 3] & 0xFF) << 24);
        }
        return ((long) (b[off] & 0xFF) << 24) | ((b[off + 1] & 0xFF) << 16) | ((b[off + 2] & 0xFF) << 8)
                | (b[off + 3] & 0xFF);
    }

    private static void put32(byte[] b, int off, long v, boolean le) {
        if (le) {
            b[off] = (byte) v;
            b[off + 1] = (byte) (v >>> 8);
            b[off + 2] = (byte) (v >>> 16);
            b[off + 3] = (byte) (v >>> 24);
        } else {
            b[off] = (byte) (v >>> 24);
            b[off + 1] = (byte) (v >>> 16);
            b[off + 2] = (byte) (v >>> 8);
            b[off + 3] = (byte) v;
        }
    }
}
