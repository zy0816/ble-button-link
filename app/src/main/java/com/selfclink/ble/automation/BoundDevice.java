package com.selfclink.ble.automation;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一台已接入的蓝牙按键设备及其手势→动作编排。
 *
 * <p>{@code bindKeyHex} 为该设备的 BindKey（仅 BINDKEY16 产品需要），随本对象一起存于加密存储；
 * 导出分享 Profile 时<b>不</b>含它。每个手势可绑定多个动作（按顺序执行），数量不限。
 */
public final class BoundDevice {

    public String mac;
    public String productId;
    public String name;
    public String bindKeyHex; // nullable
    /** gestureId → 动作键列表（有序）。 */
    public final Map<String, List<String>> gestureActions = new LinkedHashMap<>();
    /** 自学习事件（非空时，运行时与编排页以此为准，覆盖 Profile 的手势/objId 映射）。 */
    public final List<LearnedEvent> learned = new ArrayList<>();

    public boolean hasLearned() {
        return !learned.isEmpty();
    }

    public BoundDevice() {
    }

    public BoundDevice(String mac, String productId, String name, String bindKeyHex) {
        this.mac = mac;
        this.productId = productId;
        this.name = name;
        this.bindKeyHex = bindKeyHex;
    }

    public List<String> actionsFor(String gestureId) {
        List<String> a = gestureActions.get(gestureId);
        return a == null ? new ArrayList<>() : a;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("mac", mac);
        o.put("productId", productId);
        o.put("name", name);
        if (bindKeyHex != null) {
            o.put("bindKeyHex", bindKeyHex);
        }
        JSONObject ga = new JSONObject();
        for (Map.Entry<String, List<String>> e : gestureActions.entrySet()) {
            ga.put(e.getKey(), new JSONArray(e.getValue()));
        }
        o.put("gestureActions", ga);
        if (!learned.isEmpty()) {
            JSONArray le = new JSONArray();
            for (LearnedEvent e : learned) {
                le.put(e.toJson());
            }
            o.put("learned", le);
        }
        return o;
    }

    public static BoundDevice fromJson(JSONObject o) throws JSONException {
        BoundDevice d = new BoundDevice();
        d.mac = o.getString("mac");
        d.productId = o.getString("productId");
        d.name = o.optString("name", d.mac);
        d.bindKeyHex = o.has("bindKeyHex") ? o.getString("bindKeyHex") : null;
        JSONObject ga = o.optJSONObject("gestureActions");
        if (ga != null) {
            for (java.util.Iterator<String> it = ga.keys(); it.hasNext(); ) {
                String g = it.next();
                JSONArray arr = ga.getJSONArray(g);
                List<String> list = new ArrayList<>();
                for (int i = 0; i < arr.length(); i++) {
                    list.add(arr.getString(i));
                }
                d.gestureActions.put(g, list);
            }
        }
        JSONArray le = o.optJSONArray("learned");
        if (le != null) {
            for (int i = 0; i < le.length(); i++) {
                d.learned.add(LearnedEvent.fromJson(le.getJSONObject(i)));
            }
        }
        return d;
    }
}
