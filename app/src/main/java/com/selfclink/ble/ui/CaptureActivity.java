package com.selfclink.ble.ui;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.ScrollView;
import android.widget.TextView;

import com.selfclink.ble.R;
import com.selfclink.ble.automation.BoundDevice;
import com.selfclink.ble.automation.RuleStore;
import com.selfclink.ble.ble.BleScanner;
import com.selfclink.ble.product.ScanFrame;
import com.selfclink.ble.protocol.MiBeacon;
import com.selfclink.ble.util.HexUtil;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 抓包助手：被动扫描，实时打印附近 FE95 广播原始字节；对已录入 BindKey 的设备当场解密出
 * objId + value，用于挖旋钮左右旋 / 长按连发等尚无内置映射的事件编码。
 *
 * <p>纯只读诊断页，不改任何绑定；每台设备的 fe95 帧内容变化才追加一行（避免刷屏）。
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

    private BleScanner scanner;
    private TextView logText;
    private ScrollView scroll;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_capture);
        logText = findViewById(R.id.log_text);
        scroll = findViewById(R.id.log_scroll);
        findViewById(R.id.btn_clear).setOnClickListener(v -> {
            lines.clear();
            lastHex.clear();
            logText.setText("已清空，正在监听…");
        });

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
                line.append(String.format(Locale.US, "\n  → objId=0x%04X value=%s",
                        r.objId, r.value == null ? "(无)" : HexUtil.toHex(r.value)));
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
}
