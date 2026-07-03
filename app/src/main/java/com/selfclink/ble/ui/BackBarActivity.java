package com.selfclink.ble.ui;

import android.view.View;

import androidx.appcompat.app.AppCompatActivity;

import com.selfclink.ble.R;

/**
 * 二级页基类：内容视图设置后自动把顶部 {@code btn_back} 返回栏接上 finish()，
 * 各二级页只需在布局里放一个 id 为 {@code btn_back} 的视图即可。
 */
public abstract class BackBarActivity extends AppCompatActivity {

    @Override
    public void onContentChanged() {
        super.onContentChanged();
        View back = findViewById(R.id.btn_back);
        if (back != null) {
            back.setOnClickListener(v -> finish());
        }
    }
}
