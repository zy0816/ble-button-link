package com.selfclink.ble.vehicle;
import android.content.Context;
import android.provider.Settings;
import com.ecarx.xui.adaptapi.car.sensor.ISensor;
import com.ecarx.xui.adaptapi.car.sensor.ISensorEvent;
import com.selfclink.ble.automation.ConditionEngine;
import java.util.*;
/** 每次求值重新读取，单次检查内缓存；不把缺失、255、异常当作关闭。 */
public final class ConditionStates implements ConditionEngine.States {
    private final Context context;
    private final Map<String,String> snapshot=new HashMap<>();
    public ConditionStates(Context c){context=c.getApplicationContext();}
    @Override public String read(String key) {
        if(snapshot.containsKey(key))return snapshot.get(key);
        String v=null;
        try {
            if("camp".equals(key)) {
                // 原车门设置观察此字段；实车开/关对照为 3 → 0。
                v=SceneStateDecoder.camping(Settings.System.getString(
                        context.getContentResolver(),"zeekr_bs_camp_mode"));
            }else if("rest".equals(key)) {
                String raw=Settings.System.getString(context.getContentResolver(),
                    "zeekr_bs_rest_mode_enable");
                if("1".equals(raw))v="on";else if("0".equals(raw))v="off";
            }else if("gear".equals(key)) {
                EcarxCarManager car=EcarxCarManager.getInstance();car.ensureConnected(context);
                if(car.isConnected())switch(car.readSensorEvent(ISensor.SENSOR_TYPE_GEAR)) {
                    case ISensorEvent.GEAR_PARK:v="P";break;
                    case ISensorEvent.GEAR_DRIVE:v="D";break;
                    case ISensorEvent.GEAR_REVERSE:v="R";break;
                    case ISensorEvent.GEAR_NEUTRAL:v="N";break;
                }
            }else if("time".equals(key)) {
                Calendar now=Calendar.getInstance();v=String.format(Locale.ROOT,"%02d:%02d",now.get(Calendar.HOUR_OF_DAY),now.get(Calendar.MINUTE));
            }
        }catch(Throwable ignored){v=null;}
        snapshot.put(key,v);return v;
    }
}
