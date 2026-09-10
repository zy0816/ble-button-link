package com.selfclink.ble.ui;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.gridlayout.widget.GridLayout;

import com.selfclink.ble.R;
import com.selfclink.ble.automation.ActionCatalog;
import com.selfclink.ble.automation.ActionDef;
import com.selfclink.ble.automation.ActionExecutor;
import com.selfclink.ble.automation.BoundDevice;
import com.selfclink.ble.automation.CustomAction;
import com.selfclink.ble.automation.LearnedEvent;
import com.selfclink.ble.automation.RuleStore;
import com.selfclink.ble.ble.Sightings;
import com.selfclink.ble.cloud.MiAccount;
import com.selfclink.ble.product.Gesture;
import com.selfclink.ble.product.ProductAdapter;
import com.selfclink.ble.product.ProductRegistry;
import com.selfclink.ble.service.ButtonService;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 主界面（iOS 分栏）：左侧设备列表 + 入口，右侧当前设备的手势编排。
 * 选中设备、绑定手势动作、学习、重命名/删除都在这一屏完成。
 */
public final class MainActivity extends AppCompatActivity {

    private static final int REQ_PERM = 1;
    private static final String GENERIC_CARRIER_ID = "generic.mibeacon";

    private RuleStore ruleStore;
    private ActionExecutor executor;

    private LinearLayout deviceGroup;
    private TextView detailTitle, detailSub, accountStatus;
    private GridLayout gestureGrid;

    private List<BoundDevice> devices = new ArrayList<>();
    private String selectedMac;
    private BoundDevice selected;
    private String editingGestureId;

    /** 动作 key → 分类颜色（图标块用）。 */
    private final Map<String, Integer> keyColor = new HashMap<>();

