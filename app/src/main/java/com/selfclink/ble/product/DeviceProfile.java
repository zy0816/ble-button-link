package com.selfclink.ble.product;

import com.selfclink.ble.util.HexUtil;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 声明式设备配置（共创核心）。一份 JSON 描述「某种蓝牙按键如何被识别、需不需要密钥、怎么解析手势」，
 * 可导入 / 导出 / 分享，无需改代码即可新增设备。详见 docs/设计文档.md §3.1。
 *
 * <p>导出分享时<b>不含</b> BindKey（个人密钥），只含解析规则，可安全公开。
 */
public final class DeviceProfile {

    public static final int SCHEMA = 1;

    public final String productId;
    public final String displayName;
    public final String author;

    // ---- match：全部非空条件都要命中 ----
    public final String serviceUuid;     // 小写 4 hex 短 uuid，可空
    public final Integer productIdLe;    // FE95 帧内 pid（LE），可空
    public final String namePrefix;      // 设备名前缀，可空
    public final Integer manufacturerId; // 厂商数据 company id，可空

    public final ProductAdapter.CredentialSpec credential;

    // ---- decode ----
    public final String codec;                       // "mibeacon" | "advmatch"
    public final Map<Integer, String> gestureObjMap; // codec=mibeacon: objId → gestureId
    public final String advSource;                   // codec=advmatch: "service:fe95" | "manufacturer:0x038f"
    public final List<AdvRule> advRules;             // codec=advmatch

    public final List<Gesture> gestures;

    /** advmatch 单条规则：在数据 offset 处 (byte & mask) == equals 即命中该手势。 */
    public static final class AdvRule {
        public final String gestureId;
        public final int offset;
        public final byte[] mask;
        public final byte[] equals;

        AdvRule(String gestureId, int offset, byte[] mask, byte[] equals) {
            this.gestureId = gestureId;
            this.offset = offset;
            this.mask = mask;
            this.equals = equals;
        }
    }

    private DeviceProfile(Builder b) {
        this.productId = b.productId;
        this.displayName = b.displayName;
        this.author = b.author;
        this.serviceUuid = b.serviceUuid;
        this.productIdLe = b.productIdLe;
        this.namePrefix = b.namePrefix;
        this.manufacturerId = b.manufacturerId;
        this.credential = b.credential;
        this.codec = b.codec;
        this.gestureObjMap = b.gestureObjMap;
        this.advSource = b.advSource;
        this.advRules = b.advRules;
        this.gestures = b.gestures;
    }

    private static final class Builder {
        String productId, displayName, author, serviceUuid, namePrefix, codec, advSource;
        Integer productIdLe, manufacturerId;
        ProductAdapter.CredentialSpec credential = ProductAdapter.CredentialSpec.NONE;
        Map<Integer, String> gestureObjMap = new LinkedHashMap<>();
        List<AdvRule> advRules = new ArrayList<>();
        List<Gesture> gestures = new ArrayList<>();
    }

    /**
     * 解析 + 校验一份 Profile JSON。非法抛 {@link IllegalArgumentException}（带原因）。
     */
    public static DeviceProfile fromJson(String json) {
        try {
            return fromJson(new JSONObject(json));
        } catch (JSONException e) {
            throw new IllegalArgumentException("JSON 解析失败: " + e.getMessage());
        }
    }

