package com.selfclink.ble.util;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 全项目唯一日志出口。既写 logcat，也分发给 UI 日志面板。
 *
 * <p>监听回调统一在主线程触发，UI 可直接刷新。
 */
public final class AppLog {

    /** UI 日志监听。 */
    public interface Sink {
        void onLog(String line);
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final CopyOnWriteArrayList<Sink> SINKS = new CopyOnWriteArrayList<>();

    /** 诊断模式：仅在「按键诊断」页打开时置 true，用于放行 {@link #diag} 的逐帧裁决日志。 */
    private static volatile boolean diagEnabled;

    private AppLog() {
    }

    public static void setDiagEnabled(boolean enabled) {
        diagEnabled = enabled;
    }

    public static boolean isDiagEnabled() {
        return diagEnabled;
    }

    /** 逐帧诊断日志：仅诊断模式开启时输出（写 logcat 并分发给 UI），关闭时零开销。 */
    public static void diag(String tag, String msg) {
        if (!diagEnabled) {
            return;
        }
        Log.d(tag, msg);
        dispatch(tag, msg);
    }

    public static void addSink(Sink sink) {
        if (sink != null && !SINKS.contains(sink)) {
            SINKS.add(sink);
        }
    }

    public static void removeSink(Sink sink) {
        SINKS.remove(sink);
    }

    public static void d(String tag, String msg) {
        Log.d(tag, msg);
        dispatch(tag, msg);
    }

    public static void w(String tag, String msg) {
        Log.w(tag, msg);
        dispatch(tag, msg);
    }

    public static void e(String tag, String msg, Throwable t) {
        Log.e(tag, msg, t);
        dispatch(tag, msg + " : " + (t == null ? "" : t.getMessage()));
    }

    private static void dispatch(String tag, String msg) {
        if (SINKS.isEmpty()) {
            return;
        }
        final String line = tag + ": " + msg;
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                for (Sink s : SINKS) {
                    s.onLog(line);
                }
            }
        });
    }
}
