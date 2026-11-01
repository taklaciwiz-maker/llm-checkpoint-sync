package com.metakamera.core.jpeg;

import com.metakamera.core.ByteSource;
import com.metakamera.core.ByteUtils;
import com.metakamera.core.EditReport;
import com.metakamera.core.EditSpec;
import com.metakamera.core.MediaInfo;
import com.metakamera.core.ProgressListener;
import com.metakamera.core.UnsupportedFormatException;
import com.metakamera.core.jpeg.JpegStructure.Segment;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * JPEG metadata editor.
 *
 * <p>Walks the marker segments from SOI up to the first SOS (header segments), edits
 * <ul>
 *   <li>the first APP1 "Exif\0\0" block with {@link com.metakamera.core.tiff.ExifTiff} (or inserts a
 *       minimal one after SOI / leading APP0 segments when the file has none); further EXIF APP1
 *       blocks left behind by some apps are edited the same way,</li>
 *   <li>standard XMP APP1 packets (Extended XMP segments are left untouched),</li>
 *   <li>the APP2 "ICC_PROFILE" chunk set (re-split into chunks written where the first chunk was),</li>
 * </ul>
 * fixes MPF (Multi-Picture Format) image offsets when header segments after the MPF segment
 * changed size, and stream-copies everything from SOS on (entropy data, EOI and every trailer
 * such as motion-photo videos, MPF secondary images and gain maps) verbatim.
 */
public final class JpegEditor {

    /** Max TIFF bytes in an EXIF APP1 (65535 - 2 length bytes - 6 "Exif\0\0"). */
    static final int MAX_EXIF_TIFF = 65527;
    /** Max standard XMP packet size in an APP1 segment. */
    static final int MAX_XMP = 65502;
    /** Max ICC bytes per APP2 chunk (65535 - 2 - 12 "ICC_PROFILE\0" - 2 seq/count). */
    static final int MAX_ICC_CHUNK = 65519;
    /** Max payload of a segment with a length field. */
    static final int MAX_PAYLOAD = 65533;

    private JpegEditor() {
    }

