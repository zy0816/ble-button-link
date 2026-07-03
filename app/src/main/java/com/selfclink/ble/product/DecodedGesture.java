package com.selfclink.ble.product;

/**
 * 适配器从一帧广播解析出的手势事件。
 *
 * <p>{@link #dedup} 用于同一次按下的重复广播去重：加密旋钮(mibeacon)用帧计数，
 * 同次按下多帧同值；无计数的明文设备(advmatch)传 {@link #NO_DEDUP}，由上层按时间去重。
 */
public final class DecodedGesture {

    public static final int NO_DEDUP = -1;

    public final String gestureId;
    public final int dedup;

    public DecodedGesture(String gestureId, int dedup) {
        this.gestureId = gestureId;
        this.dedup = dedup;
    }

    public DecodedGesture(String gestureId) {
        this(gestureId, NO_DEDUP);
    }
}
