package com.selfclink.ble.product;

import com.selfclink.ble.protocol.MiBeacon;

import java.util.List;

/**
 * 把一份声明式 {@link DeviceProfile} 适配成 {@link ProductAdapter}，与内置 code 适配器同框架运行。
 * 这是「共创」的运行时载体：用户导入的 JSON 经此即可参与扫描识别与手势解析。
 *
 * <p>内置解码器：
 * <ul>
 *   <li>{@code mibeacon}：FE95 加密旋钮，原生 AES-CCM 解密后按 objId → 手势。</li>
 *   <li>{@code advmatch}：明文广播按 offset/mask/equals 命中手势，无需密钥。</li>
 * </ul>
 */
public final class DeclarativeAdapter implements ProductAdapter {

    private final DeviceProfile p;

    public DeclarativeAdapter(DeviceProfile profile) {
        this.p = profile;
    }

    public DeviceProfile profile() {
        return p;
    }

    @Override
    public String productId() {
        return p.productId;
    }

    @Override
    public String displayName() {
        return p.displayName;
    }

    @Override
    public CredentialSpec credentialSpec() {
        return p.credential;
    }

    @Override
    public List<Gesture> gestures() {
        return p.gestures;
    }

    @Override
    public boolean matches(ScanFrame f) {
        if (p.serviceUuid != null && !f.hasServiceData(p.serviceUuid)) {
            return false;
        }
        if (p.namePrefix != null && (f.name == null || !f.name.startsWith(p.namePrefix))) {
            return false;
        }
        if (p.manufacturerId != null && f.manufacturerData(p.manufacturerId) == null) {
            return false;
        }
        if (p.productIdLe != null) {
            byte[] sd = p.serviceUuid != null ? f.serviceData(p.serviceUuid) : null;
            if (sd == null || sd.length < 4) {
                return false;
            }
            int pid = (sd[2] & 0xFF) | ((sd[3] & 0xFF) << 8);
            if (pid != p.productIdLe) {
                return false;
            }
        }
        return true;
    }

    @Override
    public DecodedGesture parse(ScanFrame f, byte[] credential) {
        if ("mibeacon".equals(p.codec)) {
            return parseMiBeacon(f, credential);
        }
        if ("advmatch".equals(p.codec)) {
            return parseAdvMatch(f);
        }
        return null;
    }

    private DecodedGesture parseMiBeacon(ScanFrame f, byte[] bindKey) {
        byte[] sd = p.serviceUuid != null ? f.serviceData(p.serviceUuid) : f.serviceData("fe95");
        if (sd == null || bindKey == null) {
            return null;
        }
        MiBeacon.Result r = MiBeacon.parse(sd, bindKey);
        if (r == null) {
            return null;
        }
        String gid = p.gestureObjMap.get(r.objId);
        if (gid == null) {
            return null;
        }
        return new DecodedGesture(gid, r.frameCounter);
    }

    private DecodedGesture parseAdvMatch(ScanFrame f) {
        byte[] data = sourceBytes(f);
        if (data == null) {
            return null;
        }
        for (DeviceProfile.AdvRule rule : p.advRules) {
            if (matchRule(data, rule)) {
                return new DecodedGesture(rule.gestureId);
            }
        }
        return null;
    }

    private byte[] sourceBytes(ScanFrame f) {
        String src = p.advSource;
        if (src == null) {
            return null;
        }
        if (src.startsWith("service:")) {
            return f.serviceData(src.substring("service:".length()).toLowerCase());
        }
        if (src.startsWith("manufacturer:")) {
            String idStr = src.substring("manufacturer:".length()).trim();
            int id = idStr.startsWith("0x") || idStr.startsWith("0X")
                    ? Integer.parseInt(idStr.substring(2), 16)
                    : Integer.parseInt(idStr);
            return f.manufacturerData(id);
        }
        return null;
    }

    private static boolean matchRule(byte[] data, DeviceProfile.AdvRule r) {
        if (r.offset + r.equals.length > data.length) {
            return false;
        }
        for (int i = 0; i < r.equals.length; i++) {
            int actual = data[r.offset + i] & (r.mask[i] & 0xFF);
            int expect = r.equals[i] & (r.mask[i] & 0xFF);
            if (actual != expect) {
                return false;
            }
        }
        return true;
    }
}
