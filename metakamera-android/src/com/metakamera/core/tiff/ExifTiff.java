package com.metakamera.core.tiff;

import com.metakamera.core.ByteUtils;
import com.metakamera.core.EditReport;
import com.metakamera.core.EditSpec;
import com.metakamera.core.MediaInfo;
import com.metakamera.core.UnsupportedFormatException;
import com.metakamera.core.icc.IccProfileEditor;

import java.io.IOException;

import static com.metakamera.core.tiff.TiffFile.*;

/**
 * EXIF (TIFF structure) reader/editor.
 *
 * <p>Input/Output byte arrays always start at the TIFF byte-order mark ("II*\0" or "MM\0*"),
 * i.e. WITHOUT the JPEG "Exif\0\0" prefix and WITHOUT the HEIF 4-byte offset prefix.
 */
public final class ExifTiff {

    private ExifTiff() {
    }

    /**
     * Applies the device-identity edits of {@code spec} and returns the new TIFF bytes (never null;
     * may return the input array itself when nothing changed).
     *
     * <p>Append-only strategy: existing data (MakerNote, thumbnails, other values) is never moved,
     * so absolute offsets inside vendor MakerNotes stay valid. Changed values that do not fit in
     * place are appended at the end; IFDs that need new entries are re-written at the end and the
     * pointer to them updated.
     *
     * @param maxSize maximum allowed result size (JPEG APP1: 65527; other containers: Integer.MAX_VALUE).
     *                If the append strategy would exceed it, a compacting fallback is used; if even that
     *                does not fit an UnsupportedFormatException is thrown.
     */
    public static byte[] edit(byte[] tiff, EditSpec spec, int maxSize, EditReport report) throws IOException {
        if (spec == null) {
            if (tiff == null) throw new UnsupportedFormatException("EXIF verisi yok");
            return tiff;
        }
        // collect into a private report so a failed edit leaves the caller's report untouched
        EditReport local = new EditReport();
        byte[] out;
        try {
            out = TiffEditor.edit(tiff, spec, maxSize, local);
        } catch (UnsupportedFormatException e) {
            throw e;
        } catch (RuntimeException e) {
            UnsupportedFormatException u = new UnsupportedFormatException("EXIF verisi bozuk; düzenlenemedi");
            u.initCause(e);
            throw u;
        }
        if (report != null) {
            report.changes.addAll(local.changes);
            report.warnings.addAll(local.warnings);
        }
        return out;
    }

    /**
     * Builds a new minimal, spec-valid EXIF TIFF block (little-endian) containing the non-empty
     * identity fields of {@code spec} plus the tags EXIF requires (XResolution, YResolution,
     * ResolutionUnit, YCbCrPositioning, Exif IFD with ExifVersion 0232, FlashpixVersion,
     * ColorSpace, ComponentsConfiguration, and PixelX/YDimension when width/height > 0).
     */
    public static byte[] createMinimal(EditSpec spec, int width, int height) {
        TiffWriter w = new TiffWriter(true, 512);
        TiffWriter.WIfd i0 = new TiffWriter.WIfd();
        TiffWriter.WIfd ex = new TiffWriter.WIfd();
        if (spec != null) {
            addAscii(i0, TAG_MAKE, spec.make);
            addAscii(i0, TAG_MODEL, spec.model);
            addAscii(i0, TAG_SOFTWARE, spec.software);
            addAscii(i0, TAG_HOST_COMPUTER, spec.hostComputer);
            addAscii(ex, TAG_LENS_MAKE, spec.lensMake);
            addAscii(ex, TAG_LENS_MODEL, spec.lensModel);
        }
        i0.add(new TiffWriter.WEntry(0x011A, T_RATIONAL, 1, w.rational(72, 1)));
        i0.add(new TiffWriter.WEntry(0x011B, T_RATIONAL, 1, w.rational(72, 1)));
        i0.add(new TiffWriter.WEntry(0x0128, T_SHORT, 1, w.short1(2)));
        i0.add(new TiffWriter.WEntry(0x0213, T_SHORT, 1, w.short1(1)));
        TiffWriter.WEntry ptr = new TiffWriter.WEntry(TAG_EXIF_IFD, T_LONG, 1, new byte[4]);
        i0.add(ptr);

        ex.add(new TiffWriter.WEntry(TAG_EXIF_VERSION, T_UNDEFINED, 4, "0232".getBytes(ByteUtils.US_ASCII)));
        ex.add(new TiffWriter.WEntry(TAG_COMPONENTS_CONFIGURATION, T_UNDEFINED, 4, new byte[]{1, 2, 3, 0}));
        ex.add(new TiffWriter.WEntry(TAG_FLASHPIX_VERSION, T_UNDEFINED, 4, "0100".getBytes(ByteUtils.US_ASCII)));
        ex.add(new TiffWriter.WEntry(TAG_COLOR_SPACE, T_SHORT, 1, w.short1(1)));
        if (width > 0 && height > 0) {
            ex.add(new TiffWriter.WEntry(TAG_PIXEL_X, T_LONG, 1, w.long1(width)));
            ex.add(new TiffWriter.WEntry(TAG_PIXEL_Y, T_LONG, 1, w.long1(height)));
        }

        w.writeIfd(i0);
        w.setIfd0(i0.pos);
        w.writeIfd(ex);
        w.patch32(ptr.fieldPos, ex.pos);
        return w.toByteArray();
    }

