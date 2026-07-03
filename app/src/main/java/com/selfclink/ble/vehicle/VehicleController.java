package com.selfclink.ble.vehicle;

import android.content.Context;

import com.ecarx.xui.adaptapi.car.base.ICarFunction;
import com.ecarx.xui.adaptapi.car.hvac.IHvac;
import com.ecarx.xui.adaptapi.car.sensor.ISensor;
import com.ecarx.xui.adaptapi.car.sensor.ISensorEvent;
import com.ecarx.xui.adaptapi.car.vehicle.IBcm;
import com.ecarx.xui.adaptapi.vehicle.VehicleZone;
import com.selfclink.ble.util.AppLog;

/**
 * 场景助手「车辆控制」动作执行器。把 {@link com.selfclink.ble.automation.ActionCatalog} 里
 * {@code car_} 前缀的动作映射成 ecarx 车控写入（{@code setFunctionValue} / {@code setCustomizeFunctionValue}）。
 *
 * <p>funcId / zone / 开关时序全部来自实车跑通的参考实现（ZyanLauncher HvacManager），开源版只用
 * ecarx 通用 funcId、不含任何品牌字。多为「按一下」的切换/循环语义，适配蓝牙旋钮单键触发。
 *
 * <p><b>安全</b>：非 P 档禁止解锁 / 开门 / 开后备箱，读不到挡位时按非 P 处理（保守拦截）。
 */
public final class VehicleController {

    private static final String TAG = "VehicleCtrl";

    /** 动作键前缀；{@link com.selfclink.ble.automation.ActionCatalog} 与本类约定。 */
    public static final String PREFIX = "car_";

    private static final int ON = ICarFunction.COMMON_VALUE_ON;
    private static final int OFF = ICarFunction.COMMON_VALUE_OFF;

    private static final int ZONE_ALL = VehicleZone.ZONE_ALL;
    private static final int ZONE_DRV = VehicleZone.ZONE_ROW_1_LEFT;
    private static final int ZONE_PASS = VehicleZone.ZONE_ROW_1_RIGHT;
    private static final int ZONE_REAR_ALL = 128;          // 0x80 后排整体
    private static final int ZONE_TRUNK = 536870912;       // 0x20000000 后备箱
    private static final int ZONE_WIN_FL = 16;
    private static final int ZONE_WIN_FR = 32;
    private static final int ZONE_WIN_RL = 256;
    private static final int ZONE_WIN_RR = 512;

    private static final int FUNC_CENTRAL_LOCK = 537921792;
    private static final int FUNC_ONE_KEY_CLOSE = 540126208;
    private static final int FUNC_CHARGE_CAP = 553780480;
    private static final int FUNC_CAMERA_360 = 587399424;
    private static final int FUNC_PARK_COMFORT = 538837248;
    private static final int PARK_COMFORT_30MIN = 538640642;
    private static final int FUNC_DOOR_AUTO_MAN = 554762880;

    private final EcarxCarManager car = EcarxCarManager.getInstance();

    public VehicleController(Context context) {
        // 即使没有其它车态规则也要确保连接就绪，旋钮按下时才能写得进去。
        car.ensureConnected(context.getApplicationContext());
    }

