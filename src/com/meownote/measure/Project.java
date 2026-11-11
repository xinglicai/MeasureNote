package com.meownote.measure;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 项目 = 一组照片 + 每张照片上的标注。
 * 标注坐标统一存「图片原始像素坐标」，这样任何缩放/PDF 导出都能一致还原。
 */
public class Project {

    public String id;
    public String name;
    public long createdAt;
    public String unit = "mm";
    public List<Item> items = new ArrayList<>();

    public Project() {
        this.id = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        this.createdAt = System.currentTimeMillis();
    }

    public static class Item {
        public String id;
        public String imageFile;
        public long createdAt;
        public List<Ann> anns = new ArrayList<>();

        /* ---- 比例尺：一条已知实际长度的参考线，用于自动换算其它线 ---- */
        public float calX1, calY1, calX2, calY2;
        public float calValue;
        public String calUnit = "";

        public boolean hasScale() {
            if (calValue <= 0) return false;
            if (calUnit == null || calUnit.length() == 0) return false;
            return calPx() > 2f;
        }

        /** 参考线在图片上的像素长度 */
        public float calPx() {
            float dx = calX2 - calX1, dy = calY2 - calY1;
            return (float) Math.sqrt(dx * dx + dy * dy);
        }

        public String calText() {
            return "基准 " + UnitUtil.format(calValue, calUnit);
        }

        public Item() {
            this.id = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
            this.createdAt = System.currentTimeMillis();
        }
    }

    public static class Ann {
        public static final int TYPE_LINE = 0;
        public static final int TYPE_TEXT = 1;
        public static final int TYPE_RECT = 2;    // 矩形：宽 × 高 + 面积
        public static final int TYPE_CIRCLE = 3;  // 圆：直径/半径 + 面积
        public static final int TYPE_ANGLE = 4;   // 角度：三点成角

        public int type = TYPE_LINE;
        public float x1, y1, x2, y2;
        /** 角度用的第三个点（顶点在 x2,y2） */
        public float x3, y3;
        public String text = "";
        public int color = 0xFFFF3B30;
        /** LINE 类型的数值与单位（text 由二者生成，便于切换单位时换算） */
        public float value;
        public String unit = "";

        /** 线宽（dp），默认 2.2 */
        public float strokeW = 2.2f;
        /** 是否虚线 */
        public boolean dashed = false;

        /** 第二维度：矩形=高，圆=半径（与 value 同单位） */
        public float value2;

        /**
         * true = 数值被用户手动指定过（图形测得值与实际不符时用），
         * 此时拖动控制点不会覆盖它。
         */
        public boolean manual;

        /** 按三个点几何计算角度（度） */
        public float angleFromPoints() {
            if (type != TYPE_ANGLE) return 0f;
            double d1 = Math.toDegrees(Math.atan2(y1 - y2, x1 - x2));
            double d2 = Math.toDegrees(Math.atan2(y3 - y2, x3 - x2));
            double deg = Math.abs(d2 - d1);
            if (deg > 180) deg = 360 - deg;
            return (float) deg;
        }

        /** 该标注是否有面积概念 */
        public boolean hasArea() {
            return type == TYPE_RECT || type == TYPE_CIRCLE;
        }

        /** 面积（单位²）；矩形=宽×高，圆=πr² */
        public float area() {
            if (type == TYPE_RECT) return value * value2;
            if (type == TYPE_CIRCLE) return (float) (Math.PI * value * value);
            return 0f;
        }

        /** 圆周/周长（矩形=周长，圆=周长） */
        public float perimeter() {
            if (type == TYPE_RECT) return 2 * (value + value2);
            if (type == TYPE_CIRCLE) return (float) (2 * Math.PI * value);
            return 0f;
        }

        public String displayText() {
            if (type == TYPE_TEXT) return text == null ? "" : text;
            String u = (unit == null || unit.length() == 0) ? "" : (" " + unit);
            if (type == TYPE_ANGLE) {
                return String.format(java.util.Locale.US, "%.1f\u00b0", value);
            }
            if (type == TYPE_RECT) {
                return fmt(value) + " × " + fmt(value2) + u;
            }
            if (type == TYPE_CIRCLE) {
                return "\u00d8" + fmt(value * 2) + u;
            }
            if (u.length() == 0) return "";
            return fmt(value) + u;
        }

