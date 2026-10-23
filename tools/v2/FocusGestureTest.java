package com.focuspen.enhance.v2;

import java.util.List;

public final class FocusGestureTest {
    private static final int DEVICE = 7;
    private static final int KEY = 193;

    public static void main(String[] args) {
        singleWaitsForReleaseWindow();
        doubleStartsBeforeDeadline();
        boundaryStartsNewSingle();
        holdAndRepeats();
        secondPressHoldSettlesFirst();
        lostReleaseCancelsHold();
        cancellationDropsPendingWork();
        identitiesAndImmediateThresholds();
        System.out.println("Focus gesture timing: PASS");
    }

    private static void singleWaitsForReleaseWindow() {
        FocusGesture g = new FocusGesture();
        effects(g.event(DEVICE, KEY, 10, 0, 0, false, 10, 100, 200));
        deadline(g, 110);
        effects(g.event(DEVICE, KEY, 10, 1, 0, false, 50, 1, 1));
        deadline(g, 250);
        effects(g.tick(249));
        effects(g.tick(250), effect(FocusGesture.Kind.SINGLE, 1));
        effects(g.tick(999));
        deadline(g, Long.MAX_VALUE);
    }

    private static void doubleStartsBeforeDeadline() {
        FocusGesture g = new FocusGesture();
        effects(g.event(DEVICE, KEY, 1, 0, 0, false, 0, 200, 100));
        effects(g.event(DEVICE, KEY, 1, 1, 0, false, 20, 0, 0));
        effects(g.event(DEVICE, KEY, 2, 0, 0, false, 110, 200, 1));
        deadline(g, 310);
        effects(g.tick(120));
        effects(g.event(DEVICE, KEY, 2, 1, 0, false, 130, 0, 0),
                effect(FocusGesture.Kind.DOUBLE, 2));
        effects(g.tick(500));
    }

    private static void boundaryStartsNewSingle() {
        FocusGesture g = new FocusGesture();
        effects(g.event(DEVICE, KEY, 1, 0, 0, false, 0, 100, 100));
        effects(g.event(DEVICE, KEY, 1, 1, 0, false, 20, 100, 100));
        effects(g.event(DEVICE, KEY, 2, 0, 0, false, 120, 100, 100),
                effect(FocusGesture.Kind.SINGLE, 1));
        effects(g.event(DEVICE, KEY, 2, 1, 0, false, 130, 100, 100));
        effects(g.tick(230), effect(FocusGesture.Kind.SINGLE, 2));
    }

    private static void holdAndRepeats() {
        FocusGesture g = new FocusGesture();
        effects(g.event(DEVICE, KEY, 1, 0, 0, false, 0, 100, 100));
        effects(g.event(DEVICE, KEY, 1, 0, 1, false, 99, 1, 1));
        effects(g.tick(100), effect(FocusGesture.Kind.HOLD, 1));
        effects(g.event(DEVICE, KEY, 1, 0, 2, false, 101, 1, 1));
        effects(g.event(DEVICE, KEY, 1, 1, 0, false, 102, 1, 1),
                effect(FocusGesture.Kind.RELEASE_HOLD, 1));
        effects(g.tick(1000));
    }

    private static void secondPressHoldSettlesFirst() {
        FocusGesture g = new FocusGesture();
        effects(g.event(DEVICE, KEY, 1, 0, 0, false, 0, 100, 100));
        effects(g.event(DEVICE, KEY, 1, 1, 0, false, 20, 100, 100));
        effects(g.event(DEVICE, KEY, 2, 0, 0, false, 110, 50, 1));
        effects(g.tick(120));
        deadline(g, 160);
        effects(g.tick(160), effect(FocusGesture.Kind.SINGLE, 1),
                effect(FocusGesture.Kind.HOLD, 2));
        effects(g.event(DEVICE, KEY, 2, 1, 0, false, 170, 999, 999),
                effect(FocusGesture.Kind.RELEASE_HOLD, 2));
    }

