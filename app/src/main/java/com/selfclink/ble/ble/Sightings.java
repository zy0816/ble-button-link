package com.selfclink.ble.ble;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 进程内「最近见到的设备」表：扫描每帧记录 mac→时间戳，供 UI 判断设备是否在线（最近 N 秒被广播到）。
 */
public final class Sightings {

    private static final long ONLINE_MS = 15_000;
    private static final ConcurrentHashMap<String, Long> SEEN = new ConcurrentHashMap<>();
    /** mac → 电量百分比（0..100），来自 MiBeacon 电量对象 0x4803。 */
    private static final ConcurrentHashMap<String, Integer> BATTERY = new ConcurrentHashMap<>();

    private Sightings() {
    }

    public static void record(String mac) {
        if (mac != null && !mac.isEmpty()) {
            SEEN.put(mac.toUpperCase(), System.currentTimeMillis());
        }
    }

    public static void recordBattery(String mac, int pct) {
        if (mac != null && !mac.isEmpty() && pct >= 0 && pct <= 100) {
            BATTERY.put(mac.toUpperCase(), pct);
        }
    }

    /** 电量百分比，未知返回 -1。 */
    public static int battery(String mac) {
        if (mac == null) {
            return -1;
        }
        Integer p = BATTERY.get(mac.toUpperCase());
        return p == null ? -1 : p;
    }

    public static boolean isOnline(String mac) {
        if (mac == null) {
            return false;
        }
        Long t = SEEN.get(mac.toUpperCase());
        return t != null && (System.currentTimeMillis() - t) < ONLINE_MS;
    }
}
