package com.afs.guard;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;

/** 热爱券照片的私有文件通道：只允许相机写入固定目录里的 UUID.jpg。 */
public final class LovePhotoProvider extends ContentProvider {
    static final String AUTHORITY = "com.afs.guard.love.files";
    private static final String DIR = "love-photos";
    private static final String NAME = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\.jpg";

    static File dir(android.content.Context c) {
        return new File(c.getFilesDir(), DIR);
    }

    static File file(android.content.Context c, String name) {
        if (name == null || !name.matches(NAME)) throw new IllegalArgumentException("Invalid photo");
        return new File(dir(c), name);
    }

    static Uri uri(android.content.Context c, File file) {
        return new Uri.Builder().scheme("content").authority(AUTHORITY)
                .appendPath(file.getName()).build();
    }

    private String name(Uri uri) {
        if (uri == null || uri.getPathSegments().size() != 1
                || !uri.getLastPathSegment().matches(NAME)) {
            throw new IllegalArgumentException("Invalid photo");
        }
        return uri.getLastPathSegment();
    }

    @Override public boolean onCreate() { return true; }

    @Override public String getType(Uri uri) {
        name(uri);
        return "image/jpeg";
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        try {
            File file = file(getContext(), name(uri));
            if (mode == null) mode = "r";
            int flags;
            if ("r".equals(mode)) flags = ParcelFileDescriptor.MODE_READ_ONLY;
            else if ("w".equals(mode) || "wt".equals(mode))
                flags = ParcelFileDescriptor.MODE_CREATE | ParcelFileDescriptor.MODE_TRUNCATE | ParcelFileDescriptor.MODE_WRITE_ONLY;
            else if ("rw".equals(mode) || "rwt".equals(mode))
                flags = ParcelFileDescriptor.MODE_CREATE | ParcelFileDescriptor.MODE_TRUNCATE | ParcelFileDescriptor.MODE_READ_WRITE;
            else throw new FileNotFoundException("Unsupported mode");
            File parent = file.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs())
                throw new FileNotFoundException("Photo directory unavailable");
            return ParcelFileDescriptor.open(file, flags);
        } catch (RuntimeException error) {
            throw new FileNotFoundException("Photo unavailable");
        }
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection,
                                String[] args, String order) {
        try {
            File file = file(getContext(), name(uri));
            String[] columns = projection == null
                    ? new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}
                    : projection;
            MatrixCursor cursor = new MatrixCursor(columns);
            Object[] values = new Object[columns.length];
            for (int i = 0; i < columns.length; i++) {
                if (OpenableColumns.DISPLAY_NAME.equals(columns[i])) values[i] = file.getName();
                else if (OpenableColumns.SIZE.equals(columns[i])) values[i] = file.length();
            }
            cursor.addRow(values);
            return cursor;
        } catch (RuntimeException error) {
            return null;
        }
    }

    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
}
