package com.selfclink.ble.ui;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.selfclink.ble.R;
import com.selfclink.ble.automation.BoundDevice;
import com.selfclink.ble.automation.LearnedEvent;
import com.selfclink.ble.automation.RuleStore;
import com.selfclink.ble.ble.BleScanner;
import com.selfclink.ble.product.ScanFrame;
import com.selfclink.ble.protocol.MiBeacon;
import com.selfclink.ble.service.ButtonService;
import com.selfclink.ble.util.HexUtil;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 抓包助手：被动扫描，实时打印附近 FE95 广播原始字节；对已录入 BindKey 的设备当场解密出
 * objId + value，用于挖旋钮左右旋 / 长按连发等尚无内置映射的事件编码。
 *
 * <p>解密成功的事件会记入「可导入」清单，点「导入动作」即可把某条 objId/value 存成该设备的
 * {@link LearnedEvent}，回编排页当手势绑定车控。
 */
public final class CaptureActivity extends BackBarActivity {

    private static final int MAX_LINES = 200;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final SimpleDateFormat time = new SimpleDateFormat("HH:mm:ss", Locale.US);
    private final Deque<String> lines = new ArrayDeque<>();
    /** mac → 上次打印的 fe95 hex，用于去重（同帧不重复刷）。 */
    private final Map<String, String> lastHex = new HashMap<>();
    /** mac → BindKey（16 字节），来自已录入设备。 */
    private final Map<String, byte[]> keys = new HashMap<>();
    /** 去重后的可导入事件：key=mac|objId|valueHex，保持出现顺序。 */
    private final Map<String, Captured> captured = new LinkedHashMap<>();

    private BleScanner scanner;
    private TextView logText;
    private ScrollView scroll;

    /** 一条解密成功、可导入为动作的抓包事件。 */
    private static final class Captured {
        final String mac;
        final int objId;
        final byte[] value;

        Captured(String mac, int objId, byte[] value) {
            this.mac = mac;
            this.objId = objId;
            this.value = value;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_capture);
        logText = findViewById(R.id.log_text);
        scroll = findViewById(R.id.log_scroll);
        findViewById(R.id.btn_clear).setOnClickListener(v -> {
            lines.clear();
            lastHex.clear();
            captured.clear();
            logText.setText("已清空，正在监听…");
        });
        findViewById(R.id.btn_import).setOnClickListener(v -> pickToImport());

        loadKeys();
        scanner = new BleScanner(this, this::onFrame);
    }

