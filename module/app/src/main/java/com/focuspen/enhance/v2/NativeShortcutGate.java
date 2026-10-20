package com.focuspen.enhance.v2;

/** Native long-press timers must never race the module's configured gesture owner. */
final class NativeShortcutGate {
    private final boolean[] blocked = new boolean[4];
    private final boolean[] colorBlocked = new boolean[4];
    synchronized boolean observe(int button, boolean normalPen, boolean takeover, PenAction longAction) {
        return observe(button, normalPen, takeover, longAction, false);
    }
    /**
     * While the laser/annotation presentation owns the two side keys, the ROM
     * long-press timer must also stay out of the way even when their configured
     * action is native. Otherwise a late native quick-note/screenshot callback
     * can hide the active presentation before the color route handles the key.
     */
    synchronized boolean observe(int button, boolean normalPen, boolean takeover,
            PenAction longAction, boolean presentationColorOwner) {
        if (button < 1 || button > 3) return false;
        blocked[button] = normalPen && takeover
                && (presentationColorOwner || longAction != PenAction.NATIVE);
        colorBlocked[button] = normalPen && takeover && presentationColorOwner;
        return blocked[button];
    }
    synchronized boolean blocks(int button, boolean enabled, PenAction currentLongAction) {
        return button >= 1 && button <= 3 && enabled && blocked[button]
                && (currentLongAction != PenAction.NATIVE || colorBlocked[button]);
    }
    synchronized void clear() { java.util.Arrays.fill(blocked, false); java.util.Arrays.fill(colorBlocked, false); }
    static boolean ownsInput(boolean available, boolean enabled, boolean keys, boolean interactive) {
        // UI transport readiness affects action delivery, never ownership of a configured key.
        return available && enabled && keys && interactive;
    }
}
