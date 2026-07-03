package com.selfclink.ble.ui;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.PorterDuff;
import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.gridlayout.widget.GridLayout;

import com.selfclink.ble.R;
import com.selfclink.ble.automation.BoundDevice;
import com.selfclink.ble.automation.RuleStore;
import com.selfclink.ble.ble.Sightings;
import com.selfclink.ble.cloud.MiAccount;
import com.selfclink.ble.service.ButtonService;

import java.util.List;

/**
 * 主页：每台已接入设备一个彩色图标，点击进编排页，长按重命名/删除。顶部账号状态与设置入口。
 */
public final class MainActivity extends AppCompatActivity {

    private static final int REQ_PERM = 1;

    private GridLayout grid;
    private TextView accountPill;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        grid = findViewById(R.id.dev_grid);
        accountPill = findViewById(R.id.account_pill);

        findViewById(R.id.settings_pill).setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));
        accountPill.setOnClickListener(v ->
                startActivity(new Intent(this, AccountActivity.class)));

        ensurePermissions();
    }

    @Override
    protected void onResume() {
        super.onResume();
        ButtonService.start(this);
        refreshAccount();
        renderDevices();
    }

    private void refreshAccount() {
        if (MiAccount.get(this).isLoggedIn()) {
            accountPill.setText("米家 已登录");
            accountPill.setTextColor(getColor(R.color.accent));
            accountPill.setBackgroundResource(R.drawable.pill_accent);
        } else {
            accountPill.setText("米家 未登录");
            accountPill.setTextColor(getColor(R.color.sub));
            accountPill.setBackgroundResource(R.drawable.pill_bg);
        }
    }

    private void renderDevices() {
        grid.removeAllViews();
        LayoutInflater inf = LayoutInflater.from(this);
        List<BoundDevice> devices = new RuleStore(this).load();

        int cols = grid.getColumnCount();
        for (BoundDevice d : devices) {
            View tile = inf.inflate(R.layout.item_device, grid, false);
            bindTile(tile, d);
            grid.addView(tile, cellParams(cols));
        }
        // 「添加设备」卡
        View add = inf.inflate(R.layout.item_device, grid, false);
        ImageView icon = add.findViewById(R.id.dev_icon);
        icon.setImageResource(android.R.drawable.ic_input_add);
        icon.setColorFilter(getColor(R.color.sub), PorterDuff.Mode.SRC_IN);
        ((TextView) add.findViewById(R.id.dev_name)).setText("添加设备");
        ((TextView) add.findViewById(R.id.dev_status)).setText(" ");
        add.setBackgroundResource(R.drawable.card_dashed);
        add.setOnClickListener(v -> startActivity(new Intent(this, AddDeviceActivity.class)));
        grid.addView(add, cellParams(cols));
    }

    private void bindTile(View tile, BoundDevice d) {
        ImageView icon = tile.findViewById(R.id.dev_icon);
        icon.setImageResource(IconPicker.iconRes(d.productId));
        icon.setColorFilter(IconPicker.tint(d.mac), PorterDuff.Mode.SRC_IN);
        ((TextView) tile.findViewById(R.id.dev_name)).setText(d.name);

        int bindCount = 0;
        for (List<String> a : d.gestureActions.values()) {
            if (!a.isEmpty()) {
                bindCount++;
            }
        }
        TextView st = tile.findViewById(R.id.dev_status);
        boolean online = Sightings.isOnline(d.mac);
        st.setText((online ? "● 在线 · " : "○ 未发现 · ") + bindCount + " 绑定");
        st.setTextColor(getColor(online ? R.color.accent2 : R.color.sub));

        tile.setOnClickListener(v -> {
            Intent i = new Intent(this, DeviceEditorActivity.class);
            i.putExtra(DeviceEditorActivity.EXTRA_MAC, d.mac);
            startActivity(i);
        });
        tile.setOnLongClickListener(v -> {
            confirmDelete(d);
            return true;
        });
    }

    private void confirmDelete(BoundDevice d) {
        new android.app.AlertDialog.Builder(this)
                .setTitle(d.name)
                .setItems(new CharSequence[]{"重命名", "删除设备"}, (dialog, which) -> {
                    if (which == 0) {
                        rename(d);
                    } else {
                        new RuleStore(this).remove(d.mac);
                        ButtonService.reload(this);
                        renderDevices();
                    }
                })
                .show();
    }

    private void rename(BoundDevice d) {
        final android.widget.EditText et = new android.widget.EditText(this);
        et.setText(d.name);
        new android.app.AlertDialog.Builder(this)
                .setTitle("重命名")
                .setView(et)
                .setPositiveButton("保存", (dialog, w) -> {
                    d.name = et.getText().toString().trim();
                    new RuleStore(this).upsert(d);
                    ButtonService.reload(this);
                    renderDevices();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private GridLayout.LayoutParams cellParams(int cols) {
        GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
        lp.width = 0;
        lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
        lp.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1, 1f);
        int m = (int) (getResources().getDisplayMetrics().density * 7);
        lp.setMargins(m, m, m, m);
        return lp;
    }

    private void ensurePermissions() {
        String[] perms;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms = new String[]{Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT};
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
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERM) {
            ButtonService.start(this);
        }
    }
}
