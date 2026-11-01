package com.metakamera.app;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import com.metakamera.core.ByteSource;
import com.metakamera.core.EditReport;
import com.metakamera.core.EditSpec;
import com.metakamera.core.FileChannelSource;
import com.metakamera.core.FormatDetector;
import com.metakamera.core.MediaFormat;
import com.metakamera.core.MediaInfo;
import com.metakamera.core.MetadataEditor;
import com.metakamera.core.ProgressListener;
import com.metakamera.core.UnsupportedFormatException;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Process-wide state that survives activity recreation: selection, chosen device, options, the running
 * job and gallery copies.
 *
 * Threading: every public method and every field is main-thread only. IO runs on one single-thread
 * worker (inspection, editing, gallery copies, cleanup — so per-item temp files never race), and results
 * are posted back to the main looper. The activity registers itself as {@link Listener} in onStart and
 * unregisters in onStop, so no activity is referenced while it is stopped.
 */
final class AppState {

    static final int MAX_ITEMS = 100;
    private static final String TAG = "MetaKamera";
    private static final long OUTPUT_MAX_AGE_MS = 24L * 60 * 60 * 1000;

    interface Listener {
        /** Items were added/removed/reset. */
        void onItemsChanged();

        /** One item's data changed. */
        void onItemChanged(MediaItem item);

        /** Database loaded, device/variant changed. */
        void onDeviceChanged();

        /** Job or gallery copy started/finished. */
        void onBusyChanged();

        /** Overall progress of the running job changed. */
        void onJobProgress();

        void onMessage(Message message);
    }

    /** A transient message for the snackbar-like bar, optionally with an "Aç" (open) action. */
    static final class Message {
        final String text;
        final Uri openUri;
        final String openMime;

        Message(String text, Uri openUri, String openMime) {
            this.text = text;
            this.openUri = openUri;
            this.openMime = openMime;
        }
    }

    private static AppState instance;

    static AppState get(Context context) {
        if (instance == null) instance = new AppState(context.getApplicationContext());
        return instance;
    }

    final Context app;
    final Prefs prefs;
    final ThumbnailLoader thumbs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker;
    private final AtomicLong outputSeq = new AtomicLong(System.currentTimeMillis());

    final List<MediaItem> items = new ArrayList<MediaItem>();
    private long nextId = 1;

    DeviceDatabase db;
    boolean dbLoading;
    String dbError;
    Device device;
    /** Selected regional model code, null = main model. */
    String variant;
    final EditOptions options = new EditOptions();
    boolean advancedExpanded;

    boolean jobRunning;
    boolean jobFinished;
    int jobTotal;
    int jobIndex;
    float jobItemFraction;
    String jobCurrentName;
    int jobOk;
    int jobFail;
    int savingCount;

    private Listener listener;
    private Message pendingMessage;

