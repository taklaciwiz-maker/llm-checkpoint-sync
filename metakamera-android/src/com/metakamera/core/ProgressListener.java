package com.metakamera.core;

/** Receives byte-level progress while a file is rewritten. Called on the worker thread. */
public interface ProgressListener {
    void onProgress(long done, long total);
}
