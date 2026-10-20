package com.focuspen.enhance.v2;

/** Physical contact generation; injected events and hover must be filtered by the caller. */
final class PointerContact {
    private long generation;
    private boolean touching;
    private boolean observing;
    synchronized void observing(boolean value) { observing=value; touching=false; generation++; }
    synchronized void observe(int action) {
        if (action == 0 || action == 5 || action == 2 && !touching) { touching = true; generation++; }
        else if (action == 1 || action == 3) { touching = false; generation++; }
    }
    synchronized long idleGeneration() { return !observing || touching ? -1 : generation; }
    synchronized boolean unchanged(long value) { return observing && value >= 0 && !touching && generation == value; }
}
