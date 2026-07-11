package com.selfclink.ble.audio;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.NoiseSuppressor;

import com.ecarx.xui.adaptapi.audio.audiofx.Audio;
import com.ecarx.xui.adaptapi.policy.IAudioAttributes;
import com.ecarx.xui.adaptapi.policy.Policy;
import com.selfclink.ble.util.AppLog;

/**
 * 车外喊话引擎：把舱内麦克风的实时音频喊向<b>车外喇叭</b>（行人提示喇叭 / PA），实现「实时喊话」。
 *
 * <p><b>路由</b>：车机 ecarx {@link Policy} 的车外音频属性 usage={@code USAGE_OCC_MIC}、
 * contentType={@code CONTENT_TYPE_OCC} 构造 {@link AudioTrack}，车机音频策略即把这路 PCM
 * 送到车外喇叭（此路已验证能出声）。
 *
 * <p><b>降噪/回声消除</b>：录音源用 {@link MediaRecorder.AudioSource#VOICE_COMMUNICATION}
 * ——这是平台为「扬声器+麦克风同时工作」（免提/喊话）准备的音源，会启用 DSP 自带的
 * 声学回声消除（AEC）/降噪（NS）/自动增益（AGC），与原车车外喊话同一套硬件处理；
 * 再在录音会话上显式挂 {@link AcousticEchoCanceler}/{@link NoiseSuppressor}/{@link AutomaticGainControl}
 * 作双保险。之前 v3.8 用原始 {@code MIC} 源无任何处理，故有难听的回声。
 *
 * <p>{@code Audio.setMicOccupyState} 经反编译确认仅点亮仪表「喊话中」图标、不参与音频路由，
 * 故此处只作最佳努力调用。进程内单例；{@link #toggle(Context)} 一按开、再按关。
 */
public final class ExteriorVoice {

    private static final String TAG = "ExteriorVoice";

    private static final int SAMPLE_RATE = 16000;
    private static final int IN_CHANNEL = AudioFormat.CHANNEL_IN_MONO;
    private static final int OUT_CHANNEL = AudioFormat.CHANNEL_OUT_MONO;
    private static final int ENCODING = AudioFormat.ENCODING_PCM_16BIT;

    /**
     * 软件增益倍数：VOICE_COMMUNICATION 处理后电平偏低。语音是尖峰信号（峰值高、均值低），
     * 用大增益 + {@code tanh} 饱和把更多语音推到接近满幅，抬高平均电平——像扩音喇叭那样把小
     * 功率车外喇叭"催"到上限，听感才够响。饱和只加谐波不产生回声。
     */
    private static final float GAIN = 10.0f;

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
        setOn(context, !running);
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
            stop(context);
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
        final Context app = context.getApplicationContext();
        worker = new Thread(() -> loop(app, attrs), "exterior-voice");
        worker.start();
        notifyCluster(app, true);
        AppLog.d(TAG, "车外喊话已开启");
    }

    private synchronized void stop(Context context) {
        running = false;
        if (worker != null) {
            worker.interrupt();
            worker = null;
        }
        notifyCluster(context.getApplicationContext(), false);
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

    private void loop(Context context, AudioAttributes attrs) {
        AudioRecord record = null;
        AudioTrack track = null;
        AcousticEchoCanceler aec = null;
        NoiseSuppressor ns = null;
        try {
            int inBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, IN_CHANNEL, ENCODING);
            int outBuf = AudioTrack.getMinBufferSize(SAMPLE_RATE, OUT_CHANNEL, ENCODING);
            if (inBuf <= 0) {
                inBuf = SAMPLE_RATE;
            }
            if (outBuf <= 0) {
                outBuf = SAMPLE_RATE;
            }

            // VOICE_COMMUNICATION：启用 DSP 自带 AEC/降噪/AGC（免提/喊话专用音源）。
            record = new AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    SAMPLE_RATE, IN_CHANNEL, ENCODING, inBuf);

            // 只挂 AEC + 降噪：保留回声消除与降噪，但不挂 AGC——AGC 会把「喊话」拉平变小声。
            int session = record.getAudioSessionId();
            aec = enableAec(session);
            ns = enableNs(session);

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

            track.setVolume(AudioTrack.getMaxVolume());
            maxOutStream(context, attrs);

            record.startRecording();
            track.play();

            byte[] frame = new byte[Math.max(inBuf, 3200)];
            while (running && !Thread.currentThread().isInterrupted()) {
                int n = record.read(frame, 0, frame.length);
                if (n > 0) {
                    amplify(frame, n, GAIN);
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
            safeRelease(aec);
            safeRelease(ns);
            safeStopRecord(record);
            safeStopTrack(track);
        }
    }

    /** 把车外喇叭（OCC）音量流拉到最大，避免系统音量档位压低喊话声。 */
    private static void maxOutStream(Context context, AudioAttributes attrs) {
        try {
            AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            int stream = attrs.getVolumeControlStream();
            if (am == null || stream < 0) {
                return;
            }
            am.setStreamVolume(stream, am.getStreamMaxVolume(stream), 0);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 就地放大 16bit PCM 小端样本：先乘增益，再用 {@code tanh} 平滑饱和限幅到 [-1,1]。
     * 相比硬削顶，安静处被大幅抬高、大声处平滑压住不破音——响且干净。
     */
    private static void amplify(byte[] buf, int len, float gain) {
        for (int i = 0; i + 1 < len; i += 2) {
            int raw = (short) ((buf[i] & 0xff) | (buf[i + 1] << 8));
            double y = Math.tanh(gain * (raw / 32768.0));
            int s = (int) Math.round(y * 32767.0);
            buf[i] = (byte) (s & 0xff);
            buf[i + 1] = (byte) ((s >> 8) & 0xff);
        }
    }

    private static AcousticEchoCanceler enableAec(int session) {
        if (!AcousticEchoCanceler.isAvailable()) {
            AppLog.d(TAG, "本机不支持 AcousticEchoCanceler");
            return null;
        }
        try {
            AcousticEchoCanceler aec = AcousticEchoCanceler.create(session);
            if (aec != null) {
                aec.setEnabled(true);
            }
            return aec;
        } catch (Throwable t) {
            return null;
        }
    }

    private static NoiseSuppressor enableNs(int session) {
        if (!NoiseSuppressor.isAvailable()) {
            return null;
        }
        try {
            NoiseSuppressor ns = NoiseSuppressor.create(session);
            if (ns != null) {
                ns.setEnabled(true);
            }
            return ns;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 通知 HMI 仪表点亮/熄灭「喊话中」图标；纯 UI，失败不影响音频。 */
    private void notifyCluster(Context context, boolean on) {
        try {
            Audio audio = Audio.create(context);
            if (audio == null) {
                return;
            }
            audio.setMicOccupyState(on ? Audio.MicOccupyState.MIC_OCCUPYSTATE_ON
                    : Audio.MicOccupyState.MIC_OCCUPYSTATE_OFF);
        } catch (Throwable ignored) {
        }
    }

    private static void safeRelease(android.media.audiofx.AudioEffect effect) {
        if (effect == null) {
            return;
        }
        try {
            effect.release();
        } catch (Throwable ignored) {
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
