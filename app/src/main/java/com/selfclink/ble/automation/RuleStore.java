package com.selfclink.ble.automation;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

import com.selfclink.ble.util.AppLog;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 已接入设备 + 手势编排的持久化。BindKey 属敏感数据，故整体存于
 * {@link EncryptedSharedPreferences}（AES256，密钥在 Android Keystore）。
 *
 * <p>注意：账号密码 / serviceToken <b>不</b>经此存储（满足「凭据不存」），这里只存
 * 设备解密所需的 BindKey 与编排规则。
 *
 * <p><b>白屏卡死修复</b>：{@link EncryptedSharedPreferences#create} 会向 Android
 * Keystore/TEE 发同步 Binder 调用，个别车机唤醒后 keystore 僵死会让该调用无限期阻塞。
 * 过去每次 {@code new RuleStore()} 都在调用线程（含主线程 onResume）触发它 → ANR / 白屏。
 * 现在：全进程只 {@code create} 一次并缓存；这一次固定放后台线程（{@link #warmUp} 由
 * Application 启动即预热）；取用未就绪时立即返回，写入必须明确返回失败。
 */
public final class RuleStore {

    private static final String TAG = "RuleStore";
    private static final String FILE = "bound_devices";
    private static final String KEY_DEVICES = "devices";
    private static volatile SharedPreferences sPrefs;
    private static volatile boolean started;

    private final Context appCtx;
    private final java.util.function.Consumer<String> failureReporter;

    public RuleStore(Context context) {
        this(context, message -> {
            AppLog.w(TAG, message);
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() ->
                    android.widget.Toast.makeText(context.getApplicationContext(),
                            message, android.widget.Toast.LENGTH_LONG).show());
        });
    }

    RuleStore(Context context, java.util.function.Consumer<String> failureReporter) {
        this.appCtx = context.getApplicationContext();
        this.failureReporter = failureReporter;
        warmUp(this.appCtx);
    }

    /** 进程启动即调用：在后台线程完成唯一一次 keystore 访问并缓存，主线程从此不再碰 keystore。 */
    public static void warmUp(Context context) {
        if (started) {
            return;
        }
        synchronized (RuleStore.class) {
            if (started) {
                return;
            }
            started = true;
        }
        final Context ctx = context.getApplicationContext();
        Thread t = new Thread(() -> {
            SharedPreferences p;
            try {
                MasterKey key = new MasterKey.Builder(ctx)
                        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                        .build();
                p = EncryptedSharedPreferences.create(
                        ctx, FILE, key,
                        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM);
            } catch (Throwable e) {
                AppLog.e(TAG, "加密存储不可用，降级明文 prefs", e);
                p = ctx.getSharedPreferences(FILE + "_plain", Context.MODE_PRIVATE);
            }
            sPrefs = p;
        }, "rulestore-init");
        t.setDaemon(true);
        t.start();
    }

    public static boolean isReady() { return sPrefs != null; }

    /** 不等待后台密钥初始化，避免每次点击在主线程卡两秒。 */
    private SharedPreferences prefs() {
        SharedPreferences p = sPrefs;
        if (p != null) {
            return p;
        }
        warmUp(appCtx);
        return sPrefs;
    }

    public synchronized List<BoundDevice> load() {
        List<BoundDevice> out = new ArrayList<>();
        SharedPreferences prefs = prefs();
        if (prefs == null) {
            AppLog.w(TAG, "存储未就绪（keystore 无响应），本次返回空");
            return out;
        }
        String raw = prefs.getString(KEY_DEVICES, null);
        if (raw == null) {
            return out;
        }
        try {
            JSONArray arr = new JSONArray(raw);
            boolean migrated = false;
            for (int i = 0; i < arr.length(); i++) {
                BoundDevice device = BoundDevice.fromJson(arr.getJSONObject(i));
                // v3.35 曾短暂允许将整条 ScanRecord 当作按键码。整包包含设备名、
                // FE95 待机帧等无关字段，会导致学到“设备在广播”而非“按下某键”。
                // 这类规则不可修复，升级时自动删除，并同步清理它的动作绑定。
                for (int j = device.learned.size() - 1; j >= 0; j--) {
                    LearnedEvent event = device.learned.get(j);
                    if ("record".equals(event.source)) {
                        device.learned.remove(j);
                        device.gestureActions.remove(event.id);
                        migrated = true;
                    }
                }
                out.add(device);
            }
            if (migrated) {
                writeDevices(prefs, out);
                AppLog.w(TAG, "已清理旧版整包广播误学规则，请重新学习按键");
            }
        } catch (Exception e) {
            AppLog.w(TAG, "解析已接入设备失败: " + e.getMessage());
        }
        return out;
    }

    public synchronized boolean save(List<BoundDevice> devices) {
        SharedPreferences prefs = prefs();
        if (prefs == null) {
            return failed("加密存储尚未就绪，未保存。请稍后重试，原有配置未覆盖。");
        }
        return writeDevices(prefs, devices);
    }

    private boolean writeDevices(SharedPreferences prefs, List<BoundDevice> devices) {
        try {
            JSONArray arr = new JSONArray();
            for (BoundDevice d : devices) arr.put(d.toJson());
            prefs.edit().putString(KEY_DEVICES, arr.toString()).apply();
            return true;
        } catch (Exception e) {
            AppLog.e(TAG, "保存失败", e);
            return failed("保存失败，原有配置未覆盖。");
        }
    }

    private boolean failed(String message) {
        failureReporter.accept(message);
        return false;
    }

    /** 修改前严格读取；未就绪或损坏时不可把空列表作为原配置。 */
    private List<BoundDevice> loadForWrite(SharedPreferences p) throws Exception {
        JSONArray arr = new JSONArray(p.getString(KEY_DEVICES, "[]"));
        List<BoundDevice> list = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) list.add(BoundDevice.fromJson(arr.getJSONObject(i)));
        return list;
    }

    /** 新增或替换同 mac 设备。 */
    public boolean upsert(BoundDevice device) {
        synchronized (RuleStore.class) {
            SharedPreferences p = prefs();
            if (p == null) return failed("加密存储尚未就绪，未保存。原有绑定未覆盖。");
            try {
                List<BoundDevice> list = loadForWrite(p);
                list.removeIf(d -> d.mac.equalsIgnoreCase(device.mac));
                list.add(device);
                return writeDevices(p, list);
            } catch (Exception e) { return failed("原有绑定读取失败，已阻止覆盖。"); }
        }
    }

    public boolean remove(String mac) {
        synchronized (RuleStore.class) {
            SharedPreferences p = prefs();
            if (p == null) return failed("加密存储尚未就绪，未删除。原有绑定保留。");
            try {
                List<BoundDevice> list = loadForWrite(p);
                list.removeIf(d -> d.mac.equalsIgnoreCase(mac));
                return writeDevices(p, list);
            } catch (Exception e) { return failed("原有绑定读取失败，已阻止删除。"); }
        }
    }

    public synchronized BoundDevice byMac(String mac) {
        for (BoundDevice d : load()) {
            if (d.mac.equalsIgnoreCase(mac)) {
                return d;
            }
        }
        return null;
    }
}
