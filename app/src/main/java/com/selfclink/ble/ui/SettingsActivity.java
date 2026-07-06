package com.selfclink.ble.ui;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.widget.TextView;

import androidx.appcompat.widget.SwitchCompat;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.selfclink.ble.BuildConfig;
import com.selfclink.ble.R;
import com.selfclink.ble.audio.ExteriorVoice;
import com.selfclink.ble.util.AppPrefs;

/** 设置页：共创广场入口、米家账号入口、关于与版本信息。 */
public final class SettingsActivity extends BackBarActivity {

    private static final int REQ_RECORD = 41;

    private boolean hasRecordPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_RECORD && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            SwitchCompat exterior = findViewById(R.id.switch_exterior);
            exterior.setChecked(true);
            ExteriorVoice.get().setOn(this, true);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        findViewById(R.id.row_community).setOnClickListener(v ->
                startActivity(new Intent(this, CommunityActivity.class)));
        findViewById(R.id.row_account).setOnClickListener(v ->
                startActivity(new Intent(this, AccountActivity.class)));
        findViewById(R.id.row_capture).setOnClickListener(v ->
                startActivity(new Intent(this, CaptureActivity.class)));

        SwitchCompat feedback = findViewById(R.id.switch_feedback);
        feedback.setChecked(AppPrefs.feedback(this));
        feedback.setOnCheckedChangeListener((b, on) -> AppPrefs.setFeedback(this, on));

        SwitchCompat exterior = findViewById(R.id.switch_exterior);
        exterior.setChecked(ExteriorVoice.get().isOn());
        exterior.setOnCheckedChangeListener((b, on) -> {
            if (on && !hasRecordPermission()) {
                b.setChecked(false);
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.RECORD_AUDIO}, REQ_RECORD);
                return;
            }
            ExteriorVoice.get().setOn(this, on);
        });

        ((TextView) findViewById(R.id.version_text))
                .setText("版本 " + BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")");
    }
}
