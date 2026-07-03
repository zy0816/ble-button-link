package com.selfclink.ble.cloud;

/** 米家云返回的一台设备（只保留接入按键所需字段）。 */
public final class MiDevice {
    public final String name;
    public final String mac;        // 大写无分隔
    public final String did;
    public final String model;
    public final String beaconKeyHex; // BindKey，可空（非 Mesh 设备无）

    public MiDevice(String name, String mac, String did, String model, String beaconKeyHex) {
        this.name = name;
        this.mac = mac;
        this.did = did;
        this.model = model;
        this.beaconKeyHex = beaconKeyHex;
    }

    public boolean hasBindKey() {
        return beaconKeyHex != null && beaconKeyHex.length() == 32;
    }
}
