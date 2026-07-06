package com.selfclink.ble.automation;

import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.net.Uri;
import android.view.KeyEvent;

import com.selfclink.ble.util.AppLog;
import com.selfclink.ble.vehicle.VehicleController;

import java.util.List;

/**
 * 把动作键执行成实际效果：{@code car_} 走车控（{@link VehicleController}），{@code sys_} 走系统动作。
 */
public final class ActionExecutor {

    private static final String TAG = "ActionExecutor";

    private final Context context;
    private final VehicleController vehicle;
    private final AudioManager audio;

    public ActionExecutor(Context context) {
        this.context = context.getApplicationContext();
        this.vehicle = new VehicleController(this.context);
        this.audio = (AudioManager) this.context.getSystemService(Context.AUDIO_SERVICE);
    }

    /** 顺序执行一组动作。 */
    public void executeAll(List<String> keys) {
        if (keys == null) {
            return;
        }
        for (String key : keys) {
            execute(key);
        }
    }

    public void execute(String key) {
        if (key == null) {
            return;
        }
        if (key.startsWith(VehicleController.PREFIX)) {
            vehicle.execute(key);
        } else if (key.startsWith("sys_")) {
            executeSystem(key);
        } else if (key.startsWith(CustomAction.APP)) {
            openApp(CustomAction.arg(key));
        } else if (key.startsWith(CustomAction.URL)) {
            openUri(CustomAction.arg(key));
        } else if (key.startsWith(CustomAction.SHELL)) {
            runShell(CustomAction.arg(key));
        } else {
            AppLog.d(TAG, "未知动作: " + key);
        }
    }

    private void openApp(String pkg) {
        try {
            Intent i = context.getPackageManager().getLaunchIntentForPackage(pkg);
            if (i == null) {
                AppLog.d(TAG, "应用无启动入口: " + pkg);
                return;
            }
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(i);
        } catch (Exception e) {
            AppLog.d(TAG, "打开应用失败 " + pkg + ": " + e.getMessage());
        }
    }

    private void openUri(String uri) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(uri));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(i);
        } catch (Exception e) {
            AppLog.d(TAG, "打开网址失败 " + uri + ": " + e.getMessage());
        }
    }

    private void runShell(String cmd) {
        try {
            Runtime.getRuntime().exec(new String[]{"sh", "-c", cmd});
        } catch (Exception e) {
            AppLog.d(TAG, "执行命令失败 " + cmd + ": " + e.getMessage());
        }
    }

    private void executeSystem(String key) {
        if ("sys_exterior_ptt".equals(key)) {
            com.selfclink.ble.audio.ExteriorVoice.get().toggle(context);
            return;
        }
        if (audio == null) {
            return;
        }
        switch (key) {
            case "sys_media_play_pause":
                sendMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
                break;
            case "sys_media_next":
                sendMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT);
                break;
            case "sys_media_prev":
                sendMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS);
                break;
            case "sys_volume_up":
                audio.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                        AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI);
                break;
            case "sys_volume_down":
                audio.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                        AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI);
                break;
            case "sys_volume_mute":
                audio.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                        AudioManager.ADJUST_TOGGLE_MUTE, AudioManager.FLAG_SHOW_UI);
                break;
            default:
                AppLog.d(TAG, "未知系统动作: " + key);
                break;
        }
    }

    private void sendMediaKey(int keyCode) {
        audio.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, keyCode));
        audio.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, keyCode));
    }
}