    private final ActivityResultLauncher<Intent> pickAction =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() != RESULT_OK || result.getData() == null || selected == null) {
                    return;
                }
                String[] keys = result.getData().getStringArrayExtra(ActionPickerActivity.EXTRA_KEYS);
                if (editingGestureId != null && keys != null) {
                    List<String> list = new ArrayList<>();
                    for (String k : keys) {
                        list.add(k);
                    }
                    selected.gestureActions.put(editingGestureId, list);
                    ruleStore.upsert(selected);
                    ButtonService.reload(this);
                    renderDetail();
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        ruleStore = new RuleStore(this);
        executor = new ActionExecutor(this);

        deviceGroup = findViewById(R.id.device_group);
        detailTitle = findViewById(R.id.detail_title);
        detailSub = findViewById(R.id.detail_sub);
        accountStatus = findViewById(R.id.account_status);
        gestureGrid = findViewById(R.id.gesture_grid);

        buildKeyColorMap();

        findViewById(R.id.account_row).setOnClickListener(v ->
                startActivity(new Intent(this, AccountActivity.class)));
        findViewById(R.id.settings_row).setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));
        findViewById(R.id.btn_learn).setOnClickListener(v -> openLearn());
        findViewById(R.id.btn_rename).setOnClickListener(v -> renameDevice());
        findViewById(R.id.btn_delete).setOnClickListener(v -> deleteDevice());

        ensurePermissions();
    }

    @Override
    protected void onResume() {
        super.onResume();
        ButtonService.start(this);
        reload();
    }

    private void reload() {
        devices = ruleStore.load();
        if (selectedMac == null || byMac(selectedMac) == null) {
            selectedMac = devices.isEmpty() ? null : devices.get(0).mac;
        }
        selected = selectedMac == null ? null : byMac(selectedMac);
        accountStatus.setText(MiAccount.get(this).isLoggedIn() ? "已登录" : "未登录");
        renderSidebar();
        renderDetail();
    }

    private BoundDevice byMac(String mac) {
        for (BoundDevice d : devices) {
            if (d.mac.equalsIgnoreCase(mac)) {
                return d;
            }
        }
        return null;
    }

    // ---------------- 侧栏设备列表 ----------------

    private void renderSidebar() {
        deviceGroup.removeAllViews();
        LayoutInflater inf = LayoutInflater.from(this);
        for (int i = 0; i < devices.size(); i++) {
            BoundDevice d = devices.get(i);
            View row = inf.inflate(R.layout.item_dev_row, deviceGroup, false);
            boolean online = Sightings.isOnline(d.mac);
            ((ImageView) row.findViewById(R.id.d_icon)).setImageResource(IconPicker.iconRes(d.productId));
            tint(row.findViewById(R.id.d_tile), IconPicker.tint(d.mac), 13);
            ((TextView) row.findViewById(R.id.d_name)).setText(d.name);
            int batt = Sightings.battery(d.mac);
            String st = online ? "在线" : "未发现";
            if (online && batt >= 0) {
                st += " · " + batt + "%";
            }
            ((TextView) row.findViewById(R.id.d_status)).setText(st);
            boolean sel = d.mac.equalsIgnoreCase(selectedMac);
            row.setBackgroundResource(sel ? R.drawable.row_sel : android.R.color.transparent);
            final String mac = d.mac;
            row.setOnClickListener(v -> {
                selectedMac = mac;
                selected = byMac(mac);
                renderSidebar();
                renderDetail();
            });
            deviceGroup.addView(row);
            if (i < devices.size() - 1) {
                deviceGroup.addView(separator());
            }
        }
        // 添加设备行
        if (!devices.isEmpty()) {
            deviceGroup.addView(separator());
        }
        View add = inf.inflate(R.layout.item_dev_row, deviceGroup, false);
        ((ImageView) add.findViewById(R.id.d_icon)).setImageResource(android.R.drawable.ic_input_add);
        tint(add.findViewById(R.id.d_tile), getColor(R.color.c_grey), 13);
        ((TextView) add.findViewById(R.id.d_name)).setText("添加设备");
        ((TextView) add.findViewById(R.id.d_status)).setVisibility(View.GONE);
        add.setOnClickListener(v -> startActivity(new Intent(this, AddDeviceActivity.class)));
        deviceGroup.addView(add);
    }

    private View separator() {
        View v = new View(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(0.5f)));
        lp.setMarginStart(dp(64));
        v.setLayoutParams(lp);
        v.setBackgroundColor(getColor(R.color.line));
        return v;
    }

    // ---------------- 右侧手势编排 ----------------

    private void renderDetail() {
        gestureGrid.removeAllViews();
        if (selected == null) {
            detailTitle.setText("蓝牙按键");
            detailSub.setText("从左侧添加你的第一台按键设备");
            setBarButtons(false);
            return;
        }
        setBarButtons(true);
        detailTitle.setText(selected.name);

        boolean online = Sightings.isOnline(selected.mac);
        int batt = Sightings.battery(selected.mac);
        List<Gesture> gestures = gesturesOf(selected);
        int bound = 0;
        for (Gesture g : gestures) {
            if (!selected.actionsFor(g.id).isEmpty()) {
                bound++;
            }
        }
        StringBuilder sub = new StringBuilder(online ? "● 在线" : "○ 未发现");
        if (online && batt >= 0) {
            sub.append(" · 电量 ").append(batt).append('%');
        }
        sub.append(" · ").append(bound).append(" 个绑定");
        detailSub.setText(sub);
        detailSub.setTextColor(getColor(online ? R.color.accent2 : R.color.sub));

        LayoutInflater inf = LayoutInflater.from(this);
        int cols = gestureGrid.getColumnCount();
        if (gestures.isEmpty()) {
            TextView t = new TextView(this);
            t.setText("该设备暂无可绑定手势，点右上「学习动作」教一个。");
            t.setTextColor(getColor(R.color.sub));
            t.setTextSize(16);
            gestureGrid.addView(t);
            return;
        }
        for (Gesture g : gestures) {
            View card = inf.inflate(R.layout.item_gesture_card, gestureGrid, false);
            bindGestureCard(card, g);
            gestureGrid.addView(card, cell(cols));
        }
    }

    private void bindGestureCard(View card, Gesture g) {
        ((TextView) card.findViewById(R.id.g_name)).setText(g.name);
        View bindRow = card.findViewById(R.id.g_bind);
        View tile = card.findViewById(R.id.g_tile);
        TextView action = card.findViewById(R.id.g_action);
        TextView cat = card.findViewById(R.id.g_cat);
        TextView chev = card.findViewById(R.id.g_chev);

        List<String> keys = selected.actionsFor(g.id);
        if (keys.isEmpty()) {
            tile.setVisibility(View.GONE);
            cat.setVisibility(View.GONE);
            chev.setText("＋");
            action.setText("绑定动作");
            action.setTextColor(getColor(R.color.accent));
        } else {
            tile.setVisibility(View.VISIBLE);
            tint(tile, colorForKeys(keys), 12);
            chev.setText("›");
            action.setTextColor(getColor(R.color.txt));
            action.setText(describe(keys));
            cat.setVisibility(View.GONE);
        }
        View.OnClickListener open = v -> openPicker(g);
        bindRow.setOnClickListener(open);
        card.setOnClickListener(open);
        card.setOnLongClickListener(v -> {
            testGesture(g);
            return true;
        });
    }

    private GridLayout.LayoutParams cell(int cols) {
        GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
        lp.width = 0;
        lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
        lp.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1, 1f);
        int m = dp(7);
        lp.setMargins(m, m, m, m);
        return lp;
    }

    private List<Gesture> gesturesOf(BoundDevice d) {
        List<Gesture> out = new ArrayList<>();
        ProductAdapter adapter = new ProductRegistry(this).byProductId(d.productId);
        if (adapter != null && !GENERIC_CARRIER_ID.equals(d.productId)) {
            out.addAll(adapter.gestures());
        }
        for (LearnedEvent e : d.learned) {
            out.add(new Gesture(e.id, e.label));
        }
        return out;
    }

    private void openPicker(Gesture g) {
        editingGestureId = g.id;
        Intent i = new Intent(this, ActionPickerActivity.class);
        i.putExtra(ActionPickerActivity.EXTRA_TITLE, g.name);
        i.putExtra(ActionPickerActivity.EXTRA_KEYS, selected.actionsFor(g.id).toArray(new String[0]));
        pickAction.launch(i);
    }

    private void testGesture(Gesture g) {
        List<String> keys = selected.actionsFor(g.id);
        if (keys.isEmpty()) {
            return;
        }
        executor.executeAll(keys);
        Toast.makeText(this, "已执行 " + g.name, Toast.LENGTH_SHORT).show();
    }

    private String describe(List<String> keys) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < keys.size(); i++) {
            if (i > 0) {
                sb.append(" · ");
            }
            sb.append(CustomAction.label(this, keys.get(i)));
        }
        return sb.toString();
    }

    // ---------------- 设备操作 ----------------

    private void openLearn() {
        if (selected == null) {
            return;
        }
        Intent i = new Intent(this, LearnActivity.class);
        i.putExtra(LearnActivity.EXTRA_MAC, selected.mac);
        startActivity(i);
    }

    private void renameDevice() {
        if (selected == null) {
            return;
        }
        final android.widget.EditText et = new android.widget.EditText(this);
        et.setText(selected.name);
        new AlertDialog.Builder(this)
                .setTitle("重命名")
                .setView(et)
                .setPositiveButton("保存", (d, w) -> {
                    selected.name = et.getText().toString().trim();
                    ruleStore.upsert(selected);
                    ButtonService.reload(this);
                    reload();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void deleteDevice() {
        if (selected == null) {
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(selected.name)
                .setMessage("删除这台设备及其全部绑定？")
                .setPositiveButton("删除", (d, w) -> {
                    ruleStore.remove(selected.mac);
                    ButtonService.reload(this);
                    selectedMac = null;
                    reload();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ---------------- 分类颜色 ----------------

    private void buildKeyColorMap() {
        Map<String, List<ActionDef>> grouped = ActionCatalog.grouped();
        for (Map.Entry<String, List<ActionDef>> e : grouped.entrySet()) {
            int color = catColor(e.getKey());
            for (ActionDef def : e.getValue()) {
                keyColor.put(def.key, color);
            }
        }
    }

    private int catColor(String cat) {
        if (cat == null) {
            return getColor(R.color.c_green);
        }
        if (cat.contains("门") || cat.contains("窗") || cat.contains("车身")) {
            return getColor(R.color.c_orange);
        }
        if (cat.contains("空调")) {
            return getColor(R.color.c_mint);
        }
        if (cat.contains("座")) {
            return getColor(R.color.c_pink);
        }
        if (cat.contains("媒体") || cat.contains("音")) {
            return getColor(R.color.c_purple);
        }
        if (cat.contains("影") || cat.contains("环视") || cat.contains("摄")) {
            return getColor(R.color.c_indigo);
        }
        if (cat.contains("系统")) {
            return getColor(R.color.c_grey);
        }
        if (cat.contains("自定义")) {
            return getColor(R.color.c_indigo);
        }
        return getColor(R.color.c_green);
    }

    private int colorForKeys(List<String> keys) {
        for (String k : keys) {
            if (CustomAction.isCustom(k)) {
                return getColor(R.color.c_indigo);
            }
            Integer c = keyColor.get(k);
            if (c != null) {
                return c;
            }
        }
        return getColor(R.color.c_green);
    }

    // ---------------- 工具 ----------------

    private void tint(View v, int color, int radiusDp) {
        GradientDrawable gd = new GradientDrawable();
        gd.setCornerRadius(dp(radiusDp));
        gd.setColor(color);
        v.setBackground(gd);
    }

    private void setBarButtons(boolean show) {
        int vis = show ? View.VISIBLE : View.GONE;
        findViewById(R.id.btn_learn).setVisibility(vis);
        findViewById(R.id.btn_rename).setVisibility(vis);
        findViewById(R.id.btn_delete).setVisibility(vis);
    }

    private int dp(float v) {
        return (int) (getResources().getDisplayMetrics().density * v);
    }

    // ---------------- 权限 ----------------

    private void ensurePermissions() {
        String[] perms;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms = new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT};
        } else {
            perms = new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
        }
        boolean need = false;
        for (String p : perms) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                need = true;
                break;
            }
        }
        if (need) {
            ActivityCompat.requestPermissions(this, perms, REQ_PERM);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERM) {
            ButtonService.start(this);
        }
    }
}
