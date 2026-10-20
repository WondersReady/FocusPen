package com.focuspen.enhance.v2;

/** Identity and direction adapters captured when a physical press starts. */
record KeyTarget(Object window, long focusEpoch, long revision, String pkg, String up, String down) {
    boolean matches(Object currentWindow, long currentEpoch, long currentRevision) {
        return window == currentWindow && focusEpoch == currentEpoch && revision == currentRevision;
    }
    boolean supportsDirection() {
        return window != null && !pkg.isEmpty() && !pkg.equals("com.android.systemui");
    }
    String direction(boolean upwards) { return upwards ? up : down; }
}