    public static void edit(ByteSource src, OutputStream out, EditSpec spec, EditReport report,
                            ProgressListener listener) throws IOException {
        if (spec == null) throw new IllegalArgumentException("spec == null");
        if (report == null) report = new EditReport();
        JpegStructure js = JpegStructure.parse(src, false);
        long fileLen = src.length();
        List<Segment> segs = js.segments;
        int n = segs.size();
        if (js.garbageBytes > 0) {
            report.warn("JPEG başlığında " + js.garbageBytes + " bayt tanınmayan veri var; olduğu gibi korundu");
        }

        // replacement bytes per segment (complete segments incl. marker); null = copy verbatim,
        // empty array = drop
        byte[][] repl = new byte[n][];

        // ---------------------------------------------------------------- EXIF
        byte[] insertedExif = null;
        int insertAt = -1;
        int exifIdx = indexOf(segs, JpegStructure.KIND_EXIF);
        if (exifIdx >= 0) {
            Segment s = segs.get(exifIdx);
            byte[] p = s.payload;
            byte[] tiff = MetaOps.slice(p, JpegStructure.EXIF_ID.length, p.length);
            byte[] nt = MetaOps.editExif(tiff, spec, MAX_EXIF_TIFF, report);
            if (nt != tiff) {
                repl[exifIdx] = segmentBytes(s.fillCount(), s.marker,
                        MetaOps.concat(MetaOps.slice(p, 0, JpegStructure.EXIF_ID.length), nt));
            }
        } else if (MetaOps.wantsNewExif(spec)) {
            int[] dim = js.dimensions();
            EditReport tmp = new EditReport();
            byte[] t = MetaOps.createExif(spec, dim[0], dim[1], tmp);
            if (t != null && t.length + JpegStructure.EXIF_ID.length <= MAX_PAYLOAD) {
                insertedExif = segmentBytes(0, 0xE1, MetaOps.concat(JpegStructure.EXIF_ID, t));
                insertAt = 0;
                while (insertAt < n && segs.get(insertAt).marker == 0xE0) insertAt++;
                merge(report, tmp);
            } else if (t != null) {
                report.warn("EXIF bloğu JPEG sınırına sığmıyor; eklenmedi");
            } else {
                report.warnings.addAll(tmp.warnings);
            }
        }

        // further EXIF APP1 blocks would still reveal the original device: edit them the same way
        for (int i = 0; i < n; i++) {
            Segment s = segs.get(i);
            if (s.kind != JpegStructure.KIND_EXIF_EXTRA) continue;
            byte[] p = s.payload;
            byte[] tiff = MetaOps.slice(p, JpegStructure.EXIF_ID.length, p.length);
            byte[] nt = MetaOps.editExif(tiff, spec, MAX_EXIF_TIFF, report);
            if (nt != tiff) {
                repl[i] = segmentBytes(s.fillCount(), s.marker,
                        MetaOps.concat(MetaOps.slice(p, 0, JpegStructure.EXIF_ID.length), nt));
            }
        }

        // ---------------------------------------------------------------- XMP
        if (spec.editXmp) {
            for (int i = 0; i < n; i++) {
                Segment s = segs.get(i);
                if (s.kind != JpegStructure.KIND_XMP) continue;
                int hl = JpegStructure.XMP_ID.length;
                EditReport tmp = new EditReport();
                byte[] nx = MetaOps.editXmp(s.payload, hl, s.payload.length - hl, spec, tmp);
                if (nx == null) {
                    report.warnings.addAll(tmp.warnings);
                } else if (nx.length > MAX_XMP) {
                    report.warnings.addAll(tmp.warnings);
                    report.warn("XMP düzenlenince JPEG sınırını (" + MAX_XMP + " bayt) aşıyor; XMP değiştirilmedi");
                } else {
                    repl[i] = segmentBytes(s.fillCount(), s.marker, MetaOps.concat(JpegStructure.XMP_ID, nx));
                    merge(report, tmp);
                }
            }
        }

        // ---------------------------------------------------------------- ICC
        if (MetaOps.wantsIcc(spec)) editIcc(segs, repl, spec, report);

        // ---------------------------------------------------------------- MPF
        int mpfIdx = indexOf(segs, JpegStructure.KIND_MPF);
        if (mpfIdx >= 0 && repl[mpfIdx] == null) {
            Segment mpf = segs.get(mpfIdx);
            long outPos = 2;
            long outMpfHdr = -1;
            for (int i = 0; i < n; i++) {
                if (i == insertAt) outPos += insertedExif.length;
                Segment s = segs.get(i);
                if (i == mpfIdx) outMpfHdr = outPos + (mpf.payloadStart() - mpf.start) + 4;
                outPos += repl[i] == null ? s.size() : repl[i].length;
            }
            if (insertAt == n) outPos += insertedExif.length;
            long inMpfHdr = mpf.payloadStart() + 4;
            long inDist = js.tailStart - inMpfHdr;
            long delta = (outPos - outMpfHdr) - inDist;
            if (delta != 0) {
                MpfPatcher.Result r = MpfPatcher.patch(mpf.payload, inDist, delta);
                if (r.payload != null) {
                    repl[mpfIdx] = segmentBytes(mpf.fillCount(), mpf.marker, r.payload);
                    report.change("MPF ek görüntü konumları güncellendi (" + r.patched + " görüntü)");
                } else if (r.problem != null) {
                    report.warn(r.problem + "; ek görüntü konumları güncellenemedi");
                }
            }
        }

        // ---------------------------------------------------------------- write
        SpliceWriter w = new SpliceWriter();
        w.copy(0, 2);
        for (int i = 0; i < n; i++) {
            if (i == insertAt) w.add(insertedExif);
            Segment s = segs.get(i);
            if (repl[i] == null) w.copy(s.start, s.size());
            else w.add(repl[i]);
        }
        if (insertAt == n) w.add(insertedExif);
        w.copy(js.tailStart, fileLen - js.tailStart);
        w.writeTo(src, out, listener);
    }

    /** Edits the APP2 ICC_PROFILE chunk set in place (fills {@code repl}). */
    private static void editIcc(List<Segment> segs, byte[][] repl, EditSpec spec, EditReport report) {
        List<Integer> idx = new ArrayList<Integer>();
        for (int i = 0; i < segs.size(); i++) {
            if (segs.get(i).kind == JpegStructure.KIND_ICC) idx.add(i);
        }
        if (idx.isEmpty()) return;
        int hl = JpegStructure.ICC_ID.length;
        int count = segs.get(idx.get(0)).payload[hl + 1] & 0xFF;
        boolean ok = count >= 1 && count == idx.size();
        int[] bySeq = new int[256];
        for (int k = 0; k < bySeq.length; k++) bySeq[k] = -1;
        long total = 0;
        if (ok) {
            for (int i : idx) {
                byte[] p = segs.get(i).payload;
                int seq = p[hl] & 0xFF;
                int c = p[hl + 1] & 0xFF;
                if (c != count || seq < 1 || seq > count || bySeq[seq] >= 0) {
                    ok = false;
                    break;
                }
                bySeq[seq] = i;
                total += p.length - hl - 2;
            }
        }
        if (!ok) {
            report.warn("ICC profili parçaları tutarsız (sıra/sayı hatalı); ICC değiştirilmedi");
            return;
        }
        byte[] profile = new byte[(int) total];
        int[] chunkLen = new int[count + 1];
        int o = 0;
        for (int seq = 1; seq <= count; seq++) {
            byte[] p = segs.get(bySeq[seq]).payload;
            int l = p.length - hl - 2;
            System.arraycopy(p, hl + 2, profile, o, l);
            chunkLen[seq] = l;
            o += l;
        }
        EditReport tmp = new EditReport();
        byte[] np = MetaOps.editIcc(profile, spec, tmp);
        if (np == profile) {
            report.warnings.addAll(tmp.warnings);
            return;
        }
        if (np.length == profile.length) {
            // same size: keep the original chunk boundaries and positions
            int off = 0;
            for (int seq = 1; seq <= count; seq++) {
                Segment s = segs.get(bySeq[seq]);
                byte[] payload = iccChunk(seq, count, np, off, chunkLen[seq]);
                repl[bySeq[seq]] = segmentBytes(s.fillCount(), s.marker, payload);
                off += chunkLen[seq];
            }
            merge(report, tmp);
            return;
        }
        int m = np.length == 0 ? 1 : (np.length + MAX_ICC_CHUNK - 1) / MAX_ICC_CHUNK;
        if (m > 255) {
            report.warnings.addAll(tmp.warnings);
            report.warn("ICC profili JPEG'e sığmayacak kadar büyük; ICC değiştirilmedi");
            return;
        }
        Segment first = segs.get(idx.get(0));
        byte[][] parts = new byte[m][];
        int off = 0;
        for (int k = 0; k < m; k++) {
            int l = Math.min(MAX_ICC_CHUNK, np.length - off);
            parts[k] = segmentBytes(k == 0 ? first.fillCount() : 0, 0xE2, iccChunk(k + 1, m, np, off, l));
            off += l;
        }
        repl[idx.get(0)] = MetaOps.concat(parts);
        for (int k = 1; k < idx.size(); k++) repl[idx.get(k)] = new byte[0];
        merge(report, tmp);
    }

