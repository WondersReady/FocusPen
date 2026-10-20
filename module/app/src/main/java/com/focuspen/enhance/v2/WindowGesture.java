package com.focuspen.enhance.v2;

/** Window-relative swipe with bounded duration and explicit cancellation. No Android dependencies. */
final class WindowGesture {
    static final int DOWN = 0, UP = 1, MOVE = 2, CANCEL = 3;
    interface Sink { boolean send(int action, float x, float y); }
    final float x, startY, endY;
    final long downTime;
    private boolean started, done;
    private float lastY;
    WindowGesture(float left, float top, float right, float bottom, boolean up, long now) {
        if (!Float.isFinite(left + top + right + bottom) || right - left < 120 || bottom - top < 200)
            throw new IllegalArgumentException("Window too small for a swipe");
        x = (left + right) / 2;
        // To reveal earlier content, the finger moves downward; later content is the inverse.
        startY = top + (bottom - top) * (up ? .32f : .68f);
        endY = top + (bottom - top) * (up ? .68f : .32f);
        lastY = startY; downTime = now;
    }
    boolean active() { return !done; }
    void advance(long now, boolean valid, Sink sink) {
        if (done) return;
        if (!valid || now - downTime > 400 || now < downTime) { cancel(sink); return; }
        if (!started) {
            started = true;
            if (!sink.send(DOWN, x, startY)) { cancel(sink); return; }
        }
        float p = Math.min(1, (now - downTime) / 200f);
        if (p == 0) return;
        lastY = startY + (endY - startY) * p;
        if (!sink.send(p == 1 ? UP : MOVE, x, lastY)) { cancel(sink); return; }
        if (p == 1) done = true;
    }
    void cancel(Sink sink) {
        if (done) return;
        done = true;
        if (started) sink.send(CANCEL, x, lastY);
    }
}