        private static String fmt(float v) {
            if (v == Math.rint(v)) return String.valueOf((long) v);
            return String.format(java.util.Locale.US, "%.2f", v);
        }

        /** 副标题：矩形/圆显示面积，角度显示两条边长，其余为空 */
        public String subText() {
            if (type == TYPE_RECT) {
                String u = (unit == null || unit.length() == 0) ? "" : unit;
                return String.format(java.util.Locale.US, "%.2f %s\u00b2", area(), u);
            }
            if (type == TYPE_CIRCLE) {
                String u = (unit == null || unit.length() == 0) ? "" : unit;
                return String.format(java.util.Locale.US, "%.2f %s\u00b2", area(), u);
            }
            // 角度的度数已由 displayText 给出，副标题留空避免重复
            return "";
        }

        public Ann() {
        }

        public static Ann line(float x1, float y1, float x2, float y2, String text, int color) {
            Ann a = new Ann();
            a.type = TYPE_LINE;
            a.x1 = x1;
            a.y1 = y1;
            a.x2 = x2;
            a.y2 = y2;
            a.text = text;
            a.color = color;
            return a;
        }

        public static Ann rect(float x1, float y1, float x2, float y2, int color) {
            Ann a = new Ann();
            a.type = TYPE_RECT;
            a.x1 = x1; a.y1 = y1; a.x2 = x2; a.y2 = y2;
            a.color = color;
            return a;
        }

        public static Ann circle(float cx, float cy, float rx, float ry, int color) {
            Ann a = new Ann();
            a.type = TYPE_CIRCLE;
            a.x1 = cx; a.y1 = cy; a.x2 = rx; a.y2 = ry;
            a.color = color;
            return a;
        }

        public static Ann angle(float ax, float ay, float vx, float vy, float bx, float by, int color) {
            Ann a = new Ann();
            a.type = TYPE_ANGLE;
            a.x1 = ax; a.y1 = ay;
            a.x2 = vx; a.y2 = vy;
            a.x3 = bx; a.y3 = by;
            a.color = color;
            return a;
        }

        public static Ann note(float x, float y, String text, int color) {
            Ann a = new Ann();
            a.type = TYPE_TEXT;
            a.x1 = x;
            a.y1 = y;
            a.x2 = x;
            a.y2 = y;
            a.text = text;
            a.color = color;
            return a;
        }
    }

