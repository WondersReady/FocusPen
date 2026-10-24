package com.focuspen.enhance.v2;

/** Continuous source phase, a crossfaded loop seam and independent contact envelope. */
final class WritingAudioLoop {
    static final int SAMPLE_RATE = 48_000;
    static final int BLOCK_FRAMES = 240; // 5 ms; unrelated to input callback cadence.
    private final float[] sample;
    private final int overlap, period;
    private final double baseRate;
    private double phase;
    private float envelope, gain = 0.8f, rate = 1f, pan = .5f;

    WritingAudioLoop(WritingAudioSample source) {
        sample = source.pcm;
        overlap = Math.min(sample.length / 4, source.sampleRate / 50); // 20 ms
        period = sample.length - overlap;
        baseRate = source.sampleRate / (double) SAMPLE_RATE;
    }

    void render(short[] stereo, int frames, boolean moving, float pressure, float speed) {
        render(stereo, frames, moving, pressure, speed, .5f);
    }

    void render(short[] stereo, int frames, boolean moving, float pressure, float speed, float horizontalPosition) {
        if (frames < 0 || frames > stereo.length / 2) throw new IllegalArgumentException("PCM buffer");
        float velocity = (float) Math.sqrt(bounded(speed / 1.5f));
        float targetGain = (0.65f + 0.35f * bounded(pressure)) * (0.35f + .65f * velocity);
        float targetRate = 0.80f + 0.40f * velocity;
        float targetPan = Float.isFinite(horizontalPosition) ? bounded(horizontalPosition) : .5f;
        for (int i = 0; i < frames; i++) {
            envelope = moving ? Math.min(1f, envelope + 1f / 288f)
                    : Math.max(0f, envelope - 1f / 576f);
            gain += 0.002f * (targetGain - gain);
            rate += 0.001f * (targetRate - rate);
            pan += .001f * (targetPan - pan);
            float value = 0f;
            if (envelope > 0f) {
                double position = phase + overlap;
                value = sampleAt(position);
                if (position >= period) {
                    float mix = (float) ((position - period) / overlap);
                    value = value * (1f - mix) + sampleAt(position - period) * mix;
                }
                phase += baseRate * rate;
                if (phase >= period) phase -= period;
            }
            float mono = value * envelope * gain;
            // Equal-power panning keeps total energy stable across the display.
            stereo[i * 2] = pcm(mono * (float) Math.sqrt(2f * (1f - pan)));
            stereo[i * 2 + 1] = pcm(mono * (float) Math.sqrt(2f * pan));
        }
    }

    boolean audible() { return envelope > 0f; }
    void reset() { phase = 0; envelope = 0; gain = 0.8f; rate = 1f; pan = .5f; }
    private static short pcm(float value) {
        return (short) Math.round(Math.max(-.95f, Math.min(.95f, value)) * 32767f);
    }

    private float sampleAt(double index) {
        int a = (int) index;
        int b = Math.min(a + 1, sample.length - 1);
        return sample[a] + (sample[b] - sample[a]) * (float) (index - a);
    }
    private static float bounded(float v) { return Float.isFinite(v) ? Math.max(0f, Math.min(1f, v)) : 0f; }
}
