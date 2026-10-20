package com.focuspen.enhance.v2;

import java.util.ArrayList;
import java.util.List;

/** Pure input arbitration. The adapter supplies monotonic time and owns original KeyEvents. */
final class KeyArbiter {
    enum Kind { ACTION, DOUBLE_ACTION, REPLAY_PENDING_TAP, REPLAY_TAP, REPLAY_DOWN, REPLAY_EVENT, RELEASE, CANCEL }
    record Effect(Kind kind, int button, PenAction action, boolean longPress) {
        Effect(Kind kind,int button,PenAction action) { this(kind,button,action,false); }
    }
    record Result(boolean consumed, List<Effect> effects) { }
    private static final class Press {
        long deadline;
        PenAction shortAction, longAction, doubleAction = PenAction.NONE;
        int doubleMs;
        boolean fired, nativeHeld, ringControl, cancelled, second;
    }
    private static final class Tap { long deadline; PenAction shortAction; }
    private final Tap[] taps = new Tap[4];
    private final Press[] presses = new Press[4];
    private boolean ringActive;

    synchronized Result down(int button, long now, int threshold, PenAction shortAction,
                             PenAction longAction, boolean takeover) {
        return down(button,now,threshold,shortAction,longAction,PenAction.NONE,0,takeover);
    }
    synchronized Result down(int button, long now, int threshold, PenAction shortAction,
                             PenAction longAction, PenAction doubleAction, int doubleMs, boolean takeover) {
        check(button);
        Press existing = presses[button];
        if (existing != null) return existing.nativeHeld
                ? result(true, new Effect(Kind.REPLAY_EVENT, button, existing.longAction)) : result(true);
        if (!takeover && !ringActive) return result(false);
        if (!ringActive && shortAction == PenAction.NATIVE && longAction == PenAction.NATIVE && doubleMs==0) return result(false);
        Press p = new Press(); presses[button] = p;
        p.deadline = now + threshold; p.shortAction = shortAction; p.longAction = longAction;
        p.doubleAction=doubleAction; p.doubleMs=doubleMs;
        if(taps[button]!=null && now<taps[button].deadline) { p.second=true; taps[button]=null; }
        if (ringActive) {
            p.ringControl = true;
            if (button == 2) return result(true, new Effect(Kind.CANCEL, button, PenAction.NONE));
        }
        if(!ringActive && shortAction==PenAction.NONE && longAction==PenAction.NONE && doubleAction==PenAction.NONE){
            p.fired=true;p.nativeHeld=true;return result(true,new Effect(Kind.REPLAY_DOWN,button,PenAction.NONE));
        }
        return result(true);
    }

    synchronized Result up(int button, long now, boolean cancelled) {
        check(button);
        Press p = presses[button];
        if (p == null) return result(false);
        List<Effect> effects = new ArrayList<>();
        if (!cancelled && !p.cancelled && !p.ringControl && !p.fired && now >= p.deadline) fire(button, p, effects);
        presses[button] = null;
        if (p.nativeHeld) {
            // If UP wins a race with the timer, replay a pair; never pass UP ahead of delayed DOWN.
            if (!effects.isEmpty()) {
                effects.removeIf(e -> e.kind == Kind.REPLAY_DOWN);
                effects.add(new Effect(Kind.REPLAY_TAP, button, p.longAction));
                return new Result(true,effects);
            }
            // Keep repeat/UP on the same replay queue as DOWN even if injection has not run yet.
            return result(true, new Effect(Kind.REPLAY_EVENT, button, p.longAction));
        }
        if (cancelled || p.cancelled) return new Result(true, effects);
        if (p.ringControl) {
            // The current ring commits from its icon click. A key release while the
            // ring is open is consumed without confirming or replaying the key.
        } else if (p.fired) {
            effects.add(new Effect(Kind.RELEASE, button, p.longAction));
        } else if (p.second) {
            effects.add(new Effect(Kind.DOUBLE_ACTION,button,p.doubleAction));
        } else if(p.doubleMs>0) {
            Tap tap=new Tap(); tap.deadline=now+p.doubleMs; tap.shortAction=p.shortAction; taps[button]=tap;
        } else if (replay(p.shortAction)) {
            effects.add(new Effect(Kind.REPLAY_TAP, button, p.shortAction));
        } else {
            effects.add(new Effect(Kind.ACTION, button, p.shortAction));
        }
        return new Result(true, effects);
    }

    synchronized List<Effect> tick(long now) {
        List<Effect> effects = new ArrayList<>();
        for (int button = 1; button <= 3; button++) {
            Tap tap=taps[button];
            if(tap!=null && now>=tap.deadline) {
                taps[button]=null;
                effects.add(new Effect(replay(tap.shortAction)?Kind.REPLAY_PENDING_TAP:Kind.ACTION,button,tap.shortAction));
            }
            Press p = presses[button];
            if (p != null && !p.fired && !p.ringControl && !p.cancelled && now >= p.deadline) fire(button, p, effects);
        }
        return effects;
    }

    private void fire(int button, Press p, List<Effect> out) {
        p.fired = true;
        if(p.second) {
            out.add(new Effect(replay(p.shortAction)?Kind.REPLAY_PENDING_TAP:Kind.ACTION,button,p.shortAction));
            p.second=false;
        }
        if (replay(p.longAction)) {
            p.nativeHeld = true;
            out.add(new Effect(Kind.REPLAY_DOWN, button, p.longAction));
        } else out.add(new Effect(Kind.ACTION, button, p.longAction,true));
    }

    synchronized long nextDeadline() {
        long next = Long.MAX_VALUE;
        for (Press p : presses) if (p != null && !p.fired && !p.ringControl && !p.cancelled) next = Math.min(next, p.deadline);
        for(Tap t:taps) if(t!=null) next=Math.min(next,t.deadline);
        return next;
    }

    synchronized void ring(boolean active, boolean hold, int opener) {
        // Keep the old call shape for already loaded hosts; hold/opener are legacy
        // compatibility arguments and have no effect in the click-to-execute ring.
        ringActive = active;
    }

    synchronized boolean nativeHeld(int button) { return presses[button] != null && presses[button].nativeHeld; }
    synchronized void forget(int button) { presses[button] = null; taps[button]=null; }
    synchronized boolean waitingTap(int button) { return taps[button]!=null; }

    /** On focus/device/config changes, drain captured UPs without delivering a stray event. */
    synchronized void cancel() {
        cancel(false);
    }
    synchronized void cancel(boolean preserveRing) {
        if(!preserveRing) ringActive = false;
        java.util.Arrays.fill(taps,null);
        for (Press p : presses) if (p != null && !p.nativeHeld
                && !(preserveRing && ringActive && (p.ringControl || p.fired && p.longAction==PenAction.RING))) p.cancelled = true;
    }
    private static Result result(boolean consume, Effect... effects) { return new Result(consume, List.of(effects)); }
    private static boolean replay(PenAction action){return action==PenAction.NATIVE||action==PenAction.NONE;}
    private static void check(int button) { if (button < 1 || button > 3) throw new IllegalArgumentException("Unknown button"); }
}
