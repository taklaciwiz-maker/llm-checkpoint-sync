package com.metakamera.core.jpeg;

import com.metakamera.core.ByteUtils;
import com.metakamera.core.EditReport;
import com.metakamera.core.EditSpec;
import com.metakamera.core.MediaInfo;
import com.metakamera.core.icc.IccProfileEditor;
import com.metakamera.core.tiff.ExifTiff;
import com.metakamera.core.xmp.XmpEditor;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.util.Arrays;

/**
 * Container-independent wrappers around {@link ExifTiff}, {@link IccProfileEditor} and
 * {@link XmpEditor} used by the JPEG, PNG and WebP editors.
 *
 * <p>None of these methods throws on malformed metadata: the block is then kept as it is and a
 * Turkish warning is added to the report. Change messages are only added when the block really
 * changed.
 */
public final class MetaOps {

    /** maxSize for EXIF blocks in containers without a practical size limit. */
    public static final int UNLIMITED = Integer.MAX_VALUE - 1024;

    private MetaOps() {
    }

    /** True when {@code s} would be written as a non-empty value (null / "" / only NULs: false). */
    public static boolean hasText(String s) {
        return s != null && s.replace("\0", "").length() > 0;
    }

    /** True when an EXIF block should be created for a file that has none. */
    public static boolean wantsNewExif(EditSpec spec) {
        return spec != null && spec.createExifIfMissing && (hasText(spec.make) || hasText(spec.model));
    }

    /**
     * Edits a TIFF/EXIF block. Returns {@code tiff} itself when nothing changed or the block could
     * not be edited (a warning is added in that case).
     */
    public static byte[] editExif(byte[] tiff, EditSpec spec, int maxSize, EditReport report) {
        if (tiff == null || spec == null) return tiff;
        EditReport tmp = new EditReport();
        byte[] out;
        try {
            out = ExifTiff.edit(tiff, spec, maxSize, tmp);
        } catch (IOException e) {
            report.warn(exifFailure(e.getMessage()));
            return tiff;
        } catch (RuntimeException e) {
            report.warn("EXIF işlenemedi (iç hata), değiştirilmedi");
            return tiff;
        }
        if (out == null || out == tiff || Arrays.equals(out, tiff)) {
            // no byte changed: drop change messages, keep warnings
            report.warnings.addAll(tmp.warnings);
            return tiff;
        }
        report.changes.addAll(tmp.changes);
        report.warnings.addAll(tmp.warnings);
        return out;
    }

    private static String exifFailure(String msg) {
        if (msg == null || msg.length() == 0) msg = "EXIF verisi bozuk";
        return msg + " (EXIF değiştirilmedi)";
    }

    /**
     * Builds a new minimal EXIF block for a file that has none, or returns null when the spec does
     * not ask for it ({@link EditSpec#createExifIfMissing} and a non-empty make or model).
     */
    public static byte[] createExif(EditSpec spec, int width, int height, EditReport report) {
        if (!wantsNewExif(spec)) return null;
        byte[] tiff;
        try {
            tiff = ExifTiff.createMinimal(spec, Math.max(0, width), Math.max(0, height));
        } catch (RuntimeException e) {
            report.warn("EXIF oluşturulamadı (iç hata)");
            return null;
        }
        if (tiff == null || tiff.length < 8) return null;
        report.change("EXIF bloğu oluşturuldu");
        addCreated(report, "Make", spec.make);
        addCreated(report, "Model", spec.model);
        addCreated(report, "Software", spec.software);
        addCreated(report, "HostComputer", spec.hostComputer);
        addCreated(report, "LensMake", spec.lensMake);
        addCreated(report, "LensModel", spec.lensModel);
        return tiff;
    }

    private static void addCreated(EditReport report, String label, String value) {
        if (!hasText(value)) return;
        report.change("EXIF " + label + " eklendi: " + value.replace("\0", ""));
    }

    /** True when the ICC profile should be edited at all. */
    public static boolean wantsIcc(EditSpec spec) {
        return spec != null && spec.editIcc && spec.icc != null;
    }

