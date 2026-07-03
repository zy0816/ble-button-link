package com.selfclink.ble.product;

import android.content.Context;

import com.selfclink.ble.util.AppLog;

import java.util.ArrayList;
import java.util.List;

/**
 * 产品适配器登记处：持有全部适配器（声明式 Profile + 未来的内置 code 适配器），
 * 扫描到设备时据 {@link ProductAdapter#matches} 归类。
 */
public final class ProductRegistry {

    private static final String TAG = "ProductRegistry";

    private final ProfileStore profileStore;
    private final List<ProductAdapter> adapters = new ArrayList<>();

    public ProductRegistry(Context context) {
        this.profileStore = new ProfileStore(context);
        reload();
    }

    public ProfileStore profileStore() {
        return profileStore;
    }

    /** 重新从 ProfileStore 装配适配器（导入/删除 Profile 后调用）。 */
    public synchronized void reload() {
        adapters.clear();
        // 内置 code 适配器可在此 add（当前所有产品均用声明式 Profile 表达）。
        for (DeviceProfile p : profileStore.loadAll()) {
            adapters.add(new DeclarativeAdapter(p));
        }
        AppLog.d(TAG, "装配适配器 " + adapters.size() + " 个");
    }

    public synchronized List<ProductAdapter> all() {
        return new ArrayList<>(adapters);
    }

    /** 据广播找出命中的适配器，无则 null。 */
    public synchronized ProductAdapter identify(ScanFrame frame) {
        for (ProductAdapter a : adapters) {
            if (a.matches(frame)) {
                return a;
            }
        }
        return null;
    }

    public synchronized ProductAdapter byProductId(String productId) {
        for (ProductAdapter a : adapters) {
            if (a.productId().equals(productId)) {
                return a;
            }
        }
        return null;
    }
}
