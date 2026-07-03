package com.selfclink.ble.ui;

import android.content.Intent;
import android.graphics.PorterDuff;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.selfclink.ble.R;
import com.selfclink.ble.automation.ActionExecutor;
import com.selfclink.ble.automation.BoundDevice;
import com.selfclink.ble.automation.CustomAction;
import com.selfclink.ble.automation.RuleStore;
import com.selfclink.ble.product.Gesture;
import com.selfclink.ble.product.ProductAdapter;
import com.selfclink.ble.product.ProductRegistry;
import com.selfclink.ble.service.ButtonService;

import java.util.ArrayList;
import java.util.List;

/**
 * 按键编排页：可改设备名，并为该产品的每个手势（单击/双击/长按等）分别绑定一组车控/系统动作。
 */
public final class DeviceEditorActivity extends BackBarActivity {

    public static final String EXTRA_MAC = "mac";

    /** 通用自学习载体的 productId（其 Profile 只有占位手势，不并入显示）。 */
    private static final String GENERIC_CARRIER_ID = "generic.mibeacon";

    private RuleStore ruleStore;
    private ActionExecutor executor;
    private BoundDevice device;
    private List<Gesture> profileGestures = new ArrayList<>();
    private List<Gesture> gestures = new ArrayList<>();
    private boolean created;

    private LinearLayout container;
    private EditText nameEdit;

    /** 当前正在编辑的手势 id（用于接收动作选择结果）。 */
    private String editingGestureId;

    private final ActivityResultLauncher<Intent> pickAction =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() != RESULT_OK || result.getData() == null) {
                    return;
                }
                String[] keys = result.getData().getStringArrayExtra(ActionPickerActivity.EXTRA_KEYS);
                if (editingGestureId != null && keys != null) {
                    List<String> list = new ArrayList<>();
                    for (String k : keys) {
                        list.add(k);
                    }
                    device.gestureActions.put(editingGestureId, list);
                    renderGestures();
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_device_editor);
        container = findViewById(R.id.gesture_container);
        nameEdit = findViewById(R.id.edit_name);

        ruleStore = new RuleStore(this);
        executor = new ActionExecutor(this);
        String mac = getIntent().getStringExtra(EXTRA_MAC);
        device = ruleStore.byMac(mac);
        if (device == null) {
            Toast.makeText(this, "设备不存在", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        ImageView icon = findViewById(R.id.edit_icon);
        icon.setImageResource(IconPicker.iconRes(device.productId));
        icon.setColorFilter(IconPicker.tint(device.mac), PorterDuff.Mode.SRC_IN);
        nameEdit.setText(device.name);
        String macLine = formatMac(device.mac);
        int batt = com.selfclink.ble.ble.Sightings.battery(device.mac);
        if (batt >= 0) {
            macLine += "   ·   电量 " + batt + "%";
        }
        ((TextView) findViewById(R.id.mac_text)).setText(macLine);

        ProductAdapter adapter = new ProductRegistry(this).byProductId(device.productId);
        if (adapter != null) {
            profileGestures = adapter.gestures();
        }
        refreshGestures();

        findViewById(R.id.btn_learn).setOnClickListener(v -> {
            Intent i = new Intent(this, LearnActivity.class);
            i.putExtra(LearnActivity.EXTRA_MAC, device.mac);
            startActivity(i);
        });
        findViewById(R.id.btn_save).setOnClickListener(v -> save());
        created = true;
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!created) {
            return;
        }
        // 从自学习页返回：同步最新已学事件（不覆盖名称/绑定的内存改动）。
        BoundDevice fresh = ruleStore.byMac(device.mac);
        if (fresh != null) {
            device.learned.clear();
            device.learned.addAll(fresh.learned);
        }
        refreshGestures();
    }

    /**
     * Profile 内置手势 + 自学习事件并存显示。通用载体（generic.mibeacon）自身只有占位手势，
     * 不并入，避免出现永不触发的占位行。
     */
    private void refreshGestures() {
        gestures = new ArrayList<>();
        if (!GENERIC_CARRIER_ID.equals(device.productId)) {
            gestures.addAll(profileGestures);
        }
        for (com.selfclink.ble.automation.LearnedEvent e : device.learned) {
            gestures.add(new Gesture(e.id, e.label));
        }
        renderGestures();
    }

    private void renderGestures() {
        container.removeAllViews();
        LayoutInflater inf = LayoutInflater.from(this);
        if (gestures.isEmpty()) {
            TextView t = new TextView(this);
            t.setText("该产品未声明手势");
            t.setTextColor(getColor(R.color.sub));
            container.addView(t);
            return;
        }
        for (Gesture g : gestures) {
            View row = inf.inflate(R.layout.item_gesture_row, container, false);
            ((TextView) row.findViewById(R.id.g_title)).setText(g.name);
            TextView actionsText = row.findViewById(R.id.g_actions);
            List<String> keys = device.actionsFor(g.id);
            actionsText.setText(describe(keys));
            actionsText.setTextColor(getColor(keys.isEmpty() ? R.color.sub : R.color.accent2));
            View.OnClickListener open = v -> openPicker(g);
            row.setOnClickListener(open);
            row.findViewById(R.id.g_edit).setOnClickListener(open);
            row.findViewById(R.id.g_test).setOnClickListener(v -> testGesture(g));
            container.addView(row);
        }
    }

    private void openPicker(Gesture g) {
        editingGestureId = g.id;
        Intent i = new Intent(this, ActionPickerActivity.class);
        i.putExtra(ActionPickerActivity.EXTRA_TITLE, g.name);
        i.putExtra(ActionPickerActivity.EXTRA_KEYS,
                device.actionsFor(g.id).toArray(new String[0]));
        pickAction.launch(i);
    }

    private void testGesture(Gesture g) {
        List<String> keys = device.actionsFor(g.id);
        if (keys.isEmpty()) {
            Toast.makeText(this, "该手势未绑定动作", Toast.LENGTH_SHORT).show();
            return;
        }
        executor.executeAll(keys);
        Toast.makeText(this, "已执行 " + g.name, Toast.LENGTH_SHORT).show();
    }

    private String describe(List<String> keys) {
        if (keys.isEmpty()) {
            return "未绑定";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < keys.size(); i++) {
            if (i > 0) {
                sb.append(" · ");
            }
            sb.append(CustomAction.label(this, keys.get(i)));
        }
        return sb.toString();
    }

    private void save() {
        String name = nameEdit.getText().toString().trim();
        if (!name.isEmpty()) {
            device.name = name;
        }
        ruleStore.upsert(device);
        ButtonService.reload(this);
        Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show();
        finish();
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
}
