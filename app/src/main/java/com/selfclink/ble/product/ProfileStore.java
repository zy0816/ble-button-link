package com.selfclink.ble.product;

import android.content.Context;
import android.content.res.AssetManager;

import com.selfclink.ble.util.AppLog;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 设备配置（Profile）仓库：加载内置（assets/profiles）+ 用户导入（filesDir/profiles）的声明式配置，
 * 负责导入校验、导出分享、去重（同 productId 用户版覆盖内置版）。共创的存储层。
 */
public final class ProfileStore {

    private static final String TAG = "ProfileStore";
    private static final String ASSET_DIR = "profiles";
    private static final String USER_DIR = "profiles";

    private final Context context;

    public ProfileStore(Context context) {
        this.context = context.getApplicationContext();
    }

    /** 加载全部 Profile（用户版覆盖同 productId 的内置版）。 */
    public List<DeviceProfile> loadAll() {
        Map<String, DeviceProfile> byId = new LinkedHashMap<>();
        for (DeviceProfile p : loadBuiltin()) {
            byId.put(p.productId, p);
        }
        for (DeviceProfile p : loadUser()) {
            byId.put(p.productId, p); // 覆盖
        }
        return new ArrayList<>(byId.values());
    }

    private List<DeviceProfile> loadBuiltin() {
        List<DeviceProfile> out = new ArrayList<>();
        AssetManager am = context.getAssets();
        try {
            String[] files = am.list(ASSET_DIR);
            if (files != null) {
                for (String name : files) {
                    if (!name.endsWith(".json")) {
                        continue;
                    }
                    try (InputStream is = am.open(ASSET_DIR + "/" + name)) {
                        out.add(DeviceProfile.fromJson(readAll(is)));
                    } catch (Exception e) {
                        AppLog.w(TAG, "内置 Profile 解析失败 " + name + ": " + e.getMessage());
                    }
                }
            }
        } catch (IOException e) {
            AppLog.w(TAG, "列举内置 Profile 失败: " + e.getMessage());
        }
        return out;
    }

    private List<DeviceProfile> loadUser() {
        List<DeviceProfile> out = new ArrayList<>();
        File dir = userDir();
        File[] files = dir.listFiles((d, n) -> n.endsWith(".json"));
        if (files != null) {
            for (File f : files) {
                try (InputStream is = new java.io.FileInputStream(f)) {
                    out.add(DeviceProfile.fromJson(readAll(is)));
                } catch (Exception e) {
                    AppLog.w(TAG, "用户 Profile 解析失败 " + f.getName() + ": " + e.getMessage());
                }
            }
        }
        return out;
    }

    /**
     * 导入一份 Profile（校验后落盘为用户配置）。返回解析后的 Profile，非法抛 {@link IllegalArgumentException}。
     */
    public DeviceProfile importProfile(String json) {
        DeviceProfile p = DeviceProfile.fromJson(json); // 校验
        File dir = userDir();
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IllegalStateException("无法创建配置目录");
        }
        File out = new File(dir, sanitize(p.productId) + ".json");
        try (FileOutputStream fos = new FileOutputStream(out)) {
            fos.write(p.toJson().toString(2).getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("写入失败: " + e.getMessage());
        }
        AppLog.d(TAG, "导入 Profile: " + p.productId);
        return p;
    }

    /** 导出某 Profile 为分享 JSON 文本（不含 BindKey）。 */
    public String exportProfile(DeviceProfile p) {
        try {
            return p.toJson().toString(2);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 删除一份用户导入的 Profile（内置的删不了）。 */
    public boolean deleteUserProfile(String productId) {
        File f = new File(userDir(), sanitize(productId) + ".json");
        return f.exists() && f.delete();
    }

    public boolean isUserProfile(String productId) {
        return new File(userDir(), sanitize(productId) + ".json").exists();
    }

    private File userDir() {
        return new File(context.getFilesDir(), USER_DIR);
    }

    private static String sanitize(String id) {
        return id.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static String readAll(InputStream is) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) != -1) {
            bos.write(buf, 0, n);
        }
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }
}
