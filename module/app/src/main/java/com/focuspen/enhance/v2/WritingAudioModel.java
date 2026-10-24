package com.focuspen.enhance.v2;

/** Motion-to-grain model. The caller owns this instance on the audio thread. */
final class WritingAudioModel {
    static final int DOWN = 0, UP = 1, MOVE = 2, CANCEL = 3;
    private static final int GRAIN_CAPACITY = 128;
    private static final int MAX_GRAINS_PER_SAMPLE = 32;
    private static final float PRESSURE_TIME_MS = 12f;
    private static final float MAX_TRAVEL_PX = 256f;
    private static final float FIRST_GRAIN_SPACING_PX = 4f;
    private static final float SPEED_DEAD_ZONE_PX_PER_MS = 0.02f;
    private static final float MAX_GRAIN_SPACING_PX = 512f;

    static class State {
        float pressure, speedPxPerMs, accelerationPxPerMs2, curvatureRadPerPx, curvature, pan, direction;
        float lightWeight, mediumWeight, heavyWeight, gain, playbackRate, brightness, roughness;

        void copyFrom(State source) {
            pressure = source.pressure;
            speedPxPerMs = source.speedPxPerMs;
            accelerationPxPerMs2 = source.accelerationPxPerMs2;
            curvatureRadPerPx = source.curvatureRadPerPx;
            curvature = source.curvature;
            pan = source.pan;
            direction = source.direction;
            lightWeight = source.lightWeight;
            mediumWeight = source.mediumWeight;
            heavyWeight = source.heavyWeight;
            gain = source.gain;
            playbackRate = source.playbackRate;
            brightness = source.brightness;
            roughness = source.roughness;
        }
    }

    static final class Grain extends State {
        float x, y;
        long eventTimeMs;
        boolean attack;

        void copyFrom(Grain source) {
            super.copyFrom(source);
            x = source.x;
            y = source.y;
            eventTimeMs = source.eventTimeMs;
            attack = source.attack;
        }
    }

    private final float rangeMin, rangeMax, contactMin, usableMax, normalizedResolution, releaseThreshold;
    private final State state = new State();
    private final Grain[] grains = new Grain[GRAIN_CAPACITY];
    private int read, size;
    private long droppedGrains;
    private boolean touching, hasDirection, grainStarted;
    private float lastX, lastY, lastDx, lastDy, distanceSinceGrain;
    private long lastTimeMs;

    /** Range values come from MotionRange; calibrated bounds may narrow the usable span. */
    WritingAudioModel(float rangeMin, float rangeMax, float rangeResolution,
            float contactMin, float usableMax) {
        if (!Float.isFinite(rangeMin) || !Float.isFinite(rangeMax) || rangeMax <= rangeMin
                || !Float.isFinite(rangeResolution) || rangeResolution < 0f
                || !Float.isFinite(contactMin) || !Float.isFinite(usableMax)) {
            throw new IllegalArgumentException("Invalid pressure range");
        }
        this.rangeMin = rangeMin;
        this.rangeMax = rangeMax;
        this.contactMin = clamp(contactMin, rangeMin, rangeMax);
        this.usableMax = clamp(usableMax, rangeMin, rangeMax);
        if (this.usableMax <= this.contactMin) {
            throw new IllegalArgumentException("Invalid calibrated pressure span");
        }
        normalizedResolution = rangeResolution / (this.usableMax - this.contactMin);
        releaseThreshold = Math.max(rangeMin, this.contactMin
                - Math.max(rangeResolution, (this.usableMax - this.contactMin) * 0.002f));
        for (int i = 0; i < grains.length; i++) grains[i] = new Grain();
    }

