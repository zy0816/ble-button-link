package com.selfclink.ble.ui;

import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.selfclink.ble.R;
import com.selfclink.ble.cloud.MiAccount;
import com.selfclink.ble.cloud.MiCloud;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 米家登录页：账号密码 / 扫码 两种方式自由选择，含免责说明。
 * 登录票据仅存内存（{@link MiAccount}），用于后续从云端读取已绑定设备的 BindKey。
 */
public final class AccountActivity extends BackBarActivity {

    /** 区域代码（与米家 api host 前缀一致），cn 为空走默认域名。 */
    private static final String[] REGION_CODES = {"cn", "de", "us", "ru", "sg", "i2"};
    private static final String[] REGION_NAMES = {"中国大陆", "欧洲", "美国", "俄罗斯", "新加坡", "印度"};

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private LinearLayout pwdPanel, qrPanel, loggedPanel;
    private TextView tabPwd, tabQr;
    private Spinner regionSpinner;
    private ImageView qrImage;
    private TextView qrHint;

    private final AtomicBoolean qrPolling = new AtomicBoolean(false);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_account);

        pwdPanel = findViewById(R.id.pwd_panel);
        qrPanel = findViewById(R.id.qr_panel);
        loggedPanel = findViewById(R.id.logged_panel);
        tabPwd = findViewById(R.id.tab_pwd);
        tabQr = findViewById(R.id.tab_qr);
        regionSpinner = findViewById(R.id.region_spinner);
        qrImage = findViewById(R.id.qr_image);
        qrHint = findViewById(R.id.qr_hint);

        ArrayAdapter<String> ra = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, REGION_NAMES);
        regionSpinner.setAdapter(ra);

        tabPwd.setOnClickListener(v -> selectTab(true));
        tabQr.setOnClickListener(v -> selectTab(false));
        findViewById(R.id.btn_login).setOnClickListener(v -> doPasswordLogin());
        findViewById(R.id.btn_logout).setOnClickListener(v -> {
            MiAccount.get(this).clear();
            refresh();
        });

        refresh();
    }

    private void refresh() {
        boolean in = MiAccount.get(this).isLoggedIn();
        loggedPanel.setVisibility(in ? View.VISIBLE : View.GONE);
        if (in) {
            pwdPanel.setVisibility(View.GONE);
            qrPanel.setVisibility(View.GONE);
            findViewById(R.id.tab_pwd).setEnabled(false);
            findViewById(R.id.tab_qr).setEnabled(false);
        } else {
            selectTab(pwdPanel.getVisibility() != View.GONE || qrPanel.getVisibility() == View.GONE);
        }
    }

    private void selectTab(boolean pwd) {
        if (MiAccount.get(this).isLoggedIn()) {
            return;
        }
        pwdPanel.setVisibility(pwd ? View.VISIBLE : View.GONE);
        qrPanel.setVisibility(pwd ? View.GONE : View.VISIBLE);
        tabPwd.setBackgroundResource(pwd ? R.drawable.pill_accent : 0);
        tabPwd.setTextColor(getColor(pwd ? R.color.accent : R.color.sub));
        tabQr.setBackgroundResource(pwd ? 0 : R.drawable.pill_accent);
        tabQr.setTextColor(getColor(pwd ? R.color.sub : R.color.accent));
        if (!pwd) {
            startQr();
        } else {
            qrPolling.set(false);
        }
    }

    private String region() {
        int i = regionSpinner.getSelectedItemPosition();
        return (i >= 0 && i < REGION_CODES.length) ? REGION_CODES[i] : "cn";
    }

    // ---------------- 账号密码 ----------------

    private void doPasswordLogin() {
        String user = ((EditText) findViewById(R.id.et_user)).getText().toString().trim();
        String pwd = ((EditText) findViewById(R.id.et_pwd)).getText().toString();
        if (user.isEmpty() || pwd.isEmpty()) {
            toast("请输入账号和密码");
            return;
        }
        String region = region();
        toast("登录中…");
        io.execute(() -> {
            MiCloud.LoginResult r = new MiCloud().passwordLogin(user, pwd, region);
            main.post(() -> {
                if (r.ok) {
                    toast("登录成功");
                    finish();
                } else {
                    toast(r.message);
                }
            });
        });
    }

    // ---------------- 扫码 ----------------

    private void startQr() {
        qrImage.setImageBitmap(null);
        qrHint.setText("二维码生成中…");
        String region = region();
        io.execute(() -> {
            MiCloud cloud = new MiCloud();
            MiCloud.QrSession session = cloud.startQrLogin();
            if (session == null) {
                main.post(() -> qrHint.setText("二维码获取失败，请重试"));
                return;
            }
            Bitmap bmp = QrCodeUtil.encode(session.qrContent, 480);
            main.post(() -> {
                if (bmp != null) {
                    qrImage.setImageBitmap(bmp);
                }
                qrHint.setText("用「米家 / 小米账号」App 扫码确认登录");
            });
            pollQr(cloud, session, region);
        });
    }

    private void pollQr(MiCloud cloud, MiCloud.QrSession session, String region) {
        qrPolling.set(true);
        while (qrPolling.get()) {
            MiCloud.LoginResult r = cloud.pollQrLogin(session, region);
            if (r.ok) {
                main.post(() -> {
                    toast("扫码登录成功");
                    finish();
                });
                return;
            }
            // WAITING：长轮询本身已阻塞，直接继续下一轮
        }
    }

    @Override
    protected void onDestroy() {
        qrPolling.set(false);
        io.shutdownNow();
        super.onDestroy();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
