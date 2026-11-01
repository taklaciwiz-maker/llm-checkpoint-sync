package com.metakamera.core;

/**
 * What to change in a file. Shared by every format editor.
 *
 * String field semantics (applies to every nullable String below):
 * <ul>
 *   <li>{@code null}  = leave the existing value untouched</li>
 *   <li>{@code ""}    = remove the tag/property/atom if present (never create it)</li>
 *   <li>otherwise     = set (create if missing) to this value</li>
 * </ul>
 */
public final class EditSpec {

    /** EXIF Make (0x010F); also XMP tiff:Make, video make. */
    public String make;
    /** EXIF Model (0x0110); also XMP tiff:Model, video model. */
    public String model;
    /** EXIF Software (0x0131); also XMP xmp:CreatorTool (only if present), video software. */
    public String software;
    /** EXIF HostComputer (0x013C) (Apple writes the marketing name here). */
    public String hostComputer;
    /** EXIF LensMake (0xA433, Exif IFD); also XMP exifEX:LensMake. */
    public String lensMake;
    /** EXIF LensModel (0xA434, Exif IFD); also XMP exifEX:LensModel / aux:Lens. */
    public String lensModel;

    /**
     * Remove the vendor MakerNote (0x927C): the IFD entry is dropped AND its bytes are zero-filled
     * in place (so no vendor strings survive), without moving any other data.
     */
    public boolean removeMakerNote = true;

    /**
     * Remove identifiers that point at the original device: BodySerialNumber (0xA431),
     * LensSerialNumber (0xA435), CameraOwnerName (0xA430), ImageUniqueID (0xA420).
     */
    public boolean clearSerials = true;

    /** Rewrite device identity inside embedded ICC profiles (JPEG APP2, PNG iCCP, WebP ICCP, HEIF colr). */
    public boolean editIcc = true;
    /** ICC identity to apply when {@link #editIcc} is true (must not be null then). */
    public IccIdentity icc;

    /** Update device properties that already exist in XMP packets. */
    public boolean editXmp = true;

    /**
     * Create a minimal EXIF block when the file has none (JPEG/PNG/WebP/HEIF).
     * Only fields that are non-null and non-empty are written.
     */
    public boolean createExifIfMissing = true;

    public EditSpec copy() {
        EditSpec s = new EditSpec();
        s.make = make;
        s.model = model;
        s.software = software;
        s.hostComputer = hostComputer;
        s.lensMake = lensMake;
        s.lensModel = lensModel;
        s.removeMakerNote = removeMakerNote;
        s.clearSerials = clearSerials;
        s.editIcc = editIcc;
        s.icc = icc == null ? null : icc.copy();
        s.editXmp = editXmp;
        s.createExifIfMissing = createExifIfMissing;
        return s;
    }
}
