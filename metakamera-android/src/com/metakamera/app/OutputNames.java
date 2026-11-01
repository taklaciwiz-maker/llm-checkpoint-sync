package com.metakamera.app;

import com.metakamera.core.MediaFormat;
import com.metakamera.core.MediaInfo;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/** Output file naming and capture-date helpers. Pure Java. */
final class OutputNames {

    private static final int MAX_BASE_LENGTH = 120;
    /**
     * File names are limited to 255 UTF-8 bytes; keep room for the extension and the " (12)" suffix that
     * MediaStore / our legacy saver add on name clashes. Turkish letters take 2 bytes, CJK 3, emoji 4.
     */
    private static final int MAX_BASE_UTF8_BYTES = 200;

    private OutputNames() {
    }

    /** MIME type for the extensions our formats use ("" or unknown -> null). Pure fallback table. */
    static String mimeForExtension(String ext) {
        String e = ext == null ? "" : ext.toLowerCase(Locale.ROOT);
        if (e.equals("jpg") || e.equals("jpeg") || e.equals("jpe")) return "image/jpeg";
        if (e.equals("heic")) return "image/heic";
        if (e.equals("heif") || e.equals("hif")) return "image/heif";
        if (e.equals("avif")) return "image/avif";
        if (e.equals("png")) return "image/png";
        if (e.equals("webp")) return "image/webp";
        if (e.equals("mp4") || e.equals("m4v")) return "video/mp4";
        if (e.equals("3gp") || e.equals("3gpp")) return "video/3gpp";
        if (e.equals("3g2") || e.equals("3gpp2")) return "video/3gpp2";
        if (e.equals("mov") || e.equals("qt")) return "video/quicktime";
        return null;
    }

    /** Extension without the dot, original case; "" when there is none. */
    static String extensionOf(String name) {
        if (name == null) return "";
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        int dot = name.lastIndexOf('.');
        if (dot <= slash + 1 || dot == name.length() - 1) return "";
        return name.substring(dot + 1);
    }

    static String baseNameOf(String name) {
        if (name == null) return "";
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        String n = name.substring(slash + 1);
        String ext = extensionOf(n);
        return ext.length() == 0 ? n : n.substring(0, n.length() - ext.length() - 1);
    }

    /** True when {@code ext} (any case) is a normal extension for the detected container. */
    static boolean extensionFits(MediaFormat f, String ext) {
        if (f == null) return false;
        String e = ext == null ? "" : ext.toLowerCase(Locale.ROOT);
        switch (f) {
            case JPEG:
                return e.equals("jpg") || e.equals("jpeg");
            case HEIF:
                return e.equals("heic") || e.equals("heif") || e.equals("hif");
            case AVIF:
                return e.equals("avif");
            case PNG:
                return e.equals("png");
            case WEBP:
                return e.equals("webp");
            case MP4:
                return e.equals("mp4") || e.equals("m4v") || e.equals("3gp") || e.equals("3gpp") || e.equals("3g2");
            case MOV:
                return e.equals("mov") || e.equals("qt");
            default:
                return false;
        }
    }

    /**
     * Name of the edited copy.
     *
     * @param keep  keep the original base name (only the extension is corrected when it does not match
     *              the detected format); otherwise IMG_/VID_yyyyMMdd_HHmmss_N.ext from the capture time
     * @param index 1-based position in the batch (the N above)
     */
    static String outputName(String displayName, MediaFormat fmt, boolean keep, MediaInfo info, long nowMillis,
                             int index) {
        String origExt = extensionOf(displayName);
        if (keep) {
            String base = sanitize(baseNameOf(displayName));
            if (base.length() == 0) base = "MetaKamera_" + stamp(info, nowMillis);
            String ext = extensionFits(fmt, origExt) ? origExt : fmt.extension;
            return base + "." + ext;
        }
        String ext = extensionFits(fmt, origExt) ? origExt.toLowerCase(Locale.ROOT) : fmt.extension;
        String prefix = fmt.isVideo ? "VID_" : "IMG_";
        return prefix + stamp(info, nowMillis) + "_" + Math.max(1, index) + "." + ext;
    }

