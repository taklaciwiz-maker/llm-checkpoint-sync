package com.metakamera.core;

/** Current device-related metadata of a file (filled by {@link MetadataEditor#inspect}). Null = absent. */
public final class MediaInfo {

    public MediaFormat format = MediaFormat.UNKNOWN;
    public long length;

    public String make;
    public String model;
    public String software;
    public String hostComputer;
    public String lensMake;
    public String lensModel;

    /** EXIF FocalLength in mm, 0 = absent. */
    public double focalLengthMm;
    /** EXIF FocalLengthIn35mmFormat, 0 = absent. */
    public int focalLength35mm;
    /** EXIF FNumber, 0 = absent. */
    public double fNumber;
    /** EXIF PixelXDimension / PixelYDimension, 0 = absent (may differ from the real raster size). */
    public int exifImageWidth;
    public int exifImageHeight;

    /** EXIF DateTimeOriginal "yyyy:MM:dd HH:mm:ss" (images). */
    public String dateTimeOriginal;
    /** EXIF OffsetTimeOriginal e.g. "+03:00" (images), if present. */
    public String offsetTimeOriginal;
    /** Video creation time in ms since 1970 UTC (mvhd), 0 = unknown. */
    public long creationTimeMillis;

    public boolean hasExif;
    public boolean hasMakerNote;
    /** "yok" (absent), "var" (present with non-zero coordinates), "maskelenmiş" (present but zeroed/redacted). */
    public String gpsStatus;

    public boolean hasIcc;
    /** ICC header deviceManufacturer as 4 chars (trimmed), "" when 0. */
    public String iccManufacturer;
    /** ICC header deviceModel as 4 chars (trimmed), "" when 0. */
    public String iccModel;
    /** ICC profile description ('desc' tag text), e.g. "Display P3". */
    public String iccDescription;
    /** ICC 'dmnd' / 'dmdd' text if present. */
    public String iccManufacturerText;
    public String iccModelText;

    public boolean hasXmp;

    /** Image/video pixel size if cheaply known (0 = unknown). */
    public int width;
    public int height;

    /** "Apple iPhone 15 Pro" style label, or null when the file has no make/model. */
    public String deviceLabel() {
        String mk = make == null ? "" : make.trim();
        String md = model == null ? "" : model.trim();
        if (mk.length() == 0 && md.length() == 0) return null;
        if (mk.length() == 0) return md;
        if (md.length() == 0) return mk;
        if (md.toLowerCase().startsWith(mk.toLowerCase())) return md;
        return mk + " " + md;
    }
}
