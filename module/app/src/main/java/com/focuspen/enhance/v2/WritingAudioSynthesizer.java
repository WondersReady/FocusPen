package com.focuspen.enhance.v2;

/**
 * Allocation-free grain renderer backed by generated friction textures.
 *
 * <p>The current repository has no licensed pencil recordings. These banks are deliberately
 * procedural and must not be described as recorded samples in release notes. They remove the
 * pitched oscillator from the prototype while keeping the runtime path ready for decoded PCM
 * banks later.</p>
 */
final class WritingAudioSynthesizer {
    static final int SAMPLE_RATE = 48_000;
    static final int MIN_GRAIN_FRAMES = SAMPLE_RATE / 100; // 10 ms
    static final int MAX_GRAIN_FRAMES = SAMPLE_RATE * 35 / 1000; // 35 ms
    static final int OVERLAP_FRAMES = MIN_GRAIN_FRAMES / 2;
    private static final int BANK_FRAMES = 16_384;
    private static final float OUTPUT_LIMIT = 0.72f;

    private final float[][] banks = new float[3][BANK_FRAMES];
    private final float[] window = new float[MAX_GRAIN_FRAMES];
    private int randomState;

    WritingAudioSynthesizer(int seed) {
        randomState = seed == 0 ? 0x51f15e5d : seed;
        buildBanks();
        for (int i = 0; i < window.length; i++) {
            window[i] = 0.5f - 0.5f * (float) Math.cos(2.0 * Math.PI * i / (window.length - 1));
        }
    }

    int render(short[] stereo, float pressure, float speed, float acceleration, float curvature,
            float pan, float direction, boolean contactAttack, float light, float medium,
            float heavy, float gain, float playbackRate, float brightness, float roughness) {
        pressure = clamp(pressure, 0f, 1f);
        speed = Math.max(0f, speed);
        acceleration = Math.max(0f, acceleration);
        curvature = clamp(curvature, 0f, 1f);
        pan = clamp(pan, -1f, 1f);
        direction = clamp(direction, -1f, 1f);

        int frames = grainFrames(pressure, speed);
        if (stereo.length < frames * 2) throw new IllegalArgumentException("PCM buffer too small");

        light = Math.max(0f, light);
        medium = Math.max(0f, medium);
        heavy = Math.max(0f, heavy);
        float weightTotal = light + medium + heavy;
        if (weightTotal <= 0f) { light = 1f; weightTotal = 1f; }
        light /= weightTotal;
        medium /= weightTotal;
        heavy /= weightTotal;

        int lightStart = randomStart(frames);
        int mediumStart = randomStart(frames);
        int heavyStart = randomStart(frames);
        float rate = clamp(playbackRate, 0.8f, 1.32f);
        float amplitude = clamp(gain * 1.6f, 0.08f, 0.68f);
        float transientLevel = clamp(acceleration * 0.012f + curvature * 0.018f
                + roughness * 0.012f, 0f, 0.065f);
        float leftGain = (float) Math.sqrt((1f - pan) * 0.5f);
        float rightGain = (float) Math.sqrt((1f + pan) * 0.5f);
        float stereoWidth = direction * (0.012f + curvature * 0.018f);
        int windowScale = window.length - 1;

        for (int i = 0; i < frames; i++) {
            float sourceIndex = i * rate;
            float sample = light * sample(banks[0], lightStart, sourceIndex)
                    + medium * sample(banks[1], mediumStart, sourceIndex)
                    + heavy * sample(banks[2], heavyStart, sourceIndex);
            float envelope = window[Math.min(windowScale, i * windowScale / Math.max(1, frames - 1))];
            if (contactAttack) envelope *= Math.min(1f, i / 72f);
            float transientSample = 0f;
            if (i < 96 && transientLevel > 0f) {
                float decay = 1f - i / 96f;
                transientSample = sample(banks[0], lightStart + 97, i * 1.37f)
                        * transientLevel * decay;
            }
            sample += sample(banks[0], lightStart + 311, sourceIndex * 1.19f)
                    * clamp(brightness, 0f, 1f) * 0.045f;
            float mono = softLimit((sample * amplitude + transientSample) * envelope);
            float side = sample(banks[0], lightStart + 193, sourceIndex * 1.11f)
                    * stereoWidth * amplitude * envelope;
            stereo[i * 2] = toShort(mono * leftGain + side);
            stereo[i * 2 + 1] = toShort(mono * rightGain - side);
        }
        return frames;
    }

