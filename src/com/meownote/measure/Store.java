package com.meownote.measure;

import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 文件层：所有数据放在 App 专属外部目录，Android 10 上无需任何存储权限。 */
public class Store {

    private static final Charset UTF8 = Charset.forName("UTF-8");

    public static File rootDir(Context ctx) {
        File base = ctx.getExternalFilesDir(null);
        if (base == null) base = ctx.getFilesDir();
        File f = new File(base, "MeasureNote");
        if (!f.exists()) f.mkdirs();
        return f;
    }

    public static File projectsDir(Context ctx) {
        File f = new File(rootDir(ctx), "projects");
        if (!f.exists()) f.mkdirs();
        return f;
    }

    public static File projectDir(Context ctx, String projectId) {
        File f = new File(projectsDir(ctx), projectId);
        if (!f.exists()) f.mkdirs();
        return f;
    }

    public static File projectFile(Context ctx, String projectId) {
        return new File(projectDir(ctx, projectId), "project.json");
    }

    /* ---------- 读写项目 ---------- */

    public static void save(Context ctx, Project p) throws Exception {
        File f = projectFile(ctx, p.id);
        byte[] data = p.toJson().toString().getBytes(UTF8);
        FileOutputStream fos = new FileOutputStream(f);
        try {
            fos.write(data);
            fos.flush();
        } finally {
            try { fos.close(); } catch (IOException ignored) { }
        }
    }

    public static Project load(Context ctx, String projectId) throws Exception {
        File f = projectFile(ctx, projectId);
        if (!f.exists()) return null;
        String s = readText(f);
        return Project.fromJson(new JSONObject(s));
    }

    public static List<Project> listAll(Context ctx) {
        List<Project> out = new ArrayList<>();
        File dir = projectsDir(ctx);
        File[] subs = dir.listFiles();
        if (subs == null) return out;
        for (File sub : subs) {
            if (!sub.isDirectory()) continue;
            try {
                Project p = load(ctx, sub.getName());
                if (p != null) out.add(p);
            } catch (Exception ignored) { }
        }
        Collections.sort(out, new java.util.Comparator<Project>() {
            @Override
            public int compare(Project a, Project b) {
                return Long.compare(b.createdAt, a.createdAt);
            }
        });
        return out;
    }

    public static void deleteProject(Context ctx, String projectId) {
        deleteRecursive(new File(projectsDir(ctx), projectId));
    }

    public static void deleteItem(Context ctx, Project p, Project.Item it) {
        if (it.imageFile != null) {
            File img = new File(projectDir(ctx, p.id), it.imageFile);
            if (img.exists()) img.delete();
        }
        p.items.remove(it);
    }

