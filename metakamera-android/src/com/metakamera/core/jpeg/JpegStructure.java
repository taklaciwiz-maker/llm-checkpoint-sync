package com.metakamera.core.jpeg;

import com.metakamera.core.ByteSource;
import com.metakamera.core.ByteUtils;
import com.metakamera.core.UnsupportedFormatException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Marker-segment structure of a JPEG file from SOI up to (excluding) the first SOS or EOI.
 *
 * <p>Only the payloads needed for metadata work are loaded (EXIF / XMP APP1, ICC / MPF APP2, the
 * first SOFn); every other segment is described by its offsets so it can be copied verbatim.
 * Everything from the first SOS (or EOI) on is the "tail": entropy-coded data, EOI and any
 * trailing data (motion-photo videos, MPF secondary images, gain maps...).
 */
final class JpegStructure {

    static final byte[] EXIF_ID = {'E', 'x', 'i', 'f', 0, 0};
    static final byte[] XMP_ID = "http://ns.adobe.com/xap/1.0/\0".getBytes(ByteUtils.ISO_8859_1);
    static final byte[] ICC_ID = "ICC_PROFILE\0".getBytes(ByteUtils.ISO_8859_1);
    static final byte[] MPF_ID = {'M', 'P', 'F', 0};

    static final int KIND_OTHER = 0;
    static final int KIND_EXIF = 1;
    static final int KIND_XMP = 2;
    static final int KIND_ICC = 3;
    static final int KIND_MPF = 4;
    static final int KIND_SOF = 5;
    /** Bytes that are not a marker segment (garbage between segments), copied verbatim. */
    static final int KIND_RAW = 6;
    /** A further "Exif\0\0" APP1 after the first one (left behind by some apps). */
    static final int KIND_EXIF_EXTRA = 7;

    /** Upper bound for the number of header segments (protects against pathological files). */
    static final int MAX_SEGMENTS = 100000;
    /** Upper bound for metadata payload bytes held in memory (255 ICC chunks need ~16.7 MB). */
    static final long MAX_LOADED = 48L * 1024 * 1024;

    /** One header segment. */
    static final class Segment {
        /** First byte, including 0xFF fill bytes before the marker. */
        long start;
        /** Offset of the marker code byte (the byte after the last 0xFF). */
        long codePos;
        /** Marker code (0xE1 = APP1...), -1 for {@link #KIND_RAW}. */
        int marker = -1;
        /** True for segments with a 2-byte length field. */
        boolean hasLength;
        /** Exclusive end. */
        long end;
        int kind = KIND_OTHER;
        /** Payload (after the length field) for the kinds that are loaded, else null. */
        byte[] payload;

        long payloadStart() {
            return codePos + 3;
        }

        int fillCount() {
            return (int) (codePos - 1 - start);
        }

        long size() {
            return end - start;
        }
    }

    final List<Segment> segments = new ArrayList<Segment>();
    /** Start of the tail (first SOS / EOI incl. its fill bytes), or the file length. */
    long tailStart;
    /** True when the header ended without SOS/EOI (EOF or a corrupt segment). */
    boolean truncated;
    /** User-facing reason when {@link #truncated}. */
    String problem;
    /** Number of garbage bytes found between segments. */
    long garbageBytes;

    private JpegStructure() {
    }

