package com.selfclink.ble.util;

import android.content.Context;
import android.content.SharedPreferences;

/** 轻量偏好项（非敏感，明文存储）。 */
public final class AppPrefs {

    private static final String FILE = "app_prefs";
    private static final String KEY_FEEDBACK = "trigger_feedback";

    private AppPrefs() {
    }

    private static SharedPreferences sp(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    /** 触发反馈（震动 + 提示）默认开。 */
    public static boolean feedback(Context ctx) {
        return sp(ctx).getBoolean(KEY_FEEDBACK, true);
    }

    public static void setFeedback(Context ctx, boolean on) {
        sp(ctx).edit().putBoolean(KEY_FEEDBACK, on).apply();
    }
}
