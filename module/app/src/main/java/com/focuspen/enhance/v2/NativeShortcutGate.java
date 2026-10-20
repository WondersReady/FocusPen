package com.focuspen.enhance.v2;

/** Native long-press timers must never race the module's configured gesture owner. */
final class NativeShortcutGate {
    private final boolean[] blocked = new boolean[4];
    synchronized boolean observe(int button, boolean normalPen, boolean takeover, PenAction longAction) {
        if (button < 1 || button > 3) return false;
        blocked[button] = normalPen && takeover && longAction != PenAction.NATIVE;
        return blocked[button];
    }
    synchronized boolean blocks(int button, boolean enabled, PenAction currentLongAction) {
        return button >= 1 && button <= 3 && enabled && currentLongAction != PenAction.NATIVE && blocked[button];
    }
    synchronized void clear() { java.util.Arrays.fill(blocked, false); }
    static boolean ownsInput(boolean available, boolean enabled, boolean keys, boolean interactive) {
        // UI transport readiness affects action delivery, never ownership of a configured key.
        return available && enabled && keys && interactive;
    }
}
