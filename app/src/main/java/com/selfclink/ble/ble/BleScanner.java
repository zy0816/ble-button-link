package com.selfclink.ble.ble;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.os.ParcelUuid;
import android.util.SparseArray;

import com.selfclink.ble.product.ScanFrame;
import com.selfclink.ble.util.AppLog;

import java.util.HashMap;
import java.util.Map;

/**
 * 被动 BLE 扫描器：纯广播扫描（不建 GATT 连接），把每帧 {@link ScanResult} 转成与平台无关的
 * {@link ScanFrame} 回调出去，由上层 {@code ProductRegistry} 识别、适配器解析手势。
 *
 * <p>蓝牙按键/旋钮按下只广播极短一阵，故用 {@link ScanSettings#SCAN_MODE_LOW_LATENCY} 满占空扫描，
 * 避免漏按。车机睡眠唤醒后扫描器句柄会失效，{@link #restartScan()} 由服务看门狗周期兜底重开。
 */
@SuppressLint("MissingPermission")
public final class BleScanner {

    private static final String TAG = "BleScanner";

    /** 蓝牙基 UUID 后缀；据此把 128-bit UUID 还原成 16-bit 短形（如 fe95）。 */
    private static final String BASE_SUFFIX = "-0000-1000-8000-00805f9b34fb";

    public interface FrameListener {
        void onFrame(ScanFrame frame);
    }

    private final Context context;
    private final BluetoothAdapter adapter;
    private final FrameListener listener;
    private BluetoothLeScanner scanner;
    private boolean scanning;

    public BleScanner(Context context, FrameListener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        BluetoothManager bm = (BluetoothManager) this.context.getSystemService(Context.BLUETOOTH_SERVICE);
        this.adapter = bm != null ? bm.getAdapter() : BluetoothAdapter.getDefaultAdapter();
    }

    public boolean isBluetoothEnabled() {
        return adapter != null && adapter.isEnabled();
    }

    public synchronized void startScan() {
        if (!isBluetoothEnabled() || scanning) {
            return;
        }
        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) {
            return;
        }
        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();
        try {
            scanner.startScan(null, settings, scanCallback);
            scanning = true;
            AppLog.d(TAG, "开始扫描 BLE（低延迟）");
        } catch (Exception e) {
            AppLog.w(TAG, "startScan 失败: " + e.getMessage());
            scanning = false;
        }
    }

    public synchronized void stopScan() {
        if (scanner != null && scanning && isBluetoothEnabled()) {
            try {
                scanner.stopScan(scanCallback);
            } catch (Exception ignored) {
            }
        }
        scanning = false;
    }

    /** 看门狗兜底：纠正 scanning 标记与系统实际扫描脱节（睡眠唤醒后系统会悄悄停扫）。 */
    public synchronized void restartScan() {
        if (!isBluetoothEnabled()) {
            return;
        }
        if (scanner != null) {
            try {
                scanner.stopScan(scanCallback);
            } catch (Exception ignored) {
            }
        }
        scanning = false;
        startScan();
    }

    public boolean isScanning() {
        return scanning;
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            ScanFrame frame = toFrame(result);
            if (frame != null) {
                listener.onFrame(frame);
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            AppLog.w(TAG, "扫描启动失败 errorCode=" + errorCode + "，复位待重试");
            synchronized (BleScanner.this) {
                scanning = false;
            }
        }
    };

    private ScanFrame toFrame(ScanResult result) {
        if (result == null || result.getDevice() == null) {
            return null;
        }
        ScanRecord rec = result.getScanRecord();
        String name = result.getDevice().getName();
        String mac = normMac(result.getDevice().getAddress());

        Map<String, byte[]> serviceData = new HashMap<>();
        Map<Integer, byte[]> manufacturerData = new HashMap<>();
        if (rec != null) {
            Map<ParcelUuid, byte[]> sd = rec.getServiceData();
            if (sd != null) {
                for (Map.Entry<ParcelUuid, byte[]> e : sd.entrySet()) {
                    serviceData.put(shortUuid(e.getKey()), e.getValue());
                }
            }
            if (rec.getDeviceName() != null && name == null) {
                name = rec.getDeviceName();
            }
            SparseArray<byte[]> msd = rec.getManufacturerSpecificData();
            if (msd != null) {
                for (int i = 0; i < msd.size(); i++) {
                    manufacturerData.put(msd.keyAt(i), msd.valueAt(i));
                }
            }
        }
        return new ScanFrame(name, mac, result.getRssi(), serviceData, manufacturerData);
    }

    /** ParcelUuid → 小写短形（标准蓝牙 16-bit 返回 4 hex，否则返回完整 36 字符）。 */
    private static String shortUuid(ParcelUuid pu) {
        String s = pu.getUuid().toString().toLowerCase();
        if (s.startsWith("0000") && s.endsWith(BASE_SUFFIX)) {
            return s.substring(4, 8);
        }
        return s;
    }

    private static String normMac(String mac) {
        return mac == null ? "" : mac.replace(":", "").toUpperCase();
    }
}
