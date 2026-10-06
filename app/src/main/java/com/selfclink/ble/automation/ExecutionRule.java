package com.selfclink.ble.automation;

import java.util.*;
import org.json.*;

/** 用户条件规则；空设备集合代表全部设备，动作集合必须非空。 */
public final class ExecutionRule {
    public String id = UUID.randomUUID().toString(), name = "新规则";
    public boolean enabled = true, deny = true, all = true;
    public final Set<String> devices = new LinkedHashSet<>();
    public final Set<String> actions = new LinkedHashSet<>();
    public final List<Condition> conditions = new ArrayList<>();
    public static final class Condition {
        public String state, op, value;
        public Condition(String state, String op, String value) {
            this.state = state; this.op = op; this.value = value;
        }
    }
    public void validate() {
        if (id == null || id.isEmpty() || name.trim().isEmpty() || actions.isEmpty() || conditions.isEmpty())
            throw new IllegalArgumentException("请填写名称、条件并选择动作");
        for (Condition c : conditions) {
            if ("time".equals(c.state)) {
                if (!"between".equals(c.op) || !c.value.matches("\\d{2}:\\d{2}-\\d{2}:\\d{2}"))
                    throw new IllegalArgumentException("时间段格式为 22:00-07:00");
                int start = minutes(c.value.substring(0,5)), end = minutes(c.value.substring(6));
                if (start == end) throw new IllegalArgumentException("开始和结束时间不能相同");
            } else {
                if (!"eq".equals(c.op) && !"ne".equals(c.op)) throw new IllegalArgumentException("比较方式无效");
                if ("gear".equals(c.state)) {
                    if (!Arrays.asList("P","D","R","N").contains(c.value)) throw new IllegalArgumentException("挡位无效");
                } else if ("camp".equals(c.state) || "rest".equals(c.state)) {
                    if (!Arrays.asList("on","off").contains(c.value)) throw new IllegalArgumentException("状态无效");
                } else throw new IllegalArgumentException("不支持的车况条件");
            }
        }
    }
    public static int minutes(String text) {
        String[] parts = text.split(":");
        if (parts.length != 2) throw new IllegalArgumentException("时间无效");
        int h = Integer.parseInt(parts[0]), m = Integer.parseInt(parts[1]);
        if (h < 0 || h > 23 || m < 0 || m > 59) throw new IllegalArgumentException("时间超出范围");
        return h * 60 + m;
    }
    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id",id).put("name",name).put("enabled",enabled).put("deny",deny).put("all",all);
        o.put("devices",new JSONArray(devices)).put("actions",new JSONArray(actions));
        JSONArray cs = new JSONArray();
        for (Condition c:conditions) cs.put(new JSONObject().put("state",c.state).put("op",c.op).put("value",c.value));
        return o.put("conditions",cs);
    }
    public static ExecutionRule fromJson(JSONObject o) throws JSONException {
        return fromJson(o, true);
    }
    /** 仅用于未完成的编辑草稿；持久化规则始终使用严格校验入口。 */
    public static ExecutionRule fromJson(JSONObject o, boolean validate) throws JSONException {
        ExecutionRule r = new ExecutionRule();
        r.id=o.getString("id"); r.name=o.getString("name"); r.enabled=o.getBoolean("enabled");
        r.deny=o.getBoolean("deny"); r.all=o.getBoolean("all");
        JSONArray ds=o.getJSONArray("devices"), as=o.getJSONArray("actions"), cs=o.getJSONArray("conditions");
        for(int i=0;i<ds.length();i++)r.devices.add(ds.getString(i));
        for(int i=0;i<as.length();i++)r.actions.add(as.getString(i));
        for(int i=0;i<cs.length();i++) { JSONObject c=cs.getJSONObject(i);
            r.conditions.add(new Condition(c.getString("state"),c.getString("op"),c.getString("value"))); }
        if (validate) r.validate(); return r;
    }
}
