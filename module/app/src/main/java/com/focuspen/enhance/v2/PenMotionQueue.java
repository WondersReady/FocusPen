package com.focuspen.enhance.v2;

import java.util.ArrayDeque;

/** Coalesces consecutive positions while retaining contact and tool boundaries. */
final class PenMotionQueue<T> {
    private record Sample<T>(int action, int device, int display, T value) { }
    private final ArrayDeque<Sample<T>> queue = new ArrayDeque<>();

    void add(int action, int device, int display, T value) {
        Sample<T> last = queue.peekLast();
        boolean movement = action == PenPointer.MOVE || action == PenPointer.HOVER_MOVE;
        if (movement && last != null && last.action == action
                && last.device == device && last.display == display) queue.removeLast();
        queue.addLast(new Sample<>(action, device, display, value));
    }

    T poll() { Sample<T> next = queue.pollFirst(); return next == null ? null : next.value; }
}
