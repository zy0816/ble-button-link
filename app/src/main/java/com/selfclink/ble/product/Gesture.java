package com.selfclink.ble.product;

/** 一个手势定义（如 单击/双击/长按/左旋/右旋）。id 稳定，name 展示。 */
public final class Gesture {
    public final String id;
    public final String name;

    public Gesture(String id, String name) {
        this.id = id;
        this.name = name;
    }
}
