package com.selfclink.ble.automation;

import com.selfclink.ble.util.HexUtil;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * 一个「自学习」得到的按键事件签名：objId + value 掩码。用于无预置 Profile 时，用户现场教一遍即可用。
 *
 * <p>{@code mask}/{@code expected} 覆盖 MiBeacon 明文 value 字节：匹配要求 objId 相等，且对每个
 * 掩码位 {@code (value[i] & mask[i]) == (expected[i] & mask[i])}。掩码由学习时多帧求稳定位自动生成
 * （每次都相同的字节=判别位 0xFF，变化的字节=忽略 0x00），故左旋/右旋这类同 objId 靠 value 区分的事件也能拆开。
 */
public final class LearnedEvent {

    public String id;         // 稳定 id，如 "e1"
    public String label;      // 展示名，如 "左旋"
    public int objId;         // MiBeacon 对象 id
    public byte[] mask;       // 覆盖 value 的掩码
    public byte[] expected;   // 覆盖 value 的期望值（掩码下比较）

    public LearnedEvent() {
    }

    public LearnedEvent(String id, String label, int objId, byte[] mask, byte[] expected) {
        this.id = id;
        this.label = label;
        this.objId = objId;
        this.mask = mask == null ? new byte[0] : mask;
        this.expected = expected == null ? new byte[0] : expected;
    }

    /** 一帧解密结果是否命中本事件。 */
    public boolean matches(int oid, byte[] value) {
        if (oid != objId) {
            return false;
        }
        byte[] v = value == null ? new byte[0] : value;
        for (int i = 0; i < mask.length; i++) {
            int m = mask[i] & 0xFF;
            if (m == 0) {
                continue;
            }
            int actual = (i < v.length ? v[i] : 0) & m;
            int expect = expected[i] & m;
            if (actual != expect) {
                return false;
            }
        }
        return true;
    }

    /** 供 UI 展示的编码摘要。 */
    public String summary() {
        StringBuilder sb = new StringBuilder(String.format(java.util.Locale.US, "objId=0x%04X", objId));
        if (mask.length > 0) {
            sb.append(" 判别=");
            for (int i = 0; i < mask.length; i++) {
                if ((mask[i] & 0xFF) != 0) {
                    sb.append(String.format(java.util.Locale.US, "%02X", expected[i] & 0xFF));
                } else {
                    sb.append("··");
                }
            }
        }
        return sb.toString();
    }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("label", label);
        o.put("objId", objId);
        o.put("mask", HexUtil.toHex(mask));
        o.put("expected", HexUtil.toHex(expected));
        return o;
    }

    public static LearnedEvent fromJson(JSONObject o) throws JSONException {
        LearnedEvent e = new LearnedEvent();
        e.id = o.getString("id");
        e.label = o.optString("label", e.id);
        e.objId = o.getInt("objId");
        e.mask = HexUtil.fromHex(o.optString("mask", ""));
        e.expected = HexUtil.fromHex(o.optString("expected", ""));
        return e;
    }
}
