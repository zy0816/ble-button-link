package com.selfclink.ble.vehicle;

/** 仅使用已实车验证的原车场景状态。中间态及非法值不能解释为关闭。 */
public final class SceneStateDecoder {
    private SceneStateDecoder() {}
    public static String camping(String raw) {
        if ("3".equals(raw)) return "on";
        if ("0".equals(raw)) return "off";
        return null;
    }
}
