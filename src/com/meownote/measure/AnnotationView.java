package com.meownote.measure;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * 图片 + 标注画布。
 * 标注坐标一律用「图片原始像素坐标」保存，屏幕显示时经 matrix 映射。
 *
 * 交互：
 *  - 单指点在已有标注上 → 选中并整体拖动；抬手未移动则触发编辑
 *  - 选中尺寸线后，拖动两端的圆形手柄可单独调整端点
 *  - 点空白处 → 按当前模式新建尺寸线 / 注释
 *  - 双指缩放，浏览模式单指平移
 */
public class AnnotationView extends View {

    public static final int MODE_PAN = 0;
    public static final int MODE_LINE = 1;
    public static final int MODE_TEXT = 2;
    public static final int MODE_SCALE = 3;
    public static final int MODE_RECT = 4;
    public static final int MODE_CIRCLE = 5;
    public static final int MODE_ANGLE = 6;

    public interface Listener {
        void onRequestLine(float x1, float y1, float x2, float y2);

        void onRequestText(float x, float y);

        /** 点击了已选中的标注（未拖动），请求编辑 */
        void onAnnClick(Project.Ann a);

        /** 标注坐标被拖动修改，需要保存 */
        void onAnnChanged(Project.Ann a);

        /** 画了一条基准线（比例尺），请求输入实际长度 */
        void onRequestScale(float x1, float y1, float x2, float y2);

        /** 即将修改某条标注（拖动/端点调整前），用于拍撤销快照 */
        void onAnnWillChange();

        void onRequestRect(float x1, float y1, float x2, float y2);

        void onRequestCircle(float cx, float cy, float rx, float ry);

        void onRequestAngle(float ax, float ay, float vx, float vy, float bx, float by);

        /** 选中的标注发生变化（含取消选中），供界面显示编辑入口 */
        void onSelectionChanged(Project.Ann sel);
    }

    private Bitmap bitmap;
    private final Matrix matrix = new Matrix();
    private final Matrix invMatrix = new Matrix();

    private final List<Project.Ann> anns = new ArrayList<>();
    private Project.Ann preview;
    private Project.Ann selected;
    private int mode = MODE_LINE;
    private Listener listener;

    /* 拖动状态 */
    private static final int DRAG_NONE = 0;
    private static final int DRAG_MOVE = 1;
    private static final int DRAG_EP1 = 2;
    private static final int DRAG_EP2 = 3;
    private static final int DRAG_EP3 = 4;   // 角度第二边端点
    private int dragKind = DRAG_NONE;
    private float downScreenX, downScreenY;
    private float lastX, lastY;
    private float origX1, origY1, origX2, origY2, origX3, origY3;
    /** 拖动前的像素尺寸，用于无量纲缩放 */
    private float origPxLen = 1f, origPxW = 1f, origPxH = 1f;
    private boolean moving;
    /** 按下时该标注是否已处于选中态（用于区分"首次选中"和"再次点击编辑"） */
    private boolean hitWasSelected;

    /* 角度绘制：三次点击确定 顶点 -> 边1 -> 边2 */
    private int angStep = 0;
    private float angVx, angVy, angAx, angAy;

    private boolean scaling;

    private final ScaleGestureDetector scaleDetector;

    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bubblePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bubbleStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handleFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handleStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint selPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint scalePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** 当前图片的比例尺（null 表示未标定） */
    private Project.Item scaleRef;

    /* 新建标注的默认样式 */
    private int curColor = 0xFFFF3B30;
    private float curStrokeW = 2.2f;
    private boolean curDashed = false;

    public void setStyle(int color, float strokeW, boolean dashed) {
        curColor = color;
        curStrokeW = strokeW;
        curDashed = dashed;
    }

    public int getCurColor() { return curColor; }
    public float getCurStrokeW() { return curStrokeW; }
    public boolean isCurDashed() { return curDashed; }

    /** 把当前样式套到指定标注上 */
    public void applyStyleTo(Project.Ann a) {
        if (a == null) return;
        a.color = curColor;
        a.strokeW = curStrokeW;
        a.dashed = curDashed;
        invalidate();
    }

    private final float density;
    private final float MIN_LINE_LEN;
    private final float HIT_TOL;
    private final float HANDLE_R;

    public AnnotationView(Context c) {
        this(c, null);
    }