    /**
     * Parses the header.
     *
     * @param lenient true (inspect): stop at the first problem and keep what was read;
     *                false (edit): throw {@link UnsupportedFormatException} on truncation/corruption.
     */
    static JpegStructure parse(ByteSource src, boolean lenient) throws IOException {
        JpegStructure js = new JpegStructure();
        Reader r = new Reader(src);
        long len = r.len;
        if (len < 4 || r.u8(0) != 0xFF || r.u8(1) != 0xD8) {
            throw new UnsupportedFormatException("Geçerli bir JPEG dosyası değil");
        }
        long pos = 2;
        boolean exifSeen = false;
        boolean sofSeen = false;
        long loaded = 0;
        while (true) {
            if (pos >= len) {
                js.fail("JPEG dosyası kesik: görüntü verisi bulunamadı", lenient);
                js.tailStart = len;
                return js;
            }
            if (js.segments.size() >= MAX_SEGMENTS) {
                js.fail("JPEG başlığında çok fazla bölüm var", lenient);
                js.tailStart = pos;
                return js;
            }
            long segStart = pos;
            if (r.u8(pos) != 0xFF) {
                // garbage between segments: keep verbatim and resynchronise at the next marker
                long next = findMarker(r, pos);
                if (next < 0) {
                    js.fail("JPEG başlık yapısı bozuk", lenient);
                    js.tailStart = pos;
                    return js;
                }
                Segment g = new Segment();
                g.start = pos;
                g.codePos = pos;
                g.end = next;
                g.kind = KIND_RAW;
                js.segments.add(g);
                js.garbageBytes += next - pos;
                pos = next;
                continue;
            }
            while (pos < len && r.u8(pos) == 0xFF) pos++;
            if (pos >= len) {
                js.fail("JPEG dosyası kesik", lenient);
                js.tailStart = segStart;
                return js;
            }
            int m = r.u8(pos);
            long codePos = pos;
            pos++;
            if (m == 0x00) {
                js.fail("JPEG başlık yapısı bozuk (geçersiz işaretçi)", lenient);
                js.tailStart = segStart;
                return js;
            }
            if (m == 0xDA || m == 0xD9) {
                js.tailStart = segStart;
                return js;
            }
            Segment s = new Segment();
            s.start = segStart;
            s.codePos = codePos;
            s.marker = m;
            if (m == 0x01 || (m >= 0xD0 && m <= 0xD8)) {
                // standalone marker (TEM, RSTn, stray SOI): no length field
                s.end = pos;
                js.segments.add(s);
                continue;
            }
            if (pos + 2 > len) {
                js.fail("JPEG dosyası kesik", lenient);
                js.tailStart = segStart;
                return js;
            }
            int segLen = r.u16(pos);
            if (segLen < 2) {
                js.fail("JPEG başlık yapısı bozuk (geçersiz bölüm uzunluğu)", lenient);
                js.tailStart = segStart;
                return js;
            }
            long end = pos + segLen;
            if (end > len) {
                js.fail("JPEG dosyası kesik veya bozuk (bölüm dosya sonunu aşıyor)", lenient);
                js.tailStart = segStart;
                return js;
            }
            s.hasLength = true;
            s.end = end;
            int plen = segLen - 2;
            long pstart = pos + 2;
            if (m == 0xE1 || m == 0xE2) {
                byte[] id = new byte[Math.min(plen, XMP_ID.length)];
                r.read(pstart, id, 0, id.length);
                if (m == 0xE1 && ByteUtils.startsWith(id, 0, EXIF_ID)) {
                    s.kind = exifSeen ? KIND_EXIF_EXTRA : KIND_EXIF;
                    exifSeen = true;
                } else if (m == 0xE1 && ByteUtils.startsWith(id, 0, XMP_ID)) {
                    s.kind = KIND_XMP;
                } else if (m == 0xE2 && plen >= ICC_ID.length + 2 && ByteUtils.startsWith(id, 0, ICC_ID)) {
                    s.kind = KIND_ICC;
                } else if (m == 0xE2 && ByteUtils.startsWith(id, 0, MPF_ID)) {
                    s.kind = KIND_MPF;
                }
            } else if (isSof(m) && !sofSeen) {
                s.kind = KIND_SOF;
                sofSeen = true;
            }
            if (s.kind != KIND_OTHER && loaded + plen > MAX_LOADED) {
                // pathological file (thousands of metadata segments): treat the rest as opaque
                s.kind = KIND_OTHER;
            }
            if (s.kind != KIND_OTHER) {
                s.payload = new byte[plen];
                r.read(pstart, s.payload, 0, plen);
                loaded += plen;
            }
            js.segments.add(s);
            pos = end;
        }
    }

    private void fail(String msg, boolean lenient) throws UnsupportedFormatException {
        truncated = true;
        problem = msg;
        if (!lenient) throw new UnsupportedFormatException(msg);
    }

    /** SOF0..SOF15 except DHT (C4), JPG (C8) and DAC (CC). */
    static boolean isSof(int m) {
        return m >= 0xC0 && m <= 0xCF && m != 0xC4 && m != 0xC8 && m != 0xCC;
    }

    /** Next 0xFF followed by a plausible marker code, or -1. */
    private static long findMarker(Reader r, long from) throws IOException {
        for (long p = from; p + 1 < r.len; p++) {
            if (r.u8(p) != 0xFF) continue;
            int c = r.u8(p + 1);
            if (c == 0x01 || (c >= 0xC0 && c <= 0xFE)) return p;
        }
        return -1;
    }

    /** First segment of the given kind, or null. */
    Segment first(int kind) {
        for (Segment s : segments) if (s.kind == kind) return s;
        return null;
    }

    /** {width, height} from the first SOFn, or {0, 0}. */
    int[] dimensions() {
        Segment s = first(KIND_SOF);
        if (s == null || s.payload == null || s.payload.length < 5) return new int[]{0, 0};
        int h = ByteUtils.u16be(s.payload, 1);
        int w = ByteUtils.u16be(s.payload, 3);
        return new int[]{w, h};
    }

    /** Small read-ahead cache over a ByteSource (header parsing reads many tiny fields). */
    static final class Reader {
        final ByteSource src;
        final long len;
        private final byte[] buf = new byte[64 * 1024];
        private long bufPos = -1;
        private int bufLen;

        Reader(ByteSource src) throws IOException {
            this.src = src;
            this.len = src.length();
        }

        int u8(long pos) throws IOException {
            if (pos < 0 || pos >= len) throw new java.io.EOFException("EOF at " + pos);
            if (bufPos < 0 || pos < bufPos || pos >= bufPos + bufLen) fill(pos);
            return buf[(int) (pos - bufPos)] & 0xFF;
        }

        int u16(long pos) throws IOException {
            return (u8(pos) << 8) | u8(pos + 1);
        }

        void read(long pos, byte[] dst, int off, int n) throws IOException {
            if (n == 0) return;
            if (bufPos >= 0 && pos >= bufPos && pos + n <= bufPos + bufLen) {
                System.arraycopy(buf, (int) (pos - bufPos), dst, off, n);
                return;
            }
            ByteUtils.readFully(src, pos, dst, off, n);
        }

        private void fill(long pos) throws IOException {
            int want = (int) Math.min(buf.length, len - pos);
            ByteUtils.readFully(src, pos, buf, 0, want);
            bufPos = pos;
            bufLen = want;
        }
    }
}