    /** Removes characters that are invalid in file names and trims the length. */
    static String sanitize(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20 || c == 0x7F || "/\\:*?\"<>|".indexOf(c) >= 0) sb.append('_');
            else sb.append(c);
        }
        String r = sb.toString().trim();
        while (r.startsWith(".")) r = r.substring(1);
        r = truncate(r, MAX_BASE_LENGTH, MAX_BASE_UTF8_BYTES).trim();
        return r;
    }

    /** At most {@code maxChars} chars and {@code maxUtf8Bytes} UTF-8 bytes, never splitting a surrogate pair. */
    static String truncate(String s, int maxChars, int maxUtf8Bytes) {
        int bytes = 0;
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            int len = 1;
            int b;
            if (Character.isHighSurrogate(c) && i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1))) {
                len = 2;
                b = 4;
            } else if (c < 0x80) {
                b = 1;
            } else if (c < 0x800) {
                b = 2;
            } else {
                b = 3;
            }
            if (i + len > maxChars || bytes + b > maxUtf8Bytes) break;
            bytes += b;
            i += len;
        }
        return i >= s.length() ? s : s.substring(0, i);
    }

    /** yyyyMMdd_HHmmss of the capture time (EXIF / video creation time), else of {@code nowMillis}. */
    static String stamp(MediaInfo info, long nowMillis) {
        if (info != null && info.dateTimeOriginal != null) {
            String d = info.dateTimeOriginal.trim();
            if (d.matches("\\d{4}:\\d{2}:\\d{2} \\d{2}:\\d{2}:\\d{2}.*") && !d.startsWith("0000")) {
                return d.substring(0, 4) + d.substring(5, 7) + d.substring(8, 10) + "_"
                        + d.substring(11, 13) + d.substring(14, 16) + d.substring(17, 19);
            }
        }
        long t = nowMillis;
        if (info != null && info.creationTimeMillis > 0) t = info.creationTimeMillis;
        SimpleDateFormat f = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US);
        f.setTimeZone(TimeZone.getDefault());
        return f.format(new Date(t));
    }

    /**
     * Capture time in ms since 1970 for MediaStore DATE_TAKEN: video creation time, or EXIF
     * DateTimeOriginal interpreted with OffsetTimeOriginal (else the device time zone). 0 = unknown.
     */
    static long dateTakenMillis(MediaInfo info) {
        if (info == null) return 0;
        boolean video = info.format != null && info.format.isVideo;
        if (video && info.creationTimeMillis > 0) return info.creationTimeMillis;
        long t = parseExifDate(info.dateTimeOriginal, info.offsetTimeOriginal);
        if (t > 0) return t;
        return info.creationTimeMillis > 0 ? info.creationTimeMillis : 0;
    }

    static long parseExifDate(String dateTime, String offset) {
        if (dateTime == null) return 0;
        String d = dateTime.trim();
        if (!d.matches("\\d{4}:\\d{2}:\\d{2} \\d{2}:\\d{2}:\\d{2}.*")) return 0;
        d = d.substring(0, 19);
        TimeZone tz = TimeZone.getDefault();
        if (offset != null) {
            String o = offset.trim();
            if (o.matches("[+-]\\d{2}:\\d{2}")) tz = TimeZone.getTimeZone("GMT" + o);
            else if (o.matches("[+-]\\d{4}")) tz = TimeZone.getTimeZone("GMT" + o.substring(0, 3) + ":" + o.substring(3));
        }
        SimpleDateFormat f = new SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US);
        f.setLenient(false);
        f.setTimeZone(tz);
        try {
            long t = f.parse(d).getTime();
            // 1971..2100: anything else is a placeholder such as 0000:00:00 or garbage.
            return t > 31536000000L && t < 4102444800000L ? t : 0;
        } catch (ParseException e) {
            return 0;
        }
    }
}
