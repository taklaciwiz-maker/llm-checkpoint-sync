package com.metakamera.core.isobmff;

import com.metakamera.core.ByteUtils;
import com.metakamera.core.UnsupportedFormatException;

import java.util.List;

/**
 * Patches absolute file offsets that live OUTSIDE 'moov' in fragmented MP4 files, in place
 * (box sizes never change):
 * <ul>
 *   <li>moof/traf/tfhd base_data_offset (when tfhd flag 0x000001 is set)</li>
 *   <li>mfra/tfra moof_offset entries</li>
 * </ul>
 */
final class Mp4Fragments {

    private Mp4Fragments() {
    }

    /** Shifts tfhd base_data_offset values {@code >= threshold} by delta. Returns the number patched. */
    static int patchMoof(byte[] moof, long threshold, long delta) throws UnsupportedFormatException {
        BoxHeader root = BoxHeader.read(moof, 0, moof.length);
        if (root == null) throw new UnsupportedFormatException("Bozuk 'moof' kutusu");
        int n = 0;
        List<BoxHeader> kids = BoxHeader.children(moof, (int) root.payloadOffset(), (int) root.end());
        for (BoxHeader traf : kids) {
            if (!"traf".equals(traf.type)) continue;
            for (BoxHeader b : BoxHeader.children(moof, (int) traf.payloadOffset(), (int) traf.end())) {
                if (!"tfhd".equals(b.type)) continue;
                int p = (int) b.payloadOffset();
                if (b.payloadSize() < 8) throw new UnsupportedFormatException("Bozuk 'tfhd' kutusu");
                long flags = ByteUtils.u32be(moof, p) & 0xFFFFFF;
                if ((flags & 1) == 0) continue;
                if (b.payloadSize() < 16) throw new UnsupportedFormatException("Bozuk 'tfhd' kutusu");
                long v = ByteUtils.u64be(moof, p + 8);
                if (v >= threshold) {
                    ByteUtils.put64be(moof, p + 8, v + delta);
                    n++;
                }
            }
        }
        return n;
    }

    /** Shifts tfra moof_offset values {@code >= threshold} by delta. Returns the number patched. */
    static int patchMfra(byte[] mfra, long threshold, long delta) throws UnsupportedFormatException {
        BoxHeader root = BoxHeader.read(mfra, 0, mfra.length);
        if (root == null) throw new UnsupportedFormatException("Bozuk 'mfra' kutusu");
        int n = 0;
        for (BoxHeader b : BoxHeader.children(mfra, (int) root.payloadOffset(), (int) root.end())) {
            if (!"tfra".equals(b.type)) continue;
            int p = (int) b.payloadOffset();
            int end = (int) b.end();
            if (b.payloadSize() < 16) throw new UnsupportedFormatException("Bozuk 'tfra' kutusu");
            int version = mfra[p] & 0xFF;
            long sizes = ByteUtils.u32be(mfra, p + 8);
            int trafLen = (int) ((sizes >> 4) & 3) + 1;
            int trunLen = (int) ((sizes >> 2) & 3) + 1;
            int sampleLen = (int) (sizes & 3) + 1;
            long count = ByteUtils.u32be(mfra, p + 12);
            int timeLen = version == 1 ? 8 : 4;
            int entry = timeLen * 2 + trafLen + trunLen + sampleLen;
            int pos = p + 16;
            if (count > (end - pos) / entry) throw new UnsupportedFormatException("Bozuk 'tfra' kutusu");
            for (long i = 0; i < count; i++) {
                int off = pos + timeLen;
                long v = version == 1 ? ByteUtils.u64be(mfra, off) : ByteUtils.u32be(mfra, off);
                if (v >= threshold) {
                    long nv = v + delta;
                    if (version == 1) {
                        ByteUtils.put64be(mfra, off, nv);
                    } else {
                        if (nv > Mp4OffsetTable.MAX_U32 || nv < 0) {
                            throw new UnsupportedFormatException("Parçalı video ofseti 32 bit sınırını aşıyor");
                        }
                        ByteUtils.put32be(mfra, off, nv);
                    }
                    n++;
                }
                pos += entry;
            }
        }
        return n;
    }
}