    private AppState(Context app) {
        this.app = app;
        this.prefs = new Prefs(app);
        prefs.loadOptions(options);
        this.thumbs = new ThumbnailLoader(app);
        this.worker = Executors.newSingleThreadExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "mk-worker");
                t.setPriority(Thread.NORM_PRIORITY - 1);
                return t;
            }
        });
        runInBackground(new Runnable() {
            @Override
            public void run() {
                cleanupCache();
            }
        });
    }

    void setListener(Listener l) {
        listener = l;
        if (l != null && pendingMessage != null) {
            Message m = pendingMessage;
            pendingMessage = null;
            l.onMessage(m);
        }
    }

    /** Unregisters {@code l} only if it is still the listener (an older activity instance stopping late
     *  must not unregister the newer one). */
    void removeListener(Listener l) {
        if (listener == l) listener = null;
    }

    boolean isBusy() {
        return jobRunning || savingCount > 0;
    }

    // ------------------------------------------------------------------ device database

    void ensureDatabase() {
        if (db != null || dbLoading) return;
        dbLoading = true;
        dbError = null;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                DeviceDatabase loaded = null;
                String err = null;
                try {
                    loaded = DeviceDatabase.load(app);
                } catch (Throwable e) {
                    Log.w(TAG, "devices.json", e);
                    err = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                }
                final DeviceDatabase result = loaded;
                final String error = err;
                post(new Runnable() {
                    @Override
                    public void run() {
                        dbLoading = false;
                        db = result;
                        dbError = result == null ? error : null;
                        if (result != null) restoreDevice();
                        notifyDevice();
                        notifyBusy();
                        if (result == null) message(app.getString(R.string.msg_db_error, String.valueOf(error)));
                    }
                });
            }
        }, "mk-db");
        t.start();
    }

    private void restoreDevice() {
        Device d = db.find(prefs.deviceKey());
        String v = prefs.variant();
        if (d == null) {
            d = db.defaultDevice();
            v = null;
        }
        device = d;
        variant = normalizeVariant(d, v);
    }

    private static String normalizeVariant(Device d, String v) {
        if (d == null || v == null || !d.hasModel(v) || v.equals(d.model)) return null;
        return v;
    }

    BrandInfo brandInfo() {
        return db == null || device == null ? null : db.brand(device.brand);
    }

    void setDevice(Device d, String v) {
        if (d == null) return;
        device = d;
        variant = normalizeVariant(d, v);
        prefs.setDevice(d.key, variant);
        options.clearOverrides();
        notifyDevice();
        notifyBusy();
    }

    void saveOptions() {
        prefs.saveOptions(options);
    }

    // ------------------------------------------------------------------ selection

    MediaItem findItem(long id) {
        for (MediaItem it : items) if (it.id == id) return it;
        return null;
    }

    private MediaItem findByUri(Uri u) {
        for (MediaItem it : items) if (it.uri.equals(u)) return it;
        return null;
    }

    /** Adds picked uris (duplicates skipped, at most {@link #MAX_ITEMS}); returns how many were added. */
    int addUris(List<Uri> uris) {
        if (uris == null || uris.isEmpty() || isBusy()) return 0;
        int added = 0;
        boolean truncated = false;
        boolean duplicate = false;
        for (Uri u : uris) {
            if (u == null) continue;
            if (findByUri(u) != null) {
                duplicate = true;
                continue;
            }
            if (items.size() >= MAX_ITEMS) {
                truncated = true;
                break;
            }
            MediaItem it = new MediaItem(nextId++, u);
            items.add(it);
            inspect(it);
            added++;
        }
        if (added > 0) {
            jobFinished = false;
            notifyItems();
            notifyBusy();
        }
        if (truncated) message(app.getString(R.string.msg_max_items, MAX_ITEMS));
        else if (added == 0 && duplicate) message(app.getString(R.string.msg_duplicates));
        return added;
    }

    void remove(MediaItem it) {
        if (isBusy() || it == null || !items.remove(it)) return;
        discard(it);
        if (items.isEmpty()) jobFinished = false;
        notifyItems();
        notifyBusy();
    }

    void clearItems() {
        if (isBusy() || items.isEmpty()) return;
        for (MediaItem it : items) discard(it);
        items.clear();
        jobFinished = false;
        notifyItems();
        notifyBusy();
    }

    private void discard(final MediaItem it) {
        it.removed = true;
        thumbs.forget(it.id);
        final File out = it.output;
        runInBackground(new Runnable() {
            @Override
            public void run() {
                if (it.tempCopy != null) {
                    //noinspection ResultOfMethodCallIgnored
                    it.tempCopy.delete();
                    it.tempCopy = null;
                }
                deleteOutput(out);
            }
        });
    }

    /** Reads name/size/type and the current metadata of a new item. */
    private void inspect(final MediaItem it) {
        runInBackground(new Runnable() {
            @Override
            public void run() {
                if (it.removed) return;
                final InputOpener.Meta meta = InputOpener.queryMeta(app, it.uri);
                it.workerName = meta.name;
                it.readUri = InputOpener.resolveReadUri(app, it.uri);
                MediaInfo info = null;
                String err = null;
                ByteSource src = null;
                try {
                    src = InputOpener.open(app, it);
                    info = MetadataEditor.inspect(src);
                    if (info.format == MediaFormat.UNKNOWN) err = "Desteklenmeyen dosya biçimi";
                } catch (Throwable t) {
                    Log.w(TAG, "inspect", t);
                    err = UiUtil.errorMessage(t);
                } finally {
                    InputOpener.closeQuietly(src);
                }
                final MediaInfo finfo = info;
                final String ferr = err;
                post(new Runnable() {
                    @Override
                    public void run() {
                        if (meta.name != null && meta.name.length() > 0) it.name = meta.name;
                        it.mime = meta.mime;
                        it.size = meta.size >= 0 ? meta.size : (finfo != null ? finfo.length : -1);
                        it.info = finfo;
                        it.inspectError = ferr;
                        it.inspected = true;
                        notifyItem(it);
                    }
                });
            }
        });
    }

    // ------------------------------------------------------------------ editing job

    /** Starts editing every selected item with the current device/options. */
    boolean startJob() {
        if (isBusy()) return false;
        if (items.isEmpty()) {
            message(app.getString(R.string.msg_need_files));
            return false;
        }
        final EditSpec spec = SpecBuilder.build(device, brandInfo(), variant, options);
        if (spec == null) {
            message(app.getString(R.string.msg_need_device));
            return false;
        }
        final boolean keepName = prefs.keepName();
        final List<MediaItem> list = new ArrayList<MediaItem>(items);
        final List<File> oldOutputs = new ArrayList<File>();
        for (MediaItem it : list) {
            File old = it.resetResult();
            if (old != null) oldOutputs.add(old);
            it.state = MediaItem.ST_QUEUED;
        }
        jobRunning = true;
        jobFinished = false;
        jobTotal = list.size();
        jobIndex = 0;
        jobItemFraction = 0;
        jobCurrentName = null;
        jobOk = 0;
        jobFail = 0;
        notifyBusy();
        notifyItems();
        final long now = System.currentTimeMillis();
        runInBackground(new Runnable() {
            @Override
            public void run() {
                try {
                    for (File f : oldOutputs) deleteOutput(f);
                    for (int i = 0; i < list.size(); i++) processOne(list.get(i), i, spec, keepName, now);
                } finally {
                    post(new Runnable() {
                        @Override
                        public void run() {
                            finishJob();
                        }
                    });
                }
            }
        });
        return true;
    }

    private void finishJob() {
        jobRunning = false;
        jobFinished = true;
        notifyBusy();
        if (jobOk + jobFail > 0) {
            message(app.getString(jobFail == 0 ? R.string.msg_job_done : R.string.msg_job_failed));
        }
    }

    /** Worker thread: edits one item into cacheDir/edited/&lt;n&gt;/&lt;name&gt;. Never throws. */
    private void processOne(final MediaItem it, final int index, EditSpec spec, boolean keepName, long now) {
        if (it.removed) return;
        post(new Runnable() {
            @Override
            public void run() {
                it.state = MediaItem.ST_PROCESSING;
                it.progress = 0;
                jobIndex = index;
                jobItemFraction = 0;
                jobCurrentName = it.name;
                notifyItem(it);
                notifyJobProgress();
            }
        });
        ByteSource src = null;
        OutputStream os = null;
        File dir = null;
        try {
            src = InputOpener.open(app, it);
            MediaFormat fmt = FormatDetector.detect(src);
            if (fmt == MediaFormat.UNKNOWN) {
                throw new UnsupportedFormatException(
                        "Desteklenmeyen dosya biçimi. Desteklenenler: JPEG, HEIC/HEIF, AVIF, PNG, WebP, MP4, MOV, 3GP.");
            }
            MediaInfo srcInfo = null;
            try {
                srcInfo = MetadataEditor.inspect(src);
            } catch (Throwable ignored) {
                // only used for naming; the edit reports real problems
            }
            File base = EditedFileProvider.editedDir(app);
            if (!base.isDirectory() && !base.mkdirs()) throw new UserException("Çıktı klasörü oluşturulamadı");
            long need = src.length() + 2L * 1024 * 1024;
            if (base.getUsableSpace() < need) {
                throw new UserException("Yetersiz depolama alanı (" + UiUtil.formatSize(need) + " gerekli)");
            }
            String srcName = it.workerName != null ? it.workerName : "dosya";
            final String name = OutputNames.outputName(srcName, fmt, keepName, srcInfo, now, index + 1);
            dir = new File(base, Long.toString(outputSeq.incrementAndGet()));
            if (!dir.mkdirs()) throw new UserException("Çıktı klasörü oluşturulamadı");
            final File outFile = new File(dir, name);
            os = new BufferedOutputStream(new FileOutputStream(outFile), 256 * 1024);
            final EditReport report = MetadataEditor.edit(src, os, spec, new ItemProgress(it));
            os.close();
            os = null;

            MediaInfo outInfo = null;
            String outErr = null;
            ByteSource check = null;
            try {
                check = FileChannelSource.open(outFile);
                outInfo = MetadataEditor.inspect(check);
            } catch (Throwable t) {
                outErr = UiUtil.errorMessage(t);
            } finally {
                InputOpener.closeQuietly(check);
            }
            final MediaFormat ffmt = fmt;
            final MediaInfo foutInfo = outInfo;
            final String foutErr = outErr;
            post(new Runnable() {
                @Override
                public void run() {
                    it.state = MediaItem.ST_DONE;
                    it.progress = 1f;
                    it.output = outFile;
                    it.outputName = name;
                    it.outputFormat = ffmt;
                    it.report = report;
                    it.outputInfo = foutInfo;
                    it.outputInspectError = foutErr;
                    jobOk++;
                    notifyItem(it);
                    notifyJobProgress();
                }
            });
        } catch (Throwable t) {
            Log.w(TAG, "edit", t);
            InputOpener.closeQuietly(os);
            deleteRecursive(dir);
            final String msg = UiUtil.errorMessage(t);
            post(new Runnable() {
                @Override
                public void run() {
                    it.state = MediaItem.ST_ERROR;
                    it.error = msg;
                    jobFail++;
                    notifyItem(it);
                    notifyJobProgress();
                }
            });
        } finally {
            InputOpener.closeQuietly(src);
        }
    }

    /** Throttled progress forwarding from the worker to the main thread. */
    private final class ItemProgress implements ProgressListener {
        private final MediaItem item;
        private long last;

        ItemProgress(MediaItem item) {
            this.item = item;
        }

        @Override
        public void onProgress(long done, long total) {
            long t = SystemClock.uptimeMillis();
            if (t - last < 120 && done < total) return;
            last = t;
            final float f = total > 0 ? Math.max(0f, Math.min(1f, (float) done / (float) total)) : 0f;
            post(new Runnable() {
                @Override
                public void run() {
                    if (item.state != MediaItem.ST_PROCESSING) return;
                    item.progress = f;
                    jobItemFraction = f;
                    notifyItem(item);
                    notifyJobProgress();
                }
            });
        }
    }

    // ------------------------------------------------------------------ gallery

    int countUnsavedDone() {
        int n = 0;
        for (MediaItem it : items) if (it.isDone() && it.savedPath == null && !it.saving) n++;
        return n;
    }

    List<MediaItem> unsavedDone() {
        List<MediaItem> l = new ArrayList<MediaItem>();
        for (MediaItem it : items) if (it.isDone() && it.savedPath == null && !it.saving) l.add(it);
        return l;
    }

    private static final class SaveEntry {
        final MediaItem item;
        final File file;
        final String name;
        final MediaFormat format;
        final long dateTaken;

        SaveEntry(MediaItem item) {
            this.item = item;
            this.file = item.output;
            this.name = item.outputName;
            this.format = item.outputFormat;
            this.dateTaken = OutputNames.dateTakenMillis(item.outputInfo != null ? item.outputInfo : item.info);
        }
    }

    /** Copies edited items into the gallery (permission on API 26-28 is handled by the activity). */
    void saveToGallery(List<MediaItem> targets) {
        if (jobRunning || targets == null) return;
        final List<SaveEntry> list = new ArrayList<SaveEntry>();
        for (MediaItem it : targets) {
            if (it == null || it.removed || !it.isDone() || it.saving || it.outputFormat == null) continue;
            it.saving = true;
            it.saveError = null;
            list.add(new SaveEntry(it));
            notifyItem(it);
        }
        if (list.isEmpty()) return;
        savingCount++;
        notifyBusy();
        final int album = prefs.album();
        runInBackground(new Runnable() {
            @Override
            public void run() {
                int ok = 0;
                int fail = 0;
                GallerySaver.Result last = null;
                String firstError = null;
                // photos and videos may land in different folders (Pictures/… vs Movies/…)
                List<String> dirs = new ArrayList<String>();
                for (final SaveEntry e : list) {
                    GallerySaver.Result r = null;
                    String err = null;
                    try {
                        r = GallerySaver.save(app, e.file, e.name, e.format, album, e.dateTaken);
                        ok++;
                        last = r;
                        if (r.displayDir != null && !dirs.contains(r.displayDir)) dirs.add(r.displayDir);
                    } catch (Throwable t) {
                        Log.w(TAG, "gallery", t);
                        err = UiUtil.errorMessage(t);
                        fail++;
                        if (firstError == null) firstError = err;
                    }
                    final GallerySaver.Result fr = r;
                    final String ferr = err;
                    post(new Runnable() {
                        @Override
                        public void run() {
                            e.item.saving = false;
                            if (fr != null) {
                                e.item.savedUri = fr.uri;
                                e.item.savedMime = fr.mime;
                                e.item.savedPath = fr.displayPath;
                            } else {
                                e.item.saveError = ferr;
                            }
                            notifyItem(e.item);
                        }
                    });
                }
                final Message m;
                if (fail == 0 && ok == 1 && last != null) {
                    m = new Message(app.getString(R.string.msg_saved_one, last.displayPath), last.uri, last.mime);
                } else if (fail == 0 && last != null) {
                    StringBuilder where = new StringBuilder();
                    for (int i = 0; i < dirs.size(); i++) {
                        if (i > 0) where.append(i == dirs.size() - 1 ? " ve " : ", ");
                        where.append(dirs.get(i));
                    }
                    m = new Message(app.getString(R.string.msg_saved_many, ok, where.toString()),
                            last.uri, last.mime);
                } else if (ok > 0) {
                    m = new Message(app.getString(R.string.msg_saved_partial, ok, fail), null, null);
                } else {
                    m = new Message(app.getString(R.string.msg_save_failed, String.valueOf(firstError)), null, null);
                }
                post(new Runnable() {
                    @Override
                    public void run() {
                        savingCount--;
                        notifyBusy();
                        deliver(m);
                    }
                });
            }
        });
    }

    // ------------------------------------------------------------------ files

    private void cleanupCache() {
        // temp copies belong to the previous process (no items exist yet)
        deleteRecursive(new File(app.getCacheDir(), "input"));
        File[] dirs = EditedFileProvider.editedDir(app).listFiles();
        if (dirs == null) return;
        long cutoff = System.currentTimeMillis() - OUTPUT_MAX_AGE_MS;
        for (File d : dirs) {
            if (newestModification(d) < cutoff) deleteRecursive(d);
        }
    }

    private static long newestModification(File f) {
        long t = f.lastModified();
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) t = Math.max(t, newestModification(k));
        return t;
    }

    /** Deletes an output file together with its per-output directory. */
    private void deleteOutput(File out) {
        if (out == null) return;
        File dir = out.getParentFile();
        File base = EditedFileProvider.editedDir(app);
        if (dir != null && base.equals(dir.getParentFile())) deleteRecursive(dir);
        else {
            //noinspection ResultOfMethodCallIgnored
            out.delete();
        }
    }

    private static void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteRecursive(k);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    // ------------------------------------------------------------------ plumbing

    private void runInBackground(final Runnable r) {
        try {
            worker.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        r.run();
                    } catch (Throwable t) {
                        Log.e(TAG, "background task", t);
                    }
                }
            });
        } catch (RuntimeException e) {
            Log.e(TAG, "executor", e);
        }
    }

    private void post(final Runnable r) {
        main.post(new Runnable() {
            @Override
            public void run() {
                try {
                    r.run();
                } catch (RuntimeException e) {
                    Log.e(TAG, "ui update", e);
                }
            }
        });
    }

    void message(String text) {
        deliver(new Message(text, null, null));
    }

    private void deliver(Message m) {
        if (listener != null) listener.onMessage(m);
        else pendingMessage = m;
    }

    private void notifyItems() {
        if (listener != null) listener.onItemsChanged();
    }

    private void notifyItem(MediaItem it) {
        if (listener != null) listener.onItemChanged(it);
    }

    private void notifyDevice() {
        if (listener != null) listener.onDeviceChanged();
    }

    private void notifyBusy() {
        if (listener != null) listener.onBusyChanged();
    }

    private void notifyJobProgress() {
        if (listener != null) listener.onJobProgress();
    }
}
