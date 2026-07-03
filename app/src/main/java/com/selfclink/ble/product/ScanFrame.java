package com.selfclink.ble.product;

import java.util.Collections;
import java.util.Map;

/**
 * 一帧 BLE 广播的与平台无关抽象，供 {@link ProductAdapter} 解析，便于单测（不依赖 android ScanResult）。
 *
 * <p>由 {@code ble.BleScanner} 从系统 {@code ScanResult} 转换得到。
 */
public final class ScanFrame {

    /** 设备名（可空）。 */
    public final String name;
    /** 设备 MAC，统一大写无分隔（如 "A1B2C3D4E5F6"）。 */
    public final String mac;
    /** 信号强度。 */
    public final int rssi;
    /** 16-bit service uuid（小写 4 hex，如 "fe95"）→ 服务数据原始字节。 */
    private final Map<String, byte[]> serviceData;
    /** company id → 厂商数据原始字节。 */
    private final Map<Integer, byte[]> manufacturerData;

    public ScanFrame(String name, String mac, int rssi,
                     Map<String, byte[]> serviceData,
                     Map<Integer, byte[]> manufacturerData) {
        this.name = name;
        this.mac = mac;
        this.rssi = rssi;
        this.serviceData = serviceData == null ? Collections.emptyMap() : serviceData;
        this.manufacturerData = manufacturerData == null ? Collections.emptyMap() : manufacturerData;
    }

    /** 取某 16-bit service uuid 的服务数据（小写 4 hex 短 uuid），无则 null。 */
    public byte[] serviceData(String shortUuid) {
        return serviceData.get(shortUuid == null ? null : shortUuid.toLowerCase());
    }

    /** 取某 company id 的厂商数据，无则 null。 */
    public byte[] manufacturerData(int companyId) {
        return manufacturerData.get(companyId);
    }

    public boolean hasServiceData(String shortUuid) {
        return shortUuid != null && serviceData.containsKey(shortUuid.toLowerCase());
    }
}
