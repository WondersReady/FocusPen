package com.focuspen.enhance.v2;

import java.util.ArrayList;
import java.util.List;

/** State and effects for presentation gestures already recognized by the input host. */
final class PresentationState {
    enum Mode { IDLE, LASER, ANNOTATION, MOUSE }
    enum LaserActivation { HOLD, TOGGLE }
    enum Kind {
        SHOW_LASER, HIDE_LASER, SHOW_ANNOTATION, HIDE_ANNOTATION,
        START_INK, STOP_INK, CLEAR_INK, START_MOUSE, STOP_MOUSE, COLOR_CHANGED
    }

    record Effect(Kind kind, int colorIndex) { }
    record PressToken(long pressId, long generation) { }
    record Snapshot(Mode mode, boolean hasInk, boolean drawing, int colorIndex, long generation) { }
    record Result(Snapshot snapshot, List<Effect> effects, PressToken token) { }

    private final int colorCount;
    private Mode mode = Mode.IDLE;
    private int colorIndex;
    private boolean hasInk;
    private boolean pendingInk;
    private boolean drawing;
    private boolean activeLaserHold;
    private long generation;
    private long lastPressId = Long.MIN_VALUE;
    private PressToken activePress;

    PresentationState(int colorCount, int initialColorIndex) {
        if (colorCount <= 0 || initialColorIndex < 0 || initialColorIndex >= colorCount)
            throw new IllegalArgumentException("invalid color palette");
        this.colorCount = colorCount;
        colorIndex = initialColorIndex;
    }

    synchronized Snapshot snapshot() {
        return new Snapshot(mode, hasInk, drawing, colorIndex, generation);
    }

    synchronized Result focusSingle() {
        List<Effect> effects = new ArrayList<>();
        if (mode == Mode.IDLE) {
            endPress(effects);
            mode = Mode.ANNOTATION;
            generation++;
            effects.add(effect(Kind.SHOW_ANNOTATION));
        } else if (mode == Mode.ANNOTATION) {
            endPress(effects);
            mode = Mode.IDLE;
            generation++;
            effects.add(effect(Kind.HIDE_ANNOTATION));
        }
        return result(effects, null);
    }

    synchronized Result focusDouble() {
        List<Effect> effects = new ArrayList<>();
        if (mode == Mode.MOUSE) {
            endPress(effects);
            mode = Mode.IDLE;
            generation++;
            effects.add(effect(Kind.STOP_MOUSE));
        } else if (mode == Mode.ANNOTATION || hasInk || pendingInk) {
            if (mode == Mode.ANNOTATION && endPress(effects)) generation++;
            hasInk = false;
            pendingInk = false;
            effects.add(effect(Kind.CLEAR_INK));
        } else if (mode == Mode.IDLE) {
            endPress(effects);
            mode = Mode.MOUSE;
            generation++;
            effects.add(effect(Kind.START_MOUSE));
        }
        return result(effects, null);
    }

    // pressId is supplied by the host and must increase for each physical press.
    synchronized Result focusHold(long pressId, LaserActivation activation) {
        if (activation == null) throw new IllegalArgumentException("laser activation required");
        if (pressId <= lastPressId) return result(List.of(), activePress != null && activePress.pressId() == pressId ? activePress : null);
        lastPressId = pressId;
        List<Effect> effects = new ArrayList<>();
        if (activePress != null) {
            boolean wasHeldLaser = mode == Mode.LASER && activeLaserHold;
            endPress(effects);
            if (wasHeldLaser) {
                mode = Mode.IDLE;
                generation++;
                effects.add(effect(Kind.HIDE_LASER));
            }
        }
        if (mode == Mode.MOUSE) return result(effects, null);
        if (mode == Mode.ANNOTATION) {
            drawing = true;
            activePress = new PressToken(pressId, generation);
            effects.add(colored(Kind.START_INK));
        } else if (mode == Mode.IDLE) {
            mode = Mode.LASER;
            generation++;
            activeLaserHold = activation == LaserActivation.HOLD;
            activePress = new PressToken(pressId, generation);
            effects.add(colored(Kind.SHOW_LASER));
        } else if (activation == LaserActivation.TOGGLE) {
            mode = Mode.IDLE;
            generation++;
            activePress = new PressToken(pressId, generation);
            effects.add(effect(Kind.HIDE_LASER));
        }
        return result(effects, activePress);
    }

