package com.selfclink.ble.ui;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import java.util.LinkedHashMap;

import com.selfclink.ble.R;
import com.selfclink.ble.automation.BoundDevice;
import com.selfclink.ble.automation.LearnedEvent;
import com.selfclink.ble.automation.RuleStore;
import com.selfclink.ble.ble.BleScanner;
import com.selfclink.ble.product.ProductAdapter;
import com.selfclink.ble.product.ProductRegistry;
import com.selfclink.ble.product.ScanFrame;
import com.selfclink.ble.protocol.MiBeacon;
import com.selfclink.ble.service.ButtonService;
import com.selfclink.ble.util.HexUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * 自学习接入：对同一动作连续操作 N 次，App 解密 MiBeacon、多帧求稳定字节自动生成 objId + value 掩码，
 * 即可分辨单击/双击/长按/左旋/右旋等——无需预置 Profile，现场教一遍即用。
 *
 * <p>仅支持加密 MiBeacon 设备（需 BindKey）；捕获逻辑纯只读，学到的事件存到该设备的 {@link BoundDevice#learned}。
 */
public final class LearnActivity extends BackBarActivity {

    public static final String EXTRA_MAC = "mac";
    private static final int NEEDED = 3;

    private final Handler main = new Handler(Looper.getMainLooper());

    private RuleStore ruleStore;
    private BoundDevice device;
    private byte[] bindKey;
    private BleScanner scanner;

    private LinearLayout listView;
    private TextView emptyView;

    // ---- 捕获状态 ----
    private boolean capturing;
    private int lastFcnt = -1;
    private final List<int[]> sampleObj = new ArrayList<>();   // 每个样本的 objId（用 int[]{objId} 占位）
    private final List<byte[]> sampleVal = new ArrayList<>();
    private AlertDialog captureDialog;
    private TextView captureText;

    // ---- 手动选择状态：实时列出解密到的不同编码 ----
    private boolean selecting;
    private final LinkedHashMap<String, int[]> pickObj = new LinkedHashMap<>();   // key=objId+value hex → {objId}
    private final LinkedHashMap<String, byte[]> pickVal = new LinkedHashMap<>();   // key → value
    private AlertDialog selectDialog;
    private LinearLayout pickList;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_learn);
        listView = findViewById(R.id.learn_list);
        emptyView = findViewById(R.id.learn_empty);

        ruleStore = new RuleStore(this);
        String mac = getIntent().getStringExtra(EXTRA_MAC);
        device = ruleStore.byMac(mac);
        if (device == null) {
            toast("设备不存在");
            finish();
            return;
        }
        ProductAdapter adapter = new ProductRegistry(this).byProductId(device.productId);
        boolean needKey = adapter != null
                && adapter.credentialSpec() == ProductAdapter.CredentialSpec.BINDKEY16;
        if (!needKey || device.bindKeyHex == null) {
            toast("自学习目前仅支持加密米家设备（需 BindKey）");
            finish();
            return;
        }
        bindKey = HexUtil.fromHex(device.bindKeyHex);

        ((TextView) findViewById(R.id.learn_title)).setText("自学习 · " + device.name);
        findViewById(R.id.btn_learn_new).setOnClickListener(v -> chooseMode());

        scanner = new BleScanner(this, this::onFrame);
        renderList();
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

    // ---------------- 入口三选一 ----------------

    private void chooseMode() {
        new AlertDialog.Builder(this)
                .setTitle("学习新动作")
                .setItems(new CharSequence[]{
                        "自动学习（连按 3 次）",
                        "手动选择编码（看列表点选）",
                        "手动填写编码（填 hex）"}, (d, which) -> {
                    if (which == 0) {
                        startCapture();
                    } else if (which == 1) {
                        startManualSelect();
                    } else {
                        manualEntry();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ---------------- 手动选择编码：实时列出编码点选 ----------------

    private void startManualSelect() {
        selecting = true;
        lastFcnt = -1;
        pickObj.clear();
        pickVal.clear();

        pickList = new LinearLayout(this);
        pickList.setOrientation(LinearLayout.VERTICAL);
        int p = dp(20);
        pickList.setPadding(p, dp(8), p, dp(8));
        refreshPickList();

        ScrollView sv = new ScrollView(this);
        sv.addView(pickList);

        selectDialog = new AlertDialog.Builder(this)
                .setTitle("操作你的按钮，点中对应编码")
                .setView(sv)
                .setNegativeButton("完成", (d, w) -> selecting = false)
                .setOnCancelListener(d -> selecting = false)
                .create();
        selectDialog.show();
    }

    private void refreshPickList() {
        if (pickList == null) {
            return;
        }
        pickList.removeAllViews();
        if (pickObj.isEmpty()) {
            TextView tv = new TextView(this);
            tv.setText("等待按键广播…\n请单击 / 双击 / 长按你的按钮，\n每种不同编码会实时出现在下面。");
            tv.setTextColor(getColor(R.color.sub));
            tv.setTextSize(14);
            pickList.addView(tv);
            return;
        }
        for (String key : pickObj.keySet()) {
            int objId = pickObj.get(key)[0];
            byte[] val = pickVal.get(key);
            TextView row = new TextView(this);
            row.setText(String.format(java.util.Locale.US, "objId=0x%04X   value=%s",
                    objId, val.length == 0 ? "(无)" : HexUtil.toHex(val)));
            row.setTextColor(getColor(R.color.txt));
            row.setTextSize(16);
            row.setBackgroundResource(R.drawable.card_bg);
            int q = dp(16);
            row.setPadding(q, q, q, q);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(10);
            row.setLayoutParams(lp);
            row.setOnClickListener(v -> {
                selecting = false;
                if (selectDialog != null) {
                    selectDialog.dismiss();
                }
                onPicked(objId, val);
            });
            pickList.addView(row);
        }
    }

    /** 点中一条编码 → 选匹配方式 + 命名保存。 */
    private void onPicked(int objId, byte[] value) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = dp(20);
        box.setPadding(p, dp(8), p, 0);

        CheckBox exact = new CheckBox(this);
        exact.setText("精确匹配 value（区分同 objId 的双击/长按）");
        exact.setChecked(value.length > 0);
        box.addView(exact);

        EditText et = new EditText(this);
        et.setHint("命名，如 单击 / 双击 / 长按");
        et.setPadding(0, dp(12), 0, dp(12));
        box.addView(et);

        new AlertDialog.Builder(this)
                .setTitle(String.format(java.util.Locale.US, "objId=0x%04X value=%s",
                        objId, value.length == 0 ? "(无)" : HexUtil.toHex(value)))
                .setView(box)
                .setPositiveButton("保存", (d, w) -> {
                    String label = et.getText().toString().trim();
                    if (label.isEmpty()) {
                        label = "动作" + (device.learned.size() + 1);
                    }
                    saveMasked(label, objId, value, exact.isChecked());
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ---------------- 手动填写编码：直接填 hex ----------------

    private void manualEntry() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = dp(20);
        box.setPadding(p, dp(8), p, 0);

        final EditText objEt = new EditText(this);
        objEt.setHint("objId (hex)，如 1001");
        objEt.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        box.addView(objEt);

        final EditText valEt = new EditText(this);
        valEt.setHint("value (hex)，可留空，如 0100");
        valEt.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        box.addView(valEt);

        final CheckBox exact = new CheckBox(this);
        exact.setText("精确匹配 value（区分双击/长按）");
        exact.setChecked(true);
        box.addView(exact);

        final EditText nameEt = new EditText(this);
        nameEt.setHint("命名，如 长按");
        box.addView(nameEt);

        new AlertDialog.Builder(this)
                .setTitle("手动填写编码")
                .setView(box)
                .setPositiveButton("保存", (d, w) -> {
                    Integer objId = parseHexInt(objEt.getText().toString().trim());
                    if (objId == null) {
                        toast("objId 填写有误（十六进制，如 1001）");
                        return;
                    }
                    byte[] value = HexUtil.fromHex(valEt.getText().toString().trim().replaceAll("\\s", ""));
                    String label = nameEt.getText().toString().trim();
                    if (label.isEmpty()) {
                        label = "动作" + (device.learned.size() + 1);
                    }
                    saveMasked(label, objId, value == null ? new byte[0] : value,
                            exact.isChecked() && value != null && value.length > 0);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private static Integer parseHexInt(String s) {
        if (s == null || s.isEmpty()) {
            return null;
        }
        try {
            return Integer.parseInt(s.replaceFirst("(?i)^0x", ""), 16);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 由 objId+value+是否精确 生成掩码并做冲突校验后保存。 */
    private void saveMasked(String label, int objId, byte[] value, boolean exactValue) {
        byte[] mask;
        byte[] expected;
        if (exactValue && value.length > 0) {
            mask = new byte[value.length];
            java.util.Arrays.fill(mask, (byte) 0xFF);
            expected = value.clone();
        } else {
            mask = new byte[0];
            expected = new byte[0];
        }
        LearnedEvent candidate = new LearnedEvent("tmp", label, objId, mask, expected);
        for (LearnedEvent e : device.learned) {
            if (e.matches(objId, value) || candidate.matches(e.objId, sampleValFor(e))) {
                toast("与已有动作『" + e.label + "』无法区分；换一条或勾选精确匹配再试");
                return;
            }
        }
        saveEvent(label, objId, mask, expected);
    }

    // ---------------- 自动学习 ----------------

    private void startCapture() {
        sampleObj.clear();
        sampleVal.clear();
        lastFcnt = -1;
        capturing = true;

        captureText = new TextView(this);
        captureText.setText("请对同一个动作连续操作 " + NEEDED + " 次…\n\n已捕获 0/" + NEEDED);
        captureText.setTextColor(getColor(R.color.txt));
        captureText.setTextSize(16);
        int p = dp(24);
        captureText.setPadding(p, dp(16), p, dp(8));

        captureDialog = new AlertDialog.Builder(this)
                .setTitle("学习新动作")
                .setView(captureText)
                .setNegativeButton("取消", (d, w) -> capturing = false)
                .setOnCancelListener(d -> capturing = false)
                .create();
        captureDialog.show();
    }

    private void onFrame(ScanFrame frame) {
        if ((!capturing && !selecting) || device == null || !frame.mac.equalsIgnoreCase(device.mac)) {
            return;
        }
        byte[] fe95 = frame.serviceData("fe95");
        if (fe95 == null) {
            return;
        }
        MiBeacon.Result r = MiBeacon.parse(fe95, bindKey);
        if (r == null) {
            return;
        }
        if (r.frameCounter == lastFcnt) {
            return; // 同一次操作的重复帧
        }
        lastFcnt = r.frameCounter;
        final int objId = r.objId;
        final byte[] val = r.value == null ? new byte[0] : r.value.clone();
        if (capturing) {
            main.post(() -> addSample(objId, val));
        } else {
            main.post(() -> addPick(objId, val));
        }
    }

    /** 手动选择模式：把新出现的不同编码加入实时列表（同 objId+value 去重）。 */
    private void addPick(int objId, byte[] val) {
        if (!selecting) {
            return;
        }
        String key = String.format(java.util.Locale.US, "%04X:%s", objId, HexUtil.toHex(val));
        if (pickObj.containsKey(key)) {
            return;
        }
        pickObj.put(key, new int[]{objId});
        pickVal.put(key, val);
        refreshPickList();
    }

    private void addSample(int objId, byte[] val) {
        if (!capturing) {
            return;
        }
        sampleObj.add(new int[]{objId});
        sampleVal.add(val);
        if (captureText != null) {
            captureText.setText("请对同一个动作连续操作 " + NEEDED + " 次…\n\n已捕获 "
                    + sampleObj.size() + "/" + NEEDED);
        }
        if (sampleObj.size() >= NEEDED) {
            capturing = false;
            if (captureDialog != null) {
                captureDialog.dismiss();
            }
            finalizeCapture();
        }
    }

    private void finalizeCapture() {
        int objId = sampleObj.get(0)[0];
        for (int[] o : sampleObj) {
            if (o[0] != objId) {
                toast("采样不一致（多个不同事件），请对同一动作重复 " + NEEDED + " 次");
                return;
            }
        }
        // 多帧求稳定字节：同位全等→判别位(0xFF)，有变化→忽略(0x00)。
        int len = sampleVal.get(0).length;
        for (byte[] v : sampleVal) {
            len = Math.min(len, v.length);
        }
        byte[] mask = new byte[len];
        byte[] expected = new byte[len];
        byte[] first = sampleVal.get(0);
        for (int i = 0; i < len; i++) {
            boolean constant = true;
            for (byte[] v : sampleVal) {
                if (v[i] != first[i]) {
                    constant = false;
                    break;
                }
            }
            mask[i] = constant ? (byte) 0xFF : 0x00;
            expected[i] = constant ? first[i] : 0x00;
        }

        LearnedEvent candidate = new LearnedEvent("tmp", "tmp", objId, mask, expected);
        for (LearnedEvent e : device.learned) {
            if (e.matches(objId, first) || candidate.matches(e.objId, sampleValFor(e))) {
                toast("与已有动作『" + e.label + "』无法区分，未添加；换个动作试试");
                return;
            }
        }
        promptName(objId, mask, expected);
    }

    /** 用某已学事件的 expected 当作它的代表 value，供冲突反向检测。 */
    private static byte[] sampleValFor(LearnedEvent e) {
        return e.expected;
    }

    private void promptName(int objId, byte[] mask, byte[] expected) {
        EditText et = new EditText(this);
        et.setHint("如 左旋 / 右旋 / 单击 / 长按");
        int p = dp(20);
        et.setPadding(p, dp(12), p, dp(12));
        new AlertDialog.Builder(this)
                .setTitle("给这个动作命名")
                .setMessage(String.format(java.util.Locale.US, "已捕获 objId=0x%04X", objId))
                .setView(et)
                .setPositiveButton("保存", (d, w) -> {
                    String label = et.getText().toString().trim();
                    if (label.isEmpty()) {
                        label = "动作" + (device.learned.size() + 1);
                    }
                    saveEvent(label, objId, mask, expected);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void saveEvent(String label, int objId, byte[] mask, byte[] expected) {
        String id = "e" + (System.currentTimeMillis() % 100000);
        device.learned.add(new LearnedEvent(id, label, objId, mask, expected));
        ruleStore.upsert(device);
        ButtonService.reload(this);
        toast("已学：" + label);
        renderList();
    }

    // ---------------- 列表 ----------------

    private void renderList() {
        listView.removeAllViews();
        if (device.learned.isEmpty()) {
            listView.addView(emptyView);
            emptyView.setVisibility(View.VISIBLE);
            return;
        }
        for (LearnedEvent e : device.learned) {
            listView.addView(makeRow(e));
        }
    }

    private View makeRow(LearnedEvent e) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundResource(R.drawable.card_bg);
        int p = dp(20);
        row.setPadding(p, p, p, p);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rlp.bottomMargin = dp(12);
        row.setLayoutParams(rlp);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView title = new TextView(this);
        title.setText(e.label);
        title.setTextColor(getColor(R.color.txt));
        title.setTextSize(18);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        col.addView(title);

        TextView sub = new TextView(this);
        sub.setText(e.summary());
        sub.setTextColor(getColor(R.color.sub));
        sub.setTextSize(13);
        sub.setPadding(0, dp(6), 0, 0);
        col.addView(sub);
        row.addView(col);

        TextView del = new TextView(this);
        del.setText("删除");
        del.setTextColor(getColor(R.color.warn));
        del.setTextSize(16);
        del.setBackgroundResource(R.drawable.btn_ghost);
        del.setPadding(dp(18), dp(12), dp(18), dp(12));
        del.setOnClickListener(v -> confirmDelete(e));
        row.addView(del);
        return row;
    }

    private void confirmDelete(LearnedEvent e) {
        new AlertDialog.Builder(this)
                .setTitle("删除动作")
                .setMessage("删除「" + e.label + "」？其已绑定的动作也会一并移除。")
                .setPositiveButton("删除", (d, w) -> {
                    device.learned.remove(e);
                    device.gestureActions.remove(e.id);
                    ruleStore.upsert(device);
                    ButtonService.reload(this);
                    renderList();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private int dp(int v) {
        return (int) (getResources().getDisplayMetrics().density * v);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
