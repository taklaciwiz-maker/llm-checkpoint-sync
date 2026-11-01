package com.metakamera.core;

import com.metakamera.core.isobmff.BoxHeader;

import java.io.IOException;
import java.util.List;

/** Sniffs the container format from file content (never trusts names/MIME types). */
public final class FormatDetector {

    private FormatDetector() {
    }

    public static MediaFormat detect(ByteSource src) throws IOException {
        long len = src.length();
        if (len < 12) return MediaFormat.UNKNOWN;
        byte[] h = new byte[32];
        int n = src.read(0, h, 0, (int) Math.min(h.length, len));
        if (n < 12) return MediaFormat.UNKNOWN;

        if ((h[0] & 0xFF) == 0xFF && (h[1] & 0xFF) == 0xD8 && (h[2] & 0xFF) == 0xFF) {
            return MediaFormat.JPEG;
        }
        if ((h[0] & 0xFF) == 0x89 && h[1] == 'P' && h[2] == 'N' && h[3] == 'G'
                && h[4] == 0x0D && h[5] == 0x0A && h[6] == 0x1A && h[7] == 0x0A) {
            return MediaFormat.PNG;
        }
        if (h[0] == 'R' && h[1] == 'I' && h[2] == 'F' && h[3] == 'F'
                && h[8] == 'W' && h[9] == 'E' && h[10] == 'B' && h[11] == 'P') {
            return MediaFormat.WEBP;
        }
        return detectIsoBmff(src);
    }

    private static MediaFormat detectIsoBmff(ByteSource src) throws IOException {
        List<BoxHeader> boxes;
        try {
            boxes = BoxHeader.topLevel(src);
        } catch (UnsupportedFormatException e) {
            return MediaFormat.UNKNOWN;
        }
        if (boxes.isEmpty()) return MediaFormat.UNKNOWN;
        boolean hasMoov = false;
        boolean hasMeta = false;
        boolean anyKnown = false;
        String major = null;
        String compat = "";
        for (BoxHeader b : boxes) {
            String t = b.type;
            if ("moov".equals(t)) hasMoov = true;
            if ("meta".equals(t)) hasMeta = true;
            if ("ftyp".equals(t) && b.payloadSize() >= 8 && b.payloadSize() < 4096) {
                byte[] p = ByteUtils.readBytes(src, b.payloadOffset(), (int) b.payloadSize());
                major = ByteUtils.fourcc(p, 0);
                StringBuilder sb = new StringBuilder();
                for (int i = 8; i + 4 <= p.length; i += 4) sb.append(ByteUtils.fourcc(p, i)).append(',');
                compat = sb.toString();
            }
            if (t.equals("ftyp") || t.equals("moov") || t.equals("mdat") || t.equals("meta")
                    || t.equals("free") || t.equals("skip") || t.equals("wide") || t.equals("uuid")
                    || t.equals("pnot") || t.equals("moof")) {
                anyKnown = true;
            }
        }
        if (!anyKnown) return MediaFormat.UNKNOWN;
        if (hasMoov) {
            if (major == null || "qt  ".equals(major)) return MediaFormat.MOV;
            return MediaFormat.MP4;
        }
        if (hasMeta) {
            if (major != null && (major.equals("avif") || major.equals("avis"))) return MediaFormat.AVIF;
            if (major != null && major.equals("mif1") && compat.contains("avif") && !compat.contains("heic")) {
                return MediaFormat.AVIF;
            }
            return MediaFormat.HEIF;
        }
        return MediaFormat.UNKNOWN;
    }
}
