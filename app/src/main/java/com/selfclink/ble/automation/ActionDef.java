package com.selfclink.ble.automation;

/**
 * 一个可绑定的动作定义（元数据驱动，便于 UI 自动渲染与扩展）。
 *
 * <p>当前动作均为「按一下」语义（开关/循环/步进），契合蓝牙按键单次触发；无需参数。
 * 车控动作 {@code key} 以 {@code car_} 开头，系统动作以 {@code sys_} 开头。
 */
public final class ActionDef {

    public final String key;
    public final String category;
    public final String name;
    /** 是否非 P 档拦截（解锁/开门/开后备箱等）。 */
    public final boolean parkGuard;

    public ActionDef(String key, String category, String name, boolean parkGuard) {
        this.key = key;
        this.category = category;
        this.name = name;
        this.parkGuard = parkGuard;
    }
}
