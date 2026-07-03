package com.selfclink.ble.protocol;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/**
 * 小米 MiBeacon（FE95 服务数据）解析 + AES-CCM 解密，用于接入米家蓝牙 Mesh 旋钮/按键的按键事件。
 *
 * <p>这类设备不建立 GATT 连接，仅在被按下时短暂广播一帧加密 FE95；本类把广播原始字节解密成
 * 对象事件（objId）。每台设备的 BindKey（16 字节）由用户登录米家拉取后填入。
 *
 * <p>Android 自带 JCE/Conscrypt <b>不支持</b> AES/CCM，故此处用 AES/ECB/NoPadding
 * 手写 CCM（CTR 解密 + CBC-MAC 校验），算法已在 Python 上对照三种已知手势明文逐字节验证。
 *
 * <p>帧布局（小写 fc 为帧控制 LE uint16）：
 * <pre>
 *   [0..1]  fc      帧控制：bit3=加密(0x08) bit4=MAC(0x10) bit5=CAP(0x20) bit6=对象(0x40)
 *   [2..3]  pid     产品 ID（LE）
 *   [4]     fcnt    帧计数（去重用）
 *   [5..10] mac     设备 MAC（6 字节）
 *   [可选]  cap     能力字节；若 cap&0x20 还跟 2 字节 IO 能力
 *   rest = cipher(...) + extCnt(3) + mic(4)
 *   nonce = mac(6) + pid(2) + fcnt(1) + extCnt(3) = 12
 *   aad   = 0x11，tagLen = 4
 *   明文 = objId(2 LE) + len(1) + value
 * </pre>
 *
 * <p>本类只负责「解密出 objId」，objId→手势 的映射交给上层声明式 Profile（gestureObjMap），
 * 便于不同产品复用同一解码内核（共创）。
 */
public final class MiBeacon {

    private static final int FC_ENCRYPTED = 0x0008;
    private static final int FC_MAC = 0x0010;
    private static final int FC_CAP = 0x0020;
    private static final int FC_OBJECT = 0x0040;

    private static final byte[] AAD = {0x11};
    private static final int TAG_LEN = 4; // M
    private static final int NONCE_LEN = 12;

    /** 一次解密结果。 */
    public static final class Result {
        /** 米家对象 id（如 0x560c）。上层据此映射手势。 */
        public final int objId;
        /** 帧内产品 ID（LE）。用于产品匹配。 */
        public final int productId;
        /** 帧计数，调用方据此对同一次广播的重复帧去重。 */
        public final int frameCounter;
        /** objId 之后的值字节（明文 objId(2)+len(1)+value 里的 value），可空。 */
        public final byte[] value;

        Result(int objId, int productId, int frameCounter, byte[] value) {
            this.objId = objId;
            this.productId = productId;
            this.frameCounter = frameCounter;
            this.value = value;
        }
    }

    private MiBeacon() {
    }

    /**
     * 解析一帧 FE95 服务数据。仅当：含加密对象、解密成功且 MIC 校验通过时返回结果；
     * 否则返回 {@code null}（非按键帧 / 待机帧 / 校验失败）。
     *
     * @param raw     FE95 服务数据原始字节
     * @param bindKey 16 字节 BindKey
     */
    public static Result parse(byte[] raw, byte[] bindKey) {
        if (raw == null || raw.length < 11 || bindKey == null || bindKey.length != 16) {
            return null;
        }
        int fc = (raw[0] & 0xFF) | ((raw[1] & 0xFF) << 8);
        if ((fc & FC_OBJECT) == 0 || (fc & FC_ENCRYPTED) == 0 || (fc & FC_MAC) == 0) {
            return null; // 待机帧 / 明文帧 / 无 MAC，非加密按键事件
        }
        int pid = (raw[2] & 0xFF) | ((raw[3] & 0xFF) << 8);
        int fcnt = raw[4] & 0xFF;
        int idx = 5;
        byte[] mac = new byte[6];
        System.arraycopy(raw, idx, mac, 0, 6);
        idx += 6;
        if ((fc & FC_CAP) != 0) {
            if (idx >= raw.length) {
                return null;
            }
            int cap = raw[idx] & 0xFF;
            idx += 1;
            if ((cap & 0x20) != 0) {
                idx += 2; // IO 能力 2 字节
            }
        }
        int restLen = raw.length - idx;
        if (restLen < 7) {
            return null; // 不足 cipher+extCnt(3)+mic(4)
        }
        int cipherLen = restLen - 7;
        byte[] cipher = new byte[cipherLen];
        System.arraycopy(raw, idx, cipher, 0, cipherLen);
        byte[] extCnt = new byte[3];
        System.arraycopy(raw, idx + cipherLen, extCnt, 0, 3);
        byte[] mic = new byte[4];
        System.arraycopy(raw, idx + cipherLen + 3, mic, 0, 4);

        // nonce = mac(6) + pid(2, 取自帧内原始 LE 字节) + fcnt(1) + extCnt(3)
        byte[] nonce = new byte[NONCE_LEN];
        System.arraycopy(mac, 0, nonce, 0, 6);
        nonce[6] = raw[2];
        nonce[7] = raw[3];
        nonce[8] = (byte) fcnt;
        System.arraycopy(extCnt, 0, nonce, 9, 3);

        byte[] plain;
        try {
            plain = ccmDecrypt(bindKey, nonce, cipher, mic, AAD);
        } catch (Exception e) {
            return null;
        }
        if (plain == null || plain.length < 3) {
            return null; // MIC 失败或明文过短
        }
        int objId = (plain[0] & 0xFF) | ((plain[1] & 0xFF) << 8);
        byte[] value = null;
        if (plain.length >= 4) {
            int vlen = plain[2] & 0xFF;
            int avail = plain.length - 3;
            if (vlen > avail) {
                vlen = avail;
            }
            value = new byte[vlen];
            System.arraycopy(plain, 3, value, 0, vlen);
        }
        return new Result(objId, pid, fcnt, value);
    }

