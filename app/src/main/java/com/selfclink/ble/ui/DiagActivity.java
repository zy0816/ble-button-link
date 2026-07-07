package com.selfclink.ble.ui;

import android.os.Bundle;
import android.widget.ScrollView;
import android.widget.TextView;

import com.selfclink.ble.R;
import com.selfclink.ble.service.ButtonService;
import com.selfclink.ble.util.AppLog;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 按键诊断：打开诊断模式，实时展示<b>常驻服务</b>对每台已接入设备逐帧给出的裁决
 * （收到广播 → 解密 objId·value → 是否命中手势 → 是否绑定动作），用于定位「按键没反应」
 * 到底卡在哪一步：没广播、BindKey 错、还是广播码不在映射内。
 *
 * <p>只读诊断页；开启时 {@link AppLog#setDiagEnabled} 放行逐帧日志，离开即关闭以免常态刷屏。
 */
public final class DiagActivity extends BackBarActivity implements AppLog.Sink {

    private static final int MAX_LINES = 300;

    private final Deque<String> lines = new ArrayDeque<>();
    private TextView logText;
    private ScrollView scroll;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_diag);
        logText = findViewById(R.id.log_text);
        scroll = findViewById(R.id.log_scroll);
        findViewById(R.id.btn_clear).setOnClickListener(v -> {
            lines.clear();
            logText.setText("已清空，按一下你的按键…");
        });
        // 确保服务在跑（诊断需要它逐帧裁决）。
        ButtonService.start(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        AppLog.setDiagEnabled(true);
        AppLog.addSink(this);
    }

    @Override
    protected void onPause() {
        super.onPause();
        AppLog.removeSink(this);
        AppLog.setDiagEnabled(false);
    }

    @Override
    public void onLog(String line) {
        lines.addLast(line);
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
