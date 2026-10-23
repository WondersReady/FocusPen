package com.focuspen.enhance.v2;

import java.util.ArrayList;
import java.util.List;

/** Focus-key timing only. Call event/tick/nextDeadline on the same ordered input worker. */
final class FocusGesture {
    static final int ACTION_DOWN = 0;
    static final int ACTION_UP = 1;

    enum Kind { SINGLE, DOUBLE, HOLD, RELEASE_HOLD, CANCEL_HOLD }
    record Effect(Kind kind, long pressId) { }

    private static final class Tap {
        final int device;
        final int key;
        final long pressId;
        final long deadline;

        Tap(Press press, long deadline) {
            device = press.device;
            key = press.key;
            pressId = press.id;
            this.deadline = deadline;
        }

        boolean matches(int nextDevice, int nextKey) {
            return device == nextDevice && key == nextKey;
        }
    }

    private static final class Press {
        final int device;
        final int key;
        final long downTime;
        final long id;
        final long holdDeadline;
        final int doubleMs;
        Tap firstTap;
        boolean held;

        Press(int device, int key, long downTime, long id, long holdDeadline, int doubleMs) {
            this.device = device;
            this.key = key;
            this.downTime = downTime;
            this.id = id;
            this.holdDeadline = holdDeadline;
            this.doubleMs = doubleMs;
        }

        boolean matches(int nextDevice, int nextKey, long nextDownTime) {
            return device == nextDevice && key == nextKey && downTime == nextDownTime;
        }
    }

    private Press press;
    private Tap pendingTap;
    private long lastPressId;

    /** Settings are captured on a fresh DOWN; later event arguments cannot change its timers. */
    List<Effect> event(int device, int key, long downTime, int action, int repeat,
                       boolean canceled, long now, int holdMs, int doubleMs) {
        if (action != ACTION_DOWN && action != ACTION_UP)
            throw new IllegalArgumentException("Unknown focus action");
        if (now < 0) throw new IllegalArgumentException("Invalid focus time");
        if (canceled) {
            if (action == ACTION_UP && (press == null || !press.matches(device, key, downTime)))
                return List.of();
            return cancel();
        }
        List<Effect> effects = new ArrayList<>();
        settleTap(now, effects);

        if (action == ACTION_DOWN) {
            if (press != null && press.matches(device, key, downTime)) {
                fireHold(now, effects);
                return List.copyOf(effects);
            }
            if (repeat != 0) return List.copyOf(effects);
            if (holdMs < 0 || doubleMs < 0)
                throw new IllegalArgumentException("Invalid focus threshold");
            if (press != null) {
                if (press.held) effects.add(new Effect(Kind.CANCEL_HOLD, press.id));
                else if (press.firstTap != null)
                    effects.add(new Effect(Kind.SINGLE, press.firstTap.pressId));
                press = null;
            }
            if (pendingTap != null && !pendingTap.matches(device, key)) {
                effects.add(new Effect(Kind.SINGLE, pendingTap.pressId));
                pendingTap = null;
            }
            if (lastPressId == Long.MAX_VALUE) throw new IllegalStateException("Press IDs exhausted");
            Press next = new Press(device, key, downTime, ++lastPressId,
                    deadline(now, holdMs), doubleMs);
            if (pendingTap != null) {
                next.firstTap = pendingTap;
                pendingTap = null;
            }
            press = next;
            fireHold(now, effects);
            return List.copyOf(effects);
        }

        if (press == null || !press.matches(device, key, downTime)) return List.copyOf(effects);
        fireHold(now, effects);
        Press finished = press;
        press = null;
        if (finished.held) effects.add(new Effect(Kind.RELEASE_HOLD, finished.id));
        else if (finished.firstTap != null) effects.add(new Effect(Kind.DOUBLE, finished.id));
        else if (finished.doubleMs == 0) effects.add(new Effect(Kind.SINGLE, finished.id));
        else pendingTap = new Tap(finished, deadline(now, finished.doubleMs));
        return List.copyOf(effects);
    }

    List<Effect> tick(long now) {
        if (now < 0) throw new IllegalArgumentException("Invalid focus time");
        List<Effect> effects = new ArrayList<>();
        settleTap(now, effects);
        fireHold(now, effects);
        return List.copyOf(effects);
    }

    long nextDeadline() {
        long next = pendingTap == null ? Long.MAX_VALUE : pendingTap.deadline;
        if (press != null && !press.held) next = Math.min(next, press.holdDeadline);
        return next;
    }

    /** Drops pending taps; only an already fired hold needs a cancellation release. */
    List<Effect> cancel() {
        List<Effect> effects = press != null && press.held
                ? List.of(new Effect(Kind.CANCEL_HOLD, press.id)) : List.of();
        press = null;
        pendingTap = null;
        return effects;
    }

    private void settleTap(long now, List<Effect> effects) {
        if (pendingTap != null && now >= pendingTap.deadline) {
            effects.add(new Effect(Kind.SINGLE, pendingTap.pressId));
            pendingTap = null;
        }
    }

    private void fireHold(long now, List<Effect> effects) {
        if (press == null || press.held || now < press.holdDeadline) return;
        if (press.firstTap != null) {
            effects.add(new Effect(Kind.SINGLE, press.firstTap.pressId));
            press.firstTap = null;
        }
        press.held = true;
        effects.add(new Effect(Kind.HOLD, press.id));
    }

    private static long deadline(long now, int delayMs) {
        return now > Long.MAX_VALUE - delayMs ? Long.MAX_VALUE : now + delayMs;
    }
}