    /**
     * 手写 AES-CCM 解密（L=3, M=4）。校验通过返回明文，MIC 不符返回 {@code null}。
     * 仅依赖 AES/ECB/NoPadding，规避 Android JCE 不支持 CCM 的限制。
     */
    private static byte[] ccmDecrypt(byte[] key, byte[] nonce, byte[] cipher,
                                     byte[] mic, byte[] aad) throws Exception {
        Cipher ecb = Cipher.getInstance("AES/ECB/NoPadding");
        ecb.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"));

        int l = 15 - nonce.length; // =3

        // ---- CTR 解密 ----
        byte[] s0 = ecb.doFinal(ctrBlock(nonce, l, 0));
        byte[] s1 = ecb.doFinal(ctrBlock(nonce, l, 1));
        byte[] plain = new byte[cipher.length];
        for (int i = 0; i < cipher.length; i++) {
            plain[i] = (byte) (cipher[i] ^ s1[i]);
        }

        // ---- CBC-MAC 校验 ----
        int flags0 = 0x40 /*adata*/ + 8 * ((TAG_LEN - 2) / 2) + (l - 1); // =0x4A
        byte[] b0 = new byte[16];
        b0[0] = (byte) flags0;
        System.arraycopy(nonce, 0, b0, 1, nonce.length);
        // 明文长度写入低 l 字节（大端）
        int plen = plain.length;
        for (int i = 0; i < l; i++) {
            b0[15 - i] = (byte) (plen >>> (8 * i));
        }
        byte[] x = ecb.doFinal(b0);

        // AAD 块：2 字节长度（大端）+ aad，补零到 16 的整数倍
        int adLen = aad.length;
        int encLen = 2 + adLen;
        int adPadded = ((encLen + 15) / 16) * 16;
        byte[] enc = new byte[adPadded];
        enc[0] = (byte) (adLen >>> 8);
        enc[1] = (byte) adLen;
        System.arraycopy(aad, 0, enc, 2, adLen);
        for (int off = 0; off < adPadded; off += 16) {
            x = ecb.doFinal(xorBlock(x, enc, off));
        }

        // 明文块：补零到 16 的整数倍
        int ptPadded = ((plen + 15) / 16) * 16;
        byte[] ptPad = new byte[ptPadded];
        System.arraycopy(plain, 0, ptPad, 0, plen);
        for (int off = 0; off < ptPadded; off += 16) {
            x = ecb.doFinal(xorBlock(x, ptPad, off));
        }

        // T = x[:M]，U = T xor s0[:M]
        for (int i = 0; i < TAG_LEN; i++) {
            int u = (x[i] ^ s0[i]) & 0xFF;
            if (u != (mic[i] & 0xFF)) {
                return null; // MIC 不符
            }
        }
        return plain;
    }

    private static byte[] ctrBlock(byte[] nonce, int l, int counter) {
        byte[] block = new byte[16];
        block[0] = (byte) (l - 1); // flags
        System.arraycopy(nonce, 0, block, 1, nonce.length);
        for (int i = 0; i < l; i++) {
            block[15 - i] = (byte) (counter >>> (8 * i));
        }
        return block;
    }

    private static byte[] xorBlock(byte[] x, byte[] src, int off) {
        byte[] out = new byte[16];
        for (int i = 0; i < 16; i++) {
            out[i] = (byte) (x[i] ^ src[off + i]);
        }
        return out;
    }
}
