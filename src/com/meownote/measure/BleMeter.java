package com.meownote.measure;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * 博世 GLM 激光测距仪蓝牙驱动（XTP / MT Protocol over BLE）。
 *
 * 协议（逆向自 MeasureOn v3.0.0 + GLM 50-27 CG 实测）：
 *   帧格式：[MODE][CMD][LEN][DATA(LEN 字节)][CRC8]
 *   MODE   = 0xC0（LONG_REQ_LONG_RESP）
 *   CRC8   = init 0xAA / poly 0xA6，覆盖 MODE..DATA（不含 CRC 本身）
 *
 * 命令：
 *   0x55 + {0x01,0x00}  握手
 *   0x56 + {0x00}       触发测量
 *   0x55 的响应 LEN=16，DATA[0]=0x06 为测量结果，DATA[4..7] = float32 LE 米
 *
 * 依赖：零第三方库，纯 Android Framework（与诺瓦标记一致）。
 */
public class BleMeter {

    private static final String TAG = "BleMeter";

    // ---- GATT ----
    public static final UUID SVC_UUID = UUID.fromString("02A6C0D0-0451-4000-B000-FB3210111989");
    public static final UUID CHR_UUID = UUID.fromString("02A6C0D1-0451-4000-B000-FB3210111989");
    private static final UUID CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    // ---- 协议 ----
    private static final byte MODE_LONG = (byte) 0xC0;
    private static final byte CMD_HANDSHAKE = 0x55;
    private static final byte CMD_MEASURE = 0x56;
    private static final int CRC_INIT = 0xAA;
    private static final int CRC_POLY = 0xA6;

    // ---- 超时 ----
    private static final long SCAN_TIMEOUT_MS = 15000;
    private static final long MEASURE_TIMEOUT_MS = 3000;
    private static final int MEASURE_RETRY = 2;

    /** 回调（都在主线程） */
    public interface Listener {
        /** 扫描到一个候选设备（可在此刷新列表） */
        void onDeviceFound(String name, String address, int rssi);
        /** 扫描结束（无论是否有结果） */
        void onScanFinished(List<DeviceInfo> found);
        /** 已连接并完成握手 */
        void onConnected(String deviceName);
        /** 收到一次测量结果（单位：米） */
        void onMeasured(float meters);
        /** 出错，msg 可直接展示给用户 */
        void onError(String msg);
        /** 连接断开 */
        void onDisconnected();
        /** 连上了但没有博世服务 —— 这是个普通蓝牙设备（自动探测用） */
        void onNotMeter(String address);
    }

    /** 扫描结果条目 */
    public static class DeviceInfo {
        public final String name;
        public final String address;
        public final int rssi;
        /** 广播里是否带博世 XTP 服务 UUID */
        public final boolean isBosch;
        public DeviceInfo(String n, String a, int r) { this(n, a, r, false); }
        public DeviceInfo(String n, String a, int r, boolean bosch) {
            name = n; address = a; rssi = r; isBosch = bosch;
        }
        public String display() {
            String n = (name == null || name.isEmpty()) ? "未知设备" : name;
            return n + "  (" + address + ")  " + rssi + "dBm";
        }
    }

    private final Context appCtx;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private Listener listener;

    private BluetoothAdapter adapter;
    private BluetoothLeScanner scanner;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic chr;

    private final List<DeviceInfo> found = new ArrayList<DeviceInfo>();
    private boolean scanning = false;
    private boolean ready = false;          // 握手完成
    private int measureRetry = 0;
    private int measureToken = 0;
    private String connectedName = "";
    private String connectingAddress = null;

    // ==================== 设备记忆 ====================
    private static final String PREFS = "ble_meter";
    private static final String K_MAC = "last_mac";
    private static final String K_NAME = "last_name";

    /** 上次连接的设备 MAC，没有返回 null */
    public String lastDeviceMac() {
        return appCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(K_MAC, null);
    }

    /** 上次连接的设备名 */
    public String lastDeviceName() {
        return appCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(K_NAME, "测距仪");
    }

