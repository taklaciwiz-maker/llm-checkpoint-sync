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

    // ------------------------------------------------------------------ chosen camera module
    // Numeric fields: null = leave untouched; a value = set (create if missing) in EXIF and update
    // the matching XMP property / video key only where it already exists.

    /** EXIF FocalLength (0x920A, RATIONAL, mm) of the chosen camera, e.g. 6.93. */
    public Double focalLengthMm;
    /** EXIF FocalLengthIn35mmFormat (0xA405, SHORT), e.g. 24. */
    public Integer focalLength35mm;
    /**
     * EXIF FNumber (0x829D, RATIONAL), e.g. 1.4. ApertureValue (0x9202) is set to the matching APEX
     * value (2*log2(N)) and MaxApertureValue (0x9205) is updated when present.
     */
    public Double fNumber;
    /**
     * EXIF LensSpecification (0xA432, 4 RATIONALs: min focal, max focal, min F at min focal,
     * min F at max focal) — only UPDATED when the file already has it. null = keep.
     */
    public double[] lensSpecification;
    /**
     * EXIF PixelXDimension / PixelYDimension (0xA002 / 0xA003) to record — metadata only, the
     * actual pixel raster is never changed. Must be given together (both null = keep).
     */
    public Integer exifImageWidth;
    public Integer exifImageHeight;

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
        s.focalLengthMm = focalLengthMm;
        s.focalLength35mm = focalLength35mm;
        s.fNumber = fNumber;
        s.lensSpecification = lensSpecification == null ? null : lensSpecification.clone();
        s.exifImageWidth = exifImageWidth;
        s.exifImageHeight = exifImageHeight;
        s.removeMakerNote = removeMakerNote;
        s.clearSerials = clearSerials;
        s.editIcc = editIcc;
        s.icc = icc == null ? null : icc.copy();
        s.editXmp = editXmp;
        s.createExifIfMissing = createExifIfMissing;
        return s;
    }
}
