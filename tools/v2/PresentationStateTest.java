package com.focuspen.enhance.v2;

import java.util.ArrayList;
import java.util.List;

public final class PresentationStateTest {
    public static void main(String[] args) {
        laserHoldAndToggle();
        annotationAndInk();
        mouseAndContext();
        staleReleaseAndReset();
        queuedInkAndPro();
        System.out.println("Presentation state transitions: PASS");
    }

    private static void queuedInkAndPro(){
        PresentationState state=new PresentationState(3,0);
        state.focusSingle();
        var press=state.focusHold(1,PresentationState.LaserActivation.HOLD);
        state.inkQueued();state.release(press.token());state.focusSingle();
        expect(!state.snapshot().hasInk(),"queued ink is not renderer evidence");
        expectKinds(state.focusDouble(),PresentationState.Kind.CLEAR_INK);
        expect(state.snapshot().mode()==PresentationState.Mode.IDLE,"pending main-thread ink prevents accidental mouse entry");
        expectKinds(state.focusDouble(),PresentationState.Kind.START_MOUSE);
        state.enterLaser();state.proDraw(true);state.inkStored();
        expect(state.snapshot().drawing(),"Pro pinch draws in virtual laser");
        state.proDraw(false);
        expect(state.snapshot().mode()==PresentationState.Mode.LASER&&!state.snapshot().drawing()&&state.snapshot().hasInk(),"Pro release keeps laser and ink but stops drawing");
        expectKinds(state.focusDouble(),PresentationState.Kind.CLEAR_INK);
    }

    private static void laserHoldAndToggle() {
        PresentationState state = new PresentationState(3, 1);
        PresentationState.Result held = state.focusHold(1, PresentationState.LaserActivation.HOLD);
        expect(held.snapshot().mode() == PresentationState.Mode.LASER, "hold shows laser");
        expectKinds(held, PresentationState.Kind.SHOW_LASER);
        expect(held.effects().get(0).colorIndex() == 1, "laser uses selected color");
        expectKinds(state.focusHold(1, PresentationState.LaserActivation.HOLD));
        expectKinds(state.focusSingle());
        expect(state.snapshot().mode() == PresentationState.Mode.LASER, "short press cannot interrupt visible laser");
        expectKinds(state.focusDouble());
        expect(state.snapshot().mode() == PresentationState.Mode.LASER, "double cannot enter mouse while laser is visible");
        expectKinds(state.release(held.token()), PresentationState.Kind.HIDE_LASER);
        expect(state.snapshot().mode() == PresentationState.Mode.IDLE, "hold release hides laser");

        PresentationState.Result toggleOn = state.focusHold(2, PresentationState.LaserActivation.TOGGLE);
        expectKinds(toggleOn, PresentationState.Kind.SHOW_LASER);
        expectKinds(state.release(toggleOn.token()));
        expect(state.snapshot().mode() == PresentationState.Mode.LASER, "toggle release keeps laser");
        expectKinds(state.shiftColor(1), PresentationState.Kind.COLOR_CHANGED);
        expect(state.snapshot().colorIndex() == 2, "laser color changes without leaving laser");
        expectKinds(state.focusSingle());
        expectKinds(state.focusDouble());
        expect(state.snapshot().mode() == PresentationState.Mode.LASER, "permanent laser excludes mouse and annotation");
        PresentationState.Result toggleOff = state.focusHold(3, PresentationState.LaserActivation.TOGGLE);
        expectKinds(toggleOff, PresentationState.Kind.HIDE_LASER);
        expectKinds(state.focusHold(3, PresentationState.LaserActivation.TOGGLE));
        expectKinds(state.release(toggleOff.token()));
        expect(state.snapshot().mode() == PresentationState.Mode.IDLE, "second hold closes toggled laser once");
    }

    private static void annotationAndInk() {
        PresentationState state = new PresentationState(3, 0);
        expectKinds(state.focusSingle(), PresentationState.Kind.SHOW_ANNOTATION);
        PresentationState.Result draw = state.focusHold(1, PresentationState.LaserActivation.HOLD);
        expectKinds(draw, PresentationState.Kind.START_INK);
        expect(draw.effects().get(0).colorIndex() == 0, "first segment has original color");
        state.inkAdded();
        PresentationState.Result changed = state.shiftColor(1);
        expectKinds(changed, PresentationState.Kind.STOP_INK, PresentationState.Kind.COLOR_CHANGED, PresentationState.Kind.START_INK);
        expect(changed.effects().get(2).colorIndex() == 1, "new segment captures new color");
        expect(changed.snapshot().hasInk(), "color change preserves existing ink");
        expectKinds(state.release(draw.token()), PresentationState.Kind.STOP_INK);
        expect(!state.snapshot().drawing(), "release stops drawing");
        expectKinds(state.focusSingle(), PresentationState.Kind.HIDE_ANNOTATION);
        expect(state.snapshot().hasInk(), "annotation exit keeps old ink");
        PresentationState.Result laser = state.focusHold(2, PresentationState.LaserActivation.TOGGLE);
        expectKinds(laser, PresentationState.Kind.SHOW_LASER);
        state.release(laser.token());
        expectKinds(state.focusDouble(), PresentationState.Kind.CLEAR_INK);
        expect(state.snapshot().mode() == PresentationState.Mode.LASER, "ink clear keeps visible laser");
        PresentationState.Result laserOff = state.focusHold(3, PresentationState.LaserActivation.TOGGLE);
        expectKinds(laserOff, PresentationState.Kind.HIDE_LASER);
        state.release(laserOff.token());
        expectKinds(state.focusDouble(), PresentationState.Kind.START_MOUSE);
        expectKinds(state.focusDouble(), PresentationState.Kind.STOP_MOUSE);
        expectKinds(state.focusSingle(), PresentationState.Kind.SHOW_ANNOTATION);
        PresentationState.Result secondDraw = state.focusHold(4, PresentationState.LaserActivation.HOLD);
        state.inkAdded();
        state.release(secondDraw.token());
        expectKinds(state.focusSingle(), PresentationState.Kind.HIDE_ANNOTATION);
        expectKinds(state.focusDouble(), PresentationState.Kind.CLEAR_INK);
        expect(state.snapshot().mode() == PresentationState.Mode.IDLE, "idle ink clear does not enter mouse");
        expectKinds(state.focusSingle(), PresentationState.Kind.SHOW_ANNOTATION);
        expectKinds(state.focusDouble(), PresentationState.Kind.CLEAR_INK);
        expect(state.snapshot().mode() == PresentationState.Mode.ANNOTATION, "clear keeps annotation available");
        expectKinds(state.shiftColor(-2), PresentationState.Kind.COLOR_CHANGED);
        expect(state.snapshot().colorIndex() == 2, "colors wrap independently of ink");
        expectKinds(state.focusSingle(), PresentationState.Kind.HIDE_ANNOTATION);
    }

