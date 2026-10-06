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
    /** Android ScanRecord 完整广播字节，包含未被 Service/Manufacturer API 暴露的 AD Structure。 */
    private final byte[] rawRecord;
    /** AD type → payload；覆盖 Android 默认未单独暴露的类型（如 0x10 Device ID）。 */
    private final Map<Integer, byte[]> advertisingData;

    public ScanFrame(String name, String mac, int rssi,
                     Map<String, byte[]> serviceData,
                     Map<Integer, byte[]> manufacturerData) {
        this(name, mac, rssi, serviceData, manufacturerData, null, null);
    }

    public ScanFrame(String name, String mac, int rssi,
                     Map<String, byte[]> serviceData,
                     Map<Integer, byte[]> manufacturerData,
                     byte[] rawRecord) {
        this(name, mac, rssi, serviceData, manufacturerData, rawRecord, null);
    }

    public ScanFrame(String name, String mac, int rssi,
                     Map<String, byte[]> serviceData,
                     Map<Integer, byte[]> manufacturerData,
                     byte[] rawRecord,
                     Map<Integer, byte[]> advertisingData) {
        this.name = name;
        this.mac = mac;
        this.rssi = rssi;
        this.serviceData = serviceData == null ? Collections.emptyMap() : serviceData;
        this.manufacturerData = manufacturerData == null ? Collections.emptyMap() : manufacturerData;
        this.rawRecord = rawRecord == null ? new byte[0] : rawRecord.clone();
        this.advertisingData = advertisingData == null ? Collections.emptyMap() : advertisingData;
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

    /** 只读服务数据全集，供通用抓包/原始广播自学习使用。 */
    public Map<String, byte[]> serviceDataEntries() {
        return Collections.unmodifiableMap(serviceData);
    }

    /** 只读厂商数据全集，供通用抓包/原始广播自学习使用。 */
    public Map<Integer, byte[]> manufacturerDataEntries() {
        return Collections.unmodifiableMap(manufacturerData);
    }

    public byte[] rawRecord() {
        return rawRecord.clone();
    }

    public Map<Integer, byte[]> advertisingDataEntries() {
        return Collections.unmodifiableMap(advertisingData);
    }

    public byte[] advertisingData(int type) {
        return advertisingData.get(type);
    }
}