    public static DeviceProfile fromJson(JSONObject o) {
        Builder b = new Builder();
        b.productId = req(o, "productId").trim();
        b.displayName = o.optString("displayName", b.productId);
        b.author = o.optString("author", "");

        JSONObject match = o.optJSONObject("match");
        if (match == null) {
            throw new IllegalArgumentException("缺少 match");
        }
        b.serviceUuid = optLowerOrNull(match, "serviceUuid");
        b.productIdLe = optIntOrNull(match, "productIdLe");
        b.namePrefix = optStrOrNull(match, "namePrefix");
        b.manufacturerId = optIntOrNull(match, "manufacturerId");
        if (b.serviceUuid == null && b.productIdLe == null
                && b.namePrefix == null && b.manufacturerId == null) {
            throw new IllegalArgumentException("match 至少要有一条匹配条件");
        }

        String cred = o.optString("credential", "NONE").toUpperCase();
        if ("BINDKEY16".equals(cred)) {
            b.credential = ProductAdapter.CredentialSpec.BINDKEY16;
        } else if ("NONE".equals(cred)) {
            b.credential = ProductAdapter.CredentialSpec.NONE;
        } else {
            throw new IllegalArgumentException("非法 credential: " + cred);
        }

        JSONObject decode = o.optJSONObject("decode");
        if (decode == null) {
            throw new IllegalArgumentException("缺少 decode");
        }
        b.codec = decode.optString("codec", "");
        if ("mibeacon".equals(b.codec)) {
            JSONObject m = decode.optJSONObject("gestureObjMap");
            if (m == null || m.length() == 0) {
                throw new IllegalArgumentException("mibeacon 需要 gestureObjMap");
            }
            for (java.util.Iterator<String> it = m.keys(); it.hasNext(); ) {
                String k = it.next();
                b.gestureObjMap.put(parseInt(k), m.optString(k));
            }
        } else if ("advmatch".equals(b.codec)) {
            b.advSource = req(decode, "source");
            JSONArray rules = decode.optJSONArray("rules");
            if (rules == null || rules.length() == 0) {
                throw new IllegalArgumentException("advmatch 需要 rules");
            }
            for (int i = 0; i < rules.length(); i++) {
                JSONObject r = rules.optJSONObject(i);
                String g = req(r, "gesture");
                int off = r.optInt("offset", -1);
                if (off < 0) {
                    throw new IllegalArgumentException("rule.offset 非法");
                }
                byte[] eq = HexUtil.fromHex(req(r, "equals"));
                byte[] mask = r.has("mask")
                        ? HexUtil.fromHex(r.optString("mask"))
                        : fullMask(eq.length);
                if (mask.length != eq.length) {
                    throw new IllegalArgumentException("rule.mask 与 equals 长度不一致");
                }
                b.advRules.add(new AdvRule(g, off, mask, eq));
            }
        } else {
            throw new IllegalArgumentException("不支持的 codec: " + b.codec
                    + "（仅 mibeacon / advmatch）");
        }

        JSONArray gs = o.optJSONArray("gestures");
        if (gs == null || gs.length() == 0) {
            throw new IllegalArgumentException("缺少 gestures");
        }
        for (int i = 0; i < gs.length(); i++) {
            JSONObject g = gs.optJSONObject(i);
            String id = req(g, "id");
            b.gestures.add(new Gesture(id, g.optString("name", id)));
        }
        return new DeviceProfile(b);
    }

    /** 导出为分享 JSON（不含任何 BindKey）。 */
    public JSONObject toJson() {
        try {
            JSONObject o = new JSONObject();
            o.put("schema", SCHEMA);
            o.put("productId", productId);
            o.put("displayName", displayName);
            if (author != null && !author.isEmpty()) {
                o.put("author", author);
            }
            JSONObject match = new JSONObject();
            if (serviceUuid != null) match.put("serviceUuid", serviceUuid);
            if (productIdLe != null) match.put("productIdLe", "0x" + Integer.toHexString(productIdLe));
            if (namePrefix != null) match.put("namePrefix", namePrefix);
            if (manufacturerId != null) match.put("manufacturerId", manufacturerId);
            o.put("match", match);
            o.put("credential", credential.name());

            JSONObject decode = new JSONObject();
            decode.put("codec", codec);
            if ("mibeacon".equals(codec)) {
                JSONObject m = new JSONObject();
                for (Map.Entry<Integer, String> e : gestureObjMap.entrySet()) {
                    m.put("0x" + Integer.toHexString(e.getKey()), e.getValue());
                }
                decode.put("gestureObjMap", m);
            } else {
                decode.put("source", advSource);
                JSONArray rules = new JSONArray();
                for (AdvRule r : advRules) {
                    JSONObject jr = new JSONObject();
                    jr.put("gesture", r.gestureId);
                    jr.put("offset", r.offset);
                    jr.put("mask", HexUtil.toHex(r.mask));
                    jr.put("equals", HexUtil.toHex(r.equals));
                    rules.put(jr);
                }
                decode.put("rules", rules);
            }
            o.put("decode", decode);

            JSONArray gs = new JSONArray();
            for (Gesture g : gestures) {
                gs.put(new JSONObject().put("id", g.id).put("name", g.name));
            }
            o.put("gestures", gs);
            return o;
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---------------- helpers ----------------

    private static String req(JSONObject o, String key) {
        String v = o.optString(key, "");
        if (v == null || v.isEmpty()) {
            throw new IllegalArgumentException("缺少字段: " + key);
        }
        return v;
    }

    private static String optStrOrNull(JSONObject o, String key) {
        if (!o.has(key)) return null;
        String v = o.optString(key, "");
        return v.isEmpty() ? null : v;
    }

    private static String optLowerOrNull(JSONObject o, String key) {
        String v = optStrOrNull(o, key);
        return v == null ? null : v.toLowerCase();
    }

    private static Integer optIntOrNull(JSONObject o, String key) {
        if (!o.has(key) || o.isNull(key)) return null;
        Object v = o.opt(key);
        if (v instanceof Number) {
            return ((Number) v).intValue();
        }
        return parseInt(String.valueOf(v));
    }

    private static int parseInt(String s) {
        s = s.trim();
        if (s.startsWith("0x") || s.startsWith("0X")) {
            return Integer.parseInt(s.substring(2), 16);
        }
        return Integer.parseInt(s);
    }

    private static byte[] fullMask(int len) {
        byte[] m = new byte[len];
        for (int i = 0; i < len; i++) {
            m[i] = (byte) 0xFF;
        }
        return m;
    }
}