    private static void mouseAndContext() {
        PresentationState state = new PresentationState(2, 0);
        expectKinds(state.focusDouble(), PresentationState.Kind.START_MOUSE);
        long generation = state.snapshot().generation();
        expectKinds(state.contextChanged());
        expectKinds(state.contextChanged());
        expectKinds(state.contextChanged());
        expect(state.snapshot().mode() == PresentationState.Mode.MOUSE && state.snapshot().generation() == generation,
                "window, application and status bar changes keep mouse session");
        expectKinds(state.focusSingle());
        expectKinds(state.focusHold(1, PresentationState.LaserActivation.HOLD));
        expectKinds(state.shiftColor(1));
        expect(state.snapshot().mode() == PresentationState.Mode.MOUSE, "mouse ignores presentation gestures");
        expectKinds(state.focusDouble(), PresentationState.Kind.STOP_MOUSE);
        expect(state.snapshot().mode() == PresentationState.Mode.IDLE, "mouse double exits");
    }

    private static void staleReleaseAndReset() {
        PresentationState drawing = new PresentationState(2, 0);
        drawing.focusSingle();
        PresentationState.Result oldDraw = drawing.focusHold(1, PresentationState.LaserActivation.HOLD);
        PresentationState.Result newDraw = drawing.focusHold(2, PresentationState.LaserActivation.HOLD);
        expectKinds(newDraw, PresentationState.Kind.STOP_INK, PresentationState.Kind.START_INK);
        expectKinds(drawing.release(oldDraw.token()));
        expect(drawing.snapshot().drawing(), "old release cannot stop replacement drawing press");
        drawing.inkAdded();
        expectKinds(drawing.reset(), PresentationState.Kind.STOP_INK,
                PresentationState.Kind.HIDE_ANNOTATION, PresentationState.Kind.CLEAR_INK);
        expectKinds(drawing.release(newDraw.token()));
        expect(!drawing.snapshot().hasInk() && drawing.snapshot().mode() == PresentationState.Mode.IDLE,
                "reset discards drawing and ink without restoring another owner");

        PresentationState state = new PresentationState(2, 0);
        state.focusSingle();
        PresentationState.Result first = state.focusHold(1, PresentationState.LaserActivation.HOLD);
        expectKinds(state.focusSingle(), PresentationState.Kind.STOP_INK, PresentationState.Kind.HIDE_ANNOTATION);
        PresentationState.Result second = state.focusHold(2, PresentationState.LaserActivation.TOGGLE);
        expectKinds(second, PresentationState.Kind.SHOW_LASER);
        expectKinds(state.release(first.token()));
        expect(state.snapshot().mode() == PresentationState.Mode.LASER, "old release cannot close new session");
        expectKinds(state.release(second.token()));
        expect(state.snapshot().mode() == PresentationState.Mode.LASER, "toggle release keeps session");
        expectKinds(state.reset(), PresentationState.Kind.HIDE_LASER);
        expect(state.snapshot().mode() == PresentationState.Mode.IDLE, "external reset ends laser");
        expectKinds(state.release(second.token()));
        expectKinds(state.focusHold(2, PresentationState.LaserActivation.TOGGLE));
        expect(state.snapshot().mode() == PresentationState.Mode.IDLE, "old press cannot restart after reset");
        expectKinds(state.focusDouble(), PresentationState.Kind.START_MOUSE);
        expectKinds(state.reset(), PresentationState.Kind.STOP_MOUSE);
        expect(state.snapshot().mode() == PresentationState.Mode.IDLE, "external reset ends mouse");
    }

    private static void expectKinds(PresentationState.Result result, PresentationState.Kind... kinds) {
        List<PresentationState.Kind> actual = new ArrayList<>();
        for (PresentationState.Effect effect : result.effects()) actual.add(effect.kind());
        expect(actual.equals(List.of(kinds)), "effects " + actual + " expected " + List.of(kinds));
    }

    private static void expect(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
