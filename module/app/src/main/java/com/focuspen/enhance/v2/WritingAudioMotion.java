package com.focuspen.enhance.v2;

/** Continuous motion parameters with a small dead zone and missing-MOVE watchdog. */
final class WritingAudioMotion {
    static final int DOWN = 0, UP = 1, MOVE = 2, CANCEL = 3;
    static final long STALE_MS = 45;
    private final float min, max, contact, resolution;
    private boolean touching, moving;
    private float x, y, pressure, speed, startDistance;
    private long time, lastMovingTime;

    WritingAudioMotion(float min, float max, float resolution) {
        if (!Float.isFinite(min) || !Float.isFinite(max) || max <= min
                || !Float.isFinite(resolution) || resolution < 0f)
            throw new IllegalArgumentException("Pressure range");
        this.min = min; this.max = max; this.resolution = resolution;
        contact = min + Math.max(resolution * 1.5f, (max - min) / 8192f);
        if (contact >= max) throw new IllegalArgumentException("Pressure resolution");
    }

    void process(int action, float nextX, float nextY, float rawPressure, long nextTime) {
        if (action == UP || action == CANCEL) { reset(); return; }
        if ((action != DOWN && action != MOVE) || !Float.isFinite(nextX)
                || !Float.isFinite(nextY) || !Float.isFinite(rawPressure)) { reset(); return; }
        if (touching && action != DOWN && nextTime <= time) return;
        float threshold = touching ? Math.max(min, contact - resolution) : contact;
        if (rawPressure <= threshold) { reset(); return; }
        float p = clamp((rawPressure - contact) / (max - contact), 0f, 1f);
        if (action == DOWN || !touching || nextTime - time > STALE_MS) {
            reset(); touching = true;
            x = nextX; y = nextY; time = nextTime; pressure = p;
            return;
        }
        long dt = nextTime - time;
        float distance = (float) Math.hypot(nextX - x, nextY - y);
        float measured = distance / dt;
        if (!Float.isFinite(measured) || distance > 256f) { reset(); return; }
        float alpha = 1f - (float) Math.exp(-dt / 12f);
        pressure += alpha * (p - pressure);
        speed += alpha * (Math.min(3f, measured) - speed);
        // Below 0.02 px/ms always stops; restarting requires 0.03 and 0.45 px travel.
        if (measured < (moving ? 0.02f : 0.03f)) {
            moving = false; startDistance = 0f;
        } else {
            startDistance += distance;
            moving |= startDistance >= 0.45f;
            if (moving) { lastMovingTime = nextTime; startDistance = 0f; }
        }
        x = nextX; y = nextY; time = nextTime;
    }

    boolean moving(long now) { return touching && moving && now >= lastMovingTime && now - lastMovingTime <= STALE_MS; }
    boolean contactRecent(long now) { return touching && now >= time && now - time <= STALE_MS; }
    float pressure() { return pressure; }
    float speed() { return speed; }
    long eventTime() { return time; }
    void reset() { touching = moving = false; startDistance = pressure = speed = 0f; }
    private static float clamp(float v, float min, float max) { return Math.max(min, Math.min(max, v)); }
}
