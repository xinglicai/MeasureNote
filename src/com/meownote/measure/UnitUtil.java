package com.meownote.measure;

import java.util.Locale;

/** 单位定义与换算。内部统一以「米」为基准，px 单独处理。 */
public class UnitUtil {

    public static final String[] UNITS = {"mm", "cm", "m", "in", "ft", "px"};
    public static final String[] LABELS = {"毫米 mm", "厘米 cm", "米 m", "英寸 in", "英尺 ft", "像素 px"};

    private static final double[] TO_METER = {
            0.001,   // mm
            0.01,    // cm
            1.0,     // m
            0.0254,  // in
            0.3048,  // ft
            1.0      // px (不参与换算)
    };

    public static int indexOf(String unit) {
        if (unit == null) return 2;
        for (int i = 0; i < UNITS.length; i++) {
            if (UNITS[i].equalsIgnoreCase(unit)) return i;
        }
        return 2;
    }

    public static String labelOf(String unit) {
        int i = indexOf(unit);
        return LABELS[i];
    }

    /** 把 value(from) 换成 to 单位；px 与其它单位之间不换算（原样返回）。 */
    public static float convert(float value, String from, String to) {
        if (from == null || to == null) return value;
        if (from.equalsIgnoreCase(to)) return value;
        boolean pxFrom = "px".equalsIgnoreCase(from);
        boolean pxTo = "px".equalsIgnoreCase(to);
        if (pxFrom || pxTo) return value;   // px 与物理单位无换算关系
        int fi = indexOf(from), ti = indexOf(to);
        if (fi == ti) return value;
        double meters = value * TO_METER[fi];
        return (float) (meters / TO_METER[ti]);
    }

    public static String format(float value, String unit) {
        if (unit == null || unit.length() == 0) unit = "m";
        if (value == Math.rint(value)) {
            return String.valueOf((long) value) + " " + unit;
        }
        String fmt = Math.abs(value) >= 100 ? "%.0f" : (Math.abs(value) >= 1 ? "%.2f" : "%.3f");
        return String.format(Locale.US, fmt, value) + " " + unit;
    }

    /** 解析用户输入的数值，失败返回 NaN */
    public static float parse(String s) {
        if (s == null) return Float.NaN;
        s = s.trim().replace(',', '.');
        if (s.length() == 0) return Float.NaN;
        try {
            return Float.parseFloat(s);
        } catch (Exception e) {
            return Float.NaN;
        }
    }
}
