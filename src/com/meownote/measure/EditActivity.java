package com.meownote.measure;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class EditActivity extends Activity implements AnnotationView.Listener {

    /** 预设调色板 */
    private static final int[] PALETTE = {
            0xFFFF3B30, 0xFFFF9500, 0xFFFFCC00, 0xFF34C759, 0xFF32ADE6, 0xFF1E6FD9,
            0xFFAF52DE, 0xFFFF2D55, 0xFFFFFFFF, 0xFF8E8E93, 0xFF1A1A1A, 0xFF00A86B
    };
    private static final float[] WIDTHS = {1.5f, 2.2f, 3.5f, 5.0f};
    private static final String[] WIDTH_LABELS = {"细", "中", "粗", "特粗"};

    private Project project;
    /** 常驻的蓝牙测距仪句柄：整个编辑页共用一个连接 */
    private BleMeter bleMeter;
    private Project.Item item;
    private String projectId, itemId;

    private AnnotationView canvas;
    private TextView hint, tvStyle, tvUnit, tvScale;
    private View swatch;
    private LinearLayout selBar;
    private TextView selInfo;
    /** 工具项：图标 + 文字，等宽排布 */

    private int curColor = 0xFFFF3B30;
    private float curWidth = 2.2f;
    private boolean curDashed = false;

    private final ArrayList<String> undoStack = new ArrayList<>();
    private final ArrayList<String> redoStack = new ArrayList<>();
    private boolean dirty;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);

        // 初始化蓝牙测距仪并尝试自动连接上次设备
        bleMeter = new BleMeter(this);
        bleMeter.setListener(new BleMeter.Listener() {
            @Override public void onDeviceFound(String n, String a, int r) {}
            @Override public void onScanFinished(java.util.List<BleMeter.DeviceInfo> f) {}
            @Override public void onConnected(final String name) {
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        Toast.makeText(EditActivity.this,
                                "蓝牙测距仪已连接：" + name, Toast.LENGTH_SHORT).show();
                    }
                });
            }
            @Override public void onMeasured(final float meters) {
                runOnUiThread(new Runnable() {
                    @Override public void run() { onBleMeasured(meters); }
                });
            }
            @Override public void onError(final String msg) {
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (bleWaiter != null) { bleWaiter.onFail(msg); bleWaiter = null; }
                    }
                });
            }
            @Override public void onDisconnected() {}
            @Override public void onNotMeter(String address) {}
        });
        if (bleMeter.lastDeviceMac() != null) {
            bleMeter.autoConnect();
        }
        setContentView(R.layout.activity_edit);

        projectId = getIntent().getStringExtra("projectId");
        itemId = getIntent().getStringExtra("itemId");

        canvas = findViewById(R.id.canvas);
        hint = findViewById(R.id.hint);
        tvStyle = findViewById(R.id.tv_style);
        tvUnit = findViewById(R.id.tv_unit);
        tvScale = findViewById(R.id.tv_scale);
        swatch = findViewById(R.id.swatch);
        selBar = findViewById(R.id.selection_bar);
        selInfo = findViewById(R.id.sel_info);
        findViewById(R.id.sel_edit).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Project.Ann sel = canvas.getSelected();
                if (sel != null) onAnnClick(sel);
            }
        });
        findViewById(R.id.sel_delete).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Project.Ann sel = canvas.getSelected();
                if (sel == null) return;
                pushUndo();
                item.anns.remove(sel);
                canvas.setAnns(item.anns);
                canvas.clearSelection();
                changed();
            }
        });


        canvas.setListener(this);

        if (!loadData()) return;

        File img = Store.imageFile(this, project, item);
        Bitmap bm = Store.decodeFull(img);
        if (bm == null) {
            Toast.makeText(this, "图片加载失败", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        canvas.setBitmap(bm);
        canvas.setAnns(item.anns);
        canvas.setScaleRef(item);
        canvas.setMode(AnnotationView.MODE_LINE);
        canvas.setStyle(curColor, curWidth, curDashed);

        buildToolbar();
        refreshStatus();
        setTitle(project.name);
    }

    private boolean loadData() {
        try {
            project = Store.load(this, projectId);
        } catch (Exception e) {
            project = null;
        }
        if (project == null) {
            Toast.makeText(this, "项目不存在", Toast.LENGTH_LONG).show();
            finish();
            return false;
        }
        for (Project.Item it : project.items) {
            if (it.id.equals(itemId)) {
                item = it;
                break;
            }
        }
        if (item == null) {
            Toast.makeText(this, "图片记录不存在", Toast.LENGTH_LONG).show();
            finish();
            return false;
        }
        return true;
    }

    /* ---------------- 工具栏 ---------------- */

    /* ---------------- 工具栏（等宽 6 列，图标+文字，选中态同步着色） ---------------- */

    private final java.util.LinkedHashMap<Integer, View[]> toolViews = new java.util.LinkedHashMap<>();

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    /**
     * 创建一个工具项并挂到行容器上。
     * @return 保存 [item, icon, label] 以便切换选中态
     */
    private View[] addTool(LinearLayout row, int id, int iconRes, String label,
                           View.OnClickListener cl) {
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(44), 1f);
        lp.setMargins(dp(2), dp(1), dp(2), dp(1));
        item.setLayoutParams(lp);
        item.setClickable(true);
        item.setFocusable(true);
        item.setBackground(makeItemBg(false));

        ImageView iv = new ImageView(this);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(19), dp(19));
        ilp.bottomMargin = 0;
        iv.setLayoutParams(ilp);
        iv.setImageResource(iconRes);

        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextSize(9);
        tv.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = dp(1);
        tv.setLayoutParams(tlp);

        item.addView(iv);
        item.addView(tv);
        if (cl != null) item.setOnClickListener(cl);
        row.addView(item);

        View[] refs = new View[]{item, iv, tv};
        toolViews.put(id, refs);
        setToolActive(id, false);
        return refs;
    }

    private void setToolActive(int id, boolean active) {
        View[] refs = toolViews.get(id);
        if (refs == null) return;
        ImageView iv = (ImageView) refs[1];
        TextView tv = (TextView) refs[2];
        int color = active ? 0xFF1E6FD9 : 0xFF4A4F57;
        iv.setColorFilter(color);
        tv.setTextColor(color);
        refs[0].setBackground(makeItemBg(active));
    }

    /** 工具项背景：选中=淡蓝圆角，未选中=透明（仍保留点击涟漪） */
    private GradientDrawable makeItemBg(boolean active) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        g.setCornerRadius(dp(8));
        g.setColor(active ? 0xFFE8F1FD : 0x00000000);
        return g;
    }

    /** 只让一个工具处于选中（模式类按钮） */
    private void setExclusiveActive(int activeId, int[] group) {
        for (int id : group) setToolActive(id, id == activeId);
    }

    private void buildToolbar() {
        LinearLayout row1 = findViewById(R.id.row_tools);
        LinearLayout row2 = findViewById(R.id.row_actions);

        addTool(row1, R.id.btn_line, R.drawable.ic_tool_line, "画线", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                canvas.setMode(AnnotationView.MODE_LINE);
                hint.setText("拖动画线 → 输入尺寸；点标注可选中拖动");
                refreshStatus();
            }
        });
        addTool(row1, R.id.btn_rect, R.drawable.ic_tool_rect, "矩形", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                canvas.setMode(AnnotationView.MODE_RECT);
                hint.setText("拖出一个矩形 → 输入宽度，高度自动换算");
                refreshStatus();
            }
        });
        addTool(row1, R.id.btn_circle, R.drawable.ic_tool_circle, "圆", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                canvas.setMode(AnnotationView.MODE_CIRCLE);
                hint.setText("从圆心往外拖 → 输入直径");
                refreshStatus();
            }
        });
        addTool(row1, R.id.btn_angle, R.drawable.ic_tool_angle, "角度", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                canvas.setMode(AnnotationView.MODE_ANGLE);
                hint.setText("依次点击：顶点 → 第一条边 → 第二条边");
                refreshStatus();
            }
        });
        addTool(row1, R.id.btn_text, R.drawable.ic_tool_text, "文字", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                canvas.setMode(AnnotationView.MODE_TEXT);
                hint.setText("点图片空白处添加注释");
                refreshStatus();
            }
        });
        addTool(row1, R.id.btn_pan, R.drawable.ic_tool_pan, "浏览", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                canvas.setMode(AnnotationView.MODE_PAN);
                hint.setText("单指拖动平移，双指缩放");
                refreshStatus();
            }
        });

        addTool(row2, R.id.btn_style, R.drawable.ic_tool_style, "样式", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showStylePanel();
            }
        });
        addTool(row2, R.id.btn_scale, R.drawable.ic_tool_scale, "比例尺", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onScaleButton();
            }
        });
        addTool(row2, R.id.btn_list, R.drawable.ic_tool_list, "列表", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showAnnList();
            }
        });
        addTool(row2, R.id.btn_undo, R.drawable.ic_tool_undo, "撤销", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                doUndo();
            }
        });
        addTool(row2, R.id.btn_redo, R.drawable.ic_tool_redo, "重做", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                doRedo();
            }
        });
        addTool(row2, R.id.btn_del, R.drawable.ic_tool_delete, "删除", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Project.Ann sel = canvas.getSelected();
                if (sel == null) {
                    Toast.makeText(EditActivity.this, "先点一下要删除的标注", Toast.LENGTH_SHORT).show();
                    return;
                }
                pushUndo();
                item.anns.remove(sel);
                canvas.setAnns(item.anns);
                canvas.clearSelection();
                changed();
            }
        });
    }

    private void bindButtons() {
    }

    /** 标注对象列表：点选定位，长按可删 */
    private void showAnnList() {
        if (item.anns.isEmpty()) {
            Toast.makeText(this, "这张图还没有标注", Toast.LENGTH_SHORT).show();
            return;
        }
        final String[] rows = new String[item.anns.size()];
        for (int i = 0; i < item.anns.size(); i++) {
            Project.Ann a = item.anns.get(i);
            String tag;
            switch (a.type) {
                case Project.Ann.TYPE_LINE: tag = "尺寸"; break;
                case Project.Ann.TYPE_RECT: tag = "矩形"; break;
                case Project.Ann.TYPE_CIRCLE: tag = "圆"; break;
                case Project.Ann.TYPE_ANGLE: tag = "角度"; break;
                default: tag = "注释"; break;
            }
            String sub = a.subText();
            rows[i] = (i + 1) + ". [" + tag + "] " + a.displayText()
                    + (sub.length() > 0 ? "   " + sub : "");
        }
        new AlertDialog.Builder(this)
                .setTitle("标注列表（" + item.anns.size() + "）")
                .setItems(rows, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        Project.Ann a = item.anns.get(which);
                        canvas.setSelected(a);
                        canvas.setMode(AnnotationView.MODE_LINE);
                        refreshStatus();
                        hint.setText("已选中第 " + (which + 1) + " 条：" + a.displayText()
                                + "　可拖动或点「删除」");
                    }
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    private void clearAll() {
        new AlertDialog.Builder(this)
                .setTitle("清空标注")
                .setMessage("删除这张图上的所有标注？")
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.clear, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        pushUndo();
                        item.anns.clear();
                        canvas.setAnns(item.anns);
                        canvas.clearSelection();
                        changed();
                    }
                })
                .show();
    }

    private void refreshStatus() {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(curColor);
        g.setStroke((int) (1 * getResources().getDisplayMetrics().density), 0xFFCCCCCC);
        swatch.setBackground(g);

        tvStyle.setText(fmtW(curWidth) + "dp " + (curDashed ? "虚线" : "实线"));
        tvUnit.setText("单位 " + (project.unit == null ? "m" : project.unit));
        tvScale.setText(item.hasScale() ? item.calText() : "未标定");

        int m = canvas.getMode();
        setExclusiveActive(0, new int[]{
                R.id.btn_line, R.id.btn_rect, R.id.btn_circle,
                R.id.btn_angle, R.id.btn_text, R.id.btn_pan});
        if (m == AnnotationView.MODE_LINE) setToolActive(R.id.btn_line, true);
        else if (m == AnnotationView.MODE_RECT) setToolActive(R.id.btn_rect, true);
        else if (m == AnnotationView.MODE_CIRCLE) setToolActive(R.id.btn_circle, true);
        else if (m == AnnotationView.MODE_ANGLE) setToolActive(R.id.btn_angle, true);
        else if (m == AnnotationView.MODE_TEXT) setToolActive(R.id.btn_text, true);
        else if (m == AnnotationView.MODE_PAN) setToolActive(R.id.btn_pan, true);
        else if (m == AnnotationView.MODE_SCALE) setToolActive(R.id.btn_scale, true);
    }

    private String fmtW(float w) {
        if (w == Math.rint(w)) return String.valueOf((int) w);
        return String.valueOf(Math.round(w * 10) / 10.0);
    }

    /* ---------------- 样式面板 ---------------- */

    private String styleDesc() {
        return "当前：" + colorName(curColor) + " · " + fmtW(curWidth) + "dp · "
                + (curDashed ? "虚线" : "实线");
    }

    private String colorName(int c) {
        String[] names = {"红", "橙", "黄", "绿", "天蓝", "蓝", "紫", "粉", "白", "灰", "黑", "青绿"};
        for (int i = 0; i < PALETTE.length; i++) if (PALETTE[i] == c) return names[i];
        return String.format("#%06X", 0xFFFFFF & c);
    }

    /** 刷新所有色块高亮 */
    private void refreshSwatches(View[] sw, float d) {
        for (int i = 0; i < sw.length; i++) {
            if (sw[i] == null) continue;
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(PALETTE[i]);
            g.setStroke((int) ((PALETTE[i] == curColor ? 3 : 1) * d),
                    PALETTE[i] == curColor ? 0xFF1E6FD9 : 0xFFDDDDDD);
            sw[i].setBackground(g);
        }
    }

    private void showStylePanel() {
        final float d = getResources().getDisplayMetrics().density;
        int pad = (int) (16 * d);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad / 2, pad, (int) (8 * d));

        // 实时样式描述
        final TextView cur = new TextView(this);
        cur.setText(styleDesc());
        cur.setTextSize(12);
        cur.setTextColor(0xFF1E6FD9);
        cur.setPadding(0, 0, 0, (int) (6 * d));
        root.addView(cur);

        // 颜色
        TextView lc = new TextView(this);
        lc.setText("颜色");
        lc.setTextSize(12);
        lc.setTextColor(0xFF7A7F87);
        root.addView(lc);

        final View[] sw = new View[PALETTE.length];
        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < PALETTE.length; i++) {
            final int color = PALETTE[i];
            View v = new View(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, (int) (42 * d), 1f);
            lp.setMargins((int) (4 * d), (int) (6 * d), (int) (4 * d), 0);
            v.setLayoutParams(lp);
            v.setClickable(true);
            v.setFocusable(true);
            sw[i] = v;
            v.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View x) {
                    curColor = color;
                    applyStyle();
                    refreshStatus();
                    refreshSwatches(sw, d);
                    cur.setText(styleDesc());
                }
            });
            if (i < 6) row1.addView(v);
            else row2.addView(v);
        }
        refreshSwatches(sw, d);
        root.addView(row1);
        root.addView(row2);

        // 线宽
        TextView lw = new TextView(this);
        lw.setText("线宽");
        lw.setTextSize(12);
        lw.setTextColor(0xFF7A7F87);
        lw.setPadding(0, (int) (14 * d), 0, 0);
        root.addView(lw);

        final Button[] wbtns = new Button[WIDTHS.length];
        LinearLayout wr = new LinearLayout(this);
        wr.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < WIDTHS.length; i++) {
            final float w = WIDTHS[i];
            final int idx = i;
            Button bt = new Button(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            lp.setMargins((int) (3 * d), (int) (4 * d), (int) (3 * d), 0);
            bt.setLayoutParams(lp);
            bt.setText(WIDTH_LABELS[i]);
            bt.setTextSize(12);
            wbtns[i] = bt;
            bt.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View x) {
                    curWidth = w;
                    applyStyle();
                    refreshStatus();
                    for (int k = 0; k < wbtns.length; k++) {
                        boolean on = (k == idx);
                        wbtns[k].setTextColor(on ? 0xFF1E6FD9 : 0xFF333333);
                        wbtns[k].setBackgroundColor(on ? 0xFFE8F0FC : 0x00000000);
                    }
                    cur.setText(styleDesc());
                }
            });
            wr.addView(bt);
        }
        for (int k = 0; k < wbtns.length; k++) {
            boolean on = (WIDTHS[k] == curWidth);
            wbtns[k].setTextColor(on ? 0xFF1E6FD9 : 0xFF333333);
            wbtns[k].setBackgroundColor(on ? 0xFFE8F0FC : 0x00000000);
        }
        root.addView(wr);

        // 线型
        TextView ls = new TextView(this);
        ls.setText("线型");
        ls.setTextSize(12);
        ls.setTextColor(0xFF7A7F87);
        ls.setPadding(0, (int) (14 * d), 0, 0);
        root.addView(ls);

        final Button[] sbtns = new Button[2];
        LinearLayout sr = new LinearLayout(this);
        sr.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < 2; i++) {
            final boolean dash = (i == 1);
            final int idx = i;
            Button bt = new Button(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            lp.setMargins((int) (3 * d), (int) (4 * d), (int) (3 * d), 0);
            bt.setLayoutParams(lp);
            bt.setText(dash ? "虚线" : "实线");
            bt.setTextSize(12);
            sbtns[i] = bt;
            bt.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View x) {
                    curDashed = dash;
                    applyStyle();
                    refreshStatus();
                    for (int k = 0; k < sbtns.length; k++) {
                        boolean on = (k == idx);
                        sbtns[k].setTextColor(on ? 0xFF1E6FD9 : 0xFF333333);
                        sbtns[k].setBackgroundColor(on ? 0xFFE8F0FC : 0x00000000);
                    }
                    cur.setText(styleDesc());
                }
            });
            sr.addView(bt);
        }
        for (int k = 0; k < sbtns.length; k++) {
            boolean on = ((k == 1) == curDashed);
            sbtns[k].setTextColor(on ? 0xFF1E6FD9 : 0xFF333333);
            sbtns[k].setBackgroundColor(on ? 0xFFE8F0FC : 0x00000000);
        }
        root.addView(sr);

        final Project.Ann sel = canvas.getSelected();
        if (sel != null) {
            TextView tip = new TextView(this);
            tip.setText("已选中 1 条标注，改动会同时应用到它");
            tip.setTextSize(11);
            tip.setTextColor(0xFF1E6FD9);
            tip.setPadding(0, (int) (12 * d), 0, 0);
            root.addView(tip);
        }

        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(root);

        new AlertDialog.Builder(this)
                .setTitle("标注样式")
                .setView(sv)
                .setNegativeButton("关闭", null)
                .show();
    }

    /** 把当前样式套到画布与（若有）选中的标注上 */
    private void applyStyle() {
        canvas.setStyle(curColor, curWidth, curDashed);
        Project.Ann sel = canvas.getSelected();
        if (sel != null) {
            pushUndo();
            canvas.applyStyleTo(sel);
            if (sel.type == Project.Ann.TYPE_TEXT) {
                // 注释文字颜色跟随
                sel.color = curColor;
            }
            canvas.setAnns(item.anns);
            changed();
        }
    }

    /* ---------------- 撤销 / 重做 ---------------- */

    private void pushUndo() {
        undoStack.add(Project.annsToJson(item.anns));
        if (undoStack.size() > 60) undoStack.remove(0);
        redoStack.clear();
    }

    private void doUndo() {
        if (undoStack.isEmpty()) {
            Toast.makeText(this, "没有可撤销的操作", Toast.LENGTH_SHORT).show();
            return;
        }
        redoStack.add(Project.annsToJson(item.anns));
        String snap = undoStack.remove(undoStack.size() - 1);
        restore(snap);
        hint.setText("已撤销");
    }

    private void doRedo() {
        if (redoStack.isEmpty()) {
            Toast.makeText(this, "没有可重做的操作", Toast.LENGTH_SHORT).show();
            return;
        }
        undoStack.add(Project.annsToJson(item.anns));
        String snap = redoStack.remove(redoStack.size() - 1);
        restore(snap);
        hint.setText("已重做");
    }

    private void restore(String snapshot) {
        List<Project.Ann> list = Project.annsFromJson(snapshot);
        item.anns.clear();
        item.anns.addAll(list);
        canvas.setAnns(item.anns);
        canvas.clearSelection();
        dirty = true;
        saveNow(false);
    }

    private void changed() {
        dirty = true;
        saveNow(false);
        refreshSelectionBar();
    }

    /** 数据变了之后同步操作条上的文字（否则编辑完还显示旧值） */
    private void refreshSelectionBar() {
        if (selBar == null || canvas == null) return;
        onSelectionChanged(canvas.getSelected());
    }

    /* ---------------- 单位 ---------------- */

    private void pickUnit() {
        final int cur = UnitUtil.indexOf(project.unit);
        new AlertDialog.Builder(this)
                .setTitle(R.string.unit)
                .setSingleChoiceItems(UnitUtil.LABELS, cur, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        project.unit = UnitUtil.UNITS[which];
                        d.dismiss();
                        refreshStatus();
                        changed();
                    }
                })
                .show();
    }

    /* ---------------- 比例尺 ---------------- */

    private void onScaleButton() {
        if (item.hasScale()) {
            new AlertDialog.Builder(this)
                    .setTitle("比例尺")
                    .setMessage("当前：" + item.calText())
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton("重新标定", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface d, int w) {
                            startCalibrate();
                        }
                    })
                    .setNeutralButton("清除基准", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface d, int w) {
                            item.calValue = 0;
                            item.calUnit = "";
                            item.calX1 = item.calY1 = item.calX2 = item.calY2 = 0;
                            canvas.setScaleRef(item);
                            refreshStatus();
                            changed();
                        }
                    })
                    .show();
        } else {
            startCalibrate();
        }
    }

    private void startCalibrate() {
        canvas.setMode(AnnotationView.MODE_SCALE);
        refreshStatus();
        hint.setText("画一条已知长度的线作为基准（比如门宽、瓷砖边）");
    }

    @Override
    public void onRequestScale(final float x1, final float y1, final float x2, final float y2) {
        final EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        et.setHint("这条线的实际长度");
        et.setSelectAllOnFocus(true);

        final Spinner sp = new Spinner(this);
        ArrayAdapter<String> ad = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, UnitUtil.LABELS);
        sp.setAdapter(ad);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(et);
        box.addView(sp);

        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("标定比例尺")
                .setMessage("输入这条线的真实长度，之后画的线会自动算出长度。")
                .setView(box)
                .setNegativeButton(R.string.cancel, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        canvas.setMode(AnnotationView.MODE_LINE);
                        refreshStatus();
                    }
                })
                .setPositiveButton(R.string.ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        float v = UnitUtil.parse(et.getText().toString());
                        if (Float.isNaN(v) || v <= 0) {
                            Toast.makeText(EditActivity.this, "请输入大于 0 的数字", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        String unit = UnitUtil.UNITS[sp.getSelectedItemPosition()];
                        item.calX1 = x1;
                        item.calY1 = y1;
                        item.calX2 = x2;
                        item.calY2 = y2;
                        item.calValue = v;
                        item.calUnit = unit;
                        project.unit = unit;
                        canvas.setScaleRef(item);
                        canvas.setMode(AnnotationView.MODE_LINE);
                        refreshStatus();
                        changed();
                        hint.setText("基准已设定：" + item.calText() + "　之后画线自动测量");
                    }
                })
                .create();
        dlg.show();
        sp.setSelection(UnitUtil.indexOf(project.unit), false);
    }

    /* ---------------- 新建尺寸线 ---------------- */

    @Override
    public void onRequestLine(final float x1, final float y1, final float x2, final float y2) {
        if (item.hasScale()) {
            addAutoMeasured(x1, y1, x2, y2);
            return;
        }
        showLineInputDialog(x1, y1, x2, y2, -1f);
    }

    private void addAutoMeasured(float x1, float y1, float x2, float y2) {
        float pxLen = (float) Math.hypot(x2 - x1, y2 - y1);
        float realInCal = pxLen / item.calPx() * item.calValue;
        float v = UnitUtil.convert(realInCal, item.calUnit, project.unit);
        pushUndo();
        Project.Ann an = Project.Ann.line(x1, y1, x2, y2, "", curColor);
        an.value = v;
        an.unit = project.unit;
        an.strokeW = curWidth;
        an.dashed = curDashed;
        item.anns.add(an);
        canvas.setAnns(item.anns);
        canvas.setSelected(an);
        changed();
        hint.setText("自动测量：" + UnitUtil.format(v, project.unit));
    }

    /* ---------------- 矩形 ---------------- */

    @Override
    public void onRequestRect(final float x1, final float y1, final float x2, final float y2) {
        final float pxW = Math.abs(x2 - x1);
        final float pxH = Math.abs(y2 - y1);
        if (pxW < 1 || pxH < 1) return;

        if (item.hasScale()) {
            float w = pxW / item.calPx() * item.calValue;
            float h = pxH / item.calPx() * item.calValue;
            w = UnitUtil.convert(w, item.calUnit, project.unit);
            h = UnitUtil.convert(h, item.calUnit, project.unit);
            pushUndo();
            Project.Ann an = Project.Ann.rect(x1, y1, x2, y2, curColor);
            an.value = w;
            an.value2 = h;
            an.unit = project.unit;
            an.strokeW = curWidth;
            an.dashed = curDashed;
            item.anns.add(an);
            canvas.setAnns(item.anns);
            canvas.setSelected(an);
            changed();
            hint.setText("矩形 " + an.displayText() + "　面积 " + an.subText());
            return;
        }
        askRectWidth(x1, y1, x2, y2, pxW, pxH);
    }

    private void askRectWidth(final float x1, final float y1, final float x2, final float y2,
                              final float pxW, final float pxH) {
        final EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        et.setHint("矩形宽度");
        et.setSelectAllOnFocus(true);

        final Spinner sp = new Spinner(this);
        ArrayAdapter<String> ad = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, UnitUtil.LABELS);
        sp.setAdapter(ad);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(et);
        box.addView(sp);

        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("矩形尺寸")
                .setMessage("输入宽度，高度会按图上比例自动换算。")
                .setView(box)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        float v = UnitUtil.parse(et.getText().toString());
                        if (Float.isNaN(v) || v <= 0) {
                            Toast.makeText(EditActivity.this, "请输入大于 0 的数字", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        String unit = UnitUtil.UNITS[sp.getSelectedItemPosition()];
                        project.unit = unit;
                        pushUndo();
                        Project.Ann an = Project.Ann.rect(x1, y1, x2, y2, curColor);
                        an.value = v;
                        an.value2 = v * (pxH / pxW);
                        an.unit = unit;
                        an.strokeW = curWidth;
                        an.dashed = curDashed;
                        item.anns.add(an);
                        canvas.setAnns(item.anns);
                        canvas.setSelected(an);
                        refreshStatus();
                        changed();
                    }
                })
                .create();
        dlg.show();
        sp.setSelection(UnitUtil.indexOf(project.unit), false);
    }

    /* ---------------- 圆 ---------------- */

    @Override
    public void onRequestCircle(final float cx, final float cy, final float rx, final float ry) {
        float pxR = (float) Math.hypot(rx - cx, ry - cy);
        if (pxR < 2) return;

        if (item.hasScale()) {
            float r = pxR / item.calPx() * item.calValue;
            r = UnitUtil.convert(r, item.calUnit, project.unit);
            pushUndo();
            Project.Ann an = Project.Ann.circle(cx, cy, rx, ry, curColor);
            an.value = r;
            an.unit = project.unit;
            an.strokeW = curWidth;
            an.dashed = curDashed;
            item.anns.add(an);
            canvas.setAnns(item.anns);
            canvas.setSelected(an);
            changed();
            hint.setText("圆 " + an.displayText() + "　面积 " + an.subText());
            return;
        }
        askCircleDiameter(cx, cy, rx, ry);
    }

    private void askCircleDiameter(final float cx, final float cy, final float rx, final float ry) {
        final EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        et.setHint("直径");
        et.setSelectAllOnFocus(true);

        final Spinner sp = new Spinner(this);
        ArrayAdapter<String> ad = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, UnitUtil.LABELS);
        sp.setAdapter(ad);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(et);
        box.addView(sp);

        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("圆的直径")
                .setView(box)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        float dia = UnitUtil.parse(et.getText().toString());
                        if (Float.isNaN(dia) || dia <= 0) {
                            Toast.makeText(EditActivity.this, "请输入大于 0 的数字", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        String unit = UnitUtil.UNITS[sp.getSelectedItemPosition()];
                        project.unit = unit;
                        pushUndo();
                        Project.Ann an = Project.Ann.circle(cx, cy, rx, ry, curColor);
                        an.value = dia / 2f;
                        an.unit = unit;
                        an.strokeW = curWidth;
                        an.dashed = curDashed;
                        item.anns.add(an);
                        canvas.setAnns(item.anns);
                        canvas.setSelected(an);
                        refreshStatus();
                        changed();
                    }
                })
                .create();
        dlg.show();
        sp.setSelection(UnitUtil.indexOf(project.unit), false);
    }

    /* ---------------- 角度 ---------------- */

    @Override
    public void onRequestAngle(float ax, float ay, float vx, float vy, float bx, float by) {
        double a1 = Math.atan2(ay - vy, ax - vx);
        double a2 = Math.atan2(by - vy, bx - vx);
        double deg = Math.toDegrees(Math.abs(a2 - a1));
        if (deg > 180) deg = 360 - deg;
        pushUndo();
        Project.Ann an = Project.Ann.angle(ax, ay, vx, vy, bx, by, curColor);
        an.value = (float) deg;
        an.unit = "";
        an.strokeW = curWidth;
        an.dashed = curDashed;
        item.anns.add(an);
        canvas.setAnns(item.anns);
        canvas.setSelected(an);
        changed();
        hint.setText("角度：" + an.displayText());
    }

    /* ---------------- 新建注释 ---------------- */

    @Override
    public void onRequestText(final float x, final float y) {
        final EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_TEXT);
        et.setHint(R.string.note_hint);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        et.setPadding(pad, pad / 2, pad, 0);

        new AlertDialog.Builder(this)
                .setTitle(R.string.note_title)
                .setView(et)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        String t = et.getText().toString().trim();
                        if (t.length() == 0) return;
                        pushUndo();
                        Project.Ann an = Project.Ann.note(x, y, t, curColor);
                        an.strokeW = curWidth;
                        an.dashed = curDashed;
                        item.anns.add(an);
                        canvas.setAnns(item.anns);
                        canvas.setSelected(an);
                        changed();
                    }
                })
                .show();
    }

    /* ---------------- 再编辑 ---------------- */

    @Override
    public void onAnnClick(final Project.Ann a) {

        if (a == null) return;
        switch (a.type) {
            case Project.Ann.TYPE_LINE: editLine(a); break;
            case Project.Ann.TYPE_RECT: editRect(a); break;
            case Project.Ann.TYPE_CIRCLE: editCircle(a); break;
            case Project.Ann.TYPE_ANGLE: editAngle(a); break;
            default: editText(a); break;
        }
    }

    /* ---------------- 矩形编辑 ---------------- */

    private void editRect(final Project.Ann a) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);

        TextView lw = new TextView(this);
        lw.setText("宽度");
        lw.setTextSize(12);
        lw.setTextColor(0xFF7A7F87);
        box.addView(lw);

        final EditText etW = new EditText(this);
        etW.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        etW.setText(fmtValue(a.value));
        etW.setSelection(etW.getText().length());
        etW.setHint("宽度");
        box.addView(etW);

        TextView lh = new TextView(this);
        lh.setText("高度");
        lh.setTextSize(12);
        lh.setTextColor(0xFF7A7F87);
        box.addView(lh);

        final EditText etH = new EditText(this);
        etH.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        etH.setText(fmtValue(a.value2));
        etH.setSelection(etH.getText().length());
        etH.setHint("高度");
        box.addView(etH);

        final Spinner sp = new Spinner(this);
        ArrayAdapter<String> ad = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, UnitUtil.LABELS);
        sp.setAdapter(ad);
        box.addView(sp);

        AlertDialog dlg = inputDialog("修改矩形", "改完自动重算面积", box,
                "删除", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        pushUndo();
                        item.anns.remove(a);
                        canvas.setAnns(item.anns);
                        canvas.clearSelection();
                        changed();
                    }
                }, new Validator() {
                    @Override
                    public boolean ok() {
                        float vw = UnitUtil.parse(etW.getText().toString());
                        float vh = UnitUtil.parse(etH.getText().toString());
                        if (Float.isNaN(vw) || Float.isNaN(vh) || vw <= 0 || vh <= 0) {
                            Toast.makeText(EditActivity.this, "请输入大于 0 的宽和高", Toast.LENGTH_SHORT).show();
                            return false;
                        }
                        pushUndo();
                        a.value = vw;
                        a.value2 = vh;
                        a.unit = UnitUtil.UNITS[sp.getSelectedItemPosition()];
                        project.unit = a.unit;
                        canvas.setAnns(item.anns);
                        refreshStatus();
                        changed();
                        hint.setText("矩形 " + a.displayText() + "　" + a.subText());
                        return true;
                    }
                });
        dlg.show();
        sp.setSelection(UnitUtil.indexOf(
                a.unit == null || a.unit.length() == 0 ? project.unit : a.unit), false);
    }

    /* ---------------- 圆编辑 ---------------- */

    private void editCircle(final Project.Ann a) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);

        final EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        et.setText(fmtValue(a.value * 2));   // 用户看到的是直径
        et.setSelection(et.getText().length());
        et.setHint("直径");
        box.addView(et);

        final Spinner sp = new Spinner(this);
        ArrayAdapter<String> ad = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, UnitUtil.LABELS);
        sp.setAdapter(ad);
        box.addView(sp);

        AlertDialog dlg = inputDialog("修改圆",
                "当前直径 " + fmtValue(a.value * 2) + "，面积 " + a.subText(), box,
                "删除", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        pushUndo();
                        item.anns.remove(a);
                        canvas.setAnns(item.anns);
                        canvas.clearSelection();
                        changed();
                    }
                }, new Validator() {
                    @Override
                    public boolean ok() {
                        float dia = UnitUtil.parse(et.getText().toString());
                        if (Float.isNaN(dia) || dia <= 0) {
                            Toast.makeText(EditActivity.this, "请输入大于 0 的直径", Toast.LENGTH_SHORT).show();
                            return false;
                        }
                        pushUndo();
                        String unit = UnitUtil.UNITS[sp.getSelectedItemPosition()];
                        float scale = dia / 2f / a.value;
                        a.value = dia / 2f;
                        a.unit = unit;
                        project.unit = unit;
                        float cx = a.x1, cy = a.y1;
                        a.x2 = cx + (a.x2 - cx) * scale;
                        a.y2 = cy + (a.y2 - cy) * scale;
                        canvas.setAnns(item.anns);
                        refreshStatus();
                        changed();
                        hint.setText("圆 " + a.displayText() + "　" + a.subText());
                        return true;
                    }
                });
        dlg.show();
        sp.setSelection(UnitUtil.indexOf(
                a.unit == null || a.unit.length() == 0 ? project.unit : a.unit), false);
    }

    /* ---------------- 角度编辑 ---------------- */

    private void editAngle(final Project.Ann a) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);

        final EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        et.setText(String.format(java.util.Locale.US, "%.1f", a.value));
        et.setSelection(et.getText().length());
        et.setHint("角度（度）");
        box.addView(et);

        final float measured = a.angleFromPoints();
        String msg = "图形测得 " + String.format(java.util.Locale.US, "%.1f", measured) + "°";
        if (a.manual) msg += "，当前为手动指定值";
        msg += "\n可直接改成实际角度（0~180）";

        AlertDialog dlg = inputDialog("修改角度", msg, box,
                "按图形重算", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        pushUndo();
                        a.manual = false;
                        a.value = a.angleFromPoints();
                        canvas.setAnns(item.anns);
                        changed();
                        hint.setText("已按图形重算：" + a.displayText());
                    }
                }, new Validator() {
                    @Override
                    public boolean ok() {
                        float v = UnitUtil.parse(et.getText().toString());
                        if (Float.isNaN(v)) {
                            Toast.makeText(EditActivity.this, "请输入数字", Toast.LENGTH_SHORT).show();
                            return false;
                        }
                        if (v < 0 || v > 180) {
                            Toast.makeText(EditActivity.this, "角度需在 0~180 之间", Toast.LENGTH_SHORT).show();
                            return false;
                        }
                        pushUndo();
                        a.value = v;
                        a.manual = true;   // 锁定，拖动控制点不再覆盖
                        canvas.setAnns(item.anns);
                        changed();
                        hint.setText("角度已设为 " + a.displayText() + "（手动值）");
                        return true;
                    }
                });
        dlg.show();
    }
    private void editLine(final Project.Ann a) {
        final EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        et.setText(fmtValue(a.value));
        et.setSelection(et.getText().length());
        et.setHint(R.string.value_hint);

        final Spinner sp = new Spinner(this);
        ArrayAdapter<String> ad = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, UnitUtil.LABELS);
        sp.setAdapter(ad);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(et);
        box.addView(sp);

        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("修改尺寸")
                .setView(box)
                .setNeutralButton("删除", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        pushUndo();
                        item.anns.remove(a);
                        canvas.setAnns(item.anns);
                        canvas.clearSelection();
                        changed();
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        float v = UnitUtil.parse(et.getText().toString());
                        if (Float.isNaN(v)) {
                            Toast.makeText(EditActivity.this, "请输入数字", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        pushUndo();
                        String unit = UnitUtil.UNITS[sp.getSelectedItemPosition()];
                        a.value = v;
                        a.unit = unit;
                        project.unit = unit;
                        canvas.setAnns(item.anns);
                        refreshStatus();
                        changed();
                    }
                })
                .create();
        dlg.show();
        sp.setSelection(UnitUtil.indexOf(
                a.unit == null || a.unit.length() == 0 ? project.unit : a.unit), false);
    }

    private void editText(final Project.Ann a) {
        final EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_TEXT);
        et.setText(a.text == null ? "" : a.text);
        et.setSelection(et.getText().length());
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        et.setPadding(pad, pad / 2, pad, 0);

        new AlertDialog.Builder(this)
                .setTitle("修改注释")
                .setView(et)
                .setNeutralButton("删除", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        pushUndo();
                        item.anns.remove(a);
                        canvas.setAnns(item.anns);
                        canvas.clearSelection();
                        changed();
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        String t = et.getText().toString().trim();
                        if (t.length() == 0) {
                            Toast.makeText(EditActivity.this, "注释不能为空", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        pushUndo();
                        a.text = t;
                        canvas.setAnns(item.anns);
                        changed();
                    }
                })
                .show();
    }

    /** 校验回调：返回 true 才关闭对话框 */
    private interface Validator { boolean ok(); }

    /**
     * 输入型对话框：确定按钮由我们自己接管，
     * 校验不通过时保持打开，避免用户白填一遍。
     */
    private AlertDialog inputDialog(String title, String message, View content,
                                    String neutralText, DialogInterface.OnClickListener neutral,
                                    final Validator validator) {
        final AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setView(content)
                .setNeutralButton(neutralText, neutral)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.ok, null)
                .create();
        dlg.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override
            public void onShow(DialogInterface d) {
                ((AlertDialog) d).getButton(AlertDialog.BUTTON_POSITIVE)
                        .setOnClickListener(new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                if (validator.ok()) dlg.dismiss();
                            }
                        });
            }
        });
        return dlg;
    }

    private String fmtValue(float v) {
        if (v == Math.rint(v)) return String.valueOf((long) v);
        return String.valueOf(Math.round(v * 1000) / 1000.0);
    }

    /* ---------------- 拖动回调 ---------------- */

    @Override
    public void onSelectionChanged(final Project.Ann sel) {

        if (sel == null) {
            if (selBar != null) selBar.setVisibility(View.GONE);
            return;
        }
        String tag;
        switch (sel.type) {
            case Project.Ann.TYPE_LINE: tag = "尺寸"; break;
            case Project.Ann.TYPE_RECT: tag = "矩形"; break;
            case Project.Ann.TYPE_CIRCLE: tag = "圆"; break;
            case Project.Ann.TYPE_ANGLE: tag = "角度"; break;
            default: tag = "注释"; break;
        }
        String sub = sel.subText();
        selInfo.setText(tag + " · " + sel.displayText() + (sub.length() > 0 ? "  " + sub : ""));
        selBar.setVisibility(View.VISIBLE);

    }
    @Override
    public void onAnnWillChange() {
        pushUndo();
    }

    @Override
    public void onAnnChanged(Project.Ann a) {
        changed();
        hint.setText("位置已更新");
    }

    /* ---------------- 菜单 ---------------- */

    @Override
    public boolean onCreateOptionsMenu(Menu m) {
        m.add(0, 1, 0, R.string.export_single_pdf);
        m.add(0, 2, 1, R.string.save);
        m.add(0, 3, 2, R.string.unit);
        m.add(0, 4, 3, R.string.clear);
        m.add(0, 5, 4, "适应屏幕");
        m.add(0, 6, 5, "蓝牙测距仪");
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem mi) {
        if (mi.getItemId() == 1) {
            exportThis();
            return true;
        }
        if (mi.getItemId() == 2) {
            saveNow(true);
            return true;
        }
        if (mi.getItemId() == 3) {
            pickUnit();
            return true;
        }
        if (mi.getItemId() == 4) {
            clearAll();
            return true;
        }
        if (mi.getItemId() == 5) {
            canvas.fitToScreen();
            return true;
        }
        if (mi.getItemId() == 6) {
            openBleMeter();
            return true;
        }
        return super.onOptionsItemSelected(mi);
    }

    /** 最近一次蓝牙测距值（米）。未选中标注时，画线会用它作为初始值。 */
    private float pendingBleMeters = -1f;


    /**
     * 画线后输入尺寸的对话框。
     *
     * @param presetMeters 蓝牙测距得到的值（米）；&lt; 0 表示没有，正常手输。
     */
    private void showLineInputDialog(final float x1, final float y1,
                                     final float x2, final float y2,
                                     final float presetMeters) {
        final EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        et.setHint(R.string.value_hint);
        et.setSelectAllOnFocus(true);

        final Spinner sp = new Spinner(this);
        ArrayAdapter<String> a = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, UnitUtil.LABELS);
        sp.setAdapter(a);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(et);
        box.addView(sp);

        // ---- 蓝牙测距按钮（图标 + 文字）----
        final float dpi = getResources().getDisplayMetrics().density;
        final LinearLayout bleRow = new LinearLayout(this);
        bleRow.setOrientation(LinearLayout.HORIZONTAL);
        bleRow.setGravity(android.view.Gravity.CENTER);
        bleRow.setPadding(0, (int) (10 * dpi), 0, (int) (10 * dpi));

        android.graphics.drawable.GradientDrawable bleBg = new android.graphics.drawable.GradientDrawable();
        bleBg.setColor(0xFFE8F0FE);
        bleBg.setCornerRadius(10 * dpi);
        bleRow.setBackground(bleBg);

        ImageView bleIcon = new ImageView(this);
        int iconPx = (int) (20 * dpi);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(iconPx, iconPx);
        iconLp.rightMargin = (int) (8 * dpi);
        bleIcon.setLayoutParams(iconLp);
        bleIcon.setImageResource(R.drawable.ic_ble_meter);
        bleRow.addView(bleIcon);

        TextView bleLabel = new TextView(this);
        bleLabel.setText("蓝牙测距");
        bleLabel.setTextSize(14);
        bleLabel.setTextColor(0xFF1565C0);
        bleRow.addView(bleLabel);

        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rowLp.topMargin = (int) (8 * dpi);
        // 用 addView(view, lp) 传布局参数；先 setLayoutParams 会让宽度算错导致按钮跑出屏幕
        box.addView(bleRow, rowLp);

        // 有测距值时预填
        // 单位：项目里是 m 的老数据也按 mm 显示（用户偏好毫米）
        String u = "mm";
        if (project != null && project.unit != null && project.unit.length() > 0
                && !"m".equals(project.unit)) {
            u = project.unit;
        }
        if (presetMeters > 0) {
            et.setText(fmtValue(UnitUtil.convert(presetMeters, "m", u)));
        }

        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle(R.string.value_title)
                .setView(box)
                .setNegativeButton(R.string.cancel, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        canvas.setAnns(item.anns);
                    }
                })
                .setPositiveButton(R.string.ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        float v = UnitUtil.parse(et.getText().toString());
                        if (Float.isNaN(v)) {
                            Toast.makeText(EditActivity.this, "请输入数字", Toast.LENGTH_SHORT).show();
                            canvas.setAnns(item.anns);
                            return;
                        }
                        String unit = UnitUtil.UNITS[sp.getSelectedItemPosition()];
                        project.unit = unit;
                        pushUndo();
                        Project.Ann an = Project.Ann.line(x1, y1, x2, y2, "", curColor);
                        an.value = v;
                        an.unit = unit;
                        an.strokeW = curWidth;
                        an.dashed = curDashed;
                        item.anns.add(an);
                        canvas.setAnns(item.anns);
                        canvas.setSelected(an);
                        refreshStatus();
                        changed();
                    }
                })
                .create();
        // 「蓝牙测距」按钮：不关闭对话框，测到值直接填进输入框
        runOnUiThread(new Runnable() {
            @Override public void run() {
                bleRow.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        Toast.makeText(EditActivity.this, "正在测距…", Toast.LENGTH_SHORT).show();
                        requestMeasure(new BleWait() {
                            @Override public void onGot(float meters) {
                                // 直接在输入框里显示，对话框保持打开。
                                // 单位默认切到 mm（测距原始精度就是毫米级）
                                int mmIdx = UnitUtil.indexOf("mm");
                                if (mmIdx >= 0) sp.setSelection(mmIdx, false);
                                float v = UnitUtil.convert(meters, "m", "mm");
                                et.setText(fmtValue(v));
                                et.setSelection(et.getText().length());
                                Toast.makeText(EditActivity.this,
                                        String.format("测到 %.0f mm", meters * 1000),
                                        Toast.LENGTH_SHORT).show();
                            }
                            @Override public void onFail(String why) {
                                Toast.makeText(EditActivity.this, why, Toast.LENGTH_LONG).show();
                            }
                        });
                    }
                });
            }
        });

        dlg.show();
        sp.setSelection(UnitUtil.indexOf(u), false);
    }


    /* ---------------- 蓝牙测距 ---------------- */

    /** 一次测距请求的等待者 */
    private interface BleWait {
        void onGot(float meters);
        void onFail(String why);
    }

    private BleWait bleWaiter;
    /** 记录这次测距属于哪条待输入的线（画线后点测距用） */
    private float pendingX1, pendingY1, pendingX2, pendingY2;
    private boolean pendingIsLine = false;

    /** 蓝牙测到值：优先回填到待输入的线，否则套用到选中标注 */
    private void onBleMeasured(float meters) {
        if (bleWaiter != null) {
            BleWait w = bleWaiter;
            bleWaiter = null;
            w.onGot(meters);
            return;
        }
        if (pendingIsLine) {
            pendingIsLine = false;
            showLineInputDialog(pendingX1, pendingY1, pendingX2, pendingY2, meters);
            return;
        }
        applyBleMeters(meters);
    }

    /** 连测多次取中位数；未连接会自动连 */
    private void requestMeasureMulti(final int count, final BleWait waiter) {
        if (bleMeter == null) { waiter.onFail("蓝牙模块未初始化"); return; }
        if (bleMeter.isReady()) {
            bleWaiter = waiter;
            bleMeter.measureMulti(count);
            return;
        }
        if (!bleMeter.autoConnect()) { waiter.onFail("还没有选择过测距仪"); return; }
        bleWaiter = waiter;
        ui.postDelayed(new Runnable() {
            @Override public void run() {
                if (bleMeter != null && bleMeter.isReady()) {
                    bleMeter.measureMulti(count);
                } else {
                    bleWaiter = null;
                    waiter.onFail("连接超时，请靠近测距仪后重试");
                }
            }
        }, 3500);
    }

    /** 让测距仪测一次；未连接会自动连（用记住的设备） */
    private void requestMeasure(final BleWait waiter) {
        if (bleMeter == null) {
            waiter.onFail("蓝牙模块未初始化");
            return;
        }
        if (bleMeter.isReady()) {
            bleWaiter = waiter;
            if (!bleMeter.measure()) {
                bleWaiter = null;
                waiter.onFail("发送失败");
            }
            return;
        }
        // 没连上：先连（有记住的设备就直接连）
        if (!bleMeter.autoConnect()) {
            waiter.onFail("还没有选择过测距仪");
            return;
        }
        bleWaiter = waiter;
        // 等连接 + 握手完成后再发测量
        ui.postDelayed(new Runnable() {
            @Override public void run() {
                if (bleMeter != null && bleMeter.isReady()) {
                    bleMeter.measure();
                } else {
                    bleWaiter = null;
                    waiter.onFail("连接超时，请靠近测距仪后重试");
                }
            }
        }, 3500);
    }

    private final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());

    /** 画线后点「蓝牙测距」：直接测距，测到值再弹输入框预填 */
    private void openBleMeterForLine(final float x1, final float y1,
                                     final float x2, final float y2) {
        if (bleMeter == null || bleMeter.lastDeviceMac() == null) {
            // 从没选过设备，先让用户挑一个
            new BleMeterDialog(this, new BleMeterDialog.OnMeasure() {
                @Override public void onUseMeasure(float meters) {
                    showLineInputDialog(x1, y1, x2, y2, meters);
                }
            }).show();
            return;
        }
        pendingX1 = x1; pendingY1 = y1; pendingX2 = x2; pendingY2 = y2;
        pendingIsLine = true;
        Toast.makeText(this, "正在测距…", Toast.LENGTH_SHORT).show();
        requestMeasure(new BleWait() {
            @Override public void onGot(float m) { /* onBleMeasured 已处理 */ }
            @Override public void onFail(final String why) {
                pendingIsLine = false;
                Toast.makeText(EditActivity.this, why, Toast.LENGTH_LONG).show();
                showLineInputDialog(x1, y1, x2, y2, -1f);
            }
        });
    }

    /** 打开蓝牙测距仪对话框 */
    private void openBleMeter() {
        new BleMeterDialog(this, new BleMeterDialog.OnMeasure() {
            @Override
            public void onUseMeasure(float meters) {
                applyBleMeters(meters);
            }
        }).show();
    }

    /** 把测距结果填进标注 */
    private void applyBleMeters(float meters) {
        Project.Ann sel = canvas.getSelected();
        if (sel == null) {
            // 没有选中：存起来，下次画线时用
            pendingBleMeters = meters;
            Toast.makeText(this, String.format("已记录 %.3f m，画一条线即可用上", meters),
                    Toast.LENGTH_LONG).show();
            if (hint != null) hint.setText(String.format("已记录测距值 %.3f m，画线后自动填入", meters));
            return;
        }
        if (sel.type == Project.Ann.TYPE_TEXT || sel.type == Project.Ann.TYPE_ANGLE) {
            Toast.makeText(this, "该类型标注不能用长度值", Toast.LENGTH_SHORT).show();
            return;
        }
        // 测距仪返回米，统一按当前项目单位显示（默认 mm）
        String u = (project == null || project.unit == null) ? "mm" : project.unit;
        sel.value = UnitUtil.convert(meters, "m", u);
        sel.unit = u;
        sel.manual = true;       // 锁定，避免被像素换算覆盖
        pushUndo();
        canvas.invalidate();
        refreshStatus();
        Toast.makeText(this, String.format("已填入 %.0f mm", meters * 1000), Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onDestroy() {
        if (bleMeter != null) { bleMeter.release(); bleMeter = null; }
        super.onDestroy();
    }

    private void exportThis() {
        saveNow(false);
        List<Project.Item> one = new ArrayList<>();
        one.add(item);
        final PdfExporter.Result r = PdfExporter.export(this, project, one, project.name + "_单张");
        if (r.file == null) {
            Toast.makeText(this, "导出失败：" + r.error, Toast.LENGTH_LONG).show();
            return;
        }
        final Store.SavedPdf saved = Store.savePdf(this, r.file, r.file.getName());
        if (saved.uri == null) {
            Toast.makeText(this, "保存失败：" + saved.error, Toast.LENGTH_LONG).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("导出完成")
                .setMessage("共 " + r.pages + " 页\n" + saved.displayPath)
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
                        try {
                            Intent i = new Intent(Intent.ACTION_VIEW);
                            i.setDataAndType(saved.uri, "application/pdf");
                            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                            startActivity(i);
                        } catch (Exception e) {
                            Toast.makeText(EditActivity.this,
                                    "没有能打开 PDF 的应用", Toast.LENGTH_LONG).show();
                        }
                    }
                })
                .show();
    }

    private void saveNow(boolean toast) {
        try {
            Store.save(this, project);
            dirty = false;
            if (toast) Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "保存失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (dirty) saveNow(false);
    }

    @Override
    public void onBackPressed() {
        if (dirty) saveNow(false);
        super.onBackPressed();
    }
}
