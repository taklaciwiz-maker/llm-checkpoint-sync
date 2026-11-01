package com.metakamera.app;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.webkit.MimeTypeMap;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Minimal read-only provider for sharing edited files:
 * content://com.metakamera.app.files/edited/&lt;dir&gt;/&lt;file&gt; maps to cacheDir/edited/&lt;dir&gt;/&lt;file&gt;.
 * Not exported; other apps get access only through per-uri grants.
 */
public final class EditedFileProvider extends ContentProvider {

    static final String AUTHORITY = "com.metakamera.app.files";
    private static final String ROOT = "edited";
    private static final String[] DEFAULT_COLUMNS = {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};

    static File editedDir(Context c) {
        return new File(c.getCacheDir(), ROOT);
    }

    /** Content uri for a file directly inside a sub-directory of cacheDir/edited, or null. */
    static Uri uriFor(Context c, File f) {
        if (f == null) return null;
        File dir = f.getParentFile();
        if (dir == null || dir.getParentFile() == null) return null;
        if (!dir.getParentFile().getAbsolutePath().equals(editedDir(c).getAbsolutePath())) return null;
        return new Uri.Builder().scheme("content").authority(AUTHORITY)
                .appendPath(ROOT).appendPath(dir.getName()).appendPath(f.getName()).build();
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    private File fileFor(Uri uri) {
        Context c = getContext();
        if (c == null || uri == null) return null;
        List<String> seg = uri.getPathSegments();
        if (seg == null || seg.size() != 3 || !ROOT.equals(seg.get(0))) return null;
        File base = editedDir(c);
        File f = new File(new File(base, seg.get(1)), seg.get(2));
        try {
            String basePath = base.getCanonicalPath() + File.separator;
            if (!f.getCanonicalPath().startsWith(basePath)) return null;
        } catch (IOException e) {
            return null;
        }
        return f;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        File f = fileFor(uri);
        if (f == null || !f.isFile()) return null;
        String[] cols = projection == null ? DEFAULT_COLUMNS : projection;
        List<String> names = new ArrayList<String>();
        List<Object> values = new ArrayList<Object>();
        for (String col : cols) {
            if (OpenableColumns.DISPLAY_NAME.equals(col)) {
                names.add(col);
                values.add(f.getName());
            } else if (OpenableColumns.SIZE.equals(col)) {
                names.add(col);
                values.add(f.length());
            }
        }
        MatrixCursor mc = new MatrixCursor(names.toArray(new String[names.size()]), 1);
        mc.addRow(values.toArray());
        return mc;
    }

    @Override
    public String getType(Uri uri) {
        File f = fileFor(uri);
        if (f == null) return null;
        return mimeOf(f.getName());
    }

    static String mimeOf(String name) {
        String ext = OutputNames.extensionOf(name).toLowerCase(Locale.ROOT);
        String m = null;
        try {
            m = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
        } catch (RuntimeException ignored) {
            // unknown
        }
        if (m != null) return m;
        if (ext.equals("heic") || ext.equals("heif")) return "image/heic";
        if (ext.equals("avif")) return "image/avif";
        if (ext.equals("webp")) return "image/webp";
        if (ext.equals("mov")) return "video/quicktime";
        return "application/octet-stream";
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (mode == null || !mode.equals("r")) throw new FileNotFoundException("Salt okunur");
        File f = fileFor(uri);
        if (f == null || !f.isFile()) throw new FileNotFoundException("Dosya bulunamadı");
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