    /** 执行一个 {@code car_} 动作键。未知键忽略。 */
    public void execute(String key) {
        if (key == null || !key.startsWith(PREFIX)) {
            return;
        }
        AppLog.d(TAG, "车控动作: " + key + " 挡位P=" + isPark());
        switch (key) {
            // ---- 空调开关类 ----
            case "car_ac": toggleOnOff(IHvac.HVAC_FUNC_AC); break;
            case "car_auto": toggleOnOff(IHvac.HVAC_FUNC_AUTO); break;
            case "car_power": toggleOnOff(IHvac.HVAC_FUNC_POWER); break;
            case "car_eco": toggleOnOff(IHvac.HVAC_FUNC_ECO_SWITCH); break;
            case "car_sync": toggleOnOff(IHvac.HVAC_FUNC_TEMP_DUAL); break;
            case "car_front_defrost": toggleOnOff(IHvac.HVAC_FUNC_DEFROST_FRONT); break;
            case "car_rear_defrost": toggleOnOff(IHvac.HVAC_FUNC_DEFROST_REAR); break;
            case "car_fragrance": toggleOnOff(IHvac.HVAC_FUNC_AIR_FRAGRANCE); break;
            case "car_rear_power": toggleZoned(IHvac.HVAC_FUNC_POWER, ZONE_REAR_ALL); break;

            // ---- 空调循环/增减类 ----
            case "car_circulation": cycleCirculation(); break;
            case "car_fan_up": stepFan(+1); break;
            case "car_fan_down": stepFan(-1); break;
            case "car_temp_drv_up": stepTemp(ZONE_DRV, +0.5f); break;
            case "car_temp_drv_down": stepTemp(ZONE_DRV, -0.5f); break;
            case "car_temp_pass_up": stepTemp(ZONE_PASS, +0.5f); break;
            case "car_temp_pass_down": stepTemp(ZONE_PASS, -0.5f); break;

            // ---- 座椅 ----
            case "car_seat_heat_drv": cycleSeatHeat(ZONE_DRV); break;
            case "car_seat_heat_pass": cycleSeatHeat(ZONE_PASS); break;
            case "car_seat_vent_drv": cycleSeatVent(ZONE_DRV); break;
            case "car_seat_vent_pass": cycleSeatVent(ZONE_PASS); break;
            case "car_massage_drv": toggleMassage(ZONE_DRV); break;
            case "car_massage_pass": toggleMassage(ZONE_PASS); break;
            case "car_steering_heat": cycleSteeringHeat(); break;

            // ---- 车身 ----
            case "car_lock": car.setFunction(FUNC_CENTRAL_LOCK, ZONE_ALL, ON); break;
            case "car_unlock":
                if (guardPark("解锁")) car.setFunction(FUNC_CENTRAL_LOCK, ZONE_ALL, OFF);
                break;
            case "car_trunk":
                if (guardPark("开后备箱")) toggleZoned(IBcm.BCM_FUNC_DOOR, ZONE_TRUNK);
                break;
            case "car_mirror": toggleZoned(IBcm.BCM_FUNC_FOLD_REAR_MIRROR, ZONE_ALL); break;
            case "car_one_key_close": car.setFunction(FUNC_ONE_KEY_CLOSE, ZONE_ALL, 0); break;

            case "car_window_fl": toggleWindow(ZONE_WIN_FL); break;
            case "car_window_fr": toggleWindow(ZONE_WIN_FR); break;
            case "car_window_rl": toggleWindow(ZONE_WIN_RL); break;
            case "car_window_rr": toggleWindow(ZONE_WIN_RR); break;

            case "car_door_fl": toggleDoor(1); break;    // 左前
            case "car_door_fr": toggleDoor(4); break;    // 右前
            case "car_door_rl": toggleDoor(16); break;   // 左后
            case "car_door_rr": toggleDoor(64); break;   // 右后

            case "car_charge_port_l": toggleZoned(FUNC_CHARGE_CAP, 8); break;
            case "car_charge_port_r": toggleZoned(FUNC_CHARGE_CAP, 128); break;
            case "car_camera_360": toggle360(); break;
            case "car_parking_comfort": toggleParkComfort(); break;
            case "car_manual_door_front": toggleManualDoor(ZONE_DRV, ZONE_PASS); break;
            case "car_manual_door_rear": toggleManualDoor(16, 64); break;

            default:
                AppLog.d(TAG, "未知车控动作: " + key);
                break;
        }
    }

    // ---------------- 通用切换 ----------------

    private void toggleOnOff(int func) {
        toggleZoned(func, ZONE_ALL);
    }

    private void toggleZoned(int func, int zone) {
        int cur = car.readFunction(func, zone);
        car.setFunction(func, zone, cur == ON ? OFF : ON);
    }

    private void cycleCirculation() {
        int cur = car.readFunction(IHvac.HVAC_FUNC_CIRCULATION, ZONE_ALL);
        int next;
        if (cur == IHvac.CIRCULATION_INNER) {
            next = IHvac.CIRCULATION_OUTSIDE;
        } else if (cur == IHvac.CIRCULATION_OUTSIDE) {
            next = IHvac.CIRCULATION_AUTO;
        } else {
            next = IHvac.CIRCULATION_INNER;
        }
        car.setFunction(IHvac.HVAC_FUNC_CIRCULATION, ZONE_ALL, next);
    }

    private void stepFan(int delta) {
        int ui = sdkFanToUi(car.readFunction(IHvac.HVAC_FUNC_FAN_SPEED, ZONE_ALL));
        ui = clamp(ui + delta, 0, 9);
        int sdk = ui <= 0 ? IHvac.FAN_SPEED_OFF : IHvac.FAN_SPEED_LEVEL_1 + (ui - 1);
        car.setFunction(IHvac.HVAC_FUNC_FAN_SPEED, ZONE_ALL, sdk);
    }

    private void stepTemp(int zone, float delta) {
        float cur = car.readCustomizeFunction(IHvac.HVAC_FUNC_TEMP, zone);
        if (Float.isNaN(cur) || cur < 16f || cur > 32f) {
            cur = 24f;
        }
        float next = clampF(cur + delta, 16f, 32f);
        car.setCustomizeFunction(IHvac.HVAC_FUNC_TEMP, zone, next);
    }

