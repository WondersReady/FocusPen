package com.focuspen.enhance.v2;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Process;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;
import java.io.InputStream;

/** Continuous sample playback on the existing notification/speaker path. */
final class WritingAudioEngine {
    private static final String TAG = "FocusPenAudio";
    private final Object signal = new Object();
    private final WritingAudioEventQueue events = new WritingAudioEventQueue(96);
    private final WritingAudioEventQueue.Event event = new WritingAudioEventQueue.Event();
    private final short[] pcm = new short[WritingAudioLoop.BLOCK_FRAMES * 2];
    private final short[] silence = new short[WritingAudioLoop.BLOCK_FRAMES * 2];
    private volatile Context context;
    private AudioManager manager;
    private AudioTrack track;
    private Thread thread;
    private WritingAudioMotion motion;
    private WritingAudioLoop loop;
    private boolean sourceAttempted, playing;
    private volatile boolean enabled, closed;
    private volatile int volume = 65;
    private boolean hardStop, finish, volumeDirty;
    private float pressureMin, pressureMax = 1f, pressureResolution;
    private float modelMin = Float.NaN, modelMax, modelResolution;
    private int pending, offset, queuePeak;
    private long blocks, writeFailures, zeroWrites, partialWrites, inputAgeTotal, inputAgeMax;
    private long lastProgress, retryAfter, idleSince, drainUntil;
    private float pan = .5f;

    void setContext(Context ctx) {
        synchronized (signal) { context = ctx; signal.notifyAll(); }
    }

    void setEnabled(boolean on, int percent) {
        on &= ConfigSpec.WRITING_AUDIO_AVAILABLE;
        synchronized (signal) {
            int next = Math.max(0, Math.min(100, percent));
            volumeDirty |= next != volume;
            volume = next; enabled = on;
            if (!on) { events.clear(); hardStop = true; }
            if (on && (thread == null || !thread.isAlive())) {
                closed = false;
                thread = new Thread(this::run, "FocusPenAudio");
                thread.start();
            }
            signal.notifyAll();
        }
    }

    void stopMotion() {
        synchronized (signal) { events.clear(); hardStop = true; signal.notifyAll(); }
    }

    void endMotion() {
        synchronized (signal) { events.clear(); finish = true; signal.notifyAll(); }
    }

    void onMotion(int action, float x, float y, float pressure, long time, int displayWidth,
            float min, float max, float resolution, float tilt, float orientation) {
        synchronized (signal) {
            if (!enabled || closed) return;
            if (Float.isFinite(min) && Float.isFinite(max) && max > min
                    && Float.isFinite(resolution) && resolution >= 0f
                    && resolution * 1.5f < max - min) {
                pressureMin = min; pressureMax = max; pressureResolution = resolution;
            }
            events.offer(action, x, y, pressure, time, displayWidth, tilt, orientation);
            queuePeak = Math.max(queuePeak, events.size());
            signal.notifyAll();
        }
    }

    void close() {
        synchronized (signal) { enabled = false; closed = true; events.clear(); signal.notifyAll(); }
    }