    /* ---------------- JSON ---------------- */

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("name", name);
        o.put("createdAt", createdAt);
        o.put("unit", unit);
        JSONArray arr = new JSONArray();
        for (Item it : items) arr.put(itemToJson(it));
        o.put("items", arr);
        return o;
    }

    private static JSONObject itemToJson(Item it) throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", it.id);
        o.put("imageFile", it.imageFile);
        o.put("createdAt", it.createdAt);
        o.put("calX1", (double) it.calX1);
        o.put("calY1", (double) it.calY1);
        o.put("calX2", (double) it.calX2);
        o.put("calY2", (double) it.calY2);
        o.put("calValue", (double) it.calValue);
        o.put("calUnit", it.calUnit == null ? "" : it.calUnit);
        JSONArray arr = new JSONArray();
        for (Ann a : it.anns) {
            JSONObject ja = new JSONObject();
            ja.put("type", a.type);
            ja.put("x1", (double) a.x1);
            ja.put("y1", (double) a.y1);
            ja.put("x2", (double) a.x2);
            ja.put("y2", (double) a.y2);
            ja.put("text", a.text == null ? "" : a.text);
            ja.put("color", a.color);
            ja.put("value", (double) a.value);
            ja.put("unit", a.unit == null ? "" : a.unit);
            ja.put("strokeW", (double) a.strokeW);
            ja.put("dashed", a.dashed);
            ja.put("x3", (double) a.x3);
            ja.put("y3", (double) a.y3);
            ja.put("value2", (double) a.value2);
            ja.put("manual", a.manual);
            arr.put(ja);
        }
        o.put("anns", arr);
        return o;
    }

    /** 标注列表 -> JSON 字符串（撤销/重做快照用） */
    public static String annsToJson(List<Ann> list) {
        JSONArray arr = new JSONArray();
        for (Ann a : list) {
            JSONObject ja = new JSONObject();
            try {
                ja.put("type", a.type);
                ja.put("x1", (double) a.x1);
                ja.put("y1", (double) a.y1);
                ja.put("x2", (double) a.x2);
                ja.put("y2", (double) a.y2);
                ja.put("text", a.text == null ? "" : a.text);
                ja.put("color", a.color);
                ja.put("value", (double) a.value);
                ja.put("unit", a.unit == null ? "" : a.unit);
                ja.put("strokeW", (double) a.strokeW);
                ja.put("dashed", a.dashed);
                ja.put("x3", (double) a.x3);
                ja.put("y3", (double) a.y3);
                ja.put("value2", (double) a.value2);
            ja.put("manual", a.manual);
            } catch (JSONException ignored) { }
            arr.put(ja);
        }
        return arr.toString();
    }

    public static List<Ann> annsFromJson(String s) {
        List<Ann> out = new ArrayList<>();
        if (s == null || s.length() == 0) return out;
        try {
            JSONArray arr = new JSONArray(s);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject ja = arr.getJSONObject(i);
                Ann a = new Ann();
                a.type = ja.optInt("type", Ann.TYPE_LINE);
                a.x1 = (float) ja.optDouble("x1", 0);
                a.y1 = (float) ja.optDouble("y1", 0);
                a.x2 = (float) ja.optDouble("x2", 0);
                a.y2 = (float) ja.optDouble("y2", 0);
                a.text = ja.optString("text", "");
                a.color = ja.optInt("color", 0xFFFF3B30);
                a.value = (float) ja.optDouble("value", 0);
                a.unit = ja.optString("unit", "");
                a.strokeW = (float) ja.optDouble("strokeW", 2.2);
                a.dashed = ja.optBoolean("dashed", false);
                a.x3 = (float) ja.optDouble("x3", 0);
                a.y3 = (float) ja.optDouble("y3", 0);
                a.value2 = (float) ja.optDouble("value2", 0);
                a.manual = ja.optBoolean("manual", false);
                out.add(a);
            }
        } catch (Exception ignored) { }
        return out;
    }

    public static Project fromJson(JSONObject o) throws JSONException {
        Project p = new Project();
        p.id = o.optString("id", p.id);
        p.name = o.optString("name", "未命名");
        p.createdAt = o.optLong("createdAt", System.currentTimeMillis());
        p.unit = o.optString("unit", "mm");
        p.items.clear();
        JSONArray arr = o.optJSONArray("items");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject ji = arr.getJSONObject(i);
                Item it = new Item();
                it.id = ji.optString("id", it.id);
                it.imageFile = ji.optString("imageFile", "");
                it.createdAt = ji.optLong("createdAt", System.currentTimeMillis());
                it.calX1 = (float) ji.optDouble("calX1", 0);
                it.calY1 = (float) ji.optDouble("calY1", 0);
                it.calX2 = (float) ji.optDouble("calX2", 0);
                it.calY2 = (float) ji.optDouble("calY2", 0);
                it.calValue = (float) ji.optDouble("calValue", 0);
                it.calUnit = ji.optString("calUnit", "");
                JSONArray aa = ji.optJSONArray("anns");
                if (aa != null) {
                    for (int k = 0; k < aa.length(); k++) {
                        JSONObject ja = aa.getJSONObject(k);
                        Ann a = new Ann();
                        a.type = ja.optInt("type", Ann.TYPE_LINE);
                        a.x1 = (float) ja.optDouble("x1", 0);
                        a.y1 = (float) ja.optDouble("y1", 0);
                        a.x2 = (float) ja.optDouble("x2", 0);
                        a.y2 = (float) ja.optDouble("y2", 0);
                        a.text = ja.optString("text", "");
                        a.color = ja.optInt("color", 0xFFFF3B30);
                        a.value = (float) ja.optDouble("value", 0);
                        a.unit = ja.optString("unit", "");
                        a.strokeW = (float) ja.optDouble("strokeW", 2.2);
                        a.dashed = ja.optBoolean("dashed", false);
                        a.x3 = (float) ja.optDouble("x3", 0);
                        a.y3 = (float) ja.optDouble("y3", 0);
                        a.value2 = (float) ja.optDouble("value2", 0);
                        a.manual = ja.optBoolean("manual", false);
                        it.anns.add(a);
                    }
                }
                p.items.add(it);
            }
        }
        return p;
    }
}
