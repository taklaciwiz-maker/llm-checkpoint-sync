package com.metakamera.core.xmp;

import com.metakamera.core.EditReport;
import com.metakamera.core.EditSpec;
import com.metakamera.core.MediaInfo;

/** XMP packet device-property editor. STUB — to be implemented. */
public final class XmpEditor {

    private XmpEditor() {
    }

    /**
     * Updates device properties that ALREADY exist in the packet (attribute or element form):
     * tiff:Make, tiff:Model, exifEX:LensMake, exifEX:LensModel, aux:Lens, xmp:CreatorTool (software).
     * Returns the same String instance when nothing changed. Keeps the packet byte length identical
     * by adjusting trailing padding whitespace when possible.
     */
    public static String edit(String xmp, EditSpec spec, EditReport report) {
        return xmp;
    }

    /** Sets hasXmp; fills make/model/lens/software only where the MediaInfo field is still null. */
    public static void inspect(String xmp, MediaInfo info) {
        info.hasXmp = true;
    }
}
