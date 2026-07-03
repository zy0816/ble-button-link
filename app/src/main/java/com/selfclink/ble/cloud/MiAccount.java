package com.selfclink.ble.cloud;

import android.content.Context;

/**
 * 米家登录会话——<b>仅存内存，不落盘、不上传</b>（满足「凭据不存」）。
 * 进程存活期间用于拉设备/BindKey；杀进程即清空，下次需重新登录。
 */
public final class MiAccount {

    private static MiAccount instance;

    public String userId;
    public String ssecurity;
    public String serviceToken;
    public String region = "cn";

    private MiAccount() {
    }

    public static synchronized MiAccount get(Context ignored) {
        if (instance == null) {
            instance = new MiAccount();
        }
        return instance;
    }

    public boolean isLoggedIn() {
        return serviceToken != null && ssecurity != null && userId != null;
    }

    public void set(String userId, String ssecurity, String serviceToken, String region) {
        this.userId = userId;
        this.ssecurity = ssecurity;
        this.serviceToken = serviceToken;
        if (region != null && !region.isEmpty()) {
            this.region = region;
        }
    }

    public void clear() {
        userId = null;
        ssecurity = null;
        serviceToken = null;
    }
}
