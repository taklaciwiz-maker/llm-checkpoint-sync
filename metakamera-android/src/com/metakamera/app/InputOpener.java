package com.metakamera.app;

import android.Manifest;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.provider.OpenableColumns;

import com.metakamera.core.ByteSource;
import com.metakamera.core.FileChannelSource;

import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.FileChannel;

/**
 * Opens picked content as a random-access {@link ByteSource} without loading it into memory.
 * All methods do IO: call them on a background thread only.
 */
final class InputOpener {

    private static final String MEDIA_DOCUMENTS = "com.android.providers.media.documents";
    private static final String DOWNLOAD_DOCUMENTS = "com.android.providers.downloads.documents";

    private InputOpener() {
    }

    /** Display name, size and MIME type reported by the provider. */
    static final class Meta {
        String name;
        long size = -1;
        String mime;
    }

    static Meta queryMeta(Context c, Uri uri) {
        Meta m = new Meta();
        ContentResolver cr = c.getContentResolver();
        if ("file".equals(uri.getScheme())) {
            String p = uri.getPath();
            if (p != null) {
                File f = new File(p);
                m.name = f.getName();
                m.size = f.length();
            }
            return m;
        }
        Cursor cur = null;
        try {
            cur = cr.query(uri, new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}, null, null, null);
            if (cur != null && cur.moveToFirst()) {
                int ni = cur.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                int si = cur.getColumnIndex(OpenableColumns.SIZE);
                if (ni >= 0 && !cur.isNull(ni)) m.name = cur.getString(ni);
                if (si >= 0 && !cur.isNull(si)) m.size = cur.getLong(si);
            }
        } catch (Exception ignored) {
            // some providers reject projections; the name falls back to the uri
        } finally {
            if (cur != null) {
                try {
                    cur.close();
                } catch (Exception ignored) {
                    // nothing to do
                }
            }
        }
        try {
            m.mime = cr.getType(uri);
        } catch (Exception ignored) {
            // unknown type
        }
        return m;
    }

    /**
     * On API 29+ with ACCESS_MEDIA_LOCATION granted, returns a MediaStore "require original" uri for
     * MediaStore-backed items so EXIF GPS is not redacted. Falls back to {@code uri} on any problem.
     */
    static Uri resolveReadUri(Context c, Uri uri) {
        if (Build.VERSION.SDK_INT < 29 || uri == null) return uri;
        try {
            if (c.checkSelfPermission(Manifest.permission.ACCESS_MEDIA_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                return uri;
            }
            String auth = uri.getAuthority();
            Uri media = null;
            if (MediaStore.AUTHORITY.equals(auth)) {
                media = uri;
            } else if ((MEDIA_DOCUMENTS.equals(auth) || DOWNLOAD_DOCUMENTS.equals(auth))
                    && DocumentsContract.isDocumentUri(c, uri)) {
                media = MediaStore.getMediaUri(c, uri);
            }
            if (media == null) return uri;
            Uri original = MediaStore.setRequireOriginal(media);
            ParcelFileDescriptor probe = c.getContentResolver().openFileDescriptor(original, "r");
            if (probe == null) return uri;
            probe.close();
            return original;
        } catch (Exception e) {
            return uri;
        }
    }

    /** Opens the item for reading (reuses a temp copy when the provider only offers a stream). */
    static ByteSource open(Context c, MediaItem item) throws IOException {
        if (item.tempCopy != null && item.tempCopy.isFile()) return FileChannelSource.open(item.tempCopy);
        Uri read = item.readUri != null ? item.readUri : item.uri;
        try {
            return openUri(c, read, item);
        } catch (IOException e) {
            if (read.equals(item.uri)) throw e;
            item.readUri = item.uri;
            return openUri(c, item.uri, item);
        } catch (SecurityException e) {
            if (read.equals(item.uri)) throw new UserException("Dosyaya erişim izni yok", e);
            item.readUri = item.uri;
            return openUri(c, item.uri, item);
        }
    }

    private static ByteSource openUri(Context c, Uri uri, MediaItem item) throws IOException {
        if ("file".equals(uri.getScheme())) {
            String p = uri.getPath();
            if (p == null) throw new FileNotFoundException("Dosya yolu yok");
            return FileChannelSource.open(new File(p));
        }
        ParcelFileDescriptor pfd;
        try {
            pfd = c.getContentResolver().openFileDescriptor(uri, "r");
        } catch (IllegalArgumentException e) {
            throw new UserException("Dosya açılamadı", e);
        }
        if (pfd == null) throw new UserException("Dosya açılamadı");
        if (pfd.getStatSize() >= 0) {
            final FileInputStream fis = new FileInputStream(pfd.getFileDescriptor());
            final ParcelFileDescriptor owned = pfd;
            try {
                FileChannel ch = fis.getChannel();
                ch.position(0); // throws on pipes/sockets
                return new FileChannelSource(ch, new Closeable() {
                    @Override
                    public void close() throws IOException {
                        try {
                            fis.close();
                        } finally {
                            owned.close();
                        }
                    }
                });
            } catch (IOException e) {
                closeQuietly(fis);
                closeQuietly(pfd);
                // not seekable after all: reopen as a stream and copy below
                pfd = c.getContentResolver().openFileDescriptor(uri, "r");
                if (pfd == null) throw new UserException("Dosya açılamadı");
            }
        }
        return FileChannelSource.open(copyToTemp(c, pfd, item));
    }

    /** Streams a non-seekable descriptor into cacheDir/input (consumes and closes {@code pfd}). */
    private static File copyToTemp(Context c, ParcelFileDescriptor pfd, MediaItem item) throws IOException {
        File dir = new File(c.getCacheDir(), "input");
        if (!dir.isDirectory() && !dir.mkdirs()) {
            closeQuietly(pfd);
            throw new UserException("Geçici klasör oluşturulamadı");
        }
        File tmp = new File(dir, "src-" + item.id + ".bin");
        InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(pfd);
        boolean ok = false;
        try {
            OutputStream out = new FileOutputStream(tmp);
            try {
                byte[] buf = new byte[256 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            } finally {
                out.close();
            }
            ok = true;
        } finally {
            closeQuietly(in);
            if (!ok) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
        }
        item.tempCopy = tmp;
        return tmp;
    }

    static void closeQuietly(Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (Exception ignored) {
            // nothing to do
        }
    }
}