    private static void lostReleaseCancelsHold() {
        FocusGesture g = new FocusGesture();
        effects(g.event(DEVICE, KEY, 1, 0, 0, false, 0, 100, 100));
        effects(g.tick(100), effect(FocusGesture.Kind.HOLD, 1));
        effects(g.event(DEVICE, KEY, 2, 0, 0, false, 200, 100, 100),
                effect(FocusGesture.Kind.CANCEL_HOLD, 1));
        effects(g.event(DEVICE, KEY, 1, 1, 0, false, 201, 100, 100));
        effects(g.tick(300), effect(FocusGesture.Kind.HOLD, 2));
        effects(g.event(DEVICE, KEY, 2, 1, 0, false, 301, 100, 100),
                effect(FocusGesture.Kind.RELEASE_HOLD, 2));

        g = new FocusGesture();
        effects(g.event(DEVICE, KEY, 1, 0, 0, false, 0, 100, 100));
        effects(g.event(DEVICE, KEY, 2, 0, 0, false, 200, 100, 100));
        effects(g.tick(299));
        effects(g.tick(300), effect(FocusGesture.Kind.HOLD, 2));
    }

    private static void cancellationDropsPendingWork() {
        FocusGesture g = new FocusGesture();
        effects(g.event(DEVICE, KEY, 1, 0, 0, false, 0, 100, 100));
        effects(g.event(DEVICE, KEY, 1, 1, 0, false, 20, 100, 100));
        effects(g.cancel());
        effects(g.tick(200));
        deadline(g, Long.MAX_VALUE);
        effects(g.event(DEVICE, KEY, 2, 0, 0, false, 300, 100, 100));
        effects(g.tick(400), effect(FocusGesture.Kind.HOLD, 2));
        effects(g.cancel(), effect(FocusGesture.Kind.CANCEL_HOLD, 2));
        effects(g.event(DEVICE, KEY, 2, 1, 0, false, 410, 100, 100));
        effects(g.cancel());

        g = new FocusGesture();
        effects(g.event(DEVICE, KEY, 1, 0, 0, false, 0, 100, 100));
        effects(g.event(DEVICE, KEY, 1, 1, 0, true, 100, 1, 1));
        effects(g.tick(1000));

        g = new FocusGesture();
        effects(g.event(DEVICE, KEY, 1, 0, 0, false, 0, 100, 100));
        effects(g.event(DEVICE, KEY, 1, 1, 0, false, 20, 100, 100));
        effects(g.event(DEVICE, KEY, 2, 0, 0, true, 120, 100, 100));
        effects(g.tick(200));
    }

    private static void identitiesAndImmediateThresholds() {
        FocusGesture g = new FocusGesture();
        effects(g.event(DEVICE, KEY, 1, 0, 0, false, 0, 100, 100));
        effects(g.event(DEVICE + 1, KEY, 1, 1, 0, true, 10, 0, 0));
        deadline(g, 100);
        effects(g.event(DEVICE, KEY, 1, 1, 0, false, 20, 0, 0));
        effects(g.event(DEVICE + 1, KEY, 2, 0, 0, false, 30, 100, 100),
                effect(FocusGesture.Kind.SINGLE, 1));
        effects(g.event(DEVICE + 1, KEY, 2, 1, 0, false, 40, 0, 0));
        effects(g.cancel());

        g = new FocusGesture();
        effects(g.event(DEVICE, KEY, 1, 0, 0, false, 0, 0, 100),
                effect(FocusGesture.Kind.HOLD, 1));
        effects(g.event(DEVICE, KEY, 1, 1, 0, false, 0, 0, 100),
                effect(FocusGesture.Kind.RELEASE_HOLD, 1));
        effects(g.event(DEVICE, KEY, 2, 0, 0, false, 10, 100, 0));
        effects(g.event(DEVICE, KEY, 2, 1, 0, false, 20, 100, 0),
                effect(FocusGesture.Kind.SINGLE, 2));
    }

    private static FocusGesture.Effect effect(FocusGesture.Kind kind, long id) {
        return new FocusGesture.Effect(kind, id);
    }

    private static void effects(List<FocusGesture.Effect> actual, FocusGesture.Effect... expected) {
        if (!actual.equals(List.of(expected)))
            throw new AssertionError("Effects " + actual + " != " + List.of(expected));
    }

    private static void deadline(FocusGesture g, long expected) {
        if (g.nextDeadline() != expected)
            throw new AssertionError("Deadline " + g.nextDeadline() + " != " + expected);
    }
}
