package com.selfclink.ble.ui;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.selfclink.ble.R;
import com.selfclink.ble.product.DeviceProfile;
import com.selfclink.ble.product.ProfileStore;
import com.selfclink.ble.service.ButtonService;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 共创广场：列出全部设备配置（内置 + 用户导入），支持从文件 / 粘贴导入、系统分享导出、删除用户配置。
 * 导入/删除后通知 {@link ButtonService} 重载适配器。
 */
public final class CommunityActivity extends BackBarActivity {

    private ProfileStore store;
    private LinearLayout listView;

    private final ActivityResultLauncher<String[]> openDoc =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri != null) {
                    importFromUri(uri);
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_community);
        store = new ProfileStore(this);
        listView = findViewById(R.id.profile_list);

        findViewById(R.id.btn_import_file).setOnClickListener(v ->
                openDoc.launch(new String[]{"application/json", "text/plain", "*/*"}));
        findViewById(R.id.btn_import_paste).setOnClickListener(v -> showPasteDialog());

        render();
    }

    private void render() {
        listView.removeAllViews();
        LayoutInflater inf = LayoutInflater.from(this);
        List<DeviceProfile> all = store.loadAll();
        for (DeviceProfile p : all) {
            boolean isUser = store.isUserProfile(p.productId);
            View row = inf.inflate(R.layout.item_profile, listView, false);
            ((TextView) row.findViewById(R.id.p_name)).setText(p.displayName);
            ((TextView) row.findViewById(R.id.p_tag)).setText(isUser ? "导入" : "内置");
            String cred = p.credential.name().equals("BINDKEY16") ? "需密钥" : "明文";
            String author = (p.author == null || p.author.isEmpty()) ? "" : " · " + p.author;
            ((TextView) row.findViewById(R.id.p_meta))
                    .setText(p.productId + " · " + p.codec + " · " + cred + author);

            row.findViewById(R.id.p_share).setOnClickListener(v -> share(p));
            TextView del = row.findViewById(R.id.p_delete);
            if (isUser) {
                del.setOnClickListener(v -> confirmDelete(p));
            } else {
                del.setVisibility(View.GONE);
            }
            listView.addView(row);
        }
    }

    private void showPasteDialog() {
        EditText et = new EditText(this);
        et.setHint("粘贴设备配置 JSON");
        et.setMinLines(6);
        et.setGravity(android.view.Gravity.TOP);
        new AlertDialog.Builder(this)
                .setTitle("粘贴导入")
                .setView(et)
                .setPositiveButton("导入", (d, w) -> doImport(et.getText().toString()))
                .setNegativeButton("取消", null)
                .show();
    }

    private void importFromUri(Uri uri) {
        try (InputStream is = getContentResolver().openInputStream(uri)) {
            if (is == null) {
                toast("无法读取文件");
                return;
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) != -1) {
                bos.write(buf, 0, n);
            }
            doImport(new String(bos.toByteArray(), StandardCharsets.UTF_8));
        } catch (Exception e) {
            toast("读取失败: " + e.getMessage());
        }
    }

    private void doImport(String json) {
        if (json == null || json.trim().isEmpty()) {
            toast("内容为空");
            return;
        }
        try {
            DeviceProfile p = store.importProfile(json);
            ButtonService.reload(this);
            toast("已导入：" + p.displayName);
            render();
        } catch (Exception e) {
            toast("导入失败：" + e.getMessage());
        }
    }

    private void share(DeviceProfile p) {
        String text = store.exportProfile(p);
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_SUBJECT, "蓝牙按键设备配置 · " + p.displayName);
        send.putExtra(Intent.EXTRA_TEXT, text);
        startActivity(Intent.createChooser(send, "分享设备配置"));
    }

    private void confirmDelete(DeviceProfile p) {
        new AlertDialog.Builder(this)
                .setTitle("删除配置")
                .setMessage("删除「" + p.displayName + "」？已用此配置接入的设备将无法识别。")
                .setPositiveButton("删除", (d, w) -> {
                    store.deleteUserProfile(p.productId);
                    ButtonService.reload(this);
                    render();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
