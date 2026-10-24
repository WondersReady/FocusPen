package com.focuspen.enhance.v2;

import android.view.InputDevice;
import android.view.MotionEvent;

/** Limits writing feedback to physical pen-tip contact on an unlocked, undocked tablet. */
final class WritingAudioGate {
    private static final float CONTACT_PRESSURE = 0.003f;

    private Runnable stop = () -> { };
    private Runnable finish = () -> { };
    private boolean interactive;
    private boolean locked = true;
    private int dockState = PenDockMonitor.UNKNOWN;
    private boolean writing;
    private boolean tail;

    synchronized void onStop(Runnable callback) { stop = callback; finish = callback; }
    synchronized void onFinish(Runnable callback) { finish = callback; }

    synchronized void updateScreen(boolean on) {
        interactive = on;
        if (!on) stopWriting();
    }

    synchronized void updateLocked(boolean on) {
        locked = on;
        if (on) stopWriting();
    }

    synchronized void updateDock(int state) {
        dockState = state;
        if (state != PenDockMonitor.DETACHED) stopWriting();
    }

    synchronized void reset() { stopWriting(); }

    synchronized boolean allowMotion(int tool, int source, int action, float pressure,
                                     boolean physical, boolean mouseMode) {
        if (writing && action == MotionEvent.ACTION_UP && physical && !mouseMode
                && interactive && !locked && dockState == PenDockMonitor.DETACHED
                && tool == MotionEvent.TOOL_TYPE_STYLUS) {
            writing = false;
            tail = true;
            finish.run();
            return false;
        }
        boolean allowed = interactive && !locked && dockState == PenDockMonitor.DETACHED
                && physical && !mouseMode && tool == MotionEvent.TOOL_TYPE_STYLUS
                && (source & InputDevice.SOURCE_CLASS_POINTER) == InputDevice.SOURCE_CLASS_POINTER
                && (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE)
                && Float.isFinite(pressure) && pressure > CONTACT_PRESSURE;
        if (allowed) { writing = true; tail = false; }
        else if (writing || action != MotionEvent.ACTION_HOVER_MOVE) stopWriting();
        return allowed;
    }

    private void stopWriting() {
        if (!writing && !tail) return;
        writing = tail = false;
        stop.run();
    }
}
