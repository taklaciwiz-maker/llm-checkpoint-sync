package com.metakamera.core.icc;

import com.metakamera.core.EditReport;
import com.metakamera.core.IccIdentity;
import com.metakamera.core.MediaInfo;
import com.metakamera.core.UnsupportedFormatException;

import java.io.IOException;

/** ICC profile device-identity editor. STUB — to be implemented. */
public final class IccProfileEditor {

    private IccProfileEditor() {
    }

    /**
     * Returns the edited profile (never null; may be the same array if nothing changed).
     * Updates header signatures (CMM 4..7, platform 40..43, manufacturer 48..51, model 52..55,
     * creator 80..83), rewrites existing 'dmnd'/'dmdd' text tags (and adds them if
     * {@link IccIdentity#addDescTagsIfMissing}), fixes the profile size field, and recomputes the
     * MD5 profile ID (bytes 84..99) when it was non-zero.
     */
    public static byte[] edit(byte[] profile, IccIdentity id, EditReport report) throws IOException {
        throw new UnsupportedFormatException("IccProfileEditor.edit henüz uygulanmadı");
    }

    /** Fills hasIcc, iccManufacturer, iccModel, iccDescription, iccManufacturerText, iccModelText. Never throws. */
    public static void inspect(byte[] profile, MediaInfo info) {
        info.hasIcc = true;
    }
}
