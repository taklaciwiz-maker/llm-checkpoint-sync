package com.metakamera.core.tiff;

import com.metakamera.core.ByteUtils;

import java.util.List;

import static com.metakamera.core.tiff.TiffFile.*;

/**
 * Original-brand traces OUTSIDE the MakerNote, removed together with it when
 * {@code EditSpec.removeMakerNote} ("remove original-brand traces") is set.
 *
 * <h3>1. Vendor-only tags (removed whenever present in IFD0 or the Exif IFD, whatever their value)</h3>
 * Verified against the exiftool 12.76 tag table {@code Image::ExifTool::Exif} (Exif.pm). Every tag
 * below is unregistered in TIFF/EXIF and documented there as written only by one phone vendor, so
 * its mere presence (let alone its content) identifies the original brand:
 * <ul>
 *   <li>0x9999 XiaomiSettings (ExifIFD, Xiaomi): JSON string such as
 *       {@code {"AIEnable":false,"Mirror":false,"Sensor":"Rear",...}}.</li>
 *   <li>0x9A00 XiaomiModel (ExifIFD, Xiaomi): marketing model name, e.g. "Xiaomi 13".</li>
 *   <li>0x9AAA (no exiftool name; ExifIFD, Xiaomi POCO F1): int8u[2048/2176] vendor blob.</li>
 *   <li>0xA661 (no exiftool name; ExifIFD, Xiaomi): string.</li>
 *   <li>0x8889 (no exiftool name; ExifIFD, Xiaomi POCO F1): string, e.g. "portrait".</li>
 *   <li>0x8891, 0x8894, 0x8895, 0x889A (no exiftool name; ExifIFD, Xiaomi POCO F1): int16u.</li>
 *   <li>0x0220-0x0224 (no exiftool name; IFD0, Xiaomi Redmi models): int32u;
 *       0x0225 (IFD0, Xiaomi Redmi models): string.</li>
 * </ul>
 * Looked at but deliberately NOT removed: 0x9009 GooglePlusUploadCode (written by the Google+
 * uploader service, not by a phone), the Sony*Raw / SamsungRaw* tags (RAW-file SubIFD tags of
 * cameras, never in phone JPEG/HEIF EXIF).
 *
 * <h3>2. Free-text tags (removed only when they carry the old brand)</h3>
 * ImageDescription (0x010E), UserComment (0x9286), XPComment (0x9C9C), XPSubject (0x9C9F),
 * XPKeywords (0x9C9E) and their Windows sibling XPTitle (0x9C9B), in IFD0, the Exif IFD or IFD1,
 * are removed ONLY when their decoded text
 * <ul>
 *   <li>contains the OLD Make or OLD Model (IFD0/IFD1, trimmed, case-insensitive), or</li>
 *   <li>starts with "oplus_" (OPPO/OnePlus/realme feature bits written to UserComment).</li>
 * </ul>
 * An old value that equals the value being written (e.g. Apple → Apple) is not a trace of the
 * original brand and is not used (this also keeps a second identical edit a no-op). One-character
 * values are ignored, and values of 2-3 characters ("LG", "HTC", "LGE") must match as a whole word
 * so that e.g. Make "LGE" does not remove a comment saying "college".
 */
final class BrandTraces {

    private BrandTraces() {
    }

    /** Vendor-only tags (see class comment), in ascending order. */
    static final int[] VENDOR_TAGS = {
            0x0220, 0x0221, 0x0222, 0x0223, 0x0224, 0x0225,
            0x8889, 0x8891, 0x8894, 0x8895, 0x889A,
            TAG_XIAOMI_SETTINGS, TAG_XIAOMI_MODEL, 0x9AAA, 0xA661};
    /** exiftool names; null = the tag has no exiftool name (reported by its id). */
    static final String[] VENDOR_NAMES = {
            null, null, null, null, null, null,
            null, null, null, null, null,
            "XiaomiSettings", "XiaomiModel", null, null};

    /** Free-text tags that are removed only when they mention the old brand. */
    static final int[] TEXT_TAGS = {
            TAG_IMAGE_DESCRIPTION, TAG_USER_COMMENT, TAG_XP_TITLE, TAG_XP_COMMENT, TAG_XP_KEYWORDS, TAG_XP_SUBJECT};
    static final String[] TEXT_NAMES = {
            "ImageDescription", "UserComment", "XPTitle", "XPComment", "XPKeywords", "XPSubject"};

    /** OPPO / OnePlus / realme (ColorOS "oplus") feature bits, e.g. UserComment "oplus_8388608". */
    static final String OPLUS_PREFIX = "oplus_";

    /** Values shorter than this must match as a whole word. */
    private static final int WHOLE_WORD_BELOW = 4;

    /**
     * Adds {@code oldValue} (trimmed) to {@code needles} unless it is empty, a single character,
     * already listed, or equal (ignoring case) to the value being written ({@code newValue}).
     */
    static void addNeedle(List<String> needles, String oldValue, String newValue) {
        if (oldValue == null) return;
        String o = oldValue.replace('\0', ' ').trim();
        if (o.length() < 2) return;
        if (newValue != null) {
            String n = newValue.replace("\0", "").trim();
            if (n.length() > 0 && n.equalsIgnoreCase(o)) return;
        }
        for (int i = 0; i < needles.size(); i++) {
            if (needles.get(i).equalsIgnoreCase(o)) return;
        }
        needles.add(o);
    }

