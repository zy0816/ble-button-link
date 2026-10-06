package com.selfclink.ble.automation;
import android.content.Context;
import android.content.SharedPreferences;
import java.util.*;
import org.json.*;
/** 独立保存条件，不改写设备、BindKey 或动作绑定。损坏配置不得静默绕过。 */
public final class ExecutionRuleStore {
    private final SharedPreferences prefs;
    public ExecutionRuleStore(Context c){prefs=c.getApplicationContext().getSharedPreferences("execution_rules",Context.MODE_PRIVATE);}
    public List<ExecutionRule> load() {
        try {
            JSONArray a=new JSONArray(prefs.getString("rules","[]")); List<ExecutionRule> rs=new ArrayList<>();
            for(int i=0;i<a.length();i++)rs.add(ExecutionRule.fromJson(a.getJSONObject(i)));
            return rs;
        }catch(Exception e){throw new IllegalStateException("执行条件读取失败，已阻止执行；请在设置中修复规则",e);}
    }
    public void save(List<ExecutionRule> rules) {
        try { JSONArray a=new JSONArray();for(ExecutionRule r:rules){r.validate();a.put(r.toJson());}
            if(!prefs.edit().putString("rules",a.toString()).commit())throw new IllegalStateException("规则保存失败");
        }catch(JSONException e){throw new IllegalStateException("规则保存失败",e);}
    }
}