    private void buildBanks() {
        float paper = 0f;
        float body = 0f;
        float previous = 0f;
        float fiber = 0f;
        for (int i = 0; i < BANK_FRAMES; i++) {
            float noise = randomUnit();
            paper += 0.018f * (noise - paper);
            body += 0.13f * (noise - body);
            float high = noise - body;
            float band = body - paper;
            float edge = noise - previous;
            previous = noise;
            fiber *= 0.72f;
            if ((nextRandom() & 1023) < 12) fiber += randomUnit() * 0.8f;
            float surface = 0.72f + 0.28f * Math.abs(paper);
            banks[0][i] = softTexture((high * 0.70f + band * 0.24f + edge * 0.06f) * surface
                    + fiber * 0.05f);
            banks[1][i] = softTexture((high * 0.36f + band * 0.55f + paper * 0.09f) * surface
                    + fiber * 0.13f);
            banks[2][i] = softTexture((high * 0.18f + band * 0.52f + paper * 0.30f) * surface
                    + fiber * 0.28f);
        }
        normalize(banks[0], 0.30f);
        normalize(banks[1], 0.33f);
        normalize(banks[2], 0.36f);
    }

    static int grainFrames(float pressure, float speed) {
        pressure = clamp(pressure, 0f, 1f);
        speed = Math.max(0f, speed);
        float speedShape = speed / (speed + 0.55f);
        int frames = Math.round(MAX_GRAIN_FRAMES
                - (MAX_GRAIN_FRAMES - MIN_GRAIN_FRAMES)
                * (0.64f * speedShape + 0.18f * pressure));
        return Math.max(MIN_GRAIN_FRAMES, Math.min(MAX_GRAIN_FRAMES, frames));
    }

    static float audibleDurationMs(float pressure, float speed) {
        return (grainFrames(pressure, speed) - OVERLAP_FRAMES) * 1000f / SAMPLE_RATE;
    }

    private int randomStart(int frames) {
        int margin = Math.min(BANK_FRAMES - 2, Math.max(frames * 2, MAX_GRAIN_FRAMES + 512));
        return Math.floorMod(nextRandom(), Math.max(1, BANK_FRAMES - margin));
    }

    private int nextRandom() {
        int x = randomState;
        x ^= x << 13;
        x ^= x >>> 17;
        x ^= x << 5;
        randomState = x;
        return x;
    }

    private float randomUnit() {
        return ((nextRandom() >>> 8) & 0x00ffffff) / 8_388_607.5f - 1f;
    }

    private static float sample(float[] bank, int start, float offset) {
        int base = start + (int) offset;
        float fraction = offset - (int) offset;
        int a = Math.floorMod(base, bank.length);
        int b = a + 1 == bank.length ? 0 : a + 1;
        return bank[a] + (bank[b] - bank[a]) * fraction;
    }

    private static float softTexture(float value) {
        return value / (1f + 0.55f * Math.abs(value));
    }

    private static void normalize(float[] bank, float targetRms) {
        float peak = 0f;
        double squareSum = 0;
        for (float value : bank) {
            peak = Math.max(peak, Math.abs(value));
            squareSum += value * value;
        }
        if (peak <= 0f || squareSum <= 0) return;
        float rms = (float) Math.sqrt(squareSum / bank.length);
        float scale = Math.min(targetRms / rms, 0.96f / peak);
        for (int i = 0; i < bank.length; i++) bank[i] *= scale;
    }

    private static float softLimit(float value) {
        float absolute = Math.abs(value);
        if (absolute <= OUTPUT_LIMIT) return value;
        float excess = absolute - OUTPUT_LIMIT;
        float limited = OUTPUT_LIMIT + excess / (1f + excess * 5f);
        return Math.copySign(Math.min(0.98f, limited), value);
    }

    private static short toShort(float value) {
        return (short) (clamp(value, -1f, 1f) * 32767f);
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