    private void cycleSeatHeat(int zone) {
        cycleLevels(IHvac.HVAC_FUNC_SEAT_HEATING, zone, new int[]{
                OFF, IHvac.SEAT_HEATING_LEVEL_1, IHvac.SEAT_HEATING_LEVEL_2, IHvac.SEAT_HEATING_LEVEL_3});
    }

    private void cycleSeatVent(int zone) {
        cycleLevels(IHvac.HVAC_FUNC_SEAT_VENTILATION, zone, new int[]{
                OFF, IHvac.SEAT_VENTILATION_LEVEL_1, IHvac.SEAT_VENTILATION_LEVEL_2, IHvac.SEAT_VENTILATION_LEVEL_3});
    }

    private void cycleSteeringHeat() {
        cycleLevels(IHvac.HVAC_FUNC_STEERING_WHEEL_HEAT, ZONE_ALL, new int[]{
                IHvac.STEERING_WHEEL_HEAT_OFF, IHvac.STEERING_WHEEL_HEAT_LOW,
                IHvac.STEERING_WHEEL_HEAT_MID, IHvac.STEERING_WHEEL_HEAT_HIGH});
    }

    /** 在给定档位序列里循环到下一档（读不到当前值时从首档之后开始）。 */
    private void cycleLevels(int func, int zone, int[] levels) {
        int cur = car.readFunction(func, zone);
        int idx = 0;
        for (int i = 0; i < levels.length; i++) {
            if (levels[i] == cur) {
                idx = i;
                break;
            }
        }
        int next = levels[(idx + 1) % levels.length];
        car.setFunction(func, zone, next);
    }

    /** 座椅按摩开关：关→（设程序1+开），开→关。 */
    private void toggleMassage(int zone) {
        int cur = car.readFunction(IHvac.HVAC_FUNC_SEAT_MASSAGE_SWITCH, zone);
        if (cur == ON) {
            car.setFunction(IHvac.HVAC_FUNC_SEAT_MASSAGE_SWITCH, zone, OFF);
        } else {
            car.setFunction(IHvac.HVAC_FUNC_SEAT_MASSAGE_PROGRAM, zone, IHvac.SEAT_MASSAGE_PROGRAM_1);
            car.setFunction(IHvac.HVAC_FUNC_SEAT_MASSAGE_SWITCH, zone, ON);
        }
    }

    /** 车窗开合：位置 >50 视为关着 → 开(0)，否则关(100)。 */
    private void toggleWindow(int zone) {
        float cur = car.readCustomizeFunction(IBcm.BCM_FUNC_WINDOW_POS, zone);
        float target = (!Float.isNaN(cur) && cur > 50f) ? 0f : 100f;
        car.setCustomizeFunction(IBcm.BCM_FUNC_WINDOW_POS, zone, target);
    }

    /** 车门开关（非 P 档拦截）。 */
    private void toggleDoor(int zone) {
        if (!guardPark("开门")) {
            return;
        }
        int cur = car.readFunction(IBcm.BCM_FUNC_DOOR, zone);
        car.setFunction(IBcm.BCM_FUNC_DOOR, zone, cur == IBcm.DOOR_OPEN ? IBcm.DOOR_CLOSE : IBcm.DOOR_OPEN);
    }

    private void toggle360() {
        int cur = car.readFunction(FUNC_CAMERA_360, 0);
        car.setFunction(FUNC_CAMERA_360, 0, cur == 1 ? 0 : 1);
    }

    private void toggleParkComfort() {
        int cur = car.readFunction(FUNC_PARK_COMFORT, ZONE_ALL);
        car.setFunction(FUNC_PARK_COMFORT, ZONE_ALL, cur != 0 ? 0 : PARK_COMFORT_30MIN);
    }

    private void toggleManualDoor(int zoneL, int zoneR) {
        toggleZoned(FUNC_DOOR_AUTO_MAN, zoneL);
        toggleZoned(FUNC_DOOR_AUTO_MAN, zoneR);
    }

    // ---------------- 工具 ----------------

    private boolean isPark() {
        return car.readSensorEvent(ISensor.SENSOR_TYPE_GEAR) == ISensorEvent.GEAR_PARK;
    }

    /** 非 P 档拦截：返回 true 表示允许执行。 */
    private boolean guardPark(String what) {
        if (!isPark()) {
            AppLog.d(TAG, "非 P 档，禁止" + what);
            return false;
        }
        return true;
    }

    /** SDK 风速常量 → UI 1-9，关或异常返回 0。 */
    private int sdkFanToUi(int sdk) {
        if (sdk <= 0) {
            return 0;
        }
        if (sdk >= 1 && sdk <= 9) {
            return sdk;
        }
        return clamp(sdk - IHvac.FAN_SPEED_LEVEL_1 + 1, 0, 9);
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private static float clampF(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