    public AnnotationView(Context c, AttributeSet a) {
        super(c, a);
        density = getResources().getDisplayMetrics().density;
        MIN_LINE_LEN = 26f * density;
        HIT_TOL = 20f * density;
        HANDLE_R = 9f * density;

        setBackgroundColor(0xFF1B1B1B);

        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(2.2f * density);
        linePaint.setStrokeCap(Paint.Cap.ROUND);
        linePaint.setColor(0xFFFF3B30);

        textPaint.setTextSize(12f * density);
        textPaint.setColor(0xFFFF3B30);
        textPaint.setFakeBoldText(true);

        bubblePaint.setStyle(Paint.Style.FILL);
        bubblePaint.setColor(0xF2FFFFFF);

        bubbleStroke.setStyle(Paint.Style.STROKE);
        bubbleStroke.setStrokeWidth(1.2f * density);
        bubbleStroke.setColor(0xFFFF3B30);
        bubblePaint.setColor(0xF2FFFFFF);

        handleFill.setStyle(Paint.Style.FILL);
        handleFill.setColor(0xFFFFFFFF);

        handleStroke.setStyle(Paint.Style.STROKE);
        handleStroke.setStrokeWidth(2f * density);
        handleStroke.setColor(0xFF1E6FD9);

        selPaint.setStyle(Paint.Style.STROKE);
        selPaint.setStrokeWidth(1.4f * density);
        selPaint.setColor(0xFF1E6FD9);
        selPaint.setPathEffect(new DashPathEffect(new float[]{5 * density, 4 * density}, 0));

        scalePaint.setStyle(Paint.Style.STROKE);
        scalePaint.setStrokeWidth(2.6f * density);
        scalePaint.setColor(0xFF00A86B);
        scalePaint.setPathEffect(new DashPathEffect(new float[]{8 * density, 5 * density}, 0));

        scaleDetector = new ScaleGestureDetector(c, new ScaleListener());
    }

    public void setListener(Listener l) {
        this.listener = l;
    }

    public void setScaleRef(Project.Item ref) {
        this.scaleRef = ref;
        invalidate();
    }

    public void setMode(int m) {
        this.mode = m;
        preview = null;
        angStep = 0;
        if (m == MODE_PAN && selected != null) {
            selected = null;
            if (listener != null) listener.onSelectionChanged(null);
        }
        invalidate();
    }

    public int getMode() {
        return mode;
    }

    public void setBitmap(Bitmap b) {
        this.bitmap = b;
        requestLayout();
        invalidate();
    }

    public void setAnns(List<Project.Ann> list) {
        anns.clear();
        if (list != null) anns.addAll(list);
        if (selected != null && !anns.contains(selected)) selected = null;
        invalidate();
    }

    public List<Project.Ann> getAnns() {
        return anns;
    }

    public Project.Ann getSelected() {
        return selected;
    }

    public void setSelected(Project.Ann a) {
        selected = a;
        if (listener != null) listener.onSelectionChanged(a);
        invalidate();
    }

    public void clearSelection() {
        selected = null;
        if (listener != null) listener.onSelectionChanged(null);
        invalidate();
    }

    public void removeAnn(Project.Ann a) {
        anns.remove(a);
        if (selected == a) selected = null;
        invalidate();
    }

    public void fitToScreen() {
        fitCenter();
    }