    private static byte[] iccChunk(int seq, int count, byte[] profile, int off, int len) {
        int hl = JpegStructure.ICC_ID.length;
        byte[] p = new byte[hl + 2 + len];
        System.arraycopy(JpegStructure.ICC_ID, 0, p, 0, hl);
        p[hl] = (byte) seq;
        p[hl + 1] = (byte) count;
        System.arraycopy(profile, off, p, hl + 2, len);
        return p;
    }

    /** fill 0xFF bytes + FF marker + length + payload. */
    static byte[] segmentBytes(int fill, int marker, byte[] payload) {
        if (payload.length > MAX_PAYLOAD) throw new IllegalStateException("segment too large");
        byte[] b = new byte[fill + 4 + payload.length];
        for (int i = 0; i < fill; i++) b[i] = (byte) 0xFF;
        b[fill] = (byte) 0xFF;
        b[fill + 1] = (byte) marker;
        ByteUtils.put16be(b, fill + 2, payload.length + 2);
        System.arraycopy(payload, 0, b, fill + 4, payload.length);
        return b;
    }

    private static int indexOf(List<Segment> segs, int kind) {
        for (int i = 0; i < segs.size(); i++) if (segs.get(i).kind == kind) return i;
        return -1;
    }

    private static void merge(EditReport report, EditReport tmp) {
        report.changes.addAll(tmp.changes);
        report.warnings.addAll(tmp.warnings);
    }

    public static void inspect(ByteSource src, MediaInfo info) throws IOException {
        if (info == null) return;
        JpegStructure js;
        try {
            js = JpegStructure.parse(src, true);
        } catch (UnsupportedFormatException e) {
            return;
        }
        int[] dim = js.dimensions();
        if (dim[0] > 0 && dim[1] > 0) {
            info.width = dim[0];
            info.height = dim[1];
        }
        // ICC first: ExifTiff.inspect only looks at a TIFF-embedded profile when none was found
        byte[] icc = inspectIccChunks(js.segments);
        if (icc != null) MetaOps.inspectIcc(icc, info);
        Segment exif = js.first(JpegStructure.KIND_EXIF);
        if (exif != null) {
            byte[] p = exif.payload;
            MetaOps.inspectExif(MetaOps.slice(p, JpegStructure.EXIF_ID.length, p.length), info);
        }
        Segment xmp = js.first(JpegStructure.KIND_XMP);
        if (xmp != null) {
            int hl = JpegStructure.XMP_ID.length;
            MetaOps.inspectXmp(xmp.payload, hl, xmp.payload.length - hl, info);
        }
    }

    /** ICC chunks concatenated in sequence order (file order when the numbering is inconsistent). */
    private static byte[] inspectIccChunks(List<Segment> segs) {
        final int hl = JpegStructure.ICC_ID.length;
        List<Segment> chunks = new ArrayList<Segment>();
        for (Segment s : segs) {
            if (s.kind != JpegStructure.KIND_ICC) continue;
            chunks.add(s);
            if (chunks.size() == 255) break;
        }
        if (chunks.isEmpty()) return null;
        // Collections.sort is stable: equal/duplicate sequence numbers keep file order
        Collections.sort(chunks, new Comparator<Segment>() {
            @Override
            public int compare(Segment a, Segment b) {
                return (a.payload[hl] & 0xFF) - (b.payload[hl] & 0xFF);
            }
        });
        byte[][] parts = new byte[chunks.size()][];
        for (int i = 0; i < parts.length; i++) {
            byte[] p = chunks.get(i).payload;
            parts[i] = MetaOps.slice(p, hl + 2, p.length);
        }
        return MetaOps.concat(parts);
    }
}
