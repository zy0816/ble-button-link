package com.selfclink.ble.ui;

import android.content.Intent;
import android.os.Bundle;
import android.widget.TextView;

import androidx.appcompat.widget.SwitchCompat;

import com.selfclink.ble.BuildConfig;
import com.selfclink.ble.R;
import com.selfclink.ble.util.AppPrefs;

/** 设置页：共创广场入口、米家账号入口、关于与版本信息。 */
public final class SettingsActivity extends BackBarActivity {

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

        ((TextView) findViewById(R.id.version_text))
                .setText("版本 " + BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")");
    }
}