    /* ---------------- 布局与变换 ---------------- */

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        fitCenter();
    }

    private void fitCenter() {
        if (bitmap == null || getWidth() == 0 || getHeight() == 0) return;
        float vw = getWidth(), vh = getHeight();
        float bw = bitmap.getWidth(), bh = bitmap.getHeight();
        float s = Math.min(vw / bw, vh / bh);
        matrix.reset();
        matrix.postScale(s, s);
        matrix.postTranslate((vw - bw * s) / 2f, (vh - bh * s) / 2f);
        matrix.invert(invMatrix);
        invalidate();
    }

    private float[] imgToScreen(float x, float y) {
        float[] p = new float[]{x, y};
        matrix.mapPoints(p);
        return p;
    }

    private void screenToImg(float[] p) {
        matrix.invert(invMatrix);
        invMatrix.mapPoints(p);
    }

    /** 单指拖动时，屏幕位移换算成图片坐标位移 */
    private float[] screenDeltaToImg(float dx, float dy) {
        matrix.invert(invMatrix);
        float[] d = new float[]{dx, dy};
        // 只用旋转/缩放部分，去掉平移
        Matrix m = new Matrix(invMatrix);
        float[] pts = new float[]{0, 0, 1, 0, 0, 1};
        m.mapPoints(pts);
        float ax = pts[2] - pts[0], ay = pts[3] - pts[1];
        float bx = pts[4] - pts[0], by = pts[5] - pts[1];
        return new float[]{ax * dx + bx * dy, ay * dx + by * dy};
    }

    /* ---------------- 绘制 ---------------- */

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (bitmap != null) canvas.drawBitmap(bitmap, matrix, null);

        drawScaleRef(canvas);

        for (Project.Ann a : anns) {
            if (a != selected) drawAnn(canvas, a, false);
        }
        // 只有 selected 仍在列表中才单独绘制，避免撤销后残留旧引用的手柄
        if (selected != null && anns.contains(selected)) drawAnn(canvas, selected, true);
        if (preview != null) drawAnn(canvas, preview, false);
        drawAngleProgress(canvas);
    }

    /** 角度绘制过程的临时提示（顶点已确定、边1已确定） */
    private void drawAngleProgress(Canvas canvas) {
        if (mode != MODE_ANGLE || angStep == 0) return;
        float[] v = imgToScreen(angVx, angVy);
        linePaint.setPathEffect(null);
        linePaint.setColor(0xFF1E6FD9);
        linePaint.setStrokeWidth(2f * density);
        canvas.drawCircle(v[0], v[1], 7 * density, linePaint);
        if (angStep == 2) {
            float[] a = imgToScreen(angAx, angAy);
            canvas.drawLine(v[0], v[1], a[0], a[1], linePaint);
            canvas.drawCircle(a[0], a[1], 6 * density, linePaint);
        }
        textPaint.setColor(0xFF1E6FD9);
        canvas.drawText(angStep == 1 ? "已定顶点，点第一条边" : "点第二条边",
                12 * density, getHeight() - 12 * density, textPaint);
    }

    private void drawScaleRef(Canvas canvas) {
        if (scaleRef == null || !scaleRef.hasScale()) return;
        float[] p1 = imgToScreen(scaleRef.calX1, scaleRef.calY1);
        float[] p2 = imgToScreen(scaleRef.calX2, scaleRef.calY2);
        canvas.drawLine(p1[0], p1[1], p2[0], p2[1], scalePaint);

        // 端点小方块
        float t = 7f * density;
        scalePaint.setPathEffect(null);
        canvas.drawLine(p1[0] - t, p1[1] - t, p1[0] + t, p1[1] + t, scalePaint);
        canvas.drawLine(p1[0] - t, p1[1] + t, p1[0] + t, p1[1] - t, scalePaint);
        canvas.drawLine(p2[0] - t, p2[1] - t, p2[0] + t, p2[1] + t, scalePaint);
        canvas.drawLine(p2[0] - t, p2[1] + t, p2[0] + t, p2[1] - t, scalePaint);
        scalePaint.setPathEffect(new DashPathEffect(new float[]{8 * density, 5 * density}, 0));

        // 标签
        String txt = scaleRef.calText();
        textPaint.setColor(0xFF00A86B);
        float pad = 4f * density;
        Paint.FontMetrics fm = textPaint.getFontMetrics();
        float tw = textPaint.measureText(txt);
        float th = fm.descent - fm.ascent;
        RectF r = new RectF((p1[0] + p2[0]) / 2f - (tw + pad * 2) / 2f,
                (p1[1] + p2[1]) / 2f - th - pad * 2.2f,
                (p1[0] + p2[0]) / 2f + (tw + pad * 2) / 2f,
                (p1[1] + p2[1]) / 2f - pad * 0.2f);
        bubbleStroke.setColor(0xFF00A86B);
        canvas.drawRoundRect(r, 3 * density, 3 * density, bubblePaint);
        canvas.drawRoundRect(r, 3 * density, 3 * density, bubbleStroke);
        canvas.drawText(txt, r.left + pad, r.top + pad * 0.6f - fm.ascent, textPaint);
    }

    private void drawAnn(Canvas canvas, Project.Ann a, boolean sel) {
        float w = a.strokeW > 0 ? a.strokeW : 2.2f;
        linePaint.setStrokeWidth(w * density);
        linePaint.setColor(a.color);
        if (a.dashed) {
            linePaint.setPathEffect(new DashPathEffect(
                    new float[]{6 * density, 4 * density}, 0));
        } else {
            linePaint.setPathEffect(null);
        }

        switch (a.type) {
            case Project.Ann.TYPE_LINE: {
                float[] p1 = imgToScreen(a.x1, a.y1);
                float[] p2 = imgToScreen(a.x2, a.y2);
                canvas.drawLine(p1[0], p1[1], p2[0], p2[1], linePaint);
                drawTick(canvas, p1, p2, a.color);
                drawBubble(canvas, a, (p1[0] + p2[0]) / 2f, (p1[1] + p2[1]) / 2f);
                linePaint.setPathEffect(null);
                if (sel) {
                    canvas.drawCircle(p1[0], p1[1], HANDLE_R, handleFill);
                    canvas.drawCircle(p1[0], p1[1], HANDLE_R, handleStroke);
                    canvas.drawCircle(p2[0], p2[1], HANDLE_R, handleFill);
                    canvas.drawCircle(p2[0], p2[1], HANDLE_R, handleStroke);
                }
                break;
            }
            case Project.Ann.TYPE_RECT: {
                float[] p1 = imgToScreen(a.x1, a.y1);
                float[] p2 = imgToScreen(a.x2, a.y2);
                float l = Math.min(p1[0], p2[0]), t = Math.min(p1[1], p2[1]);
                float r = Math.max(p1[0], p2[0]), b = Math.max(p1[1], p2[1]);
                RectF rf = new RectF(l, t, r, b);
                canvas.drawRect(rf, linePaint);
                linePaint.setPathEffect(null);
                drawBubble(canvas, a, (l + r) / 2f, t);
                if (sel) {
                    canvas.drawCircle(l, t, HANDLE_R, handleFill);
                    canvas.drawCircle(l, t, HANDLE_R, handleStroke);
                    canvas.drawCircle(r, b, HANDLE_R, handleFill);
                    canvas.drawCircle(r, b, HANDLE_R, handleStroke);
                }
                break;
            }
            case Project.Ann.TYPE_CIRCLE: {
                float[] ct = imgToScreen(a.x1, a.y1);
                float[] rp = imgToScreen(a.x2, a.y2);
                float rad = (float) Math.hypot(rp[0] - ct[0], rp[1] - ct[1]);
                canvas.drawCircle(ct[0], ct[1], rad, linePaint);
                linePaint.setPathEffect(null);
                drawBubble(canvas, a, ct[0], ct[1] - rad);
                if (sel) {
                    canvas.drawCircle(ct[0], ct[1], HANDLE_R, handleFill);
                    canvas.drawCircle(ct[0], ct[1], HANDLE_R, handleStroke);
                    canvas.drawCircle(rp[0], rp[1], HANDLE_R, handleFill);
                    canvas.drawCircle(rp[0], rp[1], HANDLE_R, handleStroke);
                }
                break;
            }
            case Project.Ann.TYPE_ANGLE: {
                float[] va = imgToScreen(a.x1, a.y1);   // 边1端点
                float[] vv = imgToScreen(a.x2, a.y2);   // 顶点
                float[] vb = imgToScreen(a.x3, a.y3);   // 边2端点
                canvas.drawLine(vv[0], vv[1], va[0], va[1], linePaint);
                canvas.drawLine(vv[0], vv[1], vb[0], vb[1], linePaint);
                linePaint.setPathEffect(null);
                // 角弧
                double a1 = Math.toDegrees(Math.atan2(va[1] - vv[1], va[0] - vv[0]));
                double a2 = Math.toDegrees(Math.atan2(vb[1] - vv[1], vb[0] - vv[0]));
                float sweep = (float) (a2 - a1);
                while (sweep <= -180) sweep += 360;
                while (sweep > 180) sweep -= 360;
                float arcR = 26 * density;
                RectF arc = new RectF(vv[0] - arcR, vv[1] - arcR, vv[0] + arcR, vv[1] + arcR);
                canvas.drawArc(arc, (float) a1, sweep, false, linePaint);
                drawBubble(canvas, a, vv[0], vv[1]);
                if (sel) {
                    // 三个点都可拖：边1端点 / 顶点 / 边2端点
                    for (float[] hp : new float[][]{va, vv, vb}) {
                        canvas.drawCircle(hp[0], hp[1], HANDLE_R, handleFill);
                        canvas.drawCircle(hp[0], hp[1], HANDLE_R, handleStroke);
                    }
                }
                break;
            }
            default: {
                // 文字注释
                float[] p = imgToScreen(a.x1, a.y1);
                linePaint.setColor(a.color);
                float r = 5f * density;
                canvas.drawLine(p[0] - r, p[1] - r, p[0] + r, p[1] + r, linePaint);
                canvas.drawLine(p[0] - r, p[1] + r, p[0] + r, p[1] - r, linePaint);
                linePaint.setPathEffect(null);
                drawBubble(canvas, a, p[0], p[1]);
                if (sel) {
                    canvas.drawCircle(p[0], p[1], HANDLE_R, handleFill);
                    canvas.drawCircle(p[0], p[1], HANDLE_R, handleStroke);
                }
                break;
            }
        }
    }

    private void drawTick(Canvas canvas, float[] p1, float[] p2, int color) {
        float dx = p2[0] - p1[0], dy = p2[1] - p1[1];
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 1) return;
        float nx = -dy / len, ny = dx / len;
        float t = 6f * density;
        linePaint.setColor(color);
        linePaint.setPathEffect(null);
        canvas.drawLine(p1[0] - nx * t, p1[1] - ny * t, p1[0] + nx * t, p1[1] + ny * t, linePaint);
        canvas.drawLine(p2[0] - nx * t, p2[1] - ny * t, p2[0] + nx * t, p2[1] + ny * t, linePaint);
    }

    /** 计算文字气泡矩形（屏幕坐标），供绘制与命中复用 */
    private RectF bubbleRect(Project.Ann a, float cx, float cy) {
        String t = a.displayText();
        if (t == null || t.length() == 0) return null;
        textPaint.setColor(a.color);
        float pad = 4f * density;
        Paint.FontMetrics fm = textPaint.getFontMetrics();
        float tw = textPaint.measureText(t);
        float th = fm.descent - fm.ascent;
        float bw = tw + pad * 2;
        float bh = th + pad * 1.2f;
        float bx = cx - bw / 2f;
        float by = cy - bh - 10f * density;
        return new RectF(bx, by, bx + bw, by + bh);
    }

    /**
     * 形状变了之后重算标注的数值。
     * 有比例尺 -> 直接从像素换算；没有 -> 按像素比例缩放原值。
     */
    private void recalcValue(Project.Ann a) {
        if (a == null) return;
        // 用户手动指定过数值（图形与实际不符），不自动覆盖
        if (a.manual) return;
        boolean scaled = scaleRef != null && scaleRef.hasScale();
        float perPx = scaled ? scaleRef.calValue / scaleRef.calPx() : 0f;
        String from = scaled ? scaleRef.calUnit : null;
        String to = (a.unit == null || a.unit.length() == 0)
                ? (scaled ? scaleRef.calUnit : "") : a.unit;

        switch (a.type) {
            case Project.Ann.TYPE_LINE: {
                float px = (float) Math.hypot(a.x2 - a.x1, a.y2 - a.y1);
                if (scaled) {
                    a.value = UnitUtil.convert(px * perPx, from, to);
                    a.unit = to;
                } else if (origPxLen > 1f) {
                    float k = px / origPxLen;
                    a.value *= k;
                    origPxLen = px;
                }
                break;
            }
            case Project.Ann.TYPE_RECT: {
                float pw = Math.abs(a.x2 - a.x1), ph = Math.abs(a.y2 - a.y1);
                if (scaled) {
                    a.value = UnitUtil.convert(pw * perPx, from, to);
                    a.value2 = UnitUtil.convert(ph * perPx, from, to);
                    a.unit = to;
                } else if (origPxW > 1f && origPxH > 1f) {
                    a.value *= pw / origPxW;
                    a.value2 *= ph / origPxH;
                    origPxW = pw;
                    origPxH = ph;
                }
                break;
            }
            case Project.Ann.TYPE_CIRCLE: {
                float pr = (float) Math.hypot(a.x2 - a.x1, a.y2 - a.y1);
                if (scaled) {
                    a.value = UnitUtil.convert(pr * perPx, from, to);
                    a.unit = to;
                } else if (origPxLen > 1f) {
                    a.value *= pr / origPxLen;
                    origPxLen = pr;
                }
                break;
            }
            case Project.Ann.TYPE_ANGLE: {
                a.value = a.angleFromPoints();
                break;
            }
            default:
                break;
        }
    }

    /** 在白色气泡上仍然清晰的前景色 */
    static int readableOnWhite(int c) {
        int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
        double lum = (0.299 * r + 0.587 * g + 0.114 * b) / 255.0;
        if (lum > 0.72) return 0xFF333333;
        return c;
    }

    private void drawBubble(Canvas canvas, Project.Ann a, float cx, float cy) {
        String t = a.displayText();
        if (t == null || t.length() == 0) return;
        RectF r = bubbleRect(a, cx, cy);
        if (r == null) return;

        // 气泡是白底，浅色标注（白/黄）要换成深色文字才看得见
        int fg = readableOnWhite(a.color);
        textPaint.setColor(fg);
        bubbleStroke.setColor(fg);

        float rad = 3f * density;
        canvas.drawRoundRect(r, rad, rad, bubblePaint);
        canvas.drawRoundRect(r, rad, rad, bubbleStroke);

        Paint.FontMetrics fm = textPaint.getFontMetrics();
        float pad = 4f * density;
        float baseline = r.top + pad * 0.6f - fm.ascent;
        canvas.drawText(t, r.left + pad, baseline, textPaint);

        linePaint.setColor(a.color);
        linePaint.setStrokeWidth(1.2f * density);
        canvas.drawLine(cx, r.bottom, cx, cy, linePaint);
        linePaint.setStrokeWidth(2.2f * density);
    }

    /* ---------------- 命中测试 ---------------- */

    private static float distToSegment(float px, float py, float x1, float y1, float x2, float y2) {
        float dx = x2 - x1, dy = y2 - y1;
        float len2 = dx * dx + dy * dy;
        if (len2 < 1e-6f) return (float) Math.hypot(px - x1, py - y1);
        float t = ((px - x1) * dx + (py - y1) * dy) / len2;
        if (t < 0) t = 0;
        if (t > 1) t = 1;
        float cx = x1 + t * dx, cy = y1 + t * dy;
        return (float) Math.hypot(px - cx, py - cy);
    }

    /** 判断屏幕点是否落在某标注上（线/点/气泡） */
    private boolean hitAnn(Project.Ann a, float sx, float sy) {
        if (a.type == Project.Ann.TYPE_RECT) {
            float[] p1 = imgToScreen(a.x1, a.y1);
            float[] p2 = imgToScreen(a.x2, a.y2);
            float l = Math.min(p1[0], p2[0]), t = Math.min(p1[1], p2[1]);
            float r = Math.max(p1[0], p2[0]), b = Math.max(p1[1], p2[1]);
            // 边框附近或内部都算命中
            boolean nearEdge = Math.abs(sx - l) < HIT_TOL || Math.abs(sx - r) < HIT_TOL
                    || Math.abs(sy - t) < HIT_TOL || Math.abs(sy - b) < HIT_TOL;
            boolean inside = sx > l - HIT_TOL && sx < r + HIT_TOL
                    && sy > t - HIT_TOL && sy < b + HIT_TOL;
            if (nearEdge && inside) return true;
            RectF rfb = bubbleRect(a, (l + r) / 2f, t);
            if (rfb != null) {
                RectF big = new RectF(rfb);
                big.inset(-6 * density, -6 * density);
                if (big.contains(sx, sy)) return true;
            }
            return false;
        }
        if (a.type == Project.Ann.TYPE_CIRCLE) {
            float[] ct = imgToScreen(a.x1, a.y1);
            float[] rp = imgToScreen(a.x2, a.y2);
            float rad = (float) Math.hypot(rp[0] - ct[0], rp[1] - ct[1]);
            float d = (float) Math.hypot(sx - ct[0], sy - ct[1]);
            if (Math.abs(d - rad) < HIT_TOL) return true;
            RectF rfb = bubbleRect(a, ct[0], ct[1] - rad);
            if (rfb != null) {
                RectF big = new RectF(rfb);
                big.inset(-6 * density, -6 * density);
                if (big.contains(sx, sy)) return true;
            }
            return false;
        }
        if (a.type == Project.Ann.TYPE_ANGLE) {
            float[] va = imgToScreen(a.x1, a.y1);
            float[] vv = imgToScreen(a.x2, a.y2);
            float[] vb = imgToScreen(a.x3, a.y3);
            if (distToSegment(sx, sy, vv[0], vv[1], va[0], va[1]) < HIT_TOL) return true;
            if (distToSegment(sx, sy, vv[0], vv[1], vb[0], vb[1]) < HIT_TOL) return true;
            RectF rfb = bubbleRect(a, vv[0], vv[1]);
            if (rfb != null) {
                RectF big = new RectF(rfb);
                big.inset(-6 * density, -6 * density);
                if (big.contains(sx, sy)) return true;
            }
            return false;
        }
        if (a.type == Project.Ann.TYPE_LINE) {
            float[] p1 = imgToScreen(a.x1, a.y1);
            float[] p2 = imgToScreen(a.x2, a.y2);
            if (distToSegment(sx, sy, p1[0], p1[1], p2[0], p2[1]) < HIT_TOL) return true;
            RectF r = bubbleRect(a, (p1[0] + p2[0]) / 2f, (p1[1] + p2[1]) / 2f);
            if (r != null) {
                RectF big = new RectF(r);
                big.inset(-6 * density, -6 * density);
                if (big.contains(sx, sy)) return true;
            }
            return false;
        }
        // 文字
        float[] p = imgToScreen(a.x1, a.y1);
        if (Math.hypot(sx - p[0], sy - p[1]) < HIT_TOL) return true;
        RectF r = bubbleRect(a, p[0], p[1]);
        if (r != null) {
            RectF big = new RectF(r);
            big.inset(-6 * density, -6 * density);
            if (big.contains(sx, sy)) return true;
        }
        return false;
    }
    /** 命中端点手柄：返回 DRAG_EP1 / DRAG_EP2 / DRAG_NONE */
    private int hitHandle(Project.Ann a, float sx, float sy) {
        if (a == null) return DRAG_NONE;
        float rr = HANDLE_R * 2.2f;
        float[] p1 = imgToScreen(a.x1, a.y1);
        float[] p2 = imgToScreen(a.x2, a.y2);
        switch (a.type) {
            case Project.Ann.TYPE_ANGLE: {
                if (Math.hypot(sx - p1[0], sy - p1[1]) < rr) return DRAG_EP1;
                if (Math.hypot(sx - p2[0], sy - p2[1]) < rr) return DRAG_EP2;
                float[] p3 = imgToScreen(a.x3, a.y3);
                if (Math.hypot(sx - p3[0], sy - p3[1]) < rr) return DRAG_EP3;
                return DRAG_NONE;
            }
            case Project.Ann.TYPE_CIRCLE: {
                // p1=圆心, p2=半径点
                if (Math.hypot(sx - p1[0], sy - p1[1]) < rr) return DRAG_EP1;
                if (Math.hypot(sx - p2[0], sy - p2[1]) < rr) return DRAG_EP2;
                return DRAG_NONE;
            }
            default: {
                // LINE / RECT 都是两个对角控制点
                if (Math.hypot(sx - p1[0], sy - p1[1]) < rr) return DRAG_EP1;
                if (Math.hypot(sx - p2[0], sy - p2[1]) < rr) return DRAG_EP2;
                return DRAG_NONE;
            }
        }
    }

    private Project.Ann pick(float sx, float sy) {
        for (int i = anns.size() - 1; i >= 0; i--) {
            Project.Ann a = anns.get(i);
            if (hitAnn(a, sx, sy)) return a;
        }
        return null;
    }

    /* ---------------- 触摸 ---------------- */

    private class ScaleListener implements ScaleGestureDetector.OnScaleGestureListener {
        @Override
        public boolean onScale(ScaleGestureDetector d) {
            scaling = true;
            float f = d.getScaleFactor();
            matrix.postScale(f, f, d.getFocusX(), d.getFocusY());
            invalidate();
            return true;
        }

        @Override
        public boolean onScaleBegin(ScaleGestureDetector d) {
            scaling = true;
            return true;
        }

        @Override
        public void onScaleEnd(ScaleGestureDetector d) {
            scaling = false;
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        scaleDetector.onTouchEvent(e);

        if (scaleDetector.isInProgress() || e.getPointerCount() > 1) {
            dragKind = DRAG_NONE;
            preview = null;
            return true;
        }

        float x = e.getX(), y = e.getY();

        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                lastX = x;
                lastY = y;
                downScreenX = x;
                downScreenY = y;
                moving = false;

                if (mode == MODE_PAN) {
                    dragKind = DRAG_NONE;
                    return true;
                }

                // 1) 已选中的标注，先看端点手柄
                int hk = hitHandle(selected, x, y);
                if (hk != DRAG_NONE) {
                    if (listener != null) listener.onAnnWillChange();
                    dragKind = hk;
                    origX1 = selected.x1;
                    origY1 = selected.y1;
                    origX2 = selected.x2;
                    origY2 = selected.y2;
                    origX3 = selected.x3;
                    origY3 = selected.y3;
                    origPxLen = (float) Math.hypot(selected.x2 - selected.x1, selected.y2 - selected.y1);
                    origPxW = Math.abs(selected.x2 - selected.x1);
                    origPxH = Math.abs(selected.y2 - selected.y1);
                    return true;
                }

                // 2) 点中某个标注 -> 选中并准备拖动
                Project.Ann hit = pick(x, y);

                if (hit != null) {
                    hitWasSelected = (hit == selected);
                    if (listener != null) listener.onAnnWillChange();
                    selected = hit;
                    if (listener != null) listener.onSelectionChanged(selected);
                    dragKind = DRAG_MOVE;
                    origX1 = hit.x1;
                    origY1 = hit.y1;
                    origX2 = hit.x2;
                    origY2 = hit.y2;
                    origX3 = hit.x3;
                    origY3 = hit.y3;
                    origPxLen = (float) Math.hypot(hit.x2 - hit.x1, hit.y2 - hit.y1);
                    origPxW = Math.abs(hit.x2 - hit.x1);
                    origPxH = Math.abs(hit.y2 - hit.y1);
                    invalidate();
                    return true;
                }

                // 3) 点空白 -> 取消选中，按模式新建
                if (selected != null && listener != null) listener.onSelectionChanged(null);
                selected = null;
                dragKind = DRAG_NONE;
                if (mode == MODE_LINE || mode == MODE_SCALE) {
                    float[] p = new float[]{x, y};
                    screenToImg(p);
                    preview = Project.Ann.line(p[0], p[1], p[0], p[1], "", curColor);
                    preview.strokeW = curStrokeW;
                    preview.dashed = curDashed;
                } else if (mode == MODE_RECT || mode == MODE_CIRCLE) {
                    float[] p = new float[]{x, y};
                    screenToImg(p);
                    preview = (mode == MODE_RECT)
                            ? Project.Ann.rect(p[0], p[1], p[0], p[1], curColor)
                            : Project.Ann.circle(p[0], p[1], p[0], p[1], curColor);
                    preview.strokeW = curStrokeW;
                    preview.dashed = curDashed;
                }
                invalidate();
                return true;
            }

            case MotionEvent.ACTION_MOVE: {
                float dx = x - lastX, dy = y - lastY;
                if (Math.abs(x - downScreenX) > 6 * density || Math.abs(y - downScreenY) > 6 * density) {
                    moving = true;
                }

                if (mode == MODE_PAN) {
                    matrix.postTranslate(dx, dy);
                    lastX = x;
                    lastY = y;
                    invalidate();
                    return true;
                }

                if (dragKind == DRAG_MOVE && selected != null) {
                    float[] d = screenDeltaToImg(x - downScreenX, y - downScreenY);
                    selected.x1 = origX1 + d[0];
                    selected.y1 = origY1 + d[1];
                    selected.x2 = origX2 + d[0];
                    selected.y2 = origY2 + d[1];
                    if (selected.type == Project.Ann.TYPE_ANGLE) {
                        selected.x3 = origX3 + d[0];
                        selected.y3 = origY3 + d[1];
                    }
                    invalidate();
                    return true;
                }
                if ((dragKind == DRAG_EP1 || dragKind == DRAG_EP2 || dragKind == DRAG_EP3)
                        && selected != null) {
                    float[] p = new float[]{x, y};
                    screenToImg(p);
                    if (dragKind == DRAG_EP1) {
                        if (selected.type == Project.Ann.TYPE_CIRCLE) {
                            // 拖圆心：整体平移，半径不变
                            float cdx = p[0] - selected.x1, cdy = p[1] - selected.y1;
                            selected.x1 = p[0];
                            selected.y1 = p[1];
                            selected.x2 += cdx;
                            selected.y2 += cdy;
                        } else {
                            selected.x1 = p[0];
                            selected.y1 = p[1];
                        }
                    } else if (dragKind == DRAG_EP2) {
                        selected.x2 = p[0];
                        selected.y2 = p[1];
                    } else {
                        selected.x3 = p[0];
                        selected.y3 = p[1];
                    }
                    selected.manual = false;
                    recalcValue(selected);
                    invalidate();
                    return true;
                }

                if (preview != null) {
                    float[] p = new float[]{x, y};
                    screenToImg(p);
                    preview.x2 = p[0];
                    preview.y2 = p[1];
                    invalidate();
                }
                lastX = x;
                lastY = y;
                return true;
            }

            case MotionEvent.ACTION_UP: {
                if (mode == MODE_PAN) {
                    dragKind = DRAG_NONE;
                    return true;
                }

                if (dragKind != DRAG_NONE && selected != null) {
                    int kind = dragKind;
                    dragKind = DRAG_NONE;
                    if (moving) {
                        if (listener != null) listener.onAnnChanged(selected);
                    } else if (kind == DRAG_MOVE && hitWasSelected) {
                        // 已选中的标注再次轻点 -> 请求编辑

                        if (listener != null) listener.onAnnClick(selected);
                    } else {

                    }
                    invalidate();
                    return true;
                }

                if (preview != null) {
                    Project.Ann pv = preview;
                    preview = null;
                    float ddx = x - downScreenX, ddy = y - downScreenY;
                    float dist = (float) Math.sqrt(ddx * ddx + ddy * ddy);
                    if (dist > MIN_LINE_LEN && listener != null) {
                        if (mode == MODE_SCALE) {
                            listener.onRequestScale(pv.x1, pv.y1, pv.x2, pv.y2);
                        } else if (mode == MODE_RECT) {
                            listener.onRequestRect(pv.x1, pv.y1, pv.x2, pv.y2);
                        } else if (mode == MODE_CIRCLE) {
                            listener.onRequestCircle(pv.x1, pv.y1, pv.x2, pv.y2);
                        } else {
                            listener.onRequestLine(pv.x1, pv.y1, pv.x2, pv.y2);
                        }
                    }
                    invalidate();
                    return true;
                }

                if (mode == MODE_ANGLE) {
                    float[] p = new float[]{x, y};
                    screenToImg(p);
                    if (angStep == 0) {
                        angVx = p[0];
                        angVy = p[1];
                        angStep = 1;
                        invalidate();
                        return true;
                    } else if (angStep == 1) {
                        angAx = p[0];
                        angAy = p[1];
                        angStep = 2;
                        invalidate();
                        return true;
                    } else {
                        float bx = p[0], by = p[1];
                        angStep = 0;
                        if (listener != null) {
                            listener.onRequestAngle(angAx, angAy, angVx, angVy, bx, by);
                        }
                        invalidate();
                        return true;
                    }
                }

                if (mode == MODE_TEXT) {
                    float[] p = new float[]{x, y};
                    screenToImg(p);
                    if (listener != null) listener.onRequestText(p[0], p[1]);
                    return true;
                }
                return true;
            }

            case MotionEvent.ACTION_CANCEL: {
                dragKind = DRAG_NONE;
                preview = null;
                invalidate();
                return true;
            }
        }
        return true;
    }
}
