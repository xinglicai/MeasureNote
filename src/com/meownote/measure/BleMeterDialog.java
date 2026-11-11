package com.meownote.measure;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/**
 * 蓝牙测距仪对话框：自动扫描 → 选设备连接 → 反复测距。
 *
 * 设计原则：单对话框搞定全流程，不额外开 Activity，符合诺瓦标记的轻量风格。
 */
public class BleMeterDialog {

    public interface OnMeasure {
        /** 用户确认使用某次测量值（单位：米） */
        void onUseMeasure(float meters);
    }

    private final Activity act;
    private final BleMeter meter;
    private final OnMeasure cb;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private AlertDialog dlg;
    private LinearLayout listBox;
    private TextView status;
    private TextView resultView;
    private Button measureBtn;
    private Button handshakeAgain;

    private float lastMeters = -1f;
    private boolean connected = false;

    public BleMeterDialog(Activity a, OnMeasure cb) {
        this.act = a;
        this.cb = cb;
        this.meter = new BleMeter(a);
    }

    public void show() {
        if (!meter.isBluetoothOn()) {
            Toast.makeText(act, "请先打开手机蓝牙", Toast.LENGTH_LONG).show();
            return;
        }
        if (!BleMeter.hasPermissions(act)) {
            BleMeter.requestPermissions(act, 9001);
            Toast.makeText(act, "正在申请蓝牙权限，授权后请再点一次", Toast.LENGTH_LONG).show();
            return;
        }

        // ---- 构建 UI ----
        LinearLayout root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * act.getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, pad);

        status = new TextView(act);
        status.setText("正在搜索测距仪…");
        status.setTextSize(13);
        status.setTextColor(0xFF666666);
        status.setPadding(0, 0, 0, pad / 2);
        root.addView(status);

        resultView = new TextView(act);
        resultView.setTextSize(26);
        resultView.setTextColor(0xFF222222);
        resultView.setGravity(Gravity.CENTER);
        resultView.setPadding(0, pad / 2, 0, pad / 2);
        resultView.setText("—");
        resultView.setVisibility(View.GONE);
        root.addView(resultView);

