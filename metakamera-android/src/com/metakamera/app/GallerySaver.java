package com.metakamera.app;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.webkit.MimeTypeMap;

import com.metakamera.core.MediaFormat;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Copies an edited file into the shared gallery (DCIM/Camera or Pictures/MetaKamera).
 * API 29+: MediaStore insert with IS_PENDING; API 26-28: public directory + media scan
 * (needs WRITE_EXTERNAL_STORAGE, requested by the activity). Background thread only.
 */
final class GallerySaver {

    private GallerySaver() {
    }

    static final class Result {
        Uri uri;
        String mime;
        /** e.g. "DCIM/Camera/IMG_1234.jpg" */
        String displayPath;
    }

    /** Relative directory shown to the user and used as MediaStore RELATIVE_PATH. */
    static String relativeDir(int album) {
        return album == Prefs.ALBUM_APP
                ? Environment.DIRECTORY_PICTURES + "/MetaKamera"
                : Environment.DIRECTORY_DCIM + "/Camera";
    }

    /** MIME type matching the file name extension (as MediaStore checks it), else the format's type. */
    static String mimeFor(String fileName, MediaFormat fmt) {
        String ext = OutputNames.extensionOf(fileName).toLowerCase(Locale.ROOT);
        String m = null;
        try {
            m = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
        } catch (RuntimeException ignored) {
            // fall back to the format
        }
        String want = fmt.isVideo ? "video/" : "image/";
        if (m != null && m.startsWith(want)) return m;
        return fmt.mimeType;
    }

    static Result save(Context c, File src, String name, MediaFormat fmt, int album, long dateTaken)
            throws IOException {
        if (src == null || !src.isFile()) throw new UserException("Düzenlenmiş dosya bulunamadı");
        String mime = mimeFor(name, fmt);
        Result r = Build.VERSION.SDK_INT >= 29
                ? saveMediaStore(c, src, name, mime, fmt.isVideo, album, dateTaken)
                : saveLegacy(c, src, name, mime, album, dateTaken);
        r.mime = mime;
        return r;
    }

    private static Result saveMediaStore(Context c, File src, String name, String mime, boolean video, int album,
                                         long dateTaken) throws IOException {
        ContentResolver cr = c.getContentResolver();
        Uri collection = video
                ? MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                : MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        String rel = relativeDir(album);
        ContentValues v = new ContentValues();
        v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
        v.put(MediaStore.MediaColumns.RELATIVE_PATH, rel + "/");
        v.put(MediaStore.MediaColumns.IS_PENDING, 1);
        if (dateTaken > 0) v.put(MediaStore.MediaColumns.DATE_TAKEN, dateTaken);

        Uri uri;
        try {
            uri = cr.insert(collection, v);
        } catch (RuntimeException e) {
            throw new UserException("Galeri kaydı oluşturulamadı (" + mime + ")", e);
        }
        if (uri == null) throw new UserException("Galeri kaydı oluşturulamadı");

        boolean ok = false;
        try {
            OutputStream os = cr.openOutputStream(uri, "w");
            if (os == null) throw new UserException("Galeriye yazılamadı");
            try {
                copy(src, os);
            } finally {
                os.close();
            }
            ContentValues done = new ContentValues();
            done.put(MediaStore.MediaColumns.IS_PENDING, 0);
            cr.update(uri, done, null, null);
            ok = true;
        } catch (RuntimeException e) {
            throw new UserException("Galeriye yazılamadı", e);
        } finally {
            if (!ok) {
                try {
                    cr.delete(uri, null, null);
                } catch (RuntimeException ignored) {
                    // the pending row expires on its own
                }
            }
        }

        Result r = new Result();
        r.uri = uri;
        r.displayPath = rel + "/" + name;
        Cursor cur = null;
        try {
            cur = cr.query(uri, new String[]{MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.RELATIVE_PATH},
                    null, null, null);
            if (cur != null && cur.moveToFirst()) {
                String n = cur.getString(0);
                String p = cur.getString(1);
                if (n != null && p != null) {
                    if (p.endsWith("/")) p = p.substring(0, p.length() - 1);
                    r.displayPath = p + "/" + n;
                }
            }
        } catch (RuntimeException ignored) {
            // keep the requested path
        } finally {
            if (cur != null) cur.close();
        }
        return r;
    }

    @SuppressWarnings("deprecation")
    private static Result saveLegacy(Context c, File src, String name, String mime, int album, long dateTaken)
            throws IOException {
        if (!Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState())) {
            throw new UserException("Paylaşılan depolama alanı kullanılamıyor");
        }
        File base = Environment.getExternalStoragePublicDirectory(
                album == Prefs.ALBUM_APP ? Environment.DIRECTORY_PICTURES : Environment.DIRECTORY_DCIM);
        File dir = new File(base, album == Prefs.ALBUM_APP ? "MetaKamera" : "Camera");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new UserException("Klasör oluşturulamadı: " + relativeDir(album));
        File dest = uniqueFile(dir, name);
        boolean ok = false;
        try {
            OutputStream os = new FileOutputStream(dest);
            try {
                copy(src, os);
            } finally {
                os.close();
            }
            ok = true;
        } finally {
            if (!ok) {
                //noinspection ResultOfMethodCallIgnored
                dest.delete();
            }
        }
        if (dateTaken > 0) {
            //noinspection ResultOfMethodCallIgnored
            dest.setLastModified(dateTaken);
        }

        final Uri[] scanned = new Uri[1];
        final CountDownLatch latch = new CountDownLatch(1);
        try {
            MediaScannerConnection.scanFile(c, new String[]{dest.getAbsolutePath()}, new String[]{mime},
                    new MediaScannerConnection.OnScanCompletedListener() {
                        @Override
                        public void onScanCompleted(String path, Uri uri) {
                            scanned[0] = uri;
                            latch.countDown();
                        }
                    });
            latch.await(15, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException ignored) {
            // the file is saved; the gallery will pick it up on its next scan
        }
        Result r = new Result();
        r.uri = scanned[0];
        r.displayPath = relativeDir(album) + "/" + dest.getName();
        return r;
    }

    private static File uniqueFile(File dir, String name) {
        File f = new File(dir, name);
        if (!f.exists()) return f;
        String base = OutputNames.baseNameOf(name);
        String ext = OutputNames.extensionOf(name);
        for (int i = 1; i < 10000; i++) {
            f = new File(dir, base + " (" + i + ")" + (ext.length() > 0 ? "." + ext : ""));
            if (!f.exists()) return f;
        }
        return new File(dir, base + "_" + System.currentTimeMillis() + (ext.length() > 0 ? "." + ext : ""));
    }

    private static void copy(File src, OutputStream os) throws IOException {
        InputStream in = new FileInputStream(src);
        try {
            byte[] buf = new byte[256 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
            os.flush();
        } finally {
            in.close();
        }
    }
}
