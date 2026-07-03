package com.selfclink.ble.ui;

import com.selfclink.ble.R;

/**
 * 给设备挑选图标与配色：旋钮类用旋钮图标，其余在按钮/遥控间按标识哈希分配；颜色在调色板中循环，
 * 使每个按键设备在主页呈现为不同的彩色图标。
 */
public final class IconPicker {

    private static final int[] COLORS = {
            0xFF7FB4D9, // accent
            0xFFA7D3A7, // green
            0xFFD9B27F, // warn
            0xFFB39DDB, // purple
            0xFFE0A3B0  // pink
    };

    private IconPicker() {
    }

    public static int iconRes(String productId) {
        if (productId == null) {
            return R.drawable.ic_button;
        }
        String p = productId.toLowerCase();
        if (p.contains("knob") || p.contains("rotate") || p.contains("dial")) {
            return R.drawable.ic_knob;
        }
        if (p.contains("remote")) {
            return R.drawable.ic_remote;
        }
        return R.drawable.ic_button;
    }

    public static int tint(String key) {
        int h = key == null ? 0 : Math.abs(key.hashCode());
        return COLORS[h % COLORS.length];
    }
}
