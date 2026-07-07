package com.selfclink.ble.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.widget.Toast;

import androidx.annotation.Nullable;

import com.selfclink.ble.R;
import com.selfclink.ble.automation.ActionExecutor;
import com.selfclink.ble.automation.BoundDevice;
import com.selfclink.ble.automation.RuleStore;
import com.selfclink.ble.ble.BleScanner;
import com.selfclink.ble.product.DecodedGesture;
import com.selfclink.ble.product.ProductAdapter;
import com.selfclink.ble.product.ProductRegistry;
import com.selfclink.ble.product.ScanFrame;
import com.selfclink.ble.util.AppLog;
import com.selfclink.ble.util.AppPrefs;
import com.selfclink.ble.util.HexUtil;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 前台常驻服务：被动扫描 BLE → 识别产品 → 解析手势 → 执行绑定动作。看门狗周期重开扫描保活。
 *
 * <p>账号凭据不参与本服务；运行时只需 {@link RuleStore} 里的设备 BindKey 与编排。
 */
public final class ButtonService extends Service {

    private static final String TAG = "ButtonService";
    private static final String CHANNEL_ID = "ble_button";
    private static final int NOTIF_ID = 1001;
    private static final long WATCHDOG_MS = 10_000;
    /** 无帧计数设备（advmatch）的时间去重窗口。 */
    private static final long TIME_DEDUP_MS = 700;

    /** 让 UI 改动后请求服务重载绑定。 */
    public static final String ACTION_RELOAD = "com.selfclink.ble.RELOAD";

    private ProductRegistry registry;
    private RuleStore ruleStore;
    private ActionExecutor executor;
    private BleScanner scanner;

    private final Handler main = new Handler(Looper.getMainLooper());
    /** mac(大写) → 已接入设备。 */
    private volatile Map<String, BoundDevice> bindings = new HashMap<>();
    private final Map<String, Integer> lastCounter = new HashMap<>();
    private final Map<String, Long> lastTime = new HashMap<>();

