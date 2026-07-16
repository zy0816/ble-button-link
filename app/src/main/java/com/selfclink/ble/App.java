package com.selfclink.ble;

import android.app.Application;

import com.selfclink.ble.automation.RuleStore;

/**
 * 进程入口：启动即在后台线程预热加密存储（唯一一次 Android Keystore 访问），
 * 使后续任何界面/服务读写规则时都命中缓存，主线程永不发起 keystore Binder 调用，
 * 从根上消除「个别车机唤醒后 keystore 僵死导致的白屏卡死」。
 */
public final class App extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        RuleStore.warmUp(this);
    }
}
