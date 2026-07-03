package com.selfclink.ble.automation;

import android.content.Context;
import android.content.pm.PackageManager;

/**
 * 带参数的动作键（区别于 {@link ActionCatalog} 里固定的车控/系统键）。
 *
 * <p>三种前缀：{@code app_open:<包名>}、{@code url_open:<uri>}、{@code shell:<命令>}。
 * 参数直接跟在冒号后（命令/URL 本身可再含冒号，故只按第一个冒号切分）。
 */
public final class CustomAction {

    public static final String APP = "app_open:";
    public static final String URL = "url_open:";
    public static final String SHELL = "shell:";

    private CustomAction() {
    }

    public static boolean isCustom(String key) {
        return key != null
                && (key.startsWith(APP) || key.startsWith(URL) || key.startsWith(SHELL));
    }

    /** 取冒号后的参数（包名 / uri / 命令）。 */
    public static String arg(String key) {
        int i = key.indexOf(':');
        return i < 0 ? "" : key.substring(i + 1);
    }

    /** 供列表展示的中文名。app_open 会尝试解析应用名。 */
    public static String label(Context ctx, String key) {
        if (key == null) {
            return "";
        }
        if (key.startsWith(APP)) {
            return "打开应用: " + appLabel(ctx, arg(key));
        }
        if (key.startsWith(URL)) {
            return "打开: " + arg(key);
        }
        if (key.startsWith(SHELL)) {
            return "命令: " + arg(key);
        }
        return ActionCatalog.nameOf(key);
    }

    private static String appLabel(Context ctx, String pkg) {
        try {
            PackageManager pm = ctx.getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (Exception e) {
            return pkg;
        }
    }
}
