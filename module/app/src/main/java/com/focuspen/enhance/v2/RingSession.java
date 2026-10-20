package com.focuspen.enhance.v2;

/** Shared ring lifecycle; coordinates and drawing stay in one SystemUI controller. */
final class RingSession {
    enum State { IDLE, POINT_SELECT }
    private State state = State.IDLE;
    private String selection;
    private int opener;
    private long expires;
    State state() { return state; }
    boolean active() { return state != State.IDLE; }
    int opener() { return opener; }
    String selection() { return selection; }
    void open(int button, long now) {
        selection = null; opener = button; expires = now + 12000;
        state = State.POINT_SELECT;
    }
    boolean select(String id) { boolean changed = !java.util.Objects.equals(id, selection); selection = id; return changed; }
    String click(String id) { if (!active() || id == null) return null; cancel(); return id; }
    boolean expire(long now) { if (active() && now >= expires) { cancel(); return true; } return false; }
    void cancel() { state = State.IDLE; selection = null; expires = 0; }
}
