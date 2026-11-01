package com.metakamera.core.webp;

import com.metakamera.core.ByteSource;
import com.metakamera.core.ByteUtils;
import com.metakamera.core.EditReport;
import com.metakamera.core.EditSpec;
import com.metakamera.core.MediaInfo;
import com.metakamera.core.ProgressListener;
import com.metakamera.core.UnsupportedFormatException;
import com.metakamera.core.jpeg.MetaOps;
import com.metakamera.core.jpeg.SpliceWriter;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * WebP (RIFF) metadata editor.
 *
 * <p>Edits the 'ICCP' (raw ICC profile), 'EXIF' (TIFF, an optional "Exif\0\0" prefix is kept) and
 * 'XMP ' chunks; every other chunk (VP8/VP8L/ALPH/ANIM/ANMF/unknown) is copied verbatim including
 * its padding byte. When EXIF has to be created in a simple (VP8/VP8L only) file, a VP8X chunk is
 * added with the canvas size parsed from the bitstream header. The RIFF size is recomputed when
 * anything changed; data after the RIFF payload is kept.
 */
public final class WebpEditor {

    static final int FLAG_ICC = 0x20;
    static final int FLAG_ALPHA = 0x10;
    static final int FLAG_EXIF = 0x08;
    static final int FLAG_XMP = 0x04;

    /** Metadata chunks larger than this are not loaded (left untouched). */
    static final long MAX_META_CHUNK = 64L * 1024 * 1024;

    private static final byte[] EXIF_PREFIX = {'E', 'x', 'i', 'f', 0, 0};

    private WebpEditor() {
    }

    /** One RIFF chunk (or an unparseable remainder when {@link #raw}). */
    static final class Chunk {
        long start;
        /** Exclusive end including the padding byte (when present). */
        long end;
        String fourcc;
        long size;
        boolean raw;
        /** Loaded payload (VP8X / ICCP / EXIF / "XMP "), else null. */
        byte[] payload;
        /** First bytes of a VP8 / VP8L payload (bitstream header), else null. */
        byte[] head;

        long payloadStart() {
            return start + 8;
        }
    }

    static final class Riff {
        long riffSize;
        long riffEnd;
        long fileLen;
        final List<Chunk> chunks = new ArrayList<Chunk>();
        /** User-facing problem (truncation...), or null. */
        String problem;
        /** Metadata chunks too large to load. */
        final List<String> skipped = new ArrayList<String>();

        int indexOf(String fourcc) {
            for (int i = 0; i < chunks.size(); i++) {
                Chunk c = chunks.get(i);
                if (!c.raw && fourcc.equals(c.fourcc)) return i;
            }
            return -1;
        }
    }

