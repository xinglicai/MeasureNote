package com.meownote.measure;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.List;

/**
 * 极简文件共享 Provider，替代 androidx FileProvider（本项目零第三方依赖）。
 * 两种 URI：
 *   content://com.meownote.measure.files/cache/<文件名>   -> cacheDir  （拍照临时文件）
 *   content://com.meownote.measure.files/export/<文件名>  -> exportDir （导出的 PDF）
 */
public class FileProvider2 extends ContentProvider {

    public static final String AUTHORITY = "com.meownote.measure.files";
    private static final String SCHEME = "content://";

    public static Uri forCache(File f) {
        return Uri.parse(SCHEME + AUTHORITY + "/cache/" + Uri.encode(f.getName()));
    }

    public static Uri forExport(File f) {
        return Uri.parse(SCHEME + AUTHORITY + "/export/" + Uri.encode(f.getName()));
    }

    private File resolve(Uri uri) {
        List<String> seg = uri.getPathSegments();
        if (seg == null || seg.size() < 2) return null;
        String area = seg.get(0);
        String name = seg.get(1);
        if ("cache".equals(area)) {
            return new File(getContext().getCacheDir(), name);
        }
        if ("export".equals(area)) {
            return new File(Store.exportDir(getContext()), name);
        }
        return null;
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File f = resolve(uri);
        if (f == null) throw new FileNotFoundException("bad uri: " + uri);
        boolean write = mode != null && mode.contains("w");
        int flags = ParcelFileDescriptor.MODE_READ_ONLY;
        if (write) {
            File dir = f.getParentFile();
            if (dir != null && !dir.exists()) dir.mkdirs();
            flags = ParcelFileDescriptor.MODE_READ_WRITE
                    | ParcelFileDescriptor.MODE_CREATE
                    | ParcelFileDescriptor.MODE_TRUNCATE;
        }
        return ParcelFileDescriptor.open(f, flags);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        File f = resolve(uri);
        String[] cols = projection != null ? projection
                : new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        MatrixCursor c = new MatrixCursor(cols, 1);
        Object[] row = new Object[cols.length];
        for (int i = 0; i < cols.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(cols[i])) row[i] = f == null ? "" : f.getName();
            else if (OpenableColumns.SIZE.equals(cols[i])) row[i] = f == null ? 0 : f.length();
            else row[i] = null;
        }
        c.addRow(row);
        return c;
    }

    @Override
    public String getType(Uri uri) {
        List<String> seg = uri.getPathSegments();
        if (seg != null && seg.size() > 1) {
            String n = seg.get(1);
            if (n != null && n.toLowerCase().endsWith(".pdf")) return "application/pdf";
        }
        return "image/jpeg";
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
