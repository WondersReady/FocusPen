package com.focuspen.enhance.v2;

import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.util.Log;

/**
 * Experimental low-latency writing feedback. The first backend is procedural and stereo:
 * pressure and speed shape a short textured burst, while the pen position controls the pan.
 * This is deliberately independent from the drawing pipeline so audio failure cannot affect
 * input arbitration or the official pen path.
 */
final class WritingAudioEngine {
    private static final String TAG = "FocusPenAudio";
    private static final int SAMPLE_RATE = 48000;
    private static final int CHANNEL_MASK = AudioFormat.CHANNEL_OUT_STEREO;
    private static final int FRAMES_PER_BURST = 960; // 20 ms
    private static final long EMIT_INTERVAL_MS = 28;
    private static final float PRESSURE_EPS = 0.003f;
    private static final float MOVE_EPS_PX = 0.8f;

    private HandlerThread thread;
    private Handler worker;
    private AudioTrack track;
    private AudioManager audioManager;
    private volatile boolean enabled;
    private volatile int volumePercent = 65;
    private boolean touching;
    private long lastEventTime;
    private long lastEmitTime;
    private float lastX, lastY;
    private float pendingDistance;
    private double phase;
    private int noiseState = 0x31415926;
    private long burstCount;

    synchronized void setContext(Context context) {
        if (audioManager == null && context != null) audioManager = context.getSystemService(AudioManager.class);
    }

    synchronized void setEnabled(boolean on, int volume) {
        volumePercent = Math.max(0, Math.min(100, volume));
        if (enabled == on) {
            if (on && worker != null) worker.post(() -> {
                AudioTrack current = track;
                if (current != null) {
                    try { current.setVolume(volumePercent / 100f); } catch (Throwable ignored) { }
                }
            });
            if (!on && worker != null) worker.post(this::releaseTrack);
            return;
        }
        enabled = on;
        if (on) {
            ensureThread();
            worker.post(this::ensureTrack);
            Log.i(TAG, "writing audio enabled");
        } else {
            Handler h = worker;
            if (h != null) h.post(this::releaseTrack);
            touching = false;
            Log.i(TAG, "writing audio disabled");
        }
    }

    void onMotion(int action, float x, float y, float pressure, long eventTime, int displayWidth) {
        if (!enabled) return;
        ensureThread();
        final int a = action;
        final float px = x, py = y, pp = pressure;
        final long t = eventTime;
        final int width = displayWidth;
        worker.post(() -> handle(a, px, py, pp, t, width));
    }

    synchronized void close() {
        enabled = false;
        if (worker != null) worker.post(this::releaseTrack);
        if (thread != null) thread.quitSafely();
        thread = null;
        worker = null;
    }

    private synchronized void ensureThread() {
        if (worker != null) return;
        thread = new HandlerThread("FocusPenAudio", Process.THREAD_PRIORITY_AUDIO);
        thread.start();
        worker = new Handler(thread.getLooper());
    }

    private void handle(int action, float x, float y, float pressure, long eventTime, int width) {
        if (!enabled) return;
        if (isSilentMode()) {
            touching = false;
            releaseTrack();
            return;
        }
        if (action == 1 || action == 3 || pressure <= PRESSURE_EPS) { // UP/CANCEL or hover
            touching = false;
            lastEmitTime = 0;
            pendingDistance = 0;
            lastEventTime = eventTime;
            lastX = x;
            lastY = y;
            return;
        }
        if (action != 0 && action != 2) return; // DOWN/MOVE only

        float dx = x - lastX;
        float dy = y - lastY;
        long dt = eventTime - lastEventTime;
        float speed = dt > 0 ? (float) (Math.hypot(dx, dy) / dt) : 0f;
        boolean first = !touching || action == 0;
        if (first) { lastEmitTime = 0; pendingDistance = 0; }
        touching = true;
        lastX = x;
        lastY = y;
        lastEventTime = eventTime;

        if (first) return;
        pendingDistance += (float)Math.hypot(dx, dy);
        if (pendingDistance < MOVE_EPS_PX) return;
        if (!first && eventTime - lastEmitTime < EMIT_INTERVAL_MS && speed < 0.4f) return;
        boolean attack = lastEmitTime == 0;
        lastEmitTime = eventTime;
        pendingDistance = 0;
        ensureTrack();
        if (track == null || !isBuiltInSpeakerRoute()) return;

        float normalizedX = width > 0 ? x / (float) width : 0.5f;
        float pan = clamp(normalizedX * 2f - 1f, -1f, 1f);
        float intensity = clamp(0.08f + pressure * 0.18f + speed * 0.025f, 0.06f, 0.28f);
        float frequency = 135f + clamp(pressure, 0f, 1f) * 170f + clamp(speed, 0f, 8f) * 16f;
        writeBurst(intensity, frequency, pan, attack);
    }

