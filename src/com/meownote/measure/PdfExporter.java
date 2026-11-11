package com.meownote.measure;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.pdf.PdfDocument;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * PDF 导出：A4 竖版，每页一张图（等比缩放居中）+ 图片下方列出该图所有标注。
 * 标注坐标以图片像素存储，这里按页面里的实际绘制矩阵映射。
 */
public class PdfExporter {

    private static final int PAGE_W = 595;   // A4 @72dpi
    private static final int PAGE_H = 842;
    private static final int MARGIN = 28;

    public static class Result {
        public File file;
        public int pages;
        public String error;
    }

    public static Result export(Context ctx, Project p, List<Project.Item> targets, String filenamePrefix) {
        Result r = new Result();
        PdfDocument doc = new PdfDocument();
        Bitmap full = null;
        try {
            int pageNo = 1;
            for (Project.Item it : targets) {
                File imgFile = Store.imageFile(ctx, p, it);
                Bitmap raw = Store.decodeFull(imgFile);
                if (raw == null) continue;
                full = raw;

                PdfDocument.PageInfo info = new PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNo).create();
                PdfDocument.Page page = doc.startPage(info);
                Canvas c = page.getCanvas();
                c.drawColor(Color.WHITE);

                // 标题
                Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG);
                tp.setColor(0xFF1A1A1A);
                tp.setTextSize(11f);
                tp.setFakeBoldText(true);
                SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US);
                String title = p.name + "   " + sdf.format(new Date(it.createdAt));
                c.drawText(title, MARGIN, MARGIN + 4, tp);

                // 图片区域
                float areaTop = MARGIN + 16;
                float areaBottom = PAGE_H - MARGIN - footerHeight(it);
                float areaW = PAGE_W - MARGIN * 2f;
                float areaH = areaBottom - areaTop;

                float bw = raw.getWidth(), bh = raw.getHeight();
                float s = Math.min(areaW / bw, areaH / bh);
                float dw = bw * s, dh = bh * s;
                float dx = MARGIN + (areaW - dw) / 2f;
                float dy = areaTop + (areaH - dh) / 2f;

                Matrix m = new Matrix();
                m.postScale(s, s);
                m.postTranslate(dx, dy);
                c.drawBitmap(raw, m, new Paint(Paint.FILTER_BITMAP_FLAG));

                // 标注
                drawAnns(c, it, m, s);

                // 底部标注清单
                drawFooter(c, it, areaBottom + 14);

