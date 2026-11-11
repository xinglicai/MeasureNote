package com.meownote.measure;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

public class ProjectActivity extends Activity {

    private static final int REQ_PICK = 1001;
    private static final int REQ_CAPTURE = 1002;

    private Project project;
    private String projectId;
    private GridView grid;
    private TextView empty;
    private GridAdapter adapter;
    private final List<Project.Item> items = new ArrayList<>();
    private int cellHeight = 360;

    private File pendingCaptureFile;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_project);

        projectId = getIntent().getStringExtra("projectId");
        grid = findViewById(R.id.grid);
        empty = findViewById(R.id.empty);

        int screenW = getResources().getDisplayMetrics().widthPixels;
        cellHeight = (screenW - dp(8 + 8)) / 3;
        adapter = new GridAdapter();
        grid.setAdapter(adapter);

        grid.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                openEditor(items.get(position));
            }
        });

        grid.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                showItemMenu(items.get(position));
                return true;
            }
        });
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadProject();
    }

    private void loadProject() {
        try {
            project = Store.load(this, projectId);
        } catch (Exception e) {
            project = null;
        }
        if (project == null) {
            Toast.makeText(this, "项目不存在", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        setTitle(project.name);
        invalidateOptionsMenu();
        refresh();
    }

    private void refresh() {
        items.clear();
        items.addAll(project.items);
        adapter.notifyDataSetChanged();
        boolean isEmpty = items.isEmpty();
        empty.setVisibility(isEmpty ? View.VISIBLE : View.GONE);
        grid.setVisibility(isEmpty ? View.GONE : View.VISIBLE);
        if (isEmpty) empty.setText(R.string.no_photos);
    }

    private void openEditor(Project.Item it) {
        Intent i = new Intent(this, EditActivity.class);
        i.putExtra("projectId", projectId);
        i.putExtra("itemId", it.id);
        startActivity(i);
    }

    /* ---------------- 菜单 ---------------- */

    @Override
    public boolean onCreateOptionsMenu(Menu m) {
        m.add(0, 1, 0, "拍照");
        m.add(0, 2, 1, "从相册选择");
        m.add(0, 3, 2, R.string.export_project_pdf);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case 1:
                capture();
                return true;
            case 2:
                pick();
                return true;
            case 3:
                exportPdf();
                return true;
        }
        return super.onOptionsItemSelected(item);
    }

    /* ---------------- 拍照 / 相册 ---------------- */

    private void pick() {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.setType("image/*");
        i.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(Intent.createChooser(i, "选择图片"), REQ_PICK);
        } catch (android.content.ActivityNotFoundException e) {
            Toast.makeText(this, "没有可用的图片选择器", Toast.LENGTH_LONG).show();
        }
    }

    private void capture() {
        try {
            File tmp = new File(getCacheDir(), "cap_" + System.currentTimeMillis() + ".jpg");
            pendingCaptureFile = tmp;
            Uri outUri = FileProvider2.forCache(tmp);

            Intent i = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            i.putExtra(MediaStore.EXTRA_OUTPUT, outUri);
            i.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            android.util.Log.d("MN", "capture uri=" + outUri + " tmp=" + tmp.getAbsolutePath());
            startActivityForResult(i, REQ_CAPTURE);
            android.util.Log.d("MN", "capture started");
        } catch (android.content.ActivityNotFoundException e) {
            android.util.Log.e("MN", "capture ActivityNotFound", e);
            Toast.makeText(this, "这台设备没有可用的相机应用", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            android.util.Log.e("MN", "capture exception", e);
            Toast.makeText(this, "无法启动相机: " + e.getClass().getSimpleName()
                    + " " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);

        if (req == REQ_PICK) {
            if (res != RESULT_OK || data == null || data.getData() == null) return;
            try {
                InputStream in = getContentResolver().openInputStream(data.getData());
                String name = Store.importImageStream(this, project, in);
                addItem(name);
            } catch (Exception e) {
                Toast.makeText(this, "导入失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
            return;
        }

        if (req == REQ_CAPTURE) {
            File shot = pendingCaptureFile;
            pendingCaptureFile = null;
            if (res != RESULT_OK) {
                if (shot != null && shot.exists()) shot.delete();
                Toast.makeText(this, "已取消拍照", Toast.LENGTH_SHORT).show();
                return;
            }
            if (shot == null || !shot.exists() || shot.length() == 0) {
                Toast.makeText(this, "没有拿到照片（相机可能不支持写入指定路径）", Toast.LENGTH_LONG).show();
                if (shot != null) shot.delete();
                return;
            }
            try {
                String name = Store.importImage(this, project, shot);
                addItem(name);
            } catch (Exception e) {
                Toast.makeText(this, "保存失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
            } finally {
                try { shot.delete(); } catch (Exception ignored) { }
            }
        }
    }

    private void addItem(String imageFile) {
        Project.Item it = new Project.Item();
        it.imageFile = imageFile;
        project.items.add(it);
        try {
            Store.save(this, project);
            refresh();
            openEditor(it);
        } catch (Exception e) {
            Toast.makeText(this, "保存失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void showItemMenu(final Project.Item it) {
        String[] opts = {"编辑", "导出本图 PDF", "删除"};
        new AlertDialog.Builder(this)
                .setItems(opts, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        if (which == 0) openEditor(it);
                        else if (which == 1) exportSingle(it);
                        else confirmDeleteItem(it);
                    }
                })
                .show();
    }

    private void confirmDeleteItem(final Project.Item it) {
        new AlertDialog.Builder(this)
                .setTitle("删除图片")
                .setMessage("确定删除这张照片及其标注？")
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.delete, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        Store.deleteItem(ProjectActivity.this, project, it);
                        try {
                            Store.save(ProjectActivity.this, project);
                        } catch (Exception ignored) { }
                        refresh();
                    }
                })
                .show();
    }

    /* ---------------- 导出 ---------------- */

    private void exportPdf() {
        if (project.items.isEmpty()) {
            Toast.makeText(this, "没有图片可导出", Toast.LENGTH_SHORT).show();
            return;
        }
        doExport(new ArrayList<>(project.items), project.name);
    }

    private void exportSingle(Project.Item it) {
        List<Project.Item> one = new ArrayList<>();
        one.add(it);
        doExport(one, project.name + "_单张");
    }

    private void doExport(final List<Project.Item> targets, final String prefix) {
        Toast.makeText(this, "正在导出…", Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                final PdfExporter.Result r = PdfExporter.export(ProjectActivity.this, project, targets, prefix);
                final Store.SavedPdf saved = (r.file == null) ? null
                        : Store.savePdf(ProjectActivity.this, r.file, r.file.getName());
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (saved != null && saved.uri != null) {
                            showExported(r.pages, saved);
                        } else {
                            String msg = (r.file == null)
                                    ? (r.error == null ? "未知错误" : r.error)
                                    : (saved == null ? "保存失败" : saved.error);
                            Toast.makeText(ProjectActivity.this, "导出失败：" + msg, Toast.LENGTH_LONG).show();
                        }
                    }
                });
            }
        }).start();
    }
    private void showExported(int pages, final Store.SavedPdf saved) {
        new AlertDialog.Builder(this)
                .setTitle("导出完成")
                .setMessage("共 " + pages + " 页\n" + saved.displayPath)
                .setCancelable(true)
                .setNegativeButton("知道了", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        d.dismiss();
                    }
                })
                .setPositiveButton("打开", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        openPdf(saved.uri);
                    }
                })
                .show();
    }    private void openPdf(Uri uri) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, "application/pdf");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(i);
        } catch (android.content.ActivityNotFoundException e) {
            Toast.makeText(this, "没有能打开 PDF 的应用", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "打开失败：" + e.getClass().getSimpleName(), Toast.LENGTH_LONG).show();
        }
    }    /* ---------------- adapter ---------------- */

    private class GridAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public Object getItem(int i) {
            return items.get(i);
        }

        @Override
        public long getItemId(int i) {
            return i;
        }

        private class Holder {
            ImageView iv;
            TextView badge;
        }

        @Override
        public View getView(int i, View cv, ViewGroup parent) {
            Holder h;
            if (cv == null) {
                android.widget.FrameLayout fl = new android.widget.FrameLayout(ProjectActivity.this);
                ImageView iv = new ImageView(ProjectActivity.this);
                iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                iv.setBackgroundColor(0xFFE8E8E8);
                fl.addView(iv, new android.widget.FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, cellHeight));

                TextView badge = new TextView(ProjectActivity.this);
                badge.setTextSize(11);
                badge.setTextColor(0xFFFFFFFF);
                badge.setBackgroundColor(0x99000000);
                badge.setPadding(8, 2, 8, 2);
                android.widget.FrameLayout.LayoutParams lp =
                        new android.widget.FrameLayout.LayoutParams(
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.gravity = android.view.Gravity.END | android.view.Gravity.TOP;
                fl.addView(badge, lp);

                h = new Holder();
                h.iv = iv;
                h.badge = badge;
                fl.setTag(h);
                cv = fl;
            } else {
                h = (Holder) cv.getTag();
            }

            Project.Item it = items.get(i);
            File f = Store.imageFile(ProjectActivity.this, project, it);
            Bitmap bm = Store.decode(f, 480);
            if (bm != null) h.iv.setImageBitmap(bm);
            else h.iv.setImageDrawable(null);

            if (it.anns.isEmpty()) {
                h.badge.setVisibility(View.GONE);
            } else {
                h.badge.setVisibility(View.VISIBLE);
                h.badge.setText(String.valueOf(it.anns.size()));
            }
            return cv;
        }
    }
}