    synchronized Result release(PressToken token) {
        if (token == null || token != activePress || token.generation() != generation)
            return result(List.of(), null);
        List<Effect> effects = new ArrayList<>();
        boolean wasHeldLaser = mode == Mode.LASER && activeLaserHold;
        endPress(effects);
        if (wasHeldLaser) {
            mode = Mode.IDLE;
            generation++;
            effects.add(effect(Kind.HIDE_LASER));
        }
        return result(effects, null);
    }

    // The drawing adapter calls this only after it has actually added ink.
    synchronized Result inkAdded() {
        if (mode == Mode.ANNOTATION && drawing) hasInk = true;
        return result(List.of(), activePress);
    }

    // Renderer acknowledgement may arrive after the matching physical release.
    // The host rejects acknowledgements from a cleared ink epoch before calling this.
    synchronized void inkStored() { hasInk = true; }

    // A queued renderer sample must be cleared before an idle double tap can
    // enter mouse, even when the main thread has not acknowledged its ink yet.
    synchronized void inkQueued() { if(drawing)pendingInk=true; }

    synchronized Result enterMouse() {
        if(mode==Mode.MOUSE)return result(List.of(),null);
        List<Effect> effects=new ArrayList<>(reset().effects());
        mode=Mode.MOUSE;generation++;
        effects.add(effect(Kind.START_MOUSE));
        return result(effects,null);
    }

    synchronized Result enterLaser() {
        if(mode==Mode.LASER)return result(List.of(),null);
        List<Effect> effects=new ArrayList<>(reset().effects());
        mode=Mode.LASER;generation++;
        effects.add(colored(Kind.SHOW_LASER));
        return result(effects,null);
    }

    synchronized Result proDraw(boolean down) {
        List<Effect> effects=new ArrayList<>();
        if(down && mode==Mode.LASER){
            endPress(effects);mode=Mode.ANNOTATION;drawing=true;generation++;
            effects.add(effect(Kind.HIDE_LASER));effects.add(colored(Kind.SHOW_ANNOTATION));effects.add(colored(Kind.START_INK));
        }else if(!down && mode==Mode.ANNOTATION && drawing){
            drawing=false;mode=Mode.LASER;generation++;
            effects.add(effect(Kind.STOP_INK));effects.add(effect(Kind.HIDE_ANNOTATION));effects.add(colored(Kind.SHOW_LASER));
        }
        return result(effects,null);
    }

    synchronized Result shiftColor(int steps) {
        if (mode != Mode.LASER && mode != Mode.ANNOTATION) return result(List.of(), activePress);
        int next = Math.floorMod((long) colorIndex + steps, colorCount);
        if (next == colorIndex) return result(List.of(), activePress);
        List<Effect> effects = new ArrayList<>();
        if (drawing) effects.add(effect(Kind.STOP_INK));
        colorIndex = next;
        effects.add(colored(Kind.COLOR_CHANGED));
        if (drawing) effects.add(colored(Kind.START_INK));
        return result(effects, activePress);
    }

    // Focus, window and status-bar changes leave the current mode in place.
    synchronized Result contextChanged() {
        return result(List.of(), activePress);
    }

    // Called explicitly by the controller for screen, lock, disconnect or failure policy.
    synchronized Result reset() {
        List<Effect> effects = new ArrayList<>();
        endPress(effects);
        if (mode == Mode.LASER) effects.add(effect(Kind.HIDE_LASER));
        if (mode == Mode.ANNOTATION) effects.add(effect(Kind.HIDE_ANNOTATION));
        if (mode == Mode.MOUSE) effects.add(effect(Kind.STOP_MOUSE));
        if (hasInk || pendingInk) effects.add(effect(Kind.CLEAR_INK));
        mode = Mode.IDLE;
        hasInk = false;
        pendingInk = false;
        generation++;
        return result(effects, null);
    }

    private boolean endPress(List<Effect> effects) {
        if (activePress == null) return false;
        activePress = null;
        activeLaserHold = false;
        if (drawing) {
            drawing = false;
            effects.add(effect(Kind.STOP_INK));
        }
        return true;
    }

    private Effect effect(Kind kind) { return new Effect(kind, -1); }
    private Effect colored(Kind kind) { return new Effect(kind, colorIndex); }
    private Result result(List<Effect> effects, PressToken token) {
        return new Result(snapshot(), List.copyOf(effects), token);
    }
}