    private void loadKeys() {
        keys.clear();
        for (BoundDevice d : new RuleStore(this).load()) {
            if (d.mac != null && d.bindKeyHex != null) {
                try {
                    keys.put(d.mac.toUpperCase(), HexUtil.fromHex(d.bindKeyHex));
                } catch (Exception ignored) {
                }
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (scanner != null) {
            scanner.startScan();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (scanner != null) {
            scanner.stopScan();
        }
    }

    /** 扫描线程回调，切主线程更新 UI。 */
    private void onFrame(ScanFrame frame) {
        byte[] fe95 = frame.serviceData("fe95");
        if (fe95 == null) {
            return;
        }
        String hex = HexUtil.toHex(fe95);
        String prev = lastHex.get(frame.mac);
        if (hex.equals(prev)) {
            return; // 同一帧，忽略
        }
        lastHex.put(frame.mac, hex);

        StringBuilder line = new StringBuilder();
        line.append(time.format(new java.util.Date()))
                .append(' ').append(frame.mac)
                .append(" rssi=").append(frame.rssi)
                .append("\n  fe95=").append(hex);

        byte[] key = keys.get(frame.mac.toUpperCase());
        if (key != null) {
            MiBeacon.Result r = MiBeacon.parse(fe95, key);
            if (r != null) {
                byte[] val = r.value == null ? new byte[0] : r.value.clone();
                line.append(String.format(Locale.US, "\n  → objId=0x%04X value=%s",
                        r.objId, val.length == 0 ? "(无)" : HexUtil.toHex(val)));
                final String mac = frame.mac;
                final int objId = r.objId;
                main.post(() -> record(mac, objId, val));
            } else {
                line.append("\n  → 待机/非事件帧或解密失败");
            }
        }
        final String text = line.toString();
        main.post(() -> append(text));
    }

    private void append(String text) {
        lines.addLast(text);
        while (lines.size() > MAX_LINES) {
            lines.removeFirst();
        }
        StringBuilder sb = new StringBuilder();
        for (String l : lines) {
            sb.append(l).append('\n');
        }
        logText.setText(sb.toString());
        scroll.post(() -> scroll.fullScroll(ScrollView.FOCUS_DOWN));
    }

    /** 记录一条解密成功的事件，按 mac+objId+value 去重，供导入选择。 */
    private void record(String mac, int objId, byte[] value) {
        String k = mac.toUpperCase() + '|' + objId + '|' + HexUtil.toHex(value);
        if (!captured.containsKey(k)) {
            captured.put(k, new Captured(mac, objId, value));
        }
    }

    // ---------------- 导入为动作 ----------------

    private void pickToImport() {
        if (captured.isEmpty()) {
            toast("还没抓到可导入的事件；先转动/按一下按键");
            return;
        }
        RuleStore store = new RuleStore(this);
        final List<Captured> items = new ArrayList<>(captured.values());
        String[] labels = new String[items.size()];
        for (int i = 0; i < items.size(); i++) {
            Captured c = items.get(i);
            BoundDevice d = store.byMac(c.mac);
            String name = d != null ? d.name : c.mac;
            labels[i] = String.format(Locale.US, "%s\nobjId=0x%04X  value=%s",
                    name, c.objId, c.value.length == 0 ? "(无)" : HexUtil.toHex(c.value));
        }
        new AlertDialog.Builder(this)
                .setTitle("选择要导入的事件")
                .setItems(labels, (d, which) -> promptImport(items.get(which)))
                .setNegativeButton("取消", null)
                .show();
    }

    private void promptImport(Captured c) {
        RuleStore store = new RuleStore(this);
        BoundDevice device = store.byMac(c.mac);
        if (device == null) {
            toast("该设备未绑定，无法导入");
            return;
        }

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = dp(20);
        box.setPadding(p, dp(8), p, 0);

        EditText et = new EditText(this);
        et.setHint("如 单击 / 左旋 / 右旋 / 长按");
        box.addView(et);

        CheckBox exact = new CheckBox(this);
        exact.setText("精确匹配 value（区分左右旋等）");
        exact.setTextColor(getColor(R.color.sub));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        clp.topMargin = dp(12);
        exact.setLayoutParams(clp);
        box.addView(exact);

        new AlertDialog.Builder(this)
                .setTitle("导入到「" + device.name + "」")
                .setMessage(String.format(Locale.US, "objId=0x%04X  value=%s",
                        c.objId, c.value.length == 0 ? "(无)" : HexUtil.toHex(c.value)))
                .setView(box)
                .setPositiveButton("导入", (d, w) -> {
                    String label = et.getText().toString().trim();
                    if (label.isEmpty()) {
                        label = "动作" + (device.learned.size() + 1);
                    }
                    saveImported(device, c, label, exact.isChecked());
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void saveImported(BoundDevice device, Captured c, String label, boolean exactValue) {
        byte[] mask;
        byte[] expected;
        if (exactValue && c.value.length > 0) {
            mask = new byte[c.value.length];
            java.util.Arrays.fill(mask, (byte) 0xFF);
            expected = c.value.clone();
        } else {
            mask = new byte[0];
            expected = new byte[0];
        }

        LearnedEvent candidate = new LearnedEvent("tmp", label, c.objId, mask, expected);
        for (LearnedEvent e : device.learned) {
            if (e.matches(c.objId, c.value) || candidate.matches(e.objId, e.expected)) {
                toast("与已有动作『" + e.label + "』无法区分；换个动作或勾选精确匹配再试");
                return;
            }
        }

        String id = "e" + (System.currentTimeMillis() % 100000);
        device.learned.add(new LearnedEvent(id, label, c.objId, mask, expected));
        new RuleStore(this).upsert(device);
        ButtonService.reload(this);
        toast("已导入：" + label + " → 回编排页即可绑定动作");
    }

    private int dp(int v) {
        return (int) (getResources().getDisplayMetrics().density * v);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