    /** 记住这个设备，下次自动连 */
    public void rememberDevice(String mac, String name) {
        appCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(K_MAC, mac)
                .putString(K_NAME, name == null || name.isEmpty() ? "测距仪" : name)
                .apply();
        Log.i(TAG, "已记住设备 " + mac);
    }

    public void forgetDevice() {
        appCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply();
    }

    /** 是否已连接且可以测距 */
    public boolean isReady() { return ready && gatt != null; }

    public BleMeter(Context ctx) {
        appCtx = ctx.getApplicationContext();
    }

    public void setListener(Listener l) { listener = l; }

    // ==================== 权限 ====================

    /**
     * 当前系统需要的扫描/连接权限。
     *
     * 重要：即使 Android 12+ 已改用 BLUETOOTH_SCAN，**ACCESS_FINE_LOCATION 仍然必须给**。
     * 实测（博世 MeasureOn 与本 App 均验证）：缺位置权限时扫描能"启动"，
     * 但 ScanCallback 一条结果都收不到（dumpsys 里 LE scans 有计数、
     * Total number of results 恒为 0），表现为"扫不到任何设备"。
     */
    public static String[] requiredPermissions() {
        if (Build.VERSION.SDK_INT >= 31) {
            return new String[]{
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.ACCESS_FINE_LOCATION
            };
        }
        // Android 12 以下：BLE 扫描必须有位置权限（BLUETOOTH 在 manifest 里声明即可）
        return new String[]{
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
        };
    }

    public static boolean hasPermissions(Context ctx) {
        for (String p : requiredPermissions()) {
            if (ctx.checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) return false;
        }
        return true;
    }

    public static void requestPermissions(Activity a, int reqCode) {
        if (Build.VERSION.SDK_INT >= 23) {
            a.requestPermissions(requiredPermissions(), reqCode);
        }
    }

    // ==================== 扫描 ====================

    public boolean isBluetoothOn() {
        BluetoothManager bm = (BluetoothManager) appCtx.getSystemService(Context.BLUETOOTH_SERVICE);
        adapter = bm == null ? null : bm.getAdapter();
        return adapter != null && adapter.isEnabled();
    }

    /** 开始扫描博世测距仪 */
    public void startScan() {
        if (!isBluetoothOn()) {
            err("蓝牙未开启，请先打开蓝牙");
            return;
        }
        if (!hasPermissions(appCtx)) {
            err("缺少蓝牙权限");
            return;
        }
        if (scanning) return;

        found.clear();
        scanning = true;

        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) { err("设备不支持 BLE 扫描"); scanning = false; return; }

        // 注意：不能用 Service UUID 过滤！
        // 博世 GLM 的广播包里不带 02A6C0D0 这个完整服务 UUID（实测带过滤扫描
        // 结果是 0 条），必须扫全部 BLE 设备，连接时再靠服务发现验证身份。
        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();

        try {
            scanner.startScan((List<ScanFilter>) null, settings, scanCb);
        } catch (Exception e) {
            err("扫描启动失败：" + e.getMessage());
            scanning = false;
            return;
        }
        Log.i(TAG, "开始扫描");