    static Riff parse(ByteSource src) throws IOException {
        long len = src.length();
        if (len < 12) throw new UnsupportedFormatException("WebP dosyası çok kısa");
        byte[] h = ByteUtils.readBytes(src, 0, 12);
        if (h[0] != 'R' || h[1] != 'I' || h[2] != 'F' || h[3] != 'F'
                || h[8] != 'W' || h[9] != 'E' || h[10] != 'B' || h[11] != 'P') {
            throw new UnsupportedFormatException("Geçerli bir WebP dosyası değil");
        }
        Riff rf = new Riff();
        rf.fileLen = len;
        rf.riffSize = ByteUtils.u32le(h, 4);
        rf.riffEnd = 8 + rf.riffSize;
        if (rf.riffEnd > len) {
            rf.riffEnd = len;
            rf.problem = "WebP dosyası kesik görünüyor";
        }
        if (rf.riffEnd < 12) throw new UnsupportedFormatException("WebP başlığı geçersiz");
        long pos = 12;
        byte[] ch = new byte[8];
        while (pos < rf.riffEnd) {
            Chunk c = new Chunk();
            c.start = pos;
            if (rf.riffEnd - pos < 8) {
                c.raw = true;
                c.end = rf.riffEnd;
                rf.chunks.add(c);
                break;
            }
            ByteUtils.readFully(src, pos, ch, 0, 8);
            c.fourcc = ByteUtils.fourcc(ch, 0);
            c.size = ByteUtils.u32le(ch, 4);
            long payloadEnd = pos + 8 + c.size;
            if (payloadEnd > rf.riffEnd) {
                c.raw = true;
                c.end = rf.riffEnd;
                rf.chunks.add(c);
                if (rf.problem == null) rf.problem = "WebP dosyası kesik veya bozuk";
                break;
            }
            c.end = Math.min(payloadEnd + (c.size & 1), rf.riffEnd);
            String f = c.fourcc;
            if (f.equals("VP8X") || f.equals("ICCP") || f.equals("EXIF") || f.equals("XMP ")) {
                if (c.size <= MAX_META_CHUNK) {
                    c.payload = ByteUtils.readBytes(src, c.payloadStart(), (int) c.size);
                } else {
                    rf.skipped.add(f.trim());
                }
            } else if (f.equals("VP8 ") || f.equals("VP8L")) {
                c.head = ByteUtils.readBytes(src, c.payloadStart(), (int) Math.min(16, c.size));
            }
            rf.chunks.add(c);
            pos = c.end;
        }
        if (rf.chunks.isEmpty() || rf.chunks.get(0).raw) {
            throw new UnsupportedFormatException("WebP dosyası bozuk (görüntü verisi yok)");
        }
        String first = rf.chunks.get(0).fourcc;
        if (!first.equals("VP8 ") && !first.equals("VP8L") && !first.equals("VP8X")) {
            throw new UnsupportedFormatException("WebP yapısı tanınmadı");
        }
        return rf;
    }

    public static void edit(ByteSource src, OutputStream out, EditSpec spec, EditReport report,
                            ProgressListener listener) throws IOException {
        if (spec == null) throw new IllegalArgumentException("spec == null");
        if (report == null) report = new EditReport();
        Riff rf = parse(src);
        List<Chunk> chunks = rf.chunks;
        int n = chunks.size();
        if (rf.problem != null) report.warn(rf.problem + "; kalan veri olduğu gibi kopyalandı");
        for (String s : rf.skipped) report.warn("WebP " + s + " bloğu çok büyük; değiştirilmedi");

        byte[][] repl = new byte[n][];
        byte[][] before = new byte[n][];
        byte[][] after = new byte[n][];

        // ---------------------------------------------------------------- ICC
        int iccIdx = rf.indexOf("ICCP");
        if (iccIdx >= 0 && MetaOps.wantsIcc(spec) && chunks.get(iccIdx).payload != null) {
            byte[] p = chunks.get(iccIdx).payload;
            byte[] np = MetaOps.editIcc(p, spec, report);
            if (np != p) repl[iccIdx] = chunkBytes("ICCP", np);
        }

        // ---------------------------------------------------------------- EXIF
        int exifIdx = rf.indexOf("EXIF");
        if (exifIdx >= 0) {
            byte[] p = chunks.get(exifIdx).payload;
            if (p != null) {
                int pre = ByteUtils.startsWith(p, 0, EXIF_PREFIX) ? EXIF_PREFIX.length : 0;
                byte[] tiff = pre == 0 ? p : MetaOps.slice(p, pre, p.length);
                byte[] nt = MetaOps.editExif(tiff, spec, MetaOps.UNLIMITED, report);
                if (nt != tiff) {
                    repl[exifIdx] = chunkBytes("EXIF", pre == 0 ? nt : MetaOps.concat(EXIF_PREFIX, nt));
                }
            }
        } else if (MetaOps.wantsNewExif(spec)) {
            createExif(rf, spec, report, repl, before, after);
        }

        // ---------------------------------------------------------------- XMP
        int xmpIdx = rf.indexOf("XMP ");
        if (xmpIdx >= 0 && spec.editXmp && chunks.get(xmpIdx).payload != null) {
            byte[] p = chunks.get(xmpIdx).payload;
            byte[] nx = MetaOps.editXmp(p, 0, p.length, spec, report);
            if (nx != null) repl[xmpIdx] = chunkBytes("XMP ", nx);
        }

        // ---------------------------------------------------------------- write
        boolean changed = false;
        for (int i = 0; i < n; i++) {
            if (repl[i] != null || before[i] != null || after[i] != null) changed = true;
        }
        SpliceWriter w = new SpliceWriter();
        if (!changed) {
            w.copy(0, rf.fileLen);
            w.writeTo(src, out, listener);
            return;
        }
        long body = 4;
        for (int i = 0; i < n; i++) {
            Chunk c = chunks.get(i);
            if (before[i] != null) body += before[i].length;
            body += repl[i] != null ? repl[i].length : c.end - c.start;
            if (after[i] != null) body += after[i].length;
        }
        if (body > 0xFFFFFFFFL) throw new UnsupportedFormatException("WebP dosyası çok büyük");
        byte[] hdr = new byte[12];
        hdr[0] = 'R';
        hdr[1] = 'I';
        hdr[2] = 'F';
        hdr[3] = 'F';
        ByteUtils.put32le(hdr, 4, body);
        hdr[8] = 'W';
        hdr[9] = 'E';
        hdr[10] = 'B';
        hdr[11] = 'P';
        w.add(hdr);
        for (int i = 0; i < n; i++) {
            Chunk c = chunks.get(i);
            if (before[i] != null) w.add(before[i]);
            if (repl[i] != null) w.add(repl[i]);
            else w.copy(c.start, c.end - c.start);
            if (after[i] != null) w.add(after[i]);
        }
        w.copy(rf.riffEnd, rf.fileLen - rf.riffEnd);
        w.writeTo(src, out, listener);
    }