    void process(int action, float x, float y, float rawPressure, long eventTimeMs, int displayWidth) {
        if (action == UP || action == CANCEL) {
            reset();
            return;
        }
        if ((action != DOWN && action != MOVE) || !Float.isFinite(x) || !Float.isFinite(y)
                || !Float.isFinite(rawPressure)) return;
        float target = normalizedPressure(rawPressure);
        if ((!touching && target == 0f)
                || (touching && (rawPressure <= rangeMin || rawPressure < releaseThreshold))) {
            reset();
            return;
        }
        if (action == DOWN || !touching) {
            touching = true;
            lastX = x;
            lastY = y;
            lastTimeMs = eventTimeMs;
            hasDirection = false;
            grainStarted = false;
            distanceSinceGrain = 0f;
            state.pressure = target;
            state.speedPxPerMs = 0f;
            state.accelerationPxPerMs2 = 0f;
            state.curvatureRadPerPx = 0f;
            state.curvature = 0f;
            state.direction = 0f;
            state.pan = pan(x, displayWidth);
            mapTimbre();
            return;
        }

        long elapsedMs = eventTimeMs - lastTimeMs;
        if (elapsedMs < 0) return;
        float dx = x - lastX, dy = y - lastY;
        float distance = (float) Math.hypot(dx, dy);
        if (!Float.isFinite(distance)) return;
        float previousSpeed = state.speedPxPerMs;
        float measuredSpeed = elapsedMs > 0 ? clamp(distance / elapsedMs, 0f, 32f) : 0f;
        boolean moving = measuredSpeed >= SPEED_DEAD_ZONE_PX_PER_MS;
        if (!moving) measuredSpeed = 0f;
        state.speedPxPerMs += 0.5f * (measuredSpeed - state.speedPxPerMs);
        float measuredAcceleration = elapsedMs > 0
                ? (state.speedPxPerMs - previousSpeed) / elapsedMs : 0f;
        state.accelerationPxPerMs2 += 0.5f * (measuredAcceleration - state.accelerationPxPerMs2);
        float curvature = 0f;
        if (moving && distance > 0f && hasDirection) {
            float turn = (float) Math.atan2(lastDx * dy - lastDy * dx, lastDx * dx + lastDy * dy);
            curvature = clamp(Math.abs(turn) / distance, 0f, 1f);
        }
        state.curvatureRadPerPx += 0.5f * (curvature - state.curvatureRadPerPx);
        state.curvature = state.curvatureRadPerPx;
        if (moving && distance > 0f) {
            lastDx = dx / distance;
            lastDy = dy / distance;
            state.direction = lastDx;
            hasDirection = true;
        }

        float delta = target - state.pressure;
        if (Math.abs(delta) > Math.max(0.002f, normalizedResolution * 0.5f)) {
            float alpha = elapsedMs == 0 ? 0.35f
                    : 1f - (float) Math.exp(-elapsedMs / PRESSURE_TIME_MS);
            state.pressure = clamp(state.pressure + alpha * delta, 0f, 1f);
        }
        state.pan = pan(x, displayWidth);
        mapTimbre();

        // Carry distance across callbacks. Trigger positions interpolate along the current segment.
        float travel = moving ? Math.min(distance, MAX_TRAVEL_PX) : 0f;
        float spacing = spacingPx();
        float nextSpacing = grainStarted ? spacing : Math.min(spacing, FIRST_GRAIN_SPACING_PX);
        distanceSinceGrain = Math.min(distanceSinceGrain, nextSpacing * 0.999f);
        float walked = 0f;
        int emitted = 0;
        while (travel - walked + distanceSinceGrain >= nextSpacing
                && emitted < MAX_GRAINS_PER_SAMPLE) {
            float step = nextSpacing - distanceSinceGrain;
            walked += step;
            distanceSinceGrain = 0f;
            float fraction = distance > 0f ? walked / distance : 0f;
            enqueueGrain(lastX + dx * fraction, lastY + dy * fraction,
                    lastTimeMs + (long) (elapsedMs * fraction), displayWidth);
            emitted++;
            nextSpacing = spacing;
        }
        distanceSinceGrain = emitted == MAX_GRAINS_PER_SAMPLE
                ? 0f : distanceSinceGrain + travel - walked;
        lastX = x;
        lastY = y;
        lastTimeMs = eventTimeMs;
    }

    void copyState(State out) { out.copyFrom(state); }

    boolean pollGrain(Grain out) {
        if (size == 0) return false;
        out.copyFrom(grains[read]);
        read = (read + 1) % grains.length;
        size--;
        return true;
    }

    int pendingGrains() { return size; }
    long droppedGrains() { return droppedGrains; }

    void reset() {
        resetContact();
        read = 0;
        size = 0;
    }

    private void resetContact() {
        touching = false;
        hasDirection = false;
        grainStarted = false;
        distanceSinceGrain = 0f;
        state.pressure = 0f;
        state.speedPxPerMs = 0f;
        state.accelerationPxPerMs2 = 0f;
        state.curvatureRadPerPx = 0f;
        state.curvature = 0f;
        state.direction = 0f;
        mapTimbre();
    }

    private float normalizedPressure(float raw) {
        float bounded = clamp(raw, rangeMin, rangeMax);
        if (bounded <= contactMin) return 0f;
        return clamp((bounded - contactMin) / (usableMax - contactMin), 0f, 1f);
    }

    private float spacingPx() {
        // Match distance scheduling to the audible frames written for one overlapped grain.
        float durationMs = WritingAudioSynthesizer.audibleDurationMs(
                state.pressure, state.speedPxPerMs);
        return clamp(state.speedPxPerMs * durationMs, 0.75f, MAX_GRAIN_SPACING_PX);
    }

    private void mapTimbre() {
        float pressure = state.pressure;
        state.lightWeight = clamp(1f - 2f * pressure, 0f, 1f);
        state.mediumWeight = 1f - Math.abs(2f * pressure - 1f);
        state.heavyWeight = clamp(2f * pressure - 1f, 0f, 1f);
        float speed = Math.min(1f, state.speedPxPerMs);
        state.gain = clamp(0.12f + 0.32f * pressure + 0.10f * speed, 0f, 0.56f);
        state.playbackRate = 0.78f + 0.38f * speed + 0.12f * pressure;
        state.brightness = clamp(0.18f + 0.55f * speed + 0.15f * pressure, 0f, 1f);
        state.roughness = clamp(0.12f + 0.58f * pressure
                + 0.12f * Math.min(1f, Math.abs(state.accelerationPxPerMs2))
                + 0.08f * state.curvatureRadPerPx, 0f, 1f);
    }

    private void enqueueGrain(float x, float y, long eventTimeMs, int displayWidth) {
        if (size == grains.length) {
            droppedGrains++;
            return;
        }
        Grain grain = grains[(read + size) % grains.length];
        grain.copyFrom(state);
        grain.x = x;
        grain.y = y;
        grain.eventTimeMs = eventTimeMs;
        grain.pan = pan(x, displayWidth);
        grain.attack = !grainStarted;
        grainStarted = true;
        size++;
    }

    private static float pan(float x, int width) {
        return width > 1 ? clamp(2f * x / width - 1f, -1f, 1f) : 0f;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