    @Override
    public void onCreate() {
        super.onCreate();
        registry = new ProductRegistry(this);
        ruleStore = new RuleStore(this);
        executor = new ActionExecutor(this);
        scanner = new BleScanner(this, this::onFrame);
        reloadBindings();
        startForeground(NOTIF_ID, buildNotification());
        scanner.startScan();
        main.postDelayed(watchdog, WATCHDOG_MS);
        AppLog.d(TAG, "服务启动，接入设备 " + bindings.size() + " 台");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_RELOAD.equals(intent.getAction())) {
            registry.reload();
            reloadBindings();
        }
        return START_STICKY;
    }

    private void reloadBindings() {
        Map<String, BoundDevice> map = new HashMap<>();
        for (BoundDevice d : ruleStore.load()) {
            if (d.mac != null) {
                map.put(d.mac.toUpperCase(), d);
            }
        }
        bindings = map;
    }

    private void onFrame(ScanFrame frame) {
        com.selfclink.ble.ble.Sightings.record(frame.mac);
        BoundDevice device = bindings.get(frame.mac);
        if (device == null) {
            return; // 非已接入设备，忽略
        }
        boolean diag = AppLog.isDiagEnabled();
        if (diag) {
            diagFrame(frame, device);
        }
        ProductAdapter adapter = registry.byProductId(device.productId);
        if (adapter == null) {
            if (diag) {
                AppLog.diag(TAG, "  ✗ 找不到产品适配（productId=" + device.productId + "）");
            }
            return;
        }
        byte[] cred = null;
        if (adapter.credentialSpec() == ProductAdapter.CredentialSpec.BINDKEY16) {
            if (device.bindKeyHex == null) {
                if (diag) {
                    AppLog.diag(TAG, "  ✗ 未配置 BindKey");
                }
                return;
            }
            cred = HexUtil.fromHex(device.bindKeyHex);
            recordBattery(frame, cred);
        }
        // 自学习事件优先命中；未命中再回落到 Profile 内置手势解析，两者并存。
        DecodedGesture g = null;
        if (device.hasLearned()) {
            g = matchLearned(device, frame, cred);
        }
        if (g == null) {
            g = adapter.parse(frame, cred);
        }
        if (g == null) {
            if (diag) {
                AppLog.diag(TAG, "  ✗ 未匹配任何手势（无此广播码，或 BindKey 错致解密失败）");
            }
            return;
        }
        if (isDuplicate(frame.mac, g)) {
            if (diag) {
                AppLog.diag(TAG, "  · 命中「" + g.gestureId + "」但去重忽略（同一次按下）");
            }
            return;
        }
        List<String> actions = device.actionsFor(g.gestureId);
        if (!actions.isEmpty()) {
            AppLog.d(TAG, "设备 " + device.name + " 手势 " + g.gestureId + " → " + actions);
            if (diag) {
                AppLog.diag(TAG, "  ✓ 命中「" + g.gestureId + "」→ 执行 " + actions);
            }
            executor.executeAll(actions);
            feedback(device.name + " · " + g.gestureId);
        } else if (diag) {
            AppLog.diag(TAG, "  △ 命中「" + g.gestureId + "」但未绑定动作");
        }
    }

    /** 诊断模式：打印一帧的来源与解密结果（objId·value），供「按键诊断」页展示裁决前提。 */
    private void diagFrame(ScanFrame frame, BoundDevice device) {
        StringBuilder sb = new StringBuilder();
        sb.append(device.name).append("  rssi=").append(frame.rssi);
        byte[] fe95 = frame.serviceData("fe95");
        if (fe95 != null) {
            sb.append("\n  fe95=").append(HexUtil.toHex(fe95));
            if (device.bindKeyHex != null) {
                try {
                    com.selfclink.ble.protocol.MiBeacon.Result r =
                            com.selfclink.ble.protocol.MiBeacon.parse(fe95, HexUtil.fromHex(device.bindKeyHex));
                    if (r != null) {
                        sb.append(String.format(java.util.Locale.US, "\n  → objId=0x%04X value=%s",
                                r.objId, r.value == null ? "(无)" : HexUtil.toHex(r.value)));
                    } else {
                        sb.append("\n  → 待机/非事件帧或解密失败（BindKey 可能错误）");
                    }
                } catch (Exception ignored) {
                }
            }
        }
        AppLog.diag(TAG, sb.toString());
    }

    /** 自学习设备：解密 FE95 后按 objId + value 掩码匹配已学事件。 */
    private DecodedGesture matchLearned(BoundDevice d, ScanFrame f, byte[] bindKey) {
        byte[] fe95 = f.serviceData("fe95");
        if (fe95 == null || bindKey == null) {
            return null;
        }
        com.selfclink.ble.protocol.MiBeacon.Result r =
                com.selfclink.ble.protocol.MiBeacon.parse(fe95, bindKey);
        if (r == null) {
            return null;
        }
        for (com.selfclink.ble.automation.LearnedEvent e : d.learned) {
            if (e.matches(r.objId, r.value)) {
                return new DecodedGesture(e.id, r.frameCounter);
            }
        }
        return null;
    }

    /** 顺带解密 FE95，若为电量对象（0x4803）则记录百分比，供 UI 展示。 */
    private void recordBattery(ScanFrame frame, byte[] bindKey) {
        byte[] fe95 = frame.serviceData("fe95");
        if (fe95 == null) {
            return;
        }
        com.selfclink.ble.protocol.MiBeacon.Result r =
                com.selfclink.ble.protocol.MiBeacon.parse(fe95, bindKey);
        if (r != null && r.objId == 0x4803 && r.value != null && r.value.length >= 1) {
            com.selfclink.ble.ble.Sightings.recordBattery(frame.mac, r.value[0] & 0xFF);
        }
    }

    /** 触发反馈：短震动 + Toast（Settings 可关）。 */
    private void feedback(String label) {
        if (!AppPrefs.feedback(this)) {
            return;
        }
        try {
            Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (v != null && v.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    v.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE));
                } else {
                    v.vibrate(40);
                }
            }
        } catch (Exception ignored) {
        }
        main.post(() -> Toast.makeText(this, label, Toast.LENGTH_SHORT).show());
    }

    /** 同一次按下去重：有帧计数比计数，否则按时间窗口。 */
    private boolean isDuplicate(String mac, DecodedGesture g) {
        String k = mac + "|" + g.gestureId;
        long now = System.currentTimeMillis();
        if (g.dedup != DecodedGesture.NO_DEDUP) {
            Integer last = lastCounter.get(k);
            lastCounter.put(k, g.dedup);
            return last != null && last == g.dedup;
        }
        Long lt = lastTime.get(k);
        lastTime.put(k, now);
        return lt != null && (now - lt) < TIME_DEDUP_MS;
    }

    private final Runnable watchdog = new Runnable() {
        @Override
        public void run() {
            scanner.restartScan();
            main.postDelayed(this, WATCHDOG_MS);
        }
    };

    private Notification buildNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID,
                    getString(R.string.service_channel_name), NotificationManager.IMPORTANCE_MIN);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.service_notification_title))
                .setContentText(getString(R.string.service_notification_text))
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setOngoing(true)
                .build();
    }

    @Override
    public void onDestroy() {
        main.removeCallbacks(watchdog);
        if (scanner != null) {
            scanner.stopScan();
        }
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /** 启动 / 通知服务重载（UI 编排改动后调用）。 */
    public static void start(Context ctx) {
        Intent i = new Intent(ctx, ButtonService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.startForegroundService(i);
        } else {
            ctx.startService(i);
        }
    }

    public static void reload(Context ctx) {
        Intent i = new Intent(ctx, ButtonService.class).setAction(ACTION_RELOAD);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.startForegroundService(i);
        } else {
            ctx.startService(i);
        }
    }
}
