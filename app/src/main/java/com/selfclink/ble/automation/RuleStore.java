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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

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
 * Application 启动即预热）；主线程取用最多等 {@link #WAIT_MS}，超时走空数据不冻界面。
 */
public final class RuleStore {

    private static final String TAG = "RuleStore";
    private static final String FILE = "bound_devices";
    private static final String KEY_DEVICES = "devices";
    /** 主线程等待预热的上限，远低于 5s ANR 阈值；预热在进程启动即开始，正常无需等待。 */
    private static final long WAIT_MS = 2000;

    private static volatile SharedPreferences sPrefs;
    private static final CountDownLatch READY = new CountDownLatch(1);
    private static volatile boolean started;

    private final Context appCtx;

    public RuleStore(Context context) {
        this.appCtx = context.getApplicationContext();
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
            READY.countDown();
        }, "rulestore-init");
        t.setDaemon(true);
        t.start();
    }

    /** 取缓存 prefs；未就绪时有限等待，绝不无限阻塞主线程。可能返回 null（keystore 僵死）。 */
    private SharedPreferences prefs() {
        SharedPreferences p = sPrefs;
        if (p != null) {
            return p;
        }
        warmUp(appCtx);
        try {
            READY.await(WAIT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
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
            for (int i = 0; i < arr.length(); i++) {
                out.add(BoundDevice.fromJson(arr.getJSONObject(i)));
            }
        } catch (Exception e) {
            AppLog.w(TAG, "解析已接入设备失败: " + e.getMessage());
        }
        return out;
    }

    public synchronized void save(List<BoundDevice> devices) {
        SharedPreferences prefs = prefs();
        if (prefs == null) {
            AppLog.w(TAG, "存储未就绪（keystore 无响应），保存被跳过");
            return;
        }
        try {
            JSONArray arr = new JSONArray();
            for (BoundDevice d : devices) {
                arr.put(d.toJson());
            }
            prefs.edit().putString(KEY_DEVICES, arr.toString()).apply();
        } catch (Exception e) {
            AppLog.e(TAG, "保存失败", e);
        }
    }

    /** 新增或替换同 mac 设备。 */
    public synchronized void upsert(BoundDevice device) {
        List<BoundDevice> list = load();
        list.removeIf(d -> d.mac.equalsIgnoreCase(device.mac));
        list.add(device);
        save(list);
    }

    public synchronized void remove(String mac) {
        List<BoundDevice> list = load();
        list.removeIf(d -> d.mac.equalsIgnoreCase(mac));
        save(list);
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
