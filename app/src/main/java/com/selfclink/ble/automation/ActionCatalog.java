package com.selfclink.ble.automation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 全部可绑定动作的目录（对标原车「场景工坊」控制项）。UI 按 {@link ActionDef#category} 分组渲染，
 * 执行交给 {@link ActionExecutor}。车控键与 {@code vehicle.VehicleController} 一一对应。
 */
public final class ActionCatalog {

    public static final String CAT_HVAC = "空调";
    public static final String CAT_SEAT = "座椅";
    public static final String CAT_BODY = "车身";
    public static final String CAT_OTHER = "其它";
    public static final String CAT_SYSTEM = "系统";

    private static final List<ActionDef> ALL = new ArrayList<>();
    private static final Map<String, ActionDef> BY_KEY = new LinkedHashMap<>();

    static {
        // ---- 空调 ----
        add("car_power", CAT_HVAC, "空调总开关", false);
        add("car_ac", CAT_HVAC, "AC 开关", false);
        add("car_auto", CAT_HVAC, "AUTO 开关", false);
        add("car_eco", CAT_HVAC, "ECO 开关", false);
        add("car_sync", CAT_HVAC, "双区同步", false);
        add("car_circulation", CAT_HVAC, "内外循环切换", false);
        add("car_fan_up", CAT_HVAC, "风量 +", false);
        add("car_fan_down", CAT_HVAC, "风量 -", false);
        add("car_temp_drv_up", CAT_HVAC, "主驾温度 +", false);
        add("car_temp_drv_down", CAT_HVAC, "主驾温度 -", false);
        add("car_temp_pass_up", CAT_HVAC, "副驾温度 +", false);
        add("car_temp_pass_down", CAT_HVAC, "副驾温度 -", false);
        add("car_front_defrost", CAT_HVAC, "前挡除霜", false);
        add("car_rear_defrost", CAT_HVAC, "后挡除霜", false);
        add("car_fragrance", CAT_HVAC, "香氛开关", false);
        add("car_rear_power", CAT_HVAC, "后排空调开关", false);

        // ---- 座椅 ----
        add("car_seat_heat_drv", CAT_SEAT, "主驾座椅加热", false);
        add("car_seat_heat_pass", CAT_SEAT, "副驾座椅加热", false);
        add("car_seat_vent_drv", CAT_SEAT, "主驾座椅通风", false);
        add("car_seat_vent_pass", CAT_SEAT, "副驾座椅通风", false);
        add("car_massage_drv", CAT_SEAT, "主驾按摩", false);
        add("car_massage_pass", CAT_SEAT, "副驾按摩", false);
        add("car_steering_heat", CAT_SEAT, "方向盘加热", false);

        // ---- 车身 ----
        add("car_lock", CAT_BODY, "落锁", false);
        add("car_unlock", CAT_BODY, "解锁", true);
        add("car_trunk", CAT_BODY, "后备箱", true);
        add("car_mirror", CAT_BODY, "后视镜折叠", false);
        add("car_one_key_close", CAT_BODY, "一键关窗", false);
        add("car_window_fl", CAT_BODY, "左前车窗", false);
        add("car_window_fr", CAT_BODY, "右前车窗", false);
        add("car_window_rl", CAT_BODY, "左后车窗", false);
        add("car_window_rr", CAT_BODY, "右后车窗", false);
        add("car_door_fl", CAT_BODY, "左前门", true);
        add("car_door_fr", CAT_BODY, "右前门", true);
        add("car_door_rl", CAT_BODY, "左后门", true);
        add("car_door_rr", CAT_BODY, "右后门", true);
        add("car_charge_port_l", CAT_BODY, "左充电口盖", false);
        add("car_charge_port_r", CAT_BODY, "右充电口盖", false);
        add("car_manual_door_front", CAT_BODY, "前排手动门模式", false);
        add("car_manual_door_rear", CAT_BODY, "后排手动门模式", false);

        // ---- 其它 ----
        add("car_camera_360", CAT_OTHER, "360 环视", false);
        add("car_parking_comfort", CAT_OTHER, "驻车舒享", false);
        add("car_rear_screen_lock", CAT_OTHER, "后排屏幕锁定", false);

        // ---- 系统 ----
        add("sys_media_play_pause", CAT_SYSTEM, "播放/暂停", false);
        add("sys_media_next", CAT_SYSTEM, "下一曲", false);
        add("sys_media_prev", CAT_SYSTEM, "上一曲", false);
        add("sys_volume_up", CAT_SYSTEM, "音量 +", false);
        add("sys_volume_down", CAT_SYSTEM, "音量 -", false);
        add("sys_volume_mute", CAT_SYSTEM, "静音切换", false);
        add("sys_exterior_ptt", CAT_SYSTEM, "车外喊话（切换）", false);
        add("car_user_habit_1", CAT_SYSTEM, "用车习惯 1", false);
        add("car_user_habit_2", CAT_SYSTEM, "用车习惯 2", false);
        add("car_user_habit_3", CAT_SYSTEM, "用车习惯 3", false);
    }

    private ActionCatalog() {
    }

    private static void add(String key, String category, String name, boolean parkGuard) {
        ActionDef d = new ActionDef(key, category, name, parkGuard);
        ALL.add(d);
        BY_KEY.put(key, d);
    }

    public static List<ActionDef> all() {
        return Collections.unmodifiableList(ALL);
    }

    public static ActionDef byKey(String key) {
        return BY_KEY.get(key);
    }

    public static String nameOf(String key) {
        ActionDef d = BY_KEY.get(key);
        return d == null ? key : d.name;
    }

    /** 按 category 顺序分组（保持插入序）。 */
    public static Map<String, List<ActionDef>> grouped() {
        Map<String, List<ActionDef>> map = new LinkedHashMap<>();
        for (ActionDef d : ALL) {
            map.computeIfAbsent(d.category, k -> new ArrayList<>()).add(d);
        }
        return map;
    }
}
