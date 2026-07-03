package com.selfclink.ble.product;

import java.util.List;

/**
 * 产品适配框架核心接口。新增一种蓝牙按键产品 = 实现本接口并注册到 {@link ProductRegistry}，主流程不变。
 *
 * <p>两类实现：
 * <ul>
 *   <li>内置 code 适配器：需要原生解密/复杂解析的产品（如 {@code MijiaMeshRemoteAdapter}）。</li>
 *   <li>{@link DeclarativeAdapter}：用户抓包后写的声明式 JSON Profile，可导入/分享（共创）。</li>
 * </ul>
 */
public interface ProductAdapter {

    /** 凭据需求。 */
    enum CredentialSpec {
        /** 明文设备，无需密钥。 */
        NONE,
        /** 需要 16 字节 BindKey（如米家加密旋钮）。 */
        BINDKEY16
    }

    /** 全局唯一稳定标识，如 "mijia.knob.ts00"。 */
    String productId();

    /** 展示名，如 "米家蓝牙旋钮"。 */
    String displayName();

    /** 据广播判断是否本产品。 */
    boolean matches(ScanFrame frame);

    /** 本产品所需凭据。 */
    CredentialSpec credentialSpec();

    /** 本产品支持的手势集合（数量不限）。 */
    List<Gesture> gestures();

    /**
     * 解析一帧广播为手势事件；非按键帧 / 解密失败 / 凭据不符返回 {@code null}。
     *
     * @param frame      广播帧
     * @param credential 凭据（BINDKEY16 时为 16 字节 BindKey；NONE 时为 null）
     */
    DecodedGesture parse(ScanFrame frame, byte[] credential);
}