        ui.postDelayed(new Runnable() {
            @Override public void run() { stopScan(); }
        }, SCAN_TIMEOUT_MS);
    }

    public void stopScan() {
        if (!scanning) return;
        scanning = false;
        try {
            if (scanner != null) scanner.stopScan(scanCb);
        } catch (Exception ignored) {}
        ui.post(new Runnable() {
            @Override public void run() {
                if (listener != null) {
                    listener.onScanFinished(new ArrayList<DeviceInfo>(found));
                }
            }
        });
    }

    public boolean isScanning() { return scanning; }

    private final ScanCallback scanCb = new ScanCallback() {
        @Override public void onScanResult(int type, ScanResult r) {
            BluetoothDevice d = r.getDevice();
            if (d == null) return;
            String addr = d.getAddress();
            String name = null;
            try { name = d.getName(); } catch (Exception ignored) {}
            if (name == null && r.getScanRecord() != null) {
                name = r.getScanRecord().getDeviceName();
            }
            // 打印广播原始字节，用于找出博世设备的可识别特征
            String rawAdv = "";
            int mfrId = -1;
            try {
                android.bluetooth.le.ScanRecord rec = r.getScanRecord();
                if (rec != null) {
                    byte[] bytes = rec.getBytes();
                    if (bytes != null) rawAdv = hex(bytes);
                    android.util.SparseArray<byte[]> mfr = rec.getManufacturerSpecificData();
                    if (mfr != null && mfr.size() > 0) {
                        mfrId = mfr.keyAt(0);
                    }
                }
            } catch (Exception ignored) {}
            Log.i(TAG, "ADV " + addr + " name=" + name + " rssi=" + r.getRssi()
                    + " mfrId=" + mfrId
                    + (mfrId >= 0 ? " (0x" + Integer.toHexString(mfrId) + ")" : "")
                    + " hex=" + rawAdv);

            // 广播里带不带博世服务 UUID（带了就能确定是测距仪）
            boolean bosch = false;
            try {
                android.bluetooth.le.ScanRecord rec = r.getScanRecord();
                java.util.List<ParcelUuid> uuids = (rec == null) ? null : rec.getServiceUuids();
                if (uuids != null) {
                    for (ParcelUuid pu : uuids) {
                        if (SVC_UUID.equals(pu.getUuid())) {
                            bosch = true;
                            break;
                        }
                    }
                }
            } catch (Exception ignored) {}

            // 去重（已有的补一下 bosch 标记）
            for (DeviceInfo di : found) {
                if (di.address.equalsIgnoreCase(addr)) return;
            }
            // 博世可能在广播里带厂商自定义数据；如果抓到 mfrId 就一并记下
            final DeviceInfo info = new DeviceInfo(name, addr, r.getRssi(), bosch || (mfrId >= 0));
            found.add(info);
            final String fName = name;
            ui.post(new Runnable() {
                @Override public void run() {
                    if (listener != null) listener.onDeviceFound(fName, info.address, info.rssi);
                }
            });
        }

        @Override public void onScanFailed(int errorCode) {
            Log.e(TAG, "扫描失败 code=" + errorCode);
            scanning = false;
            if (errorCode == ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED) {
                err("扫描失败：系统蓝牙服务异常，请重开蓝牙");
            } else if (errorCode == ScanCallback.SCAN_FAILED_INTERNAL_ERROR) {
                err("扫描失败：内部错误");
            } else {
                err("扫描失败 code=" + errorCode);
            }
        }
    };

    // ==================== 连接 ====================

    /** 连接指定 MAC 的测距仪 */
    public void connect(String address) {
        if (!isBluetoothOn()) { err("蓝牙未开启"); return; }
        stopScan();
        disconnect(0);

        BluetoothDevice dev;
        try { dev = adapter.getRemoteDevice(address); }
        catch (Exception e) { err("无效地址"); return; }

        connectedName = "GLM";
        try {
            String n = dev.getName();
            if (n != null && !n.isEmpty()) connectedName = n;
        } catch (Exception ignored) {}
        // 连上就记住，下次自动连
        rememberDevice(address, connectedName);

        Log.i(TAG, "连接 " + address);
        // 防重复：同一 MAC 已在连就跳过，否则会产生多个 GATT 客户端互相冲突
        if (connectingAddress != null && connectingAddress.equalsIgnoreCase(address)) {
            Log.i(TAG, "已在连接中，忽略重复请求");
            return;
        }
        connectingAddress = address;

        if (Build.VERSION.SDK_INT >= 23) {
            gatt = dev.connectGatt(appCtx, false, gattCb, BluetoothDevice.TRANSPORT_LE);
        } else {
            gatt = dev.connectGatt(appCtx, false, gattCb);
        }
        if (gatt == null) {
            connectingAddress = null;
            err("连接失败");
        }
    }

    private final BluetoothGattCallback gattCb = new BluetoothGattCallback() {

        @Override public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.i(TAG, "已连接，发现服务");
                try { g.discoverServices(); } catch (Exception e) { err("服务发现失败"); }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.i(TAG, "断开 status=" + status);
                ready = false;
                connectingAddress = null;
                cccdAttempt = 0;
                ui.post(new Runnable() {
                    @Override public void run() {
                        if (listener != null) listener.onDisconnected();
                    }
                });
            }
        }

        @Override public void onServicesDiscovered(BluetoothGatt g, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) { err("服务发现失败 status=" + status); return; }
            BluetoothGattService svc = g.getService(SVC_UUID);
            if (svc == null) {
                // 不是测距仪：断开并通知（自动探测时继续试下一个）
                final String addr = connectingAddress;
                Log.i(TAG, "没有博世服务，不是测距仪: " + addr);
                disconnect();
                ui.post(new Runnable() {
                    @Override public void run() {
                        if (listener != null) listener.onNotMeter(addr);
                    }
                });
                advanceProbe();     // 立即试下一个，不干等超时
                return;
            }
            // 全量打印 GATT 树（诊断用）
            try {
                for (BluetoothGattService s2 : g.getServices()) {
                    Log.i(TAG, "SVC " + s2.getUuid());
                    for (BluetoothGattCharacteristic c2 : s2.getCharacteristics()) {
                        Log.i(TAG, "   CHR " + c2.getUuid() + " props=" + c2.getProperties());
                    }
                }
            } catch (Exception e) { Log.w(TAG, "打印 GATT 树失败", e); }

            chr = svc.getCharacteristic(CHR_UUID);
            if (chr == null) { err("未找到数据特征"); return; }
            Log.i(TAG, "目标特征 props=" + chr.getProperties());
            stopProbe();

            // 启用 notify。
            // 关键：必须延迟写 CCCD —— 紧跟在 onServicesDiscovered 里立刻写，
            // 会和系统的服务发现尾操作冲突，writeDescriptor 返回 status=128 (GATT_FAILURE)，
            // 导致 notify 实际没启用、收不到任何数据。
            try {
                g.setCharacteristicNotification(chr, true);
            } catch (Exception e) {
                Log.w(TAG, "setCharacteristicNotification 异常", e);
            }

            // 先请求 MTU（博世 App 也这么做），成功回调里再写 CCCD
            ui.postDelayed(new Runnable() {
                @Override public void run() {
                    try {
                        boolean ok = gatt.requestMtu(185);
                        Log.i(TAG, "requestMtu 提交=" + ok);
                        if (!ok) writeCccd(gatt);
                    } catch (Exception e) {
                        Log.w(TAG, "requestMtu 异常", e);
                        writeCccd(gatt);
                    }
                }
            }, 300);
        }

        @Override public void onMtuChanged(BluetoothGatt g, int mtu, int status) {
            Log.i(TAG, "MTU=" + mtu + " status=" + status);
            writeCccd(g);
        }

        @Override public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int status) {
            Log.i(TAG, "CCCD 写入 status=" + status);
            if (status == BluetoothGatt.GATT_SUCCESS) {
                sendHandshake();
            } else if (cccdAttempt == 0) {
                // notification 失败 -> 试 indication
                Log.w(TAG, "notification 失败，改试 indication");
                cccdAttempt = 1;
                writeCccd(g);
            } else {
                Log.w(TAG, "CCCD 两种方式都失败，仍然握手试试");
                sendHandshake();
            }
        }

        // Android 13+ 回调带 byte[]
        @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] value) {
            handleRx(value);
        }

        // Android 12 及以下
        @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
            byte[] v = null;
            try { v = c.getValue(); } catch (Exception ignored) {}
            handleRx(v);
        }
    };

    /**
     * 自动连接上次记住的设备。
     * @return true 表示有记录、已发起连接；false 表示没有记录
     */
    public boolean autoConnect() {
        String mac = lastDeviceMac();
        if (mac == null || mac.isEmpty()) {
            Log.i(TAG, "没有记住的设备");
            return false;
        }
        if (!isBluetoothOn()) { Log.w(TAG, "蓝牙未开，跳过自动连接"); return false; }
        if (!hasPermissions(appCtx)) { Log.w(TAG, "缺权限，跳过自动连接"); return false; }
        Log.i(TAG, "自动连接上次设备 " + mac);
        connect(mac);
        return true;
    }

    // ==================== 自动探测 ====================

    /** 探测队列：按 RSSI 从强到弱依次连接，找到博世服务就停 */
    private java.util.List<String> probeQueue = null;
    private int probeTried = 0;
    /** 每次推进 +1，用来让旧的超时定时器失效，避免重复 nextProbe */
    private int probeGeneration = 0;
    private static final int PROBE_MAX = 4;      // 最多试 4 个
    private static final long PROBE_TIMEOUT = 4000;

    /** 开始自动探测：依次连接候选设备，第一个有博世服务的就是测距仪 */
    public void startProbe(java.util.List<String> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            err("没有可探测的设备");
            return;
        }
        probeQueue = new java.util.ArrayList<String>(
                candidates.subList(0, Math.min(PROBE_MAX, candidates.size())));
        probeTried = 0;
        Log.i(TAG, "开始自动探测 " + probeQueue.size() + " 个候选设备");
        nextProbe();
    }

    private void nextProbe() {
        if (probeQueue == null || probeQueue.isEmpty()) {
            probeQueue = null;
            err("没有找到博世测距仪，请确认测距仪已开机（按一下机身蓝牙键）");
            return;
        }
        final String mac = probeQueue.remove(0);
        probeTried++;
        final int gen = ++probeGeneration;
        Log.i(TAG, "探测第 " + probeTried + " 个: " + mac);
        connect(mac);

        // 超时兜底：这个设备迟迟没结果就跳过
        ui.postDelayed(new Runnable() {
            @Override public void run() {
                if (gen != probeGeneration) return;   // 已被更快的推进取代
                if (probeQueue == null || ready) return;
                Log.w(TAG, "探测超时: " + mac);
                disconnect();
                nextProbe();
            }
        }, PROBE_TIMEOUT);
    }

    private void stopProbe() {
        probeQueue = null;
        probeGeneration++;
    }

    /** 当前设备确认不是测距仪：立即试下一个，不等超时 */
    private void advanceProbe() {
        if (probeQueue == null) return;
        probeGeneration++;          // 让旧超时定时器失效
        ui.postDelayed(new Runnable() {
            @Override public void run() {
                if (probeQueue == null || ready) return;
                nextProbe();
            }
        }, 300);                    // 稍等让 GATT 彻底断开
    }

    // ==================== 协议 ====================

    private void sendHandshake() {
        final byte[] frame = buildFrame(CMD_HANDSHAKE, new byte[]{ 0x01, 0x00 });
        boolean ok = write(frame);
        if (ok) {
            ready = true;
            Log.i(TAG, "握手已发送 " + hex(frame));
            ui.post(new Runnable() {
                @Override public void run() {
                    if (listener != null) listener.onConnected(connectedName);
                }
            });
        } else {
            err("握手发送失败");
        }
    }

    /** 触发一次测量。未连接时返回 false，由调用方决定是否先 connect */
    public boolean measure() {
        if (gatt == null || !ready) {
            Log.w(TAG, "measure(): 未就绪 gatt=" + (gatt != null) + " ready=" + ready);
            return false;
        }
        measureRetry = 0;
        sendMeasure();
        return true;
    }

    /** 当前挂着的重试任务，收到结果时要取消 */
    private Runnable measureTimeout;

    private void sendMeasure() {
        final byte[] frame = buildFrame(CMD_MEASURE, new byte[]{ 0x00 });
        write(frame);
        Log.i(TAG, "测量请求 " + hex(frame) + " retry=" + measureRetry);

        if (measureTimeout != null) ui.removeCallbacks(measureTimeout);
        final int token = ++measureToken;
        measureTimeout = new Runnable() {
            @Override public void run() {
                if (token != measureToken) return;      // 已被新请求取代
                if (measureRetry < MEASURE_RETRY) {
                    measureRetry++;
                    sendMeasure();
                } else {
                    err("测距仪无响应，请靠近后重试");
                }
            }
        };
        ui.postDelayed(measureTimeout, MEASURE_TIMEOUT_MS);
    }

    /** 收到结果时调用：停掉重试链 */
    private void cancelMeasureTimeout() {
        measureRetry = MEASURE_RETRY;
        measureToken++;
        if (measureTimeout != null) {
            ui.removeCallbacks(measureTimeout);
            measureTimeout = null;
        }
    }

    /** 写 CCCD 启用 notify；先试 notification，失败再试 indication */
    private int cccdAttempt = 0;
    private void writeCccd(final BluetoothGatt g) {
        if (g == null || chr == null) return;
        try {
            BluetoothGattDescriptor d = chr.getDescriptor(CCCD_UUID);
            if (d == null) {
                Log.w(TAG, "没找到 CCCD 描述符，直接握手");
                sendHandshake();
                return;
            }
            byte[] val = (cccdAttempt == 0)
                    ? BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    : BluetoothGattDescriptor.ENABLE_INDICATION_VALUE;
            d.setValue(val);
            boolean ok = g.writeDescriptor(d);
            Log.i(TAG, "writeDescriptor 提交=" + ok + " attempt=" + cccdAttempt);
            if (!ok) {
                // 直接提交失败：换 indication 再试一次，或者直接握手
                if (cccdAttempt == 0) {
                    cccdAttempt = 1;
                    ui.postDelayed(new Runnable() {
                        @Override public void run() { writeCccd(g); }
                    }, 200);
                } else {
                    sendHandshake();
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "写 CCCD 异常", e);
            sendHandshake();
        }
    }

    /** 处理收到的帧 */
    private void handleRx(byte[] v) {
        if (v == null || v.length < 4) return;
        Log.d(TAG, "RX " + hex(v));

        // CRC 校验
        byte expect = crc8(v, 0, v.length - 1);
        if (expect != v[v.length - 1]) {
            Log.w(TAG, String.format("CRC 不符 期望=%02X 实际=%02X", expect, v[v.length - 1]));
            return;
        }

        byte mode = v[0];
        byte cmd = v[1];
        int len = v[2] & 0xFF;
        if (3 + len + 1 > v.length) return;   // 长度不符

        // 握手应答：CMD=0x55 且 LEN=2
        if (cmd == CMD_HANDSHAKE && len == 2) {
            Log.i(TAG, "握手确认");
            return;
        }

        // 测量结果：CMD=0x55 且 LEN=16，DATA[0]==0x06
        if (cmd == CMD_HANDSHAKE && len >= 8) {
            byte type = v[3];
            if (type == 0x06) {
                final float meters = readFloatLE(v, 3 + 4);
                Log.i(TAG, "测量结果 " + meters + " m");
                cancelMeasureTimeout();          // 停掉重发链
                if (multiActive) {
                    addMultiSample(meters);
                    return;
                }
                ui.post(new Runnable() {
                    @Override public void run() {
                        if (listener != null) listener.onMeasured(meters);
                    }
                });
            }
            return;
        }

        // 状态心跳 CMD=0x11，忽略
    }

    // ==================== 多次测量取中位数 ====================

    private final java.util.List<Float> multiSamples = new java.util.ArrayList<Float>();
    private int multiTarget = 0;
    private boolean multiActive = false;

    /**
     * 连续测 count 次，取中位数返回。
     * 激光测距仪单次重复性有 ±0.5mm 波动，取中位数能明显稳住读数。
     */
    public void measureMulti(final int count) {
        if (gatt == null || !ready) {
            if (listener != null) listener.onError("未连接");
            return;
        }
        multiSamples.clear();
        multiTarget = count;
        multiActive = true;
        Log.i(TAG, "开始连测 " + count + " 次");
        measureRetry = 0;
        sendMeasure();
    }

    /** 收样：凑够 count 个取中位数回调 */
    private void addMultiSample(float v) {
        multiSamples.add(v);
        Log.i(TAG, "样本 " + multiSamples.size() + "/" + multiTarget + " = " + v);
        if (multiSamples.size() < multiTarget) {
            // 等一会儿再测下一次
            ui.postDelayed(new Runnable() {
                @Override public void run() {
                    if (multiActive && gatt != null && ready) {
                        measureRetry = 0;
                        sendMeasure();
                    }
                }
            }, 700);
            return;
        }

        // 取中位数
        java.util.List<Float> sorted = new java.util.ArrayList<Float>(multiSamples);
        java.util.Collections.sort(sorted);
        float med;
        int n = sorted.size();
        if (n % 2 == 1) med = sorted.get(n / 2);
        else med = (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2f;

        float lo = sorted.get(0), hi = sorted.get(n - 1);
        Log.i(TAG, String.format("连测完成 中位数=%.4f m (范围 %.4f~%.4f, 波动 %.3f mm)",
                med, lo, hi, (hi - lo) * 1000));
        multiActive = false;

        final float result = med;
        ui.post(new Runnable() {
            @Override public void run() {
                if (listener != null) listener.onMeasured(result);
            }
        });
    }

    // ==================== 工具 ====================

    /** 构造 XTP 帧：[MODE][CMD][LEN][DATA][CRC8] */
    static byte[] buildFrame(byte cmd, byte[] data) {
        int len = data == null ? 0 : data.length;
        byte[] f = new byte[3 + len + 1];
        f[0] = MODE_LONG;
        f[1] = cmd;
        f[2] = (byte) len;
        if (len > 0) System.arraycopy(data, 0, f, 3, len);
        f[3 + len] = crc8(f, 0, 3 + len);
        return f;
    }

    /** CRC8：init=0xAA, poly=0xA6（博世 MtProtocol 的 Crc.calcCrc8） */
    static byte crc8(byte[] data, int off, int len) {
        int crc = CRC_INIT;
        for (int i = off; i < off + len; i++) {
            int b = data[i] & 0xFF;
            for (int k = 0; k < 8; k++) {
                boolean bitCrc = (crc & 0x80) != 0;
                boolean bitData = ((b >> (7 - k)) & 1) != 0;
                if (bitCrc != bitData) crc = ((crc << 1) ^ CRC_POLY) & 0xFF;
                else                   crc = (crc << 1) & 0xFF;
            }
        }
        return (byte) crc;
    }

    /** 小端 float32 */
    private static float readFloatLE(byte[] b, int off) {
        int bits = (b[off] & 0xFF)
                | ((b[off + 1] & 0xFF) << 8)
                | ((b[off + 2] & 0xFF) << 16)
                | ((b[off + 3] & 0xFF) << 24);
        return Float.intBitsToFloat(bits);
    }

    private boolean write(byte[] data) {
        if (gatt == null || chr == null) return false;
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                int r = gatt.writeCharacteristic(chr, data, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
                return r == BluetoothGatt.GATT_SUCCESS;
            } else {
                chr.setValue(data);
                return gatt.writeCharacteristic(chr);
            }
        } catch (Exception e) {
            Log.e(TAG, "write 异常", e);
            return false;
        }
    }

    private void err(final String msg) {
        Log.e(TAG, msg);
        ui.post(new Runnable() {
            @Override public void run() {
                if (listener != null) listener.onError(msg);
            }
        });
    }

    public static String hex(byte[] b) {
        if (b == null) return "";
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x ", x));
        return sb.toString().trim();
    }

    public void disconnect() { disconnect(0); }

    private void disconnect(long delay) {
        if (delay > 0) {
            ui.postDelayed(new Runnable() {
                @Override public void run() { disconnect(0); }
            }, delay);
            return;
        }
        ready = false;
        if (gatt != null) {
            try { gatt.disconnect(); } catch (Exception ignored) {}
            try { gatt.close(); } catch (Exception ignored) {}
            gatt = null;
        }
        chr = null;
    }

    /** 释放资源 */
    public void release() {
        stopScan();
        disconnect(0);
        listener = null;
        ui.removeCallbacksAndMessages(null);
    }
}