    /** Adds a new EXIF chunk (and a VP8X chunk for simple files). */
    private static void createExif(Riff rf, EditSpec spec, EditReport report,
                                   byte[][] repl, byte[][] before, byte[][] after) {
        List<Chunk> chunks = rf.chunks;
        int n = chunks.size();
        int vp8xIdx = rf.indexOf("VP8X");
        int[] dim;
        boolean alpha = false;
        if (vp8xIdx >= 0) {
            byte[] v = chunks.get(vp8xIdx).payload;
            if (v == null || v.length < 10) {
                report.warn("WebP VP8X başlığı bozuk; EXIF eklenemedi");
                return;
            }
            dim = new int[]{1 + u24le(v, 4), 1 + u24le(v, 7)};
        } else {
            Chunk img = chunks.get(0);
            dim = bitstreamSize(img);
            if (dim == null) {
                report.warn("WebP görüntü boyutu okunamadı; EXIF eklenemedi");
                return;
            }
            alpha = dim[2] != 0;
        }
        EditReport tmp = new EditReport();
        byte[] tiff = MetaOps.createExif(spec, dim[0], dim[1], tmp);
        if (tiff == null) {
            report.warnings.addAll(tmp.warnings);
            return;
        }
        byte[] exifChunk = chunkBytes("EXIF", tiff);
        // canonical order: VP8X, ICCP, ANIM, image (ALPH+VP8 / VP8L / ANMF...), EXIF, XMP
        int lastImage = -1;
        int firstXmp = -1;
        for (int i = 0; i < n; i++) {
            Chunk c = chunks.get(i);
            if (c.raw) continue;
            String f = c.fourcc;
            if (f.equals("VP8 ") || f.equals("VP8L") || f.equals("ALPH") || f.equals("ANMF")) lastImage = i;
            if (f.equals("XMP ") && firstXmp < 0) firstXmp = i;
        }
        if (lastImage >= 0) {
            after[lastImage] = exifChunk;
        } else if (firstXmp >= 0) {
            before[firstXmp] = exifChunk;
        } else {
            int last = n - 1;
            if (chunks.get(last).raw && last > 0) before[last] = exifChunk;
            else after[last] = exifChunk;
        }
        if (vp8xIdx >= 0) {
            byte[] v = chunks.get(vp8xIdx).payload;
            if ((v[0] & FLAG_EXIF) == 0) {
                byte[] nv = v.clone();
                nv[0] = (byte) (nv[0] | FLAG_EXIF);
                repl[vp8xIdx] = chunkBytes("VP8X", nv);
            }
        } else {
            byte[] v = new byte[10];
            v[0] = (byte) (FLAG_EXIF | (alpha ? FLAG_ALPHA : 0));
            put24le(v, 4, dim[0] - 1);
            put24le(v, 7, dim[1] - 1);
            byte[] vc = chunkBytes("VP8X", v);
            before[0] = before[0] == null ? vc : MetaOps.concat(vc, before[0]);
            tmp.change("WebP genişletilmiş başlık (VP8X) eklendi");
        }
        report.changes.addAll(tmp.changes);
        report.warnings.addAll(tmp.warnings);
    }

