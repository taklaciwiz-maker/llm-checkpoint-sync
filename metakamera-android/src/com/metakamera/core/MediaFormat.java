package com.metakamera.core;

/** Container formats supported by {@link MetadataEditor}. */
public enum MediaFormat {
    JPEG("image/jpeg", "jpg", false),
    HEIF("image/heic", "heic", false),
    AVIF("image/avif", "avif", false),
    PNG("image/png", "png", false),
    WEBP("image/webp", "webp", false),
    /** ISO base media / QuickTime video: .mp4 .m4v .3gp */
    MP4("video/mp4", "mp4", true),
    /** QuickTime movie (ftyp major brand 'qt  ' or no ftyp): .mov */
    MOV("video/quicktime", "mov", true),
    UNKNOWN("application/octet-stream", "bin", false);

    public final String mimeType;
    public final String extension;
    public final boolean isVideo;

    MediaFormat(String mimeType, String extension, boolean isVideo) {
        this.mimeType = mimeType;
        this.extension = extension;
        this.isVideo = isVideo;
    }
}