    /**
     * True when the raw value {@code v} of a free-text tag carries the old brand: any of its
     * plausible decodings starts with "oplus_" or contains one of the {@code needles}.
     */
    static boolean isBrandText(byte[] v, boolean userComment, List<String> needles) {
        if (v == null || v.length == 0) return false;
        String[] texts = decodings(v, userComment);
        for (String s : texts) {
            if (startsWithIgnoreCase(trimLeading(s), OPLUS_PREFIX)) return true;
            for (int i = 0; i < needles.size(); i++) {
                if (containsNeedle(s, needles.get(i))) return true;
            }
        }
        return false;
    }

    /**
     * The value decoded every way a phone/Windows/exiftool may have written it: 8-bit text
     * (UTF-8, Latin-1 fallback) and UTF-16 in both byte orders (UserComment "UNICODE" follows the
     * EXIF byte order, XP* tags are always little-endian, a BOM wins). Decodings in the wrong
     * byte order turn Latin text into CJK code points, so they cannot produce false matches.
     * The 8-byte UserComment character-code header is skipped. NULs become spaces.
     */
    static String[] decodings(byte[] v, boolean userComment) {
        int off = userComment && hasCharCodeHeader(v) ? 8 : 0;
        return new String[]{text8(v, off), utf16(v, off, true), utf16(v, off, false)};
    }

    /** "ASCII", "UNICODE", "JIS" or undefined (all NUL/space) 8-byte header of a UserComment. */
    static boolean hasCharCodeHeader(byte[] v) {
        if (v.length < 8) return false;
        int n = 0;
        while (n < 8 && v[n] >= 'A' && v[n] <= 'Z') n++;
        if (n > 0) {
            String id = new String(v, 0, n, ByteUtils.US_ASCII);
            if (!id.equals("ASCII") && !id.equals("UNICODE") && !id.equals("JIS")) return false;
        }
        for (int i = n; i < 8; i++) {
            if (v[i] != 0 && v[i] != ' ') return false;
        }
        return true;
    }

    private static String text8(byte[] v, int off) {
        byte[] b = new byte[Math.max(0, v.length - off)];
        for (int i = 0; i < b.length; i++) {
            byte x = v[off + i];
            b[i] = x == 0 ? (byte) ' ' : x;
        }
        return TiffFile.decode(b);
    }

    private static String utf16(byte[] v, int off, boolean le) {
        int p = off;
        if (v.length - p >= 2) {
            int b0 = v[p] & 0xFF;
            int b1 = v[p + 1] & 0xFF;
            if (b0 == 0xFF && b1 == 0xFE) {
                le = true;
                p += 2;
            } else if (b0 == 0xFE && b1 == 0xFF) {
                le = false;
                p += 2;
            }
        }
        char[] c = new char[Math.max(0, (v.length - p) / 2)];
        int n = 0;
        for (; p + 1 < v.length; p += 2) {
            int ch = le ? (v[p] & 0xFF) | ((v[p + 1] & 0xFF) << 8) : ((v[p] & 0xFF) << 8) | (v[p + 1] & 0xFF);
            c[n++] = ch == 0 ? ' ' : (char) ch;
        }
        return new String(c, 0, n);
    }

    private static String trimLeading(String s) {
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c <= ' ' || c == '﻿') i++;
            else break;
        }
        return s.substring(i);
    }

    private static boolean startsWithIgnoreCase(String s, String prefix) {
        return s.regionMatches(true, 0, prefix, 0, prefix.length());
    }

    /**
     * Case-insensitive (locale independent: no Turkish dotless-i surprises) substring test; short
     * needles must be delimited by non-alphanumeric characters.
     */
    static boolean containsNeedle(String hay, String needle) {
        int n = needle.length();
        if (n == 0) return false;
        boolean wholeWord = n < WHOLE_WORD_BELOW;
        for (int i = 0; i + n <= hay.length(); i++) {
            if (!hay.regionMatches(true, i, needle, 0, n)) continue;
            if (!wholeWord) return true;
            boolean before = i == 0 || !Character.isLetterOrDigit(hay.charAt(i - 1));
            boolean after = i + n == hay.length() || !Character.isLetterOrDigit(hay.charAt(i + n));
            if (before && after) return true;
        }
        return false;
    }

    /** "0x9AAA" (locale independent). */
    static String hex(int tag) {
        String h = Integer.toHexString(tag & 0xFFFF);
        StringBuilder sb = new StringBuilder("0x");
        for (int i = h.length(); i < 4; i++) sb.append('0');
        for (int i = 0; i < h.length(); i++) {
            char c = h.charAt(i);
            sb.append(c >= 'a' && c <= 'f' ? (char) (c - 'a' + 'A') : c);
        }
        return sb.toString();
    }
}
