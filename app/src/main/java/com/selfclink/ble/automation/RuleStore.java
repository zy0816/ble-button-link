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
 */
public final class RuleStore {

    private static final String TAG = "RuleStore";
    private static final String FILE = "bound_devices";
    private static final String KEY_DEVICES = "devices";

    private final SharedPreferences prefs;

    public RuleStore(Context context) {
        this.prefs = open(context.getApplicationContext());
    }

    private static SharedPreferences open(Context ctx) {
        try {
            MasterKey key = new MasterKey.Builder(ctx)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build();
            return EncryptedSharedPreferences.create(
                    ctx, FILE, key,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM);
        } catch (Exception e) {
            AppLog.e(TAG, "加密存储不可用，降级明文 prefs", e);
            return ctx.getSharedPreferences(FILE + "_plain", Context.MODE_PRIVATE);
        }
    }

    public synchronized List<BoundDevice> load() {
        List<BoundDevice> out = new ArrayList<>();
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