    private static void addAscii(TiffWriter.WIfd ifd, int tag, String value) {
        if (value == null) return;
        value = value.replace("\0", "");
        if (value.length() == 0) return;
        byte[] b = TiffWriter.ascii(value);
        ifd.add(new TiffWriter.WEntry(tag, T_ASCII, b.length, b));
    }

    /**
     * Fills make, model, software, hostComputer, lensMake, lensModel, dateTimeOriginal,
     * offsetTimeOriginal, hasExif, hasMakerNote, gpsStatus. Never throws on corrupt data
     * (best effort; sets what it could read).
     */
    public static void inspect(byte[] tiff, MediaInfo info) {
        if (info == null) return;
        info.hasExif = true;
        TiffFile tf;
        try {
            tf = TiffFile.parse(tiff);
        } catch (UnsupportedFormatException e) {
            if (info.gpsStatus == null) info.gpsStatus = "yok";
            return;
        } catch (RuntimeException e) {
            if (info.gpsStatus == null) info.gpsStatus = "yok";
            return;
        }
        try {
            String s;
            TiffIfd i0 = tf.ifd0;
            if ((s = tf.string(i0.find(TAG_MAKE))) != null) info.make = s;
            if ((s = tf.string(i0.find(TAG_MODEL))) != null) info.model = s;
            if ((s = tf.string(i0.find(TAG_SOFTWARE))) != null) info.software = s;
            if ((s = tf.string(i0.find(TAG_HOST_COMPUTER))) != null) info.hostComputer = s;
            if (i0.find(TAG_MAKER_NOTE) != null) info.hasMakerNote = true;
            if (tf.exif != null) {
                TiffIfd ex = tf.exif;
                if ((s = tf.string(ex.find(TAG_LENS_MAKE))) != null) info.lensMake = s;
                if ((s = tf.string(ex.find(TAG_LENS_MODEL))) != null) info.lensModel = s;
                if ((s = tf.string(ex.find(TAG_DATE_TIME_ORIGINAL))) != null) info.dateTimeOriginal = s;
                if ((s = tf.string(ex.find(TAG_OFFSET_TIME_ORIGINAL))) != null) info.offsetTimeOriginal = s;
                TiffEntry mn = ex.find(TAG_MAKER_NOTE);
                if (mn != null && mn.count > 0) info.hasMakerNote = true;
            }
        } catch (RuntimeException e) {
            // best effort
        }
        try {
            info.gpsStatus = gpsStatus(tf);
        } catch (RuntimeException e) {
            if (info.gpsStatus == null) info.gpsStatus = "yok";
        }
        try {
            if (!info.hasIcc) {
                byte[] p = tf.value(tf.ifd0.find(TAG_ICC));
                if (p != null && p.length >= 128) IccProfileEditor.inspect(p, info);
            }
        } catch (RuntimeException e) {
            // best effort
        }
    }

    /** "yok" / "var" / "maskelenmiş" (Android MediaProvider zeroes redacted GPS coordinates). */
    private static String gpsStatus(TiffFile tf) {
        if (tf.gps == null) return "yok";
        TiffEntry la = tf.gps.find(GPS_LATITUDE);
        TiffEntry lo = tf.gps.find(GPS_LONGITUDE);
        if (la == null || lo == null) return "yok";
        byte[] a = tf.value(la);
        byte[] b = tf.value(lo);
        if (a == null || b == null) return "var";
        return isZeroCoordinate(tf, la, a) && isZeroCoordinate(tf, lo, b) ? "maskelenmiş" : "var";
    }

    private static boolean isZeroCoordinate(TiffFile tf, TiffEntry e, byte[] v) {
        if (e.type == T_RATIONAL || e.type == 10) {
            for (int i = 0; i + 8 <= v.length; i += 8) {
                if (tf.u32(v, i) != 0) return false;
            }
            return true;
        }
        for (byte x : v) {
            if (x != 0) return false;
        }
        return true;
    }
}
