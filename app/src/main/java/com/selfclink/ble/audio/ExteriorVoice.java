package com.selfclink.ble.audio;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;

import com.ecarx.xui.adaptapi.policy.IAudioAttributes;
import com.ecarx.xui.adaptapi.policy.Policy;
import com.selfclink.ble.util.AppLog;

/**
 * 车外喊话引擎：把车内麦克风的实时音频直接喂到<b>车外喇叭</b>（行人提示喇叭 / PA），实现「实时喊话」。
 *
 * <p>路由关键：车机 ecarx {@link Policy} 提供了一组按名解析的车外音频属性——
 * usage={@code USAGE_OCC_MIC}、contentType={@code CONTENT_TYPE_OCC}。用它构造
 * {@link AudioTrack} 的 {@link AudioAttributes}，车机音频策略便会把这路 PCM 输出到车外喇叭，
 * 无需单独去切 AVAS 开关。
 *
 * <p>数据链：{@link AudioRecord}（MIC，16kHz 单声道 PCM16）实时读取 → 直接
 * {@link AudioTrack#write} 到车外喇叭。全程只在内存中转，<b>不落盘、不上传</b>。
 *
 * <p>进程内单例；{@link #toggle(Context)} 一按开、再按关。非 ecarx 环境（单测 JVM 等）静默降级。
 */
public final class ExteriorVoice {

    private static final String TAG = "ExteriorVoice";

    private static final int SAMPLE_RATE = 16000;
    private static final int IN_CHANNEL = AudioFormat.CHANNEL_IN_MONO;
    private static final int OUT_CHANNEL = AudioFormat.CHANNEL_OUT_MONO;
    private static final int ENCODING = AudioFormat.ENCODING_PCM_16BIT;

    private static ExteriorVoice instance;

    private volatile boolean running;
    private Thread worker;

    private ExteriorVoice() {
    }

    public static synchronized ExteriorVoice get() {
        if (instance == null) {
            instance = new ExteriorVoice();
        }
        return instance;
    }

    public boolean isOn() {
        return running;
    }

    /** 一键切换：开→关 / 关→开。返回切换后的状态（true=喊话中）。 */
    public synchronized boolean toggle(Context context) {
        if (running) {
            stop();
        } else {
            start(context);
        }
        return running;
    }

    /** 显式设置开关（供 UI 开关直接调用）。 */
    public synchronized void setOn(Context context, boolean on) {
        if (on == running) {
            return;
        }
        if (on) {
            start(context);
        } else {
            stop();
        }
    }

    private synchronized void start(Context context) {
        if (running) {
            return;
        }
        final AudioAttributes attrs = buildExteriorAttributes(context);
        if (attrs == null) {
            AppLog.d(TAG, "取车外音频属性失败，无法开启车外喊话");
            return;
        }
        running = true;
        worker = new Thread(() -> loop(attrs), "exterior-voice");
        worker.start();
        AppLog.d(TAG, "车外喊话已开启");
    }

    private synchronized void stop() {
        running = false;
        if (worker != null) {
            worker.interrupt();
            worker = null;
        }
        AppLog.d(TAG, "车外喊话已关闭");
    }

    /** 用 ecarx Policy 解析车外喇叭（OCC_MIC）音频属性；不可用时返回 null。 */
    private AudioAttributes buildExteriorAttributes(Context context) {
        try {
            Policy policy = Policy.create(context.getApplicationContext());
            if (policy == null) {
                return null;
            }
            IAudioAttributes ecarxAttrs = policy.getAudioAttributes();
            if (ecarxAttrs == null) {
                return null;
            }
            int usage = ecarxAttrs.getAudioAtrributesUsage(IAudioAttributes.USAGE_OCC_MIC);
            int contentType = ecarxAttrs.getAudioAtrributesContentType(IAudioAttributes.CONTENT_TYPE_OCC);
            return new AudioAttributes.Builder()
                    .setUsage(usage)
                    .setContentType(contentType)
                    .build();
        } catch (Throwable t) {
            AppLog.d(TAG, "构造车外音频属性异常: " + t.getMessage());
            return null;
        }
    }

    private void loop(AudioAttributes attrs) {
        AudioRecord record = null;
        AudioTrack track = null;
        try {
            int inBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, IN_CHANNEL, ENCODING);
            int outBuf = AudioTrack.getMinBufferSize(SAMPLE_RATE, OUT_CHANNEL, ENCODING);
            if (inBuf <= 0) {
                inBuf = SAMPLE_RATE; // 兜底 1s
            }
            if (outBuf <= 0) {
                outBuf = SAMPLE_RATE;
            }

            record = new AudioRecord(MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE, IN_CHANNEL, ENCODING, inBuf);

            AudioFormat outFormat = new AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(OUT_CHANNEL)
                    .setEncoding(ENCODING)
                    .build();
            track = new AudioTrack(attrs, outFormat, outBuf,
                    AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE);

            if (record.getState() != AudioRecord.STATE_INITIALIZED
                    || track.getState() != AudioTrack.STATE_INITIALIZED) {
                AppLog.d(TAG, "录音/放音初始化失败");
                return;
            }

            record.startRecording();
            track.play();

            byte[] frame = new byte[Math.max(inBuf, 3200)];
            while (running && !Thread.currentThread().isInterrupted()) {
                int n = record.read(frame, 0, frame.length);
                if (n > 0) {
                    track.write(frame, 0, n);
                } else if (n < 0) {
                    AppLog.d(TAG, "读麦克风返回 " + n + "，停止");
                    break;
                }
            }
        } catch (Throwable t) {
            AppLog.d(TAG, "车外喊话循环异常: " + t.getMessage());
        } finally {
            running = false;
            safeStopRecord(record);
            safeStopTrack(track);
        }
    }

    private static void safeStopRecord(AudioRecord record) {
        if (record == null) {
            return;
        }
        try {
            if (record.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                record.stop();
            }
        } catch (Throwable ignored) {
        }
        try {
            record.release();
        } catch (Throwable ignored) {
        }
    }

    private static void safeStopTrack(AudioTrack track) {
        if (track == null) {
            return;
        }
        try {
            if (track.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) {
                track.stop();
            }
        } catch (Throwable ignored) {
        }
        try {
            track.release();
        } catch (Throwable ignored) {
        }
    }
}
