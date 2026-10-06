package com.selfclink.ble.protocol;

import com.selfclink.ble.product.ScanFrame;

/**
 * 实车采样确认的 V5 八键 AD 0x07 简短按键广播。
 * 目前仅支持已验证的 01 01 10 帧形态，不推断其它固件或双击/长按。
 * payload = header(3) + 地址后半段(3) + sequence(1) + key(1) + opaque(8)。
 * opaque 每次按下都会变化，不能拿来当稳定按键码，也不声称已验证它的完整性。
 */
public final class GiotKeyPacket {
    public static final String SOURCE = "giot:key";
    public final int key;
    public final int sequence;

    private GiotKeyPacket(int key, int sequence) {
        this.key = key;
        this.sequence = sequence;
    }

    public byte[] signature() { return new byte[]{(byte) key}; }

    public static GiotKeyPacket parse(ScanFrame frame) {
        return frame == null ? null : parse(frame.advertisingData(0x07), frame.mac);
    }

    public static GiotKeyPacket parse(byte[] data, String mac) {
        if (data == null || data.length != 16 || mac == null) return null;
        if (data[0] != 0x01 || data[1] != 0x01 || data[2] != 0x10) return null;
        String address = mac.replace(":", "").replace("-", "");
        if (address.length() != 12) return null;
        try {
            for (int i = 0; i < 3; i++) {
                int octet = Integer.parseInt(address.substring(6 + i * 2, 8 + i * 2), 16);
                if ((data[3 + i] & 0xFF) != octet) return null;
            }
        } catch (NumberFormatException e) { return null; }
        int key = data[7] & 0xFF;
        if (key < 1 || key > 8) return null;
        return new GiotKeyPacket(key, data[6] & 0xFF);
    }
}