    private void ensureTrack() {
        if (!enabled || track != null) return;
        try {
            if (isSilentMode()) return;
            AudioDeviceInfo speaker = findBuiltInSpeaker();
            if (speaker == null) {
                Log.w(TAG, "Built-in speaker unavailable; writing sound muted");
                return;
            }
            int min = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_MASK, AudioFormat.ENCODING_PCM_16BIT);
            int buffer = Math.max(min, FRAMES_PER_BURST * 4 * 4);
            AudioFormat format = new AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(CHANNEL_MASK)
                    .build();
            AudioAttributes attrs = new AudioAttributes.Builder()
                    // Match the keyboard sound path: the system notification stream
                    // is the base gain, while setVolume() applies our own slider.
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build();
            track = new AudioTrack.Builder()
                    .setAudioAttributes(attrs)
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(buffer)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                    .build();
            if (track.getState() != AudioTrack.STATE_INITIALIZED) {
                Log.w(TAG, "AudioTrack failed to initialize");
                releaseTrack();
                return;
            }
            if (!track.setPreferredDevice(speaker)) {
                Log.w(TAG, "Unable to prefer built-in speaker");
                releaseTrack();
                return;
            }
            track.setVolume(volumePercent / 100f);
            track.play();
            if (!waitForBuiltInSpeakerRoute()) {
                Log.w(TAG, "AudioTrack routed away from built-in speaker; refusing playback");
                releaseTrack();
                return;
            }
            Log.i(TAG, "AudioTrack ready rate=" + SAMPLE_RATE + " buffer=" + buffer
                    + " channels=stereo route=built-in-speaker stream=notification volume=" + volumePercent);
        } catch (Throwable failure) {
            Log.w(TAG, "AudioTrack unavailable; writing sound muted", failure);
            releaseTrack();
        }
    }

    private void writeBurst(float amplitude, float frequency, float pan, boolean attack) {
        if (isSilentMode() || !isBuiltInSpeakerRoute()) {
            releaseTrack();
            return;
        }
        short[] pcm = new short[FRAMES_PER_BURST * 2];
        double phaseStep = 2.0 * Math.PI * frequency / SAMPLE_RATE;
        float left = (float) Math.sqrt((1f - pan) * 0.5f);
        float right = (float) Math.sqrt((1f + pan) * 0.5f);
        for (int i = 0; i < FRAMES_PER_BURST; i++) {
            float progress = i / (float) FRAMES_PER_BURST;
            float envelope = attack ? Math.min(1f, i / 72f) : 1f;
            envelope *= (float) Math.pow(1f - progress, 0.72);
            float tonal = (float) Math.sin(phase);
            noiseState = noiseState * 1664525 + 1013904223;
            float noise = ((noiseState >>> 8) & 0x00ffffff) / 8388607.5f - 1f;
            float sample = (tonal * 0.32f + noise * 0.68f) * amplitude * envelope;
            pcm[i * 2] = toShort(sample * left);
            pcm[i * 2 + 1] = toShort(sample * right);
            phase += phaseStep;
            if (phase > Math.PI * 2) phase -= Math.PI * 2;
        }
        AudioTrack current = track;
        if (current == null) return;
        int written = current.write(pcm, 0, pcm.length, AudioTrack.WRITE_NON_BLOCKING);
        if (written < 0) Log.w(TAG, "AudioTrack write failed code=" + written);
        if ((++burstCount % 100) == 0) Log.d(TAG, "writing bursts=" + burstCount);
    }

    private void releaseTrack() {
        AudioTrack t = track;
        track = null;
        if (t == null) return;
        try { t.pause(); } catch (Throwable ignored) { }
        try { t.flush(); } catch (Throwable ignored) { }
        try { t.release(); } catch (Throwable ignored) { }
        Log.i(TAG, "AudioTrack released");
    }

    private boolean isSilentMode() {
        AudioManager manager = audioManager;
        return manager != null && manager.getRingerMode() == AudioManager.RINGER_MODE_SILENT;
    }

    private AudioDeviceInfo findBuiltInSpeaker() {
        AudioManager manager = audioManager;
        if (manager == null) return null;
        for (AudioDeviceInfo device : manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            if (device.getType() == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) return device;
        }
        return null;
    }

    private boolean isBuiltInSpeakerRoute() {
        AudioTrack current = track;
        if (current == null) return false;
        AudioDeviceInfo routed = current.getRoutedDevice();
        return routed != null && routed.getType() == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER;
    }

    private boolean waitForBuiltInSpeakerRoute() {
        for (int i = 0; i < 20 && enabled && !isSilentMode(); i++) {
            if (isBuiltInSpeakerRoute()) return true;
            try { Thread.sleep(5L); } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return isBuiltInSpeakerRoute();
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static short toShort(float value) {
        return (short) (clamp(value, -1f, 1f) * 32767f);
    }
}
