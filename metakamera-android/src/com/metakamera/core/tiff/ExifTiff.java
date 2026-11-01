package com.metakamera.core.tiff;

import com.metakamera.core.EditReport;
import com.metakamera.core.EditSpec;
import com.metakamera.core.MediaInfo;
import com.metakamera.core.UnsupportedFormatException;

import java.io.IOException;

/**
 * EXIF (TIFF structure) reader/editor. STUB — to be implemented.
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
        throw new UnsupportedFormatException("ExifTiff.edit henüz uygulanmadı");
    }

    /**
     * Builds a new minimal, spec-valid EXIF TIFF block (little-endian) containing the non-empty
     * identity fields of {@code spec} plus the tags EXIF requires (XResolution, YResolution,
     * ResolutionUnit, YCbCrPositioning, Exif IFD with ExifVersion 0232, FlashpixVersion,
     * ColorSpace, ComponentsConfiguration, and PixelX/YDimension when width/height > 0).
     */
    public static byte[] createMinimal(EditSpec spec, int width, int height) {
        throw new UnsupportedOperationException("ExifTiff.createMinimal henüz uygulanmadı");
    }

    /**
     * Fills make, model, software, hostComputer, lensMake, lensModel, dateTimeOriginal,
     * offsetTimeOriginal, hasExif, hasMakerNote, gpsStatus. Never throws on corrupt data
     * (best effort; sets what it could read).
     */
    public static void inspect(byte[] tiff, MediaInfo info) {
        info.hasExif = true;
    }
}