    public static void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] cs = f.listFiles();
            if (cs != null) for (File c : cs) deleteRecursive(c);
        }
        f.delete();
    }

    /* ---------- 图片 ---------- */

    public static String importImage(Context ctx, Project p, File src) throws IOException {
        String name = "img_" + System.currentTimeMillis() + ".jpg";
        File dst = new File(projectDir(ctx, p.id), name);
        copyFile(src, dst);
        return name;
    }

    public static String importImageStream(Context ctx, Project p, InputStream in) throws IOException {
        String name = "img_" + System.currentTimeMillis() + ".jpg";
        File dst = new File(projectDir(ctx, p.id), name);
        FileOutputStream fos = new FileOutputStream(dst);
        try {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
            fos.flush();
        } finally {
            try { in.close(); } catch (IOException ignored) { }
            try { fos.close(); } catch (IOException ignored) { }
        }
        return name;
    }

    public static File imageFile(Context ctx, Project p, Project.Item it) {
        return new File(projectDir(ctx, p.id), it.imageFile);
    }

    public static Bitmap decode(File f, int maxSide) {
        if (f == null || !f.exists()) return null;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getAbsolutePath(), o);
        int w = o.outWidth, h = o.outHeight;
        int sample = 1;
        while (maxSide > 0 && (w / sample) > maxSide && (h / sample) > maxSide) sample *= 2;
        BitmapFactory.Options o2 = new BitmapFactory.Options();
        o2.inSampleSize = sample;
        return BitmapFactory.decodeFile(f.getAbsolutePath(), o2);
    }

    public static Bitmap decodeFull(File f) {
        if (f == null || !f.exists()) return null;
        return BitmapFactory.decodeFile(f.getAbsolutePath());
    }

    /* ---------- 工具 ---------- */

    public static String readText(File f) throws IOException {
        java.io.FileInputStream in = new java.io.FileInputStream(f);
        try {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return new String(bos.toByteArray(), UTF8);
        } finally {
            try { in.close(); } catch (IOException ignored) { }
        }
    }

    public static void copyFile(File src, File dst) throws IOException {
        java.io.FileInputStream in = new java.io.FileInputStream(src);
        FileOutputStream out = new FileOutputStream(dst);
        try {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.flush();
        } finally {
            try { in.close(); } catch (IOException ignored) { }
            try { out.close(); } catch (IOException ignored) { }
        }
    }

    /** 从 content:// 流复制到临时文件（拍照/选图后统一走这里） */
    public static File copyToTemp(Context ctx, InputStream in) throws IOException {
        File tmp = new File(ctx.getCacheDir(), "import_" + System.currentTimeMillis() + ".jpg");
        FileOutputStream fos = new FileOutputStream(tmp);
        try {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
            fos.flush();
        } finally {
            try { in.close(); } catch (IOException ignored) { }
            try { fos.close(); } catch (IOException ignored) { }
        }
        return tmp;
    }

    /** 导出的 PDF 目录（App 专属，仅 API<29 回退用） */
    public static File exportDir(Context ctx) {
        File f = new File(rootDir(ctx), "export");
        if (!f.exists()) f.mkdirs();
        return f;
    }

    public static final String DOWNLOAD_SUB = "MeasureNote";

    /** 把 PDF 保存到公共下载目录后的结果 */
    public static class SavedPdf {
        public Uri uri;            // 可直接用于 ACTION_VIEW
        public String displayPath; // 给人看的短路径
        public String error;
    }

    /**
     * 把缓存里的 PDF 转存到公共 Download/MeasureNote/。
     * Android 10+ 走 MediaStore（无需权限，卸载 App 也不会被删）；
     * Android 9 及以下回退到 App 专属 export 目录。
     */
    public static SavedPdf savePdf(Context ctx, File src, String displayName) {
        SavedPdf r = new SavedPdf();
        if (src == null || !src.exists()) {
            r.error = "源文件不存在";
            return r;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return savePdfQ(ctx, src, displayName);
        }
        // ---- 回退：API < 29 ----
        try {
            File dir = new File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS), DOWNLOAD_SUB);
            if (!dir.exists()) dir.mkdirs();
            File dst = new File(dir, displayName);
            copyFile(src, dst);
            r.uri = Uri.fromFile(dst);
            r.displayPath = "Download/" + DOWNLOAD_SUB + "/" + displayName;
            return r;
        } catch (Exception e) {
            r.error = e.getClass().getSimpleName() + ": " + e.getMessage();
            return r;
        }
    }

    @android.annotation.TargetApi(Build.VERSION_CODES.Q)
    private static SavedPdf savePdfQ(Context ctx, File src, String displayName) {
        SavedPdf r = new SavedPdf();
        Uri uri = null;
        try {
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.Downloads.DISPLAY_NAME, displayName);
            cv.put(MediaStore.Downloads.MIME_TYPE, "application/pdf");
            cv.put(MediaStore.Downloads.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + "/" + DOWNLOAD_SUB);
            cv.put(MediaStore.Downloads.IS_PENDING, 1);
            uri = ctx.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
            if (uri == null) {
                r.error = "无法在下载目录创建文件";
                return r;
            }
            java.io.InputStream in = new java.io.FileInputStream(src);
            java.io.OutputStream out = ctx.getContentResolver().openOutputStream(uri);
            try {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                out.flush();
            } finally {
                try { in.close(); } catch (IOException ignored) { }
                try { out.close(); } catch (IOException ignored) { }
            }
            ContentValues done = new ContentValues();
            done.put(MediaStore.Downloads.IS_PENDING, 0);
            ctx.getContentResolver().update(uri, done, null, null);

            r.uri = uri;
            r.displayPath = "Download/" + DOWNLOAD_SUB + "/" + displayName;
            return r;
        } catch (Exception e) {
            if (uri != null) {
                try { ctx.getContentResolver().delete(uri, null, null); } catch (Exception ignored) { }
            }
            r.error = e.getClass().getSimpleName() + ": " + e.getMessage();
            return r;
        }
    }
}
