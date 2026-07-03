package com.selfclink.ble.ui;

import android.content.Intent;
import android.graphics.PorterDuff;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.selfclink.ble.R;
import com.selfclink.ble.automation.BoundDevice;
import com.selfclink.ble.automation.RuleStore;
import com.selfclink.ble.ble.BleScanner;
import com.selfclink.ble.cloud.MiAccount;
import com.selfclink.ble.cloud.MiCloud;
import com.selfclink.ble.cloud.MiDevice;
import com.selfclink.ble.product.ProductAdapter;
import com.selfclink.ble.product.ProductRegistry;
import com.selfclink.ble.product.ScanFrame;
import com.selfclink.ble.service.ButtonService;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * 添加设备向导：三条路并存——
 * ① 从米家设备列表直接选；② 手动输入 MAC + BindKey；③ 扫描附近蓝牙广播（含未识别的 FE95 设备）后选择。
 * 运行时 {@link ButtonService} 按 MAC 命中已接入设备，故只要 MAC + 产品 + 密钥正确即可生效。
 */
public final class AddDeviceActivity extends BackBarActivity {

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private ProductRegistry registry;
    private RuleStore ruleStore;
    private BleScanner scanner;

    private LinearLayout list;
    private TextView emptyText;

    /** mac → 已展示行（去重）。 */
    private final Map<String, View> rows = new LinkedHashMap<>();
    /** 已接入的 mac，避免重复添加。 */
    private final Map<String, Boolean> bound = new HashMap<>();
    /** 云端 mac → BindKey（登录后预取）。 */
    private final Map<String, String> cloudKeys = new HashMap<>();
    /** 云端含 BindKey 的设备（供按 MAC 匹配失败时手动指认）。 */
    private final List<MiDevice> cloudDevices = new ArrayList<>();
    /** 云端全部设备（用于「米家设备列表」展示，含无密钥的）。 */
    private final List<MiDevice> cloudAll = new ArrayList<>();
    /** 已成功拉取过云端设备，避免重复请求。 */
    private volatile boolean cloudFetched = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_add_device);
        list = findViewById(R.id.scan_list);
        emptyText = findViewById(R.id.empty_text);

        registry = new ProductRegistry(this);
        ruleStore = new RuleStore(this);
        scanner = new BleScanner(this, this::onFrame);

        for (BoundDevice d : ruleStore.load()) {
            bound.put(d.mac.toUpperCase(), true);
        }

        findViewById(R.id.btn_cloud).setOnClickListener(v -> openCloudList());
        findViewById(R.id.btn_manual).setOnClickListener(v -> openManualAdd());
    }

    @Override
    protected void onResume() {
        super.onResume();
        scanner.startScan();
        if (!scanner.isBluetoothEnabled()) {
            emptyText.setText("蓝牙未开启，请先打开蓝牙");
        }
        prefetchCloudKeys(); // 登录后返回本页会再次进入，确保拉到云端 BindKey
    }

    @Override
    protected void onPause() {
        super.onPause();
        scanner.stopScan();
    }

    private boolean isLoggedIn() {
        return MiAccount.get(this).isLoggedIn();
    }

    private void prefetchCloudKeys() {
        if (cloudFetched || !isLoggedIn()) {
            return;
        }
        io.execute(() -> {
            List<MiDevice> devs = new MiCloud().getDevices();
            int withKey = 0;
            for (MiDevice d : devs) {
                cloudAll.add(d);
                if (d.hasBindKey()) {
                    cloudKeys.put(d.mac.toUpperCase(), d.beaconKeyHex);
                    cloudDevices.add(d);
                    withKey++;
                }
            }
            cloudFetched = true;
            final int total = devs.size();
            final int n = withKey;
            main.post(() -> toast("已从米家读取 " + total + " 个设备（含密钥 " + n + " 个）"));
        });
    }

    // ---------------- ① 米家设备列表 ----------------

    private void openCloudList() {
        if (!isLoggedIn()) {
            promptLogin("从米家设备列表选择需要先登录米家账号。");
            return;
        }
        if (!cloudFetched) {
            toast("正在读取米家设备，请稍候…");
            prefetchCloudKeys();
            return;
        }
        if (cloudAll.isEmpty()) {
            toast("米家账号下未读取到设备");
            return;
        }
        CharSequence[] names = new CharSequence[cloudAll.size()];
        for (int i = 0; i < cloudAll.size(); i++) {
            MiDevice d = cloudAll.get(i);
            names[i] = nameOf(d) + "\n" + formatMac(d.mac)
                    + (d.hasBindKey() ? "  · 有密钥" : "  · 无密钥");
        }
        new AlertDialog.Builder(this)
                .setTitle("选择米家设备")
                .setItems(names, (dlg, which) -> {
                    MiDevice d = cloudAll.get(which);
                    pickProduct(adapter -> {
                        String key = d.hasBindKey() ? d.beaconKeyHex : null;
                        if (adapter.credentialSpec() == ProductAdapter.CredentialSpec.BINDKEY16
                                && key == null) {
                            toast("该设备未读取到 BindKey，无法用于加密产品");
                            return;
                        }
                        askName(d.mac, adapter.productId(), nameOf(d), key);
                    });
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ---------------- ② 手动输入 ----------------

    private void openManualAdd() {
        pickProduct(adapter -> inputMac(adapter));
    }

    private void inputMac(ProductAdapter adapter) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        box.setPadding(pad, dp(8), pad, 0);

        TextView tip = new TextView(this);
        tip.setText("输入设备的蓝牙 MAC（12 位十六进制，可带冒号）。");
        tip.setTextColor(getColor(R.color.sub));
        tip.setTextSize(14);
        box.addView(tip);

        EditText et = new EditText(this);
        et.setHint("MAC，如 A1:B2:C3:D4:E5:F6");
        et.setInputType(InputType.TYPE_CLASS_TEXT);
        box.addView(et);

        new AlertDialog.Builder(this)
                .setTitle("输入设备 MAC")
                .setView(box)
                .setPositiveButton("下一步", (d, w) -> {
                    String mac = et.getText().toString().replace(":", "").replace("-", "")
                            .trim().toUpperCase();
                    if (!mac.matches("[0-9A-F]{12}")) {
                        toast("MAC 需为 12 位十六进制");
                        return;
                    }
                    resolveKeyThenName(mac, adapter, adapter.displayName());
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ---------------- ③ 扫描列表 ----------------

    private void onFrame(ScanFrame frame) {
        ProductAdapter adapter = registry.identify(frame);
        boolean fe95 = frame.hasServiceData("fe95");
        if (adapter == null && !fe95) {
            return; // 非已登记产品、又非米家 FE95 广播，忽略
        }
        String mac = frame.mac.toUpperCase();
        if (bound.containsKey(mac) || rows.containsKey(mac)) {
            return;
        }
        main.post(() -> addRow(frame, adapter));
    }

    private void addRow(ScanFrame frame, ProductAdapter adapter) {
        if (rows.containsKey(frame.mac.toUpperCase())) {
            return;
        }
        emptyText.setVisibility(View.GONE);
        boolean known = adapter != null;
        View row = LayoutInflater.from(this).inflate(R.layout.item_scan, list, false);
        ImageView icon = row.findViewById(R.id.scan_icon);
        icon.setImageResource(known ? IconPicker.iconRes(adapter.productId()) : R.drawable.ic_button);
        icon.setColorFilter(IconPicker.tint(frame.mac), PorterDuff.Mode.SRC_IN);

        String title = (frame.name != null && !frame.name.isEmpty())
                ? frame.name : (known ? adapter.displayName() : "未知设备");
        ((TextView) row.findViewById(R.id.scan_title)).setText(title);
        String fmtMac = formatMac(frame.mac);
        String sub;
        if (known) {
            boolean needKey = adapter.credentialSpec() == ProductAdapter.CredentialSpec.BINDKEY16;
            sub = fmtMac + " · " + adapter.displayName() + (needKey ? " · 需密钥" : "");
        } else {
            sub = fmtMac + " · 未知米家设备（FE95）";
        }
        ((TextView) row.findViewById(R.id.scan_sub)).setText(sub);

        row.findViewById(R.id.scan_add).setOnClickListener(v -> {
            if (known) {
                resolveKeyThenName(frame.mac, adapter, title);
            } else {
                pickProduct(a -> resolveKeyThenName(frame.mac, a, title));
            }
        });
        rows.put(frame.mac.toUpperCase(), row);
        list.addView(row);
    }

    // ---------------- 公共流程：选产品 → 取密钥 → 命名 ----------------

    private void pickProduct(Consumer<ProductAdapter> onPicked) {
        List<ProductAdapter> all = registry.all();
        if (all.isEmpty()) {
            toast("无可用产品配置，请先在共创广场导入");
            return;
        }
        CharSequence[] names = new CharSequence[all.size()];
        for (int i = 0; i < all.size(); i++) {
            ProductAdapter a = all.get(i);
            boolean needKey = a.credentialSpec() == ProductAdapter.CredentialSpec.BINDKEY16;
            names[i] = a.displayName() + (needKey ? "  · 需密钥" : "  · 明文");
        }
        new AlertDialog.Builder(this)
                .setTitle("选择设备型号")
                .setItems(names, (d, w) -> onPicked.accept(all.get(w)))
                .setNegativeButton("取消", null)
                .show();
    }

    private void resolveKeyThenName(String mac, ProductAdapter adapter, String suggestName) {
        if (adapter.credentialSpec() == ProductAdapter.CredentialSpec.BINDKEY16) {
            String key = cloudKeys.get(mac.toUpperCase());
            if (key != null && key.length() == 32) {
                askName(mac, adapter.productId(), suggestName, key);
            } else {
                chooseKeySource(mac, adapter, suggestName);
            }
        } else {
            askName(mac, adapter.productId(), suggestName, null);
        }
    }

    /** 自动匹配不到 BindKey 时，让用户选择获取方式（从米家列表选 / 手动输入 / 去登录）。 */
    private void chooseKeySource(String mac, ProductAdapter adapter, String suggestName) {
        boolean loggedIn = isLoggedIn();
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();

        if (loggedIn && !cloudDevices.isEmpty()) {
            labels.add("从米家设备列表选择（含密钥 " + cloudDevices.size() + " 个）");
            actions.add(() -> chooseFromCloud(mac, adapter, suggestName));
        }
        labels.add("手动输入 BindKey");
        actions.add(() -> inputBindKey(mac, adapter, suggestName));
        if (!loggedIn) {
            labels.add("去登录米家自动获取");
            actions.add(() -> startActivity(new Intent(this, AccountActivity.class)));
        }

        String msg = loggedIn
                ? "未能按 MAC 自动匹配到此设备的 BindKey（蓝牙广播地址可能与米家记录不一致）。"
                : "该产品为加密类型，需要 BindKey。";

        new AlertDialog.Builder(this)
                .setTitle("获取 BindKey")
                .setMessage(msg)
                .setItems(labels.toArray(new CharSequence[0]),
                        (d, which) -> actions.get(which).run())
                .setNegativeButton("取消", null)
                .show();
    }

    /** 列出米家全部含 BindKey 的设备，由用户手动指认对应哪台（绕开 MAC 不一致），密钥配给真实广播 MAC。 */
    private void chooseFromCloud(String mac, ProductAdapter adapter, String suggestName) {
        CharSequence[] names = new CharSequence[cloudDevices.size()];
        for (int i = 0; i < cloudDevices.size(); i++) {
            MiDevice d = cloudDevices.get(i);
            names[i] = nameOf(d) + "  ·  " + formatMac(d.mac);
        }
        new AlertDialog.Builder(this)
                .setTitle("选择对应的米家设备")
                .setItems(names, (dlg, which) ->
                        askName(mac, adapter.productId(), suggestName,
                                cloudDevices.get(which).beaconKeyHex))
                .setNegativeButton("返回", null)
                .show();
    }

    private void inputBindKey(String mac, ProductAdapter adapter, String suggestName) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        box.setPadding(pad, dp(8), pad, 0);

        TextView tip = new TextView(this);
        tip.setText("输入该设备的 32 位十六进制 BindKey。");
        tip.setTextColor(getColor(R.color.sub));
        tip.setTextSize(14);
        box.addView(tip);

        EditText et = new EditText(this);
        et.setHint("BindKey（32 hex）");
        et.setInputType(InputType.TYPE_CLASS_TEXT);
        et.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(32)});
        box.addView(et);

        new AlertDialog.Builder(this)
                .setTitle("输入 BindKey")
                .setView(box)
                .setPositiveButton("下一步", (d, w) -> {
                    String key = et.getText().toString().trim().toUpperCase();
                    if (!key.matches("[0-9A-F]{32}")) {
                        toast("BindKey 需为 32 位十六进制");
                        return;
                    }
                    askName(mac, adapter.productId(), suggestName, key);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void askName(String mac, String productId, String suggestName, String bindKeyHex) {
        EditText et = new EditText(this);
        et.setText(suggestName);
        et.setSelectAllOnFocus(true);
        new AlertDialog.Builder(this)
                .setTitle("给设备命名")
                .setView(et)
                .setPositiveButton("保存", (d, w) -> {
                    String name = et.getText().toString().trim();
                    if (name.isEmpty()) {
                        name = suggestName;
                    }
                    BoundDevice dev = new BoundDevice(mac.toUpperCase(), productId, name, bindKeyHex);
                    ruleStore.upsert(dev);
                    ButtonService.reload(this);
                    toast("已添加：" + name);
                    finish();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void promptLogin(String msg) {
        new AlertDialog.Builder(this)
                .setTitle("未登录米家")
                .setMessage(msg)
                .setPositiveButton("去登录", (d, w) ->
                        startActivity(new Intent(this, AccountActivity.class)))
                .setNegativeButton("取消", null)
                .show();
    }

    private static String nameOf(MiDevice d) {
        if (d.name != null && !d.name.isEmpty()) {
            return d.name;
        }
        return (d.model == null || d.model.isEmpty()) ? "未命名设备" : d.model;
    }

    private static String formatMac(String mac) {
        if (mac == null || mac.length() != 12) {
            return mac;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 12; i += 2) {
            if (i > 0) {
                sb.append(':');
            }
            sb.append(mac, i, i + 2);
        }
        return sb.toString();
    }

    private int dp(int v) {
        return (int) (getResources().getDisplayMetrics().density * v);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }
}
