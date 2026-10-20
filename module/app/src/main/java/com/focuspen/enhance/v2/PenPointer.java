package com.focuspen.enhance.v2;

/** Display-coordinate pointer state with bounded lifetime; has no Android dependencies. */
final class PenPointer {
    static final int DOWN = 0, UP = 1, MOVE = 2, CANCEL = 3, HOVER_MOVE = 7, HOVER_ENTER = 9, HOVER_EXIT = 10;
    static final long MAX_SILENCE_MS = 700;
    private int device = -1;
    private long lastTime = -1;
    private float x, y;
    private boolean hover, present;
    record Update(boolean accepted, boolean deviceChanged, boolean contact) { }
    Update accept(int action, int deviceId, int display, int targetDisplay,
                  long eventTime, long now, float x, float y) {
        if (display != targetDisplay || !Float.isFinite(x) || !Float.isFinite(y)
                || eventTime > now + 50 || now - eventTime > 500 || eventTime < lastTime)
            return new Update(false, false, false);
        if (action != DOWN && action != UP && action != MOVE && action != CANCEL
                && action != HOVER_MOVE && action != HOVER_ENTER && action != HOVER_EXIT)
            return new Update(false, false, false);
        boolean changed = device != -1 && device != deviceId;
        device = deviceId; lastTime = eventTime; this.x = x; this.y = y;
        hover = action == HOVER_MOVE || action == HOVER_ENTER;
        present = hover || action == DOWN || action == MOVE;
        return new Update(true, changed, action == DOWN);
    }
    boolean expire(long now) {
        if (present && now - lastTime > MAX_SILENCE_MS) { hover = false; present = false; return true; }
        return false;
    }
    boolean hover() { return hover; }
    boolean recent(long now) { return present && now - lastTime <= MAX_SILENCE_MS; }
    float x() { return x; }
    float y() { return y; }
    void clear() { hover = false; present = false; device = -1; lastTime = -1; }
}
