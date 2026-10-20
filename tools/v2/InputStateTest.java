package com.focuspen.enhance.v2;

import java.util.List;

public final class InputStateTest {
    public static void main(String[] args) {
        nativeOwnership();
        pointerAndLayout();
        motionCoalescing();
        gestureTarget();
        doublePress();
        KeyArbiter k = new KeyArbiter();
        require(k.down(1, 0, 200, PenAction.NATIVE, PenAction.RING, true).consumed(), "capture DOWN");
        require(k.tick(199).isEmpty(), "do not trigger early");
        require(k.up(1, 199, false).effects().get(0).kind() == KeyArbiter.Kind.REPLAY_TAP, "short native pair");
        k.down(1, 1000, 200, PenAction.UP, PenAction.RING, true);
        k.down(1, 1100, 200, PenAction.UP, PenAction.RING, true);
        require(k.tick(1200).size() == 1, "repeat must not postpone threshold");
        require(k.tick(1300).isEmpty(), "long fires once");
        require(k.up(1, 1400, false).effects().stream().noneMatch(e -> e.kind() == KeyArbiter.Kind.ACTION), "long UP cannot become tap");
        k.down(1, 0, 200, PenAction.UP, PenAction.RING, true);
        List<KeyArbiter.Effect> boundary = k.up(1, 200, false).effects();
        require(boundary.size() == 2 && boundary.get(0).action() == PenAction.RING, "exact threshold is long even before timer runs");
        k.down(1, 0, 200, PenAction.UP, PenAction.RING, true);
        k.cancel();
        require(k.tick(500).isEmpty() && k.up(1, 600, false).effects().isEmpty(), "focus change cancels timer and drains UP");
        require(!k.down(3, 0, 200, PenAction.NATIVE, PenAction.NATIVE, true).consumed(), "native pair is immediate pass-through");
        k.down(3, 0, 200, PenAction.MEDIA, PenAction.NATIVE, true);
        require(k.tick(200).get(0).kind() == KeyArbiter.Kind.REPLAY_DOWN, "native long begins after arbitration");
        require(k.down(3, 210, 200, PenAction.MEDIA, PenAction.NATIVE, true).effects().get(0).kind()
                == KeyArbiter.Kind.REPLAY_EVENT, "native repeat follows queued DOWN");
        KeyArbiter.Result nativeUp = k.up(3, 400, false);
        require(nativeUp.consumed() && nativeUp.effects().get(0).kind() == KeyArbiter.Kind.REPLAY_EVENT,
                "native-held UP follows queued DOWN even when replay worker is delayed");
        k.down(3, 0, 200, PenAction.MEDIA, PenAction.NATIVE, true);
        KeyArbiter.Result nativeRace = k.up(3, 200, false);
        require(nativeRace.consumed() && nativeRace.effects().get(0).kind() == KeyArbiter.Kind.REPLAY_TAP, "UP/timer race cannot deliver UP before native DOWN");
        k.down(3, 0, 200, PenAction.MEDIA, PenAction.NATIVE, true); k.tick(200); k.cancel();
        require(k.up(3, 500, true).effects().get(0).kind() == KeyArbiter.Kind.REPLAY_EVENT,
                "configuration change must pair a native DOWN with its UP");
        k.down(1, 0, 200, PenAction.UP, PenAction.RING, true); k.cancel(); k.forget(1);
        k.down(1, 1000, 200, PenAction.UP, PenAction.RING, true);
        require(k.tick(1200).get(0).action() == PenAction.RING, "missing old UP cannot poison new press");
        k.up(1, 1300, false);
        k.ring(true, false, 1);
        k.down(1, 0, 200, PenAction.UP, PenAction.RING, true);
        require(k.up(1, 60, false).effects().isEmpty(), "ring key release is consumed without confirmation");
        require(k.down(2, 0, 200, PenAction.DOWN, PenAction.RECOGNITION, true).effects().get(0).kind() == KeyArbiter.Kind.CANCEL, "second key cancels ring");
        require(k.tick(300).isEmpty(), "cancel key cannot start recognition");
        k.up(2, 400, false);
        k.ring(false, false, 1);
        require(!k.down(1, 0, 200, PenAction.UP, PenAction.RING, false).consumed(), "unavailable must pass through");
        RingSession r = new RingSession();
        r.open(1, 0);
        require(r.click(null) == null && r.active(), "empty area does not execute");
        require("notes".equals(r.click("notes")) && !r.active(), "icon click executes one item");
        r.open(1, 0); r.select("notes");
        require("other".equals(r.click("other")) && !r.active(), "any icon click executes the clicked item");
        r.open(1, 0); require(r.expire(12000) && !r.active(), "bounded ring timeout");
        RingMotion motion = new RingMotion();
        motion.target(1, 0, true, false);
        require(motion.value(100) > 0 && motion.running(100), "glass animation advances");
        float interrupted = motion.value(100); motion.target(0, 100, true, false);
        require(Math.abs(motion.value(100) - interrupted) < 0.001f, "cancel preserves visual continuity");
        require(motion.value(10000) == 0 && !motion.running(10000), "stalled frame still reaches final state");
        motion.target(1, 10000, false, true);
        require(motion.value(10000) == 1 && !motion.running(10000), "reduce motion has no spring or active frame loop");
        System.out.println("Input arbitration, native ownership, pointer lifetime, ring geometry and transitions: PASS");
    }
    private static void gestureTarget() {
        Object window = new Object(), secondWindow = new Object();
        KeyTarget target = new KeyTarget(window, 4, 10, "reader.example", "page", "dpad");
        KeyArbiter keys = new KeyArbiter();
        keys.down(1, 0, 250, PenAction.UP, PenAction.RING, PenAction.NOTE, 280, true);
        require(target.matches(window, 4, 10), "current press target remains eligible");
        require(!target.matches(secondWindow, 5, 10), "same-package different window rejects queued gesture");
        require(!target.matches(window, 6, 10), "leaving and returning to same window cannot revive old action");
        require(keys.tick(250).get(0).action() == PenAction.RING && !target.matches(window, 4, 11),
                "configuration published before observer cancels still rejects expired timer result");
        require(target.direction(true).equals("page") && target.direction(false).equals("dpad"),
                "direction adapters stay bound to original press");
        require(target.supportsDirection(), "ordinary focused application accepts direction");
        require(!new KeyTarget(null, 0, 10, "", "native", "native").supportsDirection(),
                "unknown focus cannot receive delayed direction replay");
        require(!new KeyTarget(window, 4, 10, "com.android.systemui", "page", "page").supportsDirection(),
                "notification shade cannot receive app direction replay");
    }
    private static void motionCoalescing() {
        PenMotionQueue<Integer> queue = new PenMotionQueue<>();
        queue.add(PenPointer.HOVER_ENTER, 1, 0, 0);
        for (int i = 1; i <= 1000; i++) queue.add(PenPointer.HOVER_MOVE, 1, 0, i);
        queue.add(PenPointer.HOVER_EXIT, 1, 0, 1001);
        queue.add(PenPointer.DOWN, 1, 0, 1002);
        queue.add(PenPointer.MOVE, 1, 0, 1003);
        queue.add(PenPointer.MOVE, 1, 0, 1004);
        queue.add(PenPointer.UP, 1, 0, 1005);
        queue.add(PenPointer.HOVER_ENTER, 1, 0, 1006);
        for (int expected : new int[]{0,1000,1001,1002,1004,1005,1006})
            require(Integer.valueOf(expected).equals(queue.poll()), "latest position retains ordered pen boundaries");
        require(queue.poll() == null, "movement burst leaves no stale backlog");
        queue.add(PenPointer.MOVE, 1, 0, 1);
        queue.add(PenPointer.MOVE, 2, 0, 2);
        queue.add(PenPointer.MOVE, 2, 1, 3);
        queue.add(PenPointer.CANCEL, 2, 1, 4);
        queue.add(PenPointer.MOVE, 2, 1, 5);
        for (int expected = 1; expected <= 5; expected++)
            require(Integer.valueOf(expected).equals(queue.poll()), "device, display and cancellation split motion batches");
        PenPointer pointer = new PenPointer();
        queue.add(PenPointer.DOWN, 1, 0, PenPointer.DOWN);
        queue.add(PenPointer.MOVE, 1, 0, PenPointer.MOVE);
        queue.add(PenPointer.UP, 1, 0, PenPointer.UP);
        int contacts = 0; Integer action;
        while ((action = queue.poll()) != null) {
            if (pointer.accept(action, 1, 0, 0, 100, 100, 50, 50).contact()) contacts++;
        }
        require(contacts == 1 && !pointer.recent(100) && !pointer.hover(),
                "fast down-move-up still emits contact once and clears pointer");
    }
    private static void nativeOwnership() {
        NativeShortcutGate gate = new NativeShortcutGate();
        KeyArbiter keys = new KeyArbiter();
        boolean owned = NativeShortcutGate.ownsInput(true, true, true, true);
        require(owned, "configured interactive keys belong to module without UI transport");
        require(gate.observe(1, true, owned, PenAction.RING), "custom long disables native timer");
        keys.down(1, 0, 250, PenAction.NATIVE, PenAction.RING, owned);
        // No UI connection or heartbeat is supplied: a long press still cannot emit a native replay.
        require(keys.tick(250).stream().allMatch(e -> e.kind() == KeyArbiter.Kind.ACTION && e.action() == PenAction.RING),
                "UI outage cannot change a custom long press into native replay");
        require(gate.blocks(1, owned, PenAction.RING), "late native action blocked while UI is absent");
        keys.up(1, 400, false);
        keys.down(1, 500, 250, PenAction.NATIVE, PenAction.RING, owned);
        require(keys.up(1, 550, false).effects().get(0).kind() == KeyArbiter.Kind.REPLAY_TAP, "native short survives custom long");
        require(gate.observe(1, true, owned, PenAction.RING), "replayed short cannot restart native long timer");
        require(!gate.blocks(1, owned, PenAction.NATIVE), "switch to native takes effect before next input");
        require(!gate.observe(1, true, owned, PenAction.NATIVE), "native long retains its timer");
        require(!gate.blocks(1, owned, PenAction.RING), "native observation clears old ownership");
        require(gate.observe(1, true, owned, PenAction.NATIVE, true),
                "presentation color owner must guard native timer even with native action");
        require(gate.blocks(1, owned, PenAction.NATIVE),
                "presentation color owner lost native timer guard");
        require(!gate.observe(1, true, owned, PenAction.NATIVE, false),
                "presentation guard must clear after presentation ends");
        gate.observe(2, true, owned, PenAction.RECOGNITION);
        require(!gate.observe(2, false, owned, PenAction.RECOGNITION), "other pen types retain native policy");
        require(!gate.blocks(2, owned, PenAction.RECOGNITION), "other pen clears delayed-action guard");
        gate.observe(3, true, owned, PenAction.RING);
        keys.down(3, 1000, 250, PenAction.NATIVE, PenAction.RING, owned);
        require(keys.up(3, 1050, false).effects().get(0).kind() == KeyArbiter.Kind.REPLAY_TAP
                && gate.blocks(3, owned, PenAction.RING), "focus key native tap remains replayable with its long action guarded");
        gate.observe(1, true, owned, PenAction.NONE);
        require(gate.blocks(1, owned, PenAction.NONE), "explicit no-action also blocks native long");
        require(!gate.blocks(1, false, PenAction.NONE), "disable immediately restores native actions");
        gate.clear();
        require(!gate.blocks(1, owned, PenAction.NONE), "screen off removes prior ownership");
        require(!NativeShortcutGate.ownsInput(false, true, true, true), "failed configuration is native");
        require(!NativeShortcutGate.ownsInput(true, false, true, true), "master switch off is native");
        require(!NativeShortcutGate.ownsInput(true, true, false, true), "keys switch off is native");
        require(!NativeShortcutGate.ownsInput(true, true, true, false), "locked or screen off is native");
    }
    private static void pointerAndLayout() {
        PenPointer p = new PenPointer();
        require(p.accept(9, 1, 0, 0, 1000, 1000, 200, 300).accepted() && p.hover(), "hover enters");
        require(!p.accept(7, 1, 1, 0, 1010, 1010, 200, 300).accepted(), "other display ignored");
        require(!p.accept(7, 1, 0, 0, 999, 1010, 200, 300).accepted(), "out of order ignored");
        require(!p.accept(7, 1, 0, 0, 1010, 1600, 200, 300).accepted(), "stale pointer ignored");
        require(!p.accept(7, 1, 0, 0, 1700, 1600, 200, 300).accepted(), "future pointer ignored");
        require(!p.accept(7, 1, 0, 0, 1600, 1600, Float.NaN, 300).accepted(), "nonfinite coordinate ignored");
        require(p.expire(1701) && !p.hover() && !p.recent(1701), "silent pointer cannot keep a selection alive");
        require(p.accept(9, 2, 0, 0, 1800, 1800, 200, 300).deviceChanged(), "pen change is observable");
        require(p.accept(0, 2, 0, 0, 1810, 1810, 200, 300).contact() && !p.hover(), "contact stops hover");
        p.accept(3, 2, 0, 0, 1820, 1820, 200, 300);
        require(!p.recent(1820), "tool cancellation drops pointer");
        for (int count = 1; count <= 12; count++) {
            for (float x : new float[]{-50, 500, 2000}) for (float y : new float[]{-50, 400, 2000}) {
                RingLayout layout = RingLayout.place(x, y, 20, 30, 980, 770, 2, count);
                require(layout.fits(19.99f,29.99f,980.01f,770.01f), "entire arc and targets remain within safe bounds");
                for (int i = 0; i < count; i++) require(layout.hit(layout.itemX(i), layout.itemY(i)) == i, "draw and hit coordinates agree");
                require(layout.hit(layout.x, layout.y) == -1, "center is not an application target");
            }
        }
        for(int count=1;count<=12;count++) for(int angle=0;angle<360;angle+=15) {
            float direction=(float)Math.toRadians(angle);
            RingLayout oriented=RingLayout.place(500,500,0,0,1000,1000,1,count,direction);
            require(!oriented.edgeFallback && Math.abs(oriented.start+oriented.sweep/2-direction)<.001,"valid direction is retained");
        }
        RingLayout edge=RingLayout.place(10,500,0,0,1000,1000,1,6,(float)Math.PI);
        require(edge.edgeFallback && edge.itemX(3)>edge.x,"left edge chooses right-facing layout");
        RingLayout compact = RingLayout.place(100, 100, 0, 0, 240, 360, 1, 6);
        require(compact.hitRadius * 2 >= 48, "small supported layout keeps usable hit targets");
        RingLayout circle = RingLayout.place(500, 500, 0, 0, 1000, 1000, 1, 12, 0);
        require(circle.fullCircle() && circle.hit(circle.itemX(0), circle.itemY(11)) == 11, "twelve items form a full ring without duplicate endpoint");
        try { RingLayout.place(100, 100, 0, 0, 200, 200, 1, 6); throw new AssertionError("tiny layout accepted"); }
        catch (IllegalArgumentException expected) { }
    }
    private static void doublePress() {
        KeyArbiter k=new KeyArbiter();
        k.down(1,0,200,PenAction.NATIVE,PenAction.RING,PenAction.NOTE,280,true);
        require(k.up(1,50,false).effects().isEmpty(),"first tap waits for second");
        require(k.tick(329).isEmpty(),"short not early");
        require(k.tick(330).get(0).kind()==KeyArbiter.Kind.REPLAY_PENDING_TAP && k.tick(331).isEmpty(),"short exactly once after interval");
        k.down(1,1000,200,PenAction.NATIVE,PenAction.RING,PenAction.NOTE,280,true); k.up(1,1050,false);
        k.down(1,1200,200,PenAction.NATIVE,PenAction.RING,PenAction.NOTE,280,true);
        var twice=k.up(1,1250,false).effects();
        require(twice.size()==1 && twice.get(0).kind()==KeyArbiter.Kind.DOUBLE_ACTION && twice.get(0).action()==PenAction.NOTE,"double emits only requested action");
        require(k.tick(2000).isEmpty(),"double leaves no delayed short");
        k.down(1,3000,200,PenAction.NATIVE,PenAction.RING,PenAction.NOTE,280,true); k.up(1,3050,false);
        k.cancel(); require(k.tick(4000).isEmpty(),"focus change cancels pending short");
        k.down(1,5000,200,PenAction.NATIVE,PenAction.NATIVE,PenAction.NOTE,280,true); k.up(1,5050,false);
        k.down(1,5100,200,PenAction.NATIVE,PenAction.NATIVE,PenAction.NOTE,280,true);
        var secondLong=k.up(1,5300,false).effects();
        require(secondLong.size()==2 && secondLong.get(0).kind()==KeyArbiter.Kind.REPLAY_PENDING_TAP
                && secondLong.get(1).kind()==KeyArbiter.Kind.REPLAY_TAP,"second native long keeps first short at timer race");
        k.down(1,6000,200,PenAction.UP,PenAction.RING,PenAction.NONE,280,true);
        require(k.tick(6200).get(0).longPress(),"long flag survives timer delivery");
        k.ring(true,true,1); k.cancel(true);
        require(k.up(1,6300,false).effects().get(0).kind()==KeyArbiter.Kind.RELEASE,"shade focus preserves opener release");
        k.ring(false,false,1);
        k.down(1,7000,200,PenAction.UP,PenAction.RING,PenAction.NONE,280,true); k.up(1,7050,false);
        k.down(2,7100,200,PenAction.DOWN,PenAction.RECOGNITION,PenAction.NONE,280,true); k.up(2,7150,false);
        require(k.tick(7330).size()==1 && k.tick(7430).size()==1,"buttons have independent tap deadlines");
    }
    static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
