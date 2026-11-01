package com.metakamera.app;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.util.Size;
import android.widget.ImageView;

import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/** Loads small previews off the main thread with an in-memory LRU cache. Main-thread API. */
final class ThumbnailLoader {

    private final Context app;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService pool;
    private final LruCache<Long, Bitmap> cache;
    private final Set<Long> failed = new HashSet<Long>();
    private final Map<Long, WeakReference<ImageView>> inFlight = new HashMap<Long, WeakReference<ImageView>>();

    ThumbnailLoader(Context context) {
        this.app = context.getApplicationContext();
        this.pool = Executors.newFixedThreadPool(2, new ThreadFactory() {
            private int n;

            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "mk-thumb-" + (++n));
                t.setPriority(Thread.MIN_PRIORITY);
                return t;
            }
        });
        int maxKb = (int) Math.min(16 * 1024, Runtime.getRuntime().maxMemory() / 1024 / 16);
        this.cache = new LruCache<Long, Bitmap>(Math.max(1024, maxKb)) {
            @Override
            protected int sizeOf(Long key, Bitmap value) {
                return Math.max(1, value.getByteCount() / 1024);
            }
        };
    }

    /** Shows the cached preview or a placeholder, loading the preview in the background. */
    void load(MediaItem item, ImageView view, int sizePx) {
        final Long key = item.id;
        view.setTag(key);
        Bitmap b = cache.get(key);
        if (b != null) {
            view.setScaleType(ImageView.ScaleType.CENTER_CROP);
            view.setImageBitmap(b);
            return;
        }
        view.setImageResource(item.isVideo() ? R.drawable.ic_video : R.drawable.ic_photo);
        view.setScaleType(ImageView.ScaleType.CENTER);
        if (failed.contains(key)) return;
        boolean running = inFlight.containsKey(key);
        inFlight.put(key, new WeakReference<ImageView>(view));
        if (running) return;
        final Uri uri = item.uri;
        final int px = Math.max(64, sizePx);
        pool.execute(new Runnable() {
            @Override
            public void run() {
                Bitmap bm = null;
                try {
                    bm = decode(uri, px);
                } catch (Throwable t) {
                    bm = null;
                }
                final Bitmap result = bm;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        deliver(key, result);
                    }
                });
            }
        });
    }

    void forget(long id) {
        cache.remove(id);
        failed.remove(id);
        inFlight.remove(id);
    }

    private void deliver(Long key, Bitmap bm) {
        WeakReference<ImageView> ref = inFlight.remove(key);
        if (bm == null) {
            failed.add(key);
            return;
        }
        cache.put(key, bm);
        ImageView v = ref == null ? null : ref.get();
        if (v != null && key.equals(v.getTag())) {
            v.setScaleType(ImageView.ScaleType.CENTER_CROP);
            v.setImageBitmap(bm);
        }
    }

    // ------------------------------------------------------------------ background decoding

    /**
     * Decodes and shrinks the preview to what the px×px centre-crop view needs (shorter side = px, longer
     * side at most 4·px), so up to 100 rows hold ~100 small bitmaps instead of up-to-2x decodes.
     */
    private Bitmap decode(Uri uri, int px) {
        Bitmap b = decodeRaw(uri, px);
        return b == null ? null : fit(b, px);
    }

    private static Bitmap fit(Bitmap src, int px) {
        int w = src.getWidth();
        int h = src.getHeight();
        if (w <= 0 || h <= 0) return null;
        float s = Math.min((float) px / Math.min(w, h), (float) (4 * px) / Math.max(w, h));
        if (s >= 0.95f) return src;
        try {
            Bitmap r = Bitmap.createScaledBitmap(src, Math.max(1, Math.round(w * s)), Math.max(1, Math.round(h * s)), true);
            if (r != src) src.recycle();
            return r;
        } catch (RuntimeException e) {
            return src;
        } catch (OutOfMemoryError e) {
            return src;
        }
    }

    private Bitmap decodeRaw(Uri uri, int px) {
        ContentResolver cr = app.getContentResolver();
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                Bitmap b = cr.loadThumbnail(uri, new Size(px, px), null);
                if (b != null) return b;
            } catch (Throwable ignored) {
                // provider without thumbnail support: decode ourselves
            }
        }
        String type = null;
        try {
            type = cr.getType(uri);
        } catch (Exception ignored) {
            // unknown
        }
        if (type != null && type.startsWith("video/")) return videoFrame(uri, px);
        Bitmap b = decodeImage(cr, uri, px);
        if (b != null) return b;
        return videoFrame(uri, px);
    }

    private static Bitmap decodeImage(ContentResolver cr, Uri uri, int px) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        InputStream in = null;
        try {
            in = cr.openInputStream(uri);
            if (in == null) return null;
            BitmapFactory.decodeStream(in, null, o);
        } catch (Exception e) {
            return null;
        } finally {
            InputOpener.closeQuietly(in);
        }
        if (o.outWidth <= 0 || o.outHeight <= 0) return null;
        int sample = 1;
        while (Math.min(o.outWidth, o.outHeight) / (sample * 2) >= px) sample *= 2;
        // extreme panoramas: also bound the longer side
        while (Math.max(o.outWidth, o.outHeight) / sample > 16 * px) sample *= 2;
        BitmapFactory.Options d = new BitmapFactory.Options();
        d.inSampleSize = sample;
        Bitmap bm;
        in = null;
        try {
            in = cr.openInputStream(uri);
            if (in == null) return null;
            bm = BitmapFactory.decodeStream(in, null, d);
        } catch (Exception e) {
            return null;
        } finally {
            InputOpener.closeQuietly(in);
        }
        if (bm == null) return null;
        int rotation = exifRotation(cr, uri);
        if (rotation != 0) {
            try {
                Matrix m = new Matrix();
                m.postRotate(rotation);
                Bitmap r = Bitmap.createBitmap(bm, 0, 0, bm.getWidth(), bm.getHeight(), m, true);
                if (r != bm) bm.recycle();
                bm = r;
            } catch (RuntimeException ignored) {
                // keep the unrotated preview
            }
        }
        return bm;
    }

    private static int exifRotation(ContentResolver cr, Uri uri) {
        InputStream in = null;
        try {
            in = cr.openInputStream(uri);
            if (in == null) return 0;
            ExifInterface exif = new ExifInterface(in);
            int o = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            if (o == ExifInterface.ORIENTATION_ROTATE_90) return 90;
            if (o == ExifInterface.ORIENTATION_ROTATE_180) return 180;
            if (o == ExifInterface.ORIENTATION_ROTATE_270) return 270;
            return 0;
        } catch (Throwable t) {
            return 0;
        } finally {
            InputOpener.closeQuietly(in);
        }
    }

    private Bitmap videoFrame(Uri uri, int px) {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(app, uri);
            Bitmap f;
            if (Build.VERSION.SDK_INT >= 27) {
                f = r.getScaledFrameAtTime(-1, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, px * 2, px * 2);
            } else {
                f = r.getFrameAtTime(-1, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                if (f != null) f = scaleDown(f, px * 2);
            }
            return f;
        } catch (Throwable t) {
            return null;
        } finally {
            try {
                r.release();
            } catch (Throwable ignored) {
                // nothing to do
            }
        }
    }

    private static Bitmap scaleDown(Bitmap src, int maxSide) {
        int w = src.getWidth();
        int h = src.getHeight();
        int m = Math.max(w, h);
        if (m <= maxSide) return src;
        float s = (float) maxSide / m;
        Bitmap r = Bitmap.createScaledBitmap(src, Math.max(1, Math.round(w * s)), Math.max(1, Math.round(h * s)), true);
        if (r != src) src.recycle();
        return r;
    }
}