    /** {width, height, alpha(0/1)} from a VP8 key frame or VP8L header, or null. */
    static int[] bitstreamSize(Chunk c) {
        if (c == null || c.raw || c.head == null) return null;
        byte[] b = c.head;
        if (c.fourcc.equals("VP8 ")) {
            if (b.length < 10) return null;
            if ((b[0] & 1) != 0) return null; // not a key frame
            if ((b[3] & 0xFF) != 0x9D || (b[4] & 0xFF) != 0x01 || (b[5] & 0xFF) != 0x2A) return null;
            int w = ByteUtils.u16le(b, 6) & 0x3FFF;
            int h = ByteUtils.u16le(b, 8) & 0x3FFF;
            if (w == 0 || h == 0) return null;
            return new int[]{w, h, 0};
        }
        if (c.fourcc.equals("VP8L")) {
            if (b.length < 5 || (b[0] & 0xFF) != 0x2F) return null;
            long bits = ByteUtils.u32le(b, 1);
            int w = (int) (bits & 0x3FFF) + 1;
            int h = (int) ((bits >>> 14) & 0x3FFF) + 1;
            int a = (int) ((bits >>> 28) & 1);
            return new int[]{w, h, a};
        }
        return null;
    }

    /** fourcc + LE size + payload + padding byte for odd sizes. */
    static byte[] chunkBytes(String fourcc, byte[] payload) {
        int pad = payload.length & 1;
        byte[] b = new byte[8 + payload.length + pad];
        System.arraycopy(ByteUtils.fourccBytes(fourcc), 0, b, 0, 4);
        ByteUtils.put32le(b, 4, payload.length);
        System.arraycopy(payload, 0, b, 8, payload.length);
        return b;
    }

    static int u24le(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8) | ((b[off + 2] & 0xFF) << 16);
    }

    static void put24le(byte[] b, int off, int v) {
        b[off] = (byte) v;
        b[off + 1] = (byte) (v >>> 8);
        b[off + 2] = (byte) (v >>> 16);
    }

    public static void inspect(ByteSource src, MediaInfo info) throws IOException {
        if (info == null) return;
        Riff rf;
        try {
            rf = parse(src);
        } catch (UnsupportedFormatException e) {
            return;
        }
        int vp8x = rf.indexOf("VP8X");
        int[] dim = null;
        if (vp8x >= 0) {
            byte[] v = rf.chunks.get(vp8x).payload;
            if (v != null && v.length >= 10) dim = new int[]{1 + u24le(v, 4), 1 + u24le(v, 7)};
        }
        if (dim == null) {
            for (Chunk c : rf.chunks) {
                dim = bitstreamSize(c);
                if (dim != null) break;
            }
        }
        if (dim != null) {
            info.width = dim[0];
            info.height = dim[1];
        }
        int icc = rf.indexOf("ICCP");
        if (icc >= 0) {
            byte[] p = rf.chunks.get(icc).payload;
            if (p != null) MetaOps.inspectIcc(p, info);
            else info.hasIcc = true;
        }
        int exif = rf.indexOf("EXIF");
        if (exif >= 0) {
            byte[] p = rf.chunks.get(exif).payload;
            if (p != null) {
                int pre = ByteUtils.startsWith(p, 0, EXIF_PREFIX) ? EXIF_PREFIX.length : 0;
                MetaOps.inspectExif(pre == 0 ? p : MetaOps.slice(p, pre, p.length), info);
            } else {
                info.hasExif = true;
            }
        }
        int xmp = rf.indexOf("XMP ");
        if (xmp >= 0) {
            byte[] p = rf.chunks.get(xmp).payload;
            if (p != null) MetaOps.inspectXmp(p, 0, p.length, info);
            else info.hasXmp = true;
        }
    }
}
