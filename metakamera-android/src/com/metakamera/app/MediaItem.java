package com.metakamera.app;

import android.net.Uri;

import com.metakamera.core.EditReport;
import com.metakamera.core.MediaFormat;
import com.metakamera.core.MediaInfo;

import java.io.File;

/**
 * One selected photo/video and everything known about it.
 *
 * Threading: plain fields are owned by the main thread (background tasks post their results there).
 * Fields marked "worker" are only touched by {@link AppState}'s single worker thread.
 */
final class MediaItem {

    static final int ST_IDLE = 0;
    static final int ST_QUEUED = 1;
    static final int ST_PROCESSING = 2;
    static final int ST_DONE = 3;
    static final int ST_ERROR = 4;

    final long id;
    /** Uri as returned by the picker. */
    final Uri uri;

    /** Set on the main thread when the item leaves the list; read by background tasks. */
    volatile boolean removed;

    // ---- worker thread only
    /** Uri used for reading (possibly MediaStore "require original" for unredacted GPS). */
    Uri readUri;
    /** Local copy for non-seekable sources (pipes); deleted when the item is removed. */
    File tempCopy;
    /** Display name as queried by the worker (used for output naming). */
    String workerName;

    // ---- main thread
    String name;
    String mime;
    long size = -1;
    boolean inspected;
    MediaInfo info;
    String inspectError;

    int state = ST_IDLE;
    float progress;
    String error;
    File output;
    String outputName;
    MediaFormat outputFormat;
    EditReport report;
    MediaInfo outputInfo;
    String outputInspectError;
    boolean expanded;

    boolean saving;
    Uri savedUri;
    String savedMime;
    String savedPath;
    String saveError;

    MediaItem(long id, Uri uri) {
        this.id = id;
        this.uri = uri;
        String seg = null;
        try {
            seg = uri.getLastPathSegment();
        } catch (RuntimeException ignored) {
            // malformed uri: keep the fallback name
        }
        this.name = seg == null || seg.length() == 0 ? "dosya" : seg;
    }

    MediaFormat format() {
        if (outputFormat != null) return outputFormat;
        if (info != null && info.format != null && info.format != MediaFormat.UNKNOWN) return info.format;
        return MediaFormat.UNKNOWN;
    }

    boolean isVideo() {
        MediaFormat f = format();
        if (f != MediaFormat.UNKNOWN) return f.isVideo;
        return mime != null && mime.startsWith("video/");
    }

    boolean isDone() {
        return state == ST_DONE && output != null;
    }

    /** Clears the previous edit result before a new run; returns the old output file (may be null). */
    File resetResult() {
        File old = output;
        state = ST_IDLE;
        progress = 0;
        error = null;
        output = null;
        outputName = null;
        outputFormat = null;
        report = null;
        outputInfo = null;
        outputInspectError = null;
        saving = false;
        savedUri = null;
        savedMime = null;
        savedPath = null;
        saveError = null;
        return old;
    }
}
