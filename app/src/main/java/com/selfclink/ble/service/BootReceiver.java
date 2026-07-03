package com.selfclink.ble.service;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.selfclink.ble.util.AppLog;

/** 开机自启：拉起前台扫描服务。 */
public final class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent != null ? intent.getAction() : null;
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)) {
            AppLog.d(TAG, "开机自启，拉起服务");
            ButtonService.start(context);
        }
    }
}