                doc.finishPage(page);
                raw.recycle();
                pageNo++;
            }

            if (pageNo == 1) {
                r.error = "没有可导出的图片";
                return r;
            }

            String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            // 先写到缓存，导出完成后由 Store.savePdf 转入公共 Download 目录
            File out = new File(ctx.getCacheDir(), safe(filenamePrefix) + "_" + stamp + ".pdf");
            FileOutputStream fos = new FileOutputStream(out);
            try {
                doc.writeTo(fos);
                fos.flush();
            } finally {
                try { fos.close(); } catch (Exception ignored) { }
            }
            r.file = out;
            r.pages = pageNo - 1;
            return r;
        } catch (Exception e) {
            r.error = e.getClass().getSimpleName() + ": " + e.getMessage();
            return r;
        } finally {
            try { doc.close(); } catch (Exception ignored) { }
        }
    }

    private static float footerHeight(Project.Item it) {
        int n = it.anns.size();
        if (n == 0) return 18;
        return 18 + Math.min(n, 8) * 13;
    }

    private static void drawAnns(Canvas c, Project.Item it, Matrix m, float scale) {
        float density = 1f;
        Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeWidth(1.6f);

        Paint txt = new Paint(Paint.ANTI_ALIAS_FLAG);
        txt.setTextSize(9f);
        txt.setFakeBoldText(true);

        Paint bub = new Paint(Paint.ANTI_ALIAS_FLAG);
        bub.setStyle(Paint.Style.FILL);
        bub.setColor(0xF2FFFFFF);

        Paint bubS = new Paint(Paint.ANTI_ALIAS_FLAG);
        bubS.setStyle(Paint.Style.STROKE);
        bubS.setStrokeWidth(0.9f);

        for (Project.Ann a : it.anns) {
            line.setColor(a.color);
            int fg = AnnotationView.readableOnWhite(a.color);
            txt.setColor(fg);
            bubS.setColor(fg);

            // 与屏幕端保持一致：线宽按 dp->pt 折算，虚线用 DashPathEffect
            float w = a.strokeW > 0 ? a.strokeW : 2.2f;
            line.setStrokeWidth(Math.max(0.8f, w * 0.62f));
            if (a.dashed) {
                line.setPathEffect(new android.graphics.DashPathEffect(
                        new float[]{4.2f, 3f}, 0));
            } else {
                line.setPathEffect(null);
            }

            if (a.type == Project.Ann.TYPE_LINE) {
                float[] p1 = new float[]{a.x1, a.y1};
                float[] p2 = new float[]{a.x2, a.y2};
                m.mapPoints(p1);
                m.mapPoints(p2);
                c.drawLine(p1[0], p1[1], p2[0], p2[1], line);
                drawTick(c, p1, p2, line);
                bubble(c, a.displayText(), (p1[0] + p2[0]) / 2f, (p1[1] + p2[1]) / 2f,
                        txt, bub, bubS);
            } else {
                float[] pt = new float[]{a.x1, a.y1};
                m.mapPoints(pt);
                float rrr = 4f;
                c.drawLine(pt[0] - rrr, pt[1] - rrr, pt[0] + rrr, pt[1] + rrr, line);
                c.drawLine(pt[0] - rrr, pt[1] + rrr, pt[0] + rrr, pt[1] - rrr, line);
                bubble(c, a.displayText(), pt[0], pt[1], txt, bub, bubS);
            }
        }
    }

    private static void drawTick(Canvas c, float[] p1, float[] p2, Paint line) {
        android.graphics.PathEffect old = line.getPathEffect();
        line.setPathEffect(null);
        float dx = p2[0] - p1[0], dy = p2[1] - p1[1];
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 0.5f) return;
        float nx = -dy / len, ny = dx / len;
        float t = 4f;
        c.drawLine(p1[0] - nx * t, p1[1] - ny * t, p1[0] + nx * t, p1[1] + ny * t, line);
        c.drawLine(p2[0] - nx * t, p2[1] - ny * t, p2[0] + nx * t, p2[1] + ny * t, line);
        line.setPathEffect(old);
    }

    private static void bubble(Canvas c, String t, float cx, float cy,
                               Paint txt, Paint bub, Paint bubS) {
        if (t == null || t.length() == 0) return;
        float pad = 3f;
        Paint.FontMetrics fm = txt.getFontMetrics();
        float tw = txt.measureText(t);
        float th = fm.descent - fm.ascent;
        float bw = tw + pad * 2, bh = th + pad;
        float bx = cx - bw / 2f, by = cy - bh - 7f;
        RectF rect = new RectF(bx, by, bx + bw, by + bh);
        c.drawRoundRect(rect, 2.5f, 2.5f, bub);
        c.drawRoundRect(rect, 2.5f, 2.5f, bubS);
        c.drawText(t, bx + pad, by + pad * 0.5f - fm.ascent, txt);
        Paint lt = new Paint(bubS);
        lt.setStrokeWidth(0.9f);
        c.drawLine(cx, by + bh, cx, cy, lt);
    }

    private static void drawFooter(Canvas c, Project.Item it, float top) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0xFF444444);
        p.setTextSize(9.5f);
        float y = top;
        if (it.anns.isEmpty()) {
            p.setColor(0xFF999999);
            c.drawText("（无标注）", MARGIN, y, p);
            return;
        }
        p.setFakeBoldText(true);
        c.drawText("标注清单", MARGIN, y, p);
        p.setFakeBoldText(false);
        y += 12;
        int shown = 0;
        for (Project.Ann a : it.anns) {
            if (shown >= 8) {
                c.drawText("… 共 " + it.anns.size() + " 条", MARGIN + 12, y, p);
                break;
            }
            String tag = a.type == Project.Ann.TYPE_LINE ? "尺寸" : "注释";
            c.drawText("• [" + tag + "] " + a.displayText(), MARGIN + 12, y, p);
            y += 13;
            shown++;
        }
    }

    private static String safe(String s) {
        if (s == null || s.trim().length() == 0) return "measure";
        return s.replaceAll("[\\\\/:*?\"<>|\\s]+", "_");
    }
}