    /**
     * Edits an ICC profile. Returns {@code profile} itself when nothing changed (or editing is
     * disabled / impossible; warnings are added by the ICC editor).
     */
    public static byte[] editIcc(byte[] profile, EditSpec spec, EditReport report) {
        if (profile == null || !wantsIcc(spec)) return profile;
        EditReport tmp = new EditReport();
        byte[] out;
        try {
            out = IccProfileEditor.edit(profile, spec.icc, tmp);
        } catch (IOException e) {
            report.warn("ICC profili işlenemedi, değiştirilmedi");
            return profile;
        } catch (RuntimeException e) {
            report.warn(IccProfileEditor.INTERNAL_ERROR_WARNING);
            return profile;
        }
        if (out == null || out == profile || Arrays.equals(out, profile)) {
            report.warnings.addAll(tmp.warnings);
            return profile;
        }
        report.changes.addAll(tmp.changes);
        report.warnings.addAll(tmp.warnings);
        return out;
    }

    /**
     * Edits an XMP packet stored as UTF-8 bytes {@code [off, off+len)}. Returns the new UTF-8 bytes,
     * or null when nothing changed (or the packet is not valid UTF-8 / editing is disabled).
     */
    public static byte[] editXmp(byte[] buf, int off, int len, EditSpec spec, EditReport report) {
        if (buf == null || spec == null || !spec.editXmp) return null;
        if (off < 0 || len < 0 || off > buf.length || len > buf.length - off) return null;
        String text = decodeUtf8Strict(buf, off, len);
        if (text == null) {
            report.warn("XMP paketi geçerli UTF-8 değil, değiştirilmedi");
            return null;
        }
        EditReport tmp = new EditReport();
        String edited;
        try {
            edited = XmpEditor.edit(text, spec, tmp);
        } catch (RuntimeException e) {
            report.warn(XmpEditor.INTERNAL_ERROR_WARNING);
            return null;
        }
        report.warnings.addAll(tmp.warnings);
        if (edited == null || edited == text || edited.equals(text)) return null;
        byte[] enc = edited.getBytes(ByteUtils.UTF_8);
        if (len == enc.length && ByteUtils.startsWith(buf, off, enc)) return null;
        report.changes.addAll(tmp.changes);
        return enc;
    }

    /** XMP inspect from UTF-8 bytes (lenient decoding). Never throws. */
    public static void inspectXmp(byte[] buf, int off, int len, MediaInfo info) {
        if (buf == null || info == null) return;
        if (off < 0 || len < 0 || off > buf.length || len > buf.length - off) return;
        try {
            XmpEditor.inspect(new String(buf, off, len, ByteUtils.UTF_8), info);
        } catch (RuntimeException e) {
            info.hasXmp = true;
        }
    }

    /** EXIF inspect that never throws. */
    public static void inspectExif(byte[] tiff, MediaInfo info) {
        if (tiff == null || info == null) return;
        try {
            ExifTiff.inspect(tiff, info);
        } catch (RuntimeException e) {
            info.hasExif = true;
        }
    }

    /** ICC inspect that never throws. */
    public static void inspectIcc(byte[] profile, MediaInfo info) {
        if (profile == null || info == null) return;
        try {
            IccProfileEditor.inspect(profile, info);
        } catch (RuntimeException e) {
            info.hasIcc = true;
        }
    }

    /** Strict UTF-8 decoding; null when the bytes are not well-formed UTF-8. */
    public static String decodeUtf8Strict(byte[] buf, int off, int len) {
        CharsetDecoder dec = ByteUtils.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            CharBuffer cb = dec.decode(ByteBuffer.wrap(buf, off, len));
            return cb.toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }

    /** Concatenates byte arrays. */
    public static byte[] concat(byte[]... parts) {
        int n = 0;
        for (byte[] p : parts) n += p.length;
        byte[] r = new byte[n];
        int o = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, r, o, p.length);
            o += p.length;
        }
        return r;
    }

    /** Copy of {@code b[from, to)}. */
    public static byte[] slice(byte[] b, int from, int to) {
        byte[] r = new byte[to - from];
        System.arraycopy(b, from, r, 0, r.length);
        return r;
    }
}