    private void run() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
        try {
            while (!closed) {
                applyRequests();
                if (!enabled || !loadSource()) { await(100); continue; }
                long now = SystemClock.uptimeMillis();
                boolean moving = motion.moving(now);
                if (!motion.contactRecent(now) && !moving && !loop.audible() && pending == 0) {
                    if (idleSince == 0) idleSince = now;
                    if (track == null || now - idleSince >= 1500) {
                        if (track != null) { logStats(); releaseTrack(); }
                        await(100); continue;
                    }
                    // Keep feeding silence between strokes: an underrun can reset the
                    // native start threshold and strand a later short/slow stroke.
                } else idleSince = 0;
                if (silent() || volume == 0) { clearMotion(); releaseTrack(); await(10); continue; }
                if (now < retryAfter) { await(10); continue; }
                if (!ensureTrack()) {
                    clearMotion(); releaseTrack();
                    synchronized (signal) {
                        if (!hardStop && !finish && enabled && !closed) retryAfter = now + 1000;
                    }
                    continue;
                }
                if (!builtInRoute()) { clearMotion(); releaseTrack(); retryAfter = now + 1000; continue; }
                synchronized (signal) { if (hardStop || finish || !enabled || closed) continue; }
                if (pending == 0) {
                    moving = motion.moving(SystemClock.uptimeMillis());
                    loop.render(pcm, WritingAudioLoop.BLOCK_FRAMES, moving, motion.pressure(), motion.speed(), pan);
                    offset = 0; pending = pcm.length;
                    lastProgress = SystemClock.uptimeMillis();
                    if (moving) {
                        long age = Math.max(0, lastProgress - motion.eventTime());
                        inputAgeTotal += age; inputAgeMax = Math.max(inputAgeMax, age); blocks++;
                        if (blocks % 400 == 0) logStats();
                    }
                }
                int written;
                synchronized (signal) {
                    if (hardStop || finish || !enabled || closed) continue;
                    written = track.write(pcm, offset, pending, AudioTrack.WRITE_NON_BLOCKING);
                }
                if (written < 0) {
                    writeFailures++; Log.w(TAG, "write failed=" + written);
                    clearMotion(); releaseTrack(); retryAfter = now + 1000;
                } else if (written == 0) {
                    zeroWrites++;
                    if (SystemClock.uptimeMillis() - lastProgress > 500) {
                        writeFailures++;
                        Log.w(TAG, "write stalled bufferFrames=" + track.getBufferSizeInFrames());
                        clearMotion(); releaseTrack(); retryAfter = now + 100;
                    } else await(2);
                } else {
                    if (written < pending) partialWrites++;
                    offset += written; pending -= written;
                    lastProgress = SystemClock.uptimeMillis();
                }
            }
        } catch (Throwable failure) {
            Log.e(TAG, "Continuous writing audio stopped", failure);
        } finally { releaseTrack(); }
    }

    private void applyRequests() {
        boolean stop, end, changeVolume;
        float min, max, resolution;
        synchronized (signal) {
            stop = hardStop; end = finish; changeVolume = volumeDirty;
            hardStop = finish = volumeDirty = false;
            min = pressureMin; max = pressureMax; resolution = pressureResolution;
        }
        if (stop) { clearMotion(); releaseTrack(); }
        if (end) {
            if (motion != null) motion.reset();
            pending = offset = 0;
        }
        if (changeVolume && track != null) track.setVolume(volume / 100f);
        if (motion == null || min != modelMin || max != modelMax || resolution != modelResolution) {
            motion = new WritingAudioMotion(min, max, resolution);
            modelMin = min; modelMax = max; modelResolution = resolution;
        }
        // Bounded drain. A concurrently arriving stop is checked before any audible write.
        for (int i = 0; i < 96 && events.poll(event); i++) {
            motion.process(event.action, event.x, event.y, event.pressure, event.timeMs);
            if (event.displayWidth > 0 && Float.isFinite(event.x))
                pan = Math.max(0f, Math.min(1f, event.x / event.displayWidth));
        }
    }

    private boolean loadSource() {
        if (loop != null) return true;
        if (sourceAttempted || context == null) return false;
        sourceAttempted = true;
        try {
            manager = context.getSystemService(AudioManager.class);
            Context module = context.createPackageContext(BuildConfig.APPLICATION_ID, 0);
            try (InputStream input = module.getAssets().open("writing-audio/pen.wav")) {
                WritingAudioSample source = WritingAudioSample.read(input);
                loop = new WritingAudioLoop(source);
                Log.i(TAG, "sample loaded rate=" + source.sampleRate + " frames=" + source.pcm.length
                        + " sourceRms=" + source.sourceRms + " normalization=" + source.normalizationGain);
            }
        } catch (Throwable failure) { Log.e(TAG, "Writing sample unavailable; silent", failure); }
        return loop != null;
    }

    private void await(long ms) throws InterruptedException {
        synchronized (signal) {
            if (closed || hardStop || finish || volumeDirty || events.size() > 0) return;
            signal.wait(ms);
        }
    }

    private void clearMotion() {
        if (motion != null) motion.reset();
        if (loop != null) loop.reset();
        pending = offset = 0;
    }

    private boolean ensureTrack() {
        try {
            if (track == null) {
                AudioDeviceInfo speaker = null;
                if (manager != null) for (AudioDeviceInfo device : manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS))
                    if (device.getType() == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) { speaker = device; break; }
                if (speaker == null) return false;
                int minimum = AudioTrack.getMinBufferSize(WritingAudioLoop.SAMPLE_RATE,
                        AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
                if (minimum <= 0) return false;
                int bufferBytes = Math.max(minimum, pcm.length * 2 * 3);
                track = new AudioTrack.Builder().setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                        .setAudioFormat(new AudioFormat.Builder().setSampleRate(WritingAudioLoop.SAMPLE_RATE)
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                        .setBufferSizeInBytes(bufferBytes).setTransferMode(AudioTrack.MODE_STREAM)
                        .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY).build();
                if (track.getState() != AudioTrack.STATE_INITIALIZED || !track.setPreferredDevice(speaker)) {
                    releaseTrack(); return false;
                }
                // Capacity and playback threshold are different. Do not wait for the
                // ROM's entire ~80 ms capacity before starting a short stroke.
                int frames = track.setBufferSizeInFrames(WritingAudioLoop.BLOCK_FRAMES * 4);
                if (Build.VERSION.SDK_INT >= 31)
                    track.setStartThresholdInFrames(WritingAudioLoop.BLOCK_FRAMES);
                Log.i(TAG, "track configured effectiveFrames=" + frames
                        + " startFrames=" + (Build.VERSION.SDK_INT >= 31 ? track.getStartThresholdInFrames() : -1));
                track.setVolume(volume / 100f);
            }
            if (!playing) {
                // Establish routing with silence, before submitting any audible sample.
                int count = track.write(silence, 0, silence.length, AudioTrack.WRITE_NON_BLOCKING);
                if (count < 0) { releaseTrack(); return false; }
                track.play(); playing = true;
                for (int i = 0; i < 20 && enabled && !closed && !silent(); i++) {
                    synchronized (signal) { if (hardStop || finish) return false; }
                    if (builtInRoute()) {
                        Log.i(TAG, "loop ready stream=notification route=built-in-speaker volume="
                                + volume + " bufferFrames=" + track.getBufferSizeInFrames());
                        return true;
                    }
                    Thread.sleep(5);
                }
                return false;
            }
            return true;
        } catch (Throwable failure) {
            Log.w(TAG, "AudioTrack unavailable", failure); releaseTrack(); return false;
        }
    }

    private void releaseTrack() {
        AudioTrack current = track; track = null; playing = false; drainUntil = 0;
        pending = offset = 0;
        if (current == null) return;
        try { current.pause(); current.flush(); } catch (Throwable ignored) { }
        try { current.release(); } catch (Throwable ignored) { }
    }

    private boolean silent() { return manager != null && manager.getRingerMode() == AudioManager.RINGER_MODE_SILENT; }
    private boolean builtInRoute() {
        AudioDeviceInfo device = track == null ? null : track.getRoutedDevice();
        return device != null && device.getType() == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER;
    }
    private void logStats() {
        Log.i(TAG, "loop stats blocks=" + blocks + " eventCoalescedOrDropped=" + events.droppedCount()
                + " queuePeak=" + queuePeak + " partialWrites=" + partialWrites + " zeroWrites=" + zeroWrites
                + " writeFailures=" + writeFailures + " underruns=" + (track == null ? -1 : track.getUnderrunCount())
                + " inputAgeAvgMs=" + (blocks == 0 ? 0 : inputAgeTotal / blocks) + " inputAgeMaxMs=" + inputAgeMax);
    }
}