        LinearLayout btnRow = new LinearLayout(act);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);

        measureBtn = new Button(act);
        measureBtn.setText("测 距");
        measureBtn.setEnabled(false);
        measureBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                meter.measure();
                resultView.setText("测量中…");
            }
        });
        btnRow.addView(measureBtn, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        handshakeAgain = new Button(act);
        handshakeAgain.setText("重连");
        handshakeAgain.setEnabled(false);
        handshakeAgain.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                connected = false;
                measureBtn.setEnabled(false);
                handshakeAgain.setEnabled(false);
                status.setText("正在重新搜索…");
                resultView.setVisibility(View.GONE);
                resultView.setText("—");
                meter.disconnect();
                meter.startScan();
            }
        });
        btnRow.addView(handshakeAgain, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(btnRow);

        ScrollView sv = new ScrollView(act);
        listBox = new LinearLayout(act);
        listBox.setOrientation(LinearLayout.VERTICAL);
        sv.addView(listBox);
        root.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int) (220 * act.getResources().getDisplayMetrics().density)));

        dlg = new AlertDialog.Builder(act)
                .setTitle("蓝牙激光测距仪")
                .setView(root)
                .setNegativeButton("关闭", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { dismiss(); }
                })
                .setPositiveButton("使用此值", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        if (lastMeters > 0 && cb != null) cb.onUseMeasure(lastMeters);
                        dismiss();
                    }
                })
                .create();
        dlg.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override public void onShow(DialogInterface d) {
                // 未测到值时禁用"使用此值"
                dlg.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
            }
        });
        dlg.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override public void onDismiss(DialogInterface d) { meter.release(); }
        });
        dlg.show();

        // ---- 挂监听并开扫 ----
        meter.setListener(new BleMeter.Listener() {
            @Override public void onDeviceFound(final String name, final String address, final int rssi) {
                ui.post(new Runnable() {
                    @Override public void run() { addDeviceRow(name, address, rssi); }
                });
            }

            @Override public void onScanFinished(List<BleMeter.DeviceInfo> found) {
                ui.post(new Runnable() {
                    @Override public void run() {
                        if (connected) return;
                        rebuildList();
                        int n = deviceList.size();
                        if (n == 0) {
                            status.setText("没找到任何蓝牙设备。请确认：\n①测距仪已开机\n②按一下测距仪上的蓝牙键唤醒\n③手机蓝牙已开");
                            return;
                        }
                        // 自动识别：按信号强度排序，依次连接验证是否带博世服务
                        java.util.List<BleMeter.DeviceInfo> sorted =
                                new java.util.ArrayList<BleMeter.DeviceInfo>(deviceList);
                        java.util.Collections.sort(sorted, new java.util.Comparator<BleMeter.DeviceInfo>() {
                            @Override public int compare(BleMeter.DeviceInfo a, BleMeter.DeviceInfo b) {
                                return b.rssi - a.rssi;
                            }
                        });
                        java.util.List<String> candidates = new java.util.ArrayList<String>();
                        for (BleMeter.DeviceInfo d : sorted) candidates.add(d.address);

                        status.setText("正在自动识别测距仪（依次尝试信号最强的 "
                                + Math.min(4, candidates.size()) + " 个）…");
                        meter.startProbe(candidates);
                    }
                });
            }

            @Override public void onConnected(String deviceName) {
                ui.post(new Runnable() {
                    @Override public void run() {
                        connected = true;
                        status.setText("✓ 已自动识别并连接：" + deviceName + "\n点「测 距」开始测量");
                        measureBtn.setEnabled(true);
                        handshakeAgain.setEnabled(true);
                        resultView.setVisibility(View.VISIBLE);
                        listBox.removeAllViews();
                    }
                });
            }

            @Override public void onMeasured(float meters) {
                lastMeters = meters;
                ui.post(new Runnable() {
                    @Override public void run() {
                        resultView.setText(String.format("%.0f mm", lastMeters * 1000));
                        if (dlg != null && dlg.isShowing()) {
                            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                        }
                    }
                });
            }

            @Override public void onError(final String msg) {
                ui.post(new Runnable() {
                    @Override public void run() {
                        status.setText(msg);
                        if (!connected) measureBtn.setEnabled(false);
                    }
                });
            }

            @Override public void onNotMeter(String address) {
                ui.post(new Runnable() {
                    @Override public void run() {
                        if (!connected) status.setText("继续自动识别…");
                    }
                });
            }

            @Override public void onDisconnected() {
                ui.post(new Runnable() {
                    @Override public void run() {
                        if (connected) {
                            connected = false;
                            measureBtn.setEnabled(false);
                            handshakeAgain.setEnabled(false);
                            status.setText("连接已断开（测距仪休眠了？按一下它的蓝牙键再点重连）");
                        }
                    }
                });
            }
        });
        meter.startScan();
    }

    /** 已发现的设备（用于排序重绘） */
    private final java.util.List<BleMeter.DeviceInfo> deviceList =
            new java.util.ArrayList<BleMeter.DeviceInfo>();

    private void addDeviceRow(String name, final String address, int rssi) {
        if (listBox == null) return;
        // 去重
        for (BleMeter.DeviceInfo d : deviceList) {
            if (d.address.equalsIgnoreCase(address)) return;
        }
        BleMeter.DeviceInfo di = new BleMeter.DeviceInfo(name, address, rssi);
        deviceList.add(di);
        // 扫描中只追加，绝不重排 —— 否则列表跳动会让用户点错设备
        listBox.addView(makeRow(di));
        status.setText("搜索中… 已发现 " + deviceList.size() + " 个设备");
    }

    /** 疑似测距仪：广播带博世服务 UUID，或名字像 */
    private static boolean looksLikeMeter(BleMeter.DeviceInfo d) {
        if (d.isBosch) return true;
        String n = d.name;
        if (n == null) return false;
        String s = n.toLowerCase();
        return s.contains("bosch") || s.contains("glm") || s.contains("laser")
                || s.contains("range");
    }

    /** 重绘：疑似测距仪优先，然后按 RSSI 从强到弱 */
    private void rebuildList() {
        if (listBox == null) return;

        java.util.List<BleMeter.DeviceInfo> sorted =
                new java.util.ArrayList<BleMeter.DeviceInfo>(deviceList);
        java.util.Collections.sort(sorted, new java.util.Comparator<BleMeter.DeviceInfo>() {
            @Override public int compare(BleMeter.DeviceInfo a, BleMeter.DeviceInfo b) {
                boolean ma = looksLikeMeter(a), mb = looksLikeMeter(b);
                if (ma != mb) return ma ? -1 : 1;
                return b.rssi - a.rssi;
            }
        });

        listBox.removeAllViews();
        for (BleMeter.DeviceInfo di : sorted) {
            listBox.addView(makeRow(di));
        }
        status.setText("自动识别未完成，可手动选择（★ 为疑似测距仪）");
    }

    private View makeRow(final BleMeter.DeviceInfo di) {
        String nm = (di.name == null || di.name.isEmpty()) ? "未知设备" : di.name;
        String label = nm + "\n" + di.address + "    " + di.rssi + " dBm";
        if (looksLikeMeter(di)) label = "★ " + label;

        TextView tv = new TextView(act);
        tv.setText(label);
        tv.setTextSize(13);
        tv.setPadding(24, 18, 24, 18);
        tv.setTag(di.address);
        tv.setTextColor(looksLikeMeter(di) ? 0xFF1565C0 : 0xFF333333);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(looksLikeMeter(di) ? 0xFFE8F0FE : 0xFFF5F6F8);
        bg.setCornerRadius(12);
        tv.setBackground(bg);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = 8;
        tv.setLayoutParams(lp);

        tv.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                status.setText("正在连接 " + di.address + " …");
                meter.connect(di.address);
            }
        });
        return tv;
    }

    public void dismiss() {
        if (dlg != null && dlg.isShowing()) dlg.dismiss();
    }
}
