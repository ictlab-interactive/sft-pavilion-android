package com.sftpavilion.updates;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;

/** Read-only, grant-only access to one verified APK; no arbitrary file paths. */
public final class UpdateFileProvider extends ContentProvider {
    static final String AUTHORITY = ReleaseInfo.PACKAGE + ".updates";
    static final Uri URI = Uri.parse("content://" + AUTHORITY + "/apk/SFT-Pavilion-update.apk");
    static File apk(android.content.Context c) { return new File(c.getCacheDir(), "SFT-Pavilion-update.apk"); }
    private void requireUri(Uri uri) {
        if (!URI.equals(uri)) throw new IllegalArgumentException("Unknown update file");
    }
    @Override public boolean onCreate() { return true; }
    @Override public String getType(Uri uri) { requireUri(uri); return "application/vnd.android.package-archive"; }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        requireUri(uri);
        if (!"r".equals(mode)) throw new FileNotFoundException("Read only");
        return ParcelFileDescriptor.open(apk(getContext()), ParcelFileDescriptor.MODE_READ_ONLY);
    }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) {
        requireUri(uri);
        String[] columns = projection == null ? new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE} : projection;
        MatrixCursor c = new MatrixCursor(columns);
        Object[] row = new Object[columns.length];
        for (int i = 0; i < columns.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(columns[i])) row[i] = "SFT-Pavilion.apk";
            else if (OpenableColumns.SIZE.equals(columns[i])) row[i] = apk(getContext()).length();
        }
        c.addRow(row); return c;
    }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String where, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String where, String[] args) { throw new UnsupportedOperationException(); }
}
