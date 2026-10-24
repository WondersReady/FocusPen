package com.focuspen.enhance.v2;

public final class WritingAudioModelTest {
    public static void main(String[] args) {
        pressureCalibrationAndSmoothing();
        distanceSchedulingAndGrainFields();
        stationaryJitterDeadZone();
        curvatureAndStrokeBoundary();
        boundedQueue();
        System.out.println("Writing audio model and queue: PASS");
    }

    private static void pressureCalibrationAndSmoothing() {
        WritingAudioModel model = new WritingAudioModel(-10f, 110f, 2f, 10f, 90f);
        WritingAudioModel.State state = new WritingAudioModel.State();
        model.process(WritingAudioModel.DOWN, 50f, 0f, 50f, 0L, 100);
        model.copyState(state);
        near(state.pressure, 0.5f, 0.0001f, "calibrated half pressure");
        near(state.pan, 0f, 0.0001f, "center pan");
        model.process(WritingAudioModel.MOVE, 50f, 0f, 50.5f, 1L, 100);
        model.copyState(state);
        near(state.pressure, 0.5f, 0.0001f, "resolution suppresses sub-step jitter");
        model.process(WritingAudioModel.MOVE, 50f, 0f, 110f, 2L, 100);
        model.copyState(state);
        check(state.pressure > 0.5f && state.pressure < 1f, "pressure rise is smoothed");
        float rising = state.pressure;
        model.process(WritingAudioModel.MOVE, 50f, 0f, 110f, 50L, 100);
        model.copyState(state);
        check(state.pressure > rising && state.pressure <= 1f, "pressure approaches calibrated ceiling");
        near(state.lightWeight + state.mediumWeight + state.heavyWeight, 1f, 0.0001f,
                "continuous material weights sum to one");
        model.process(WritingAudioModel.MOVE, 50f, 0f, 9.5f, 51L, 100);
        model.copyState(state);
        check(state.pressure > 0f, "one resolution step below contact threshold retains stroke");
        model.process(WritingAudioModel.UP, 50f, 0f, 0f, 51L, 100);
        model.copyState(state);
        near(state.pressure, 0f, 0f, "lift clears pressure");
        check(model.pendingGrains() == 0, "lift clears pending grains");
    }

    private static void distanceSchedulingAndGrainFields() {
        WritingAudioModel slow = new WritingAudioModel(0f, 1f, 0.001f, 0f, 1f);
        slow.process(WritingAudioModel.DOWN, 0f, 0f, 0.5f, 0L, 100);
        for (int i = 1; i <= 20; i++) {
            slow.process(WritingAudioModel.MOVE, 0f, 0f, 0.5f, i * 100L, 100);
        }
        check(slow.pendingGrains() == 0, "elapsed time without travel emits nothing");
        slow.process(WritingAudioModel.MOVE, 40f, 0f, 0.5f, 2100L, 100);
        int slowCount = slow.pendingGrains();
        check(slowCount >= 5, "one slow move emits multiple distance crossings");
        WritingAudioModel.Grain grain = new WritingAudioModel.Grain();
        check(slow.pollGrain(grain), "first grain exists");
        check(grain.attack && grain.x > 0f && grain.x < 40f,
                "first grain marks attack and interpolates within move");
        check(grain.pan < 0f && grain.direction > 0.99f, "grain pan and direction follow position");
        check(grain.eventTimeMs > 2000L && grain.eventTimeMs <= 2100L,
                "grain timestamp interpolates along event interval");
        check(grain.gain > 0f && grain.playbackRate > 0f && grain.brightness > 0f,
                "grain carries continuous timbre parameters");
        check(slow.pollGrain(grain) && !grain.attack, "attack occurs once per stroke");

        WritingAudioModel fast = new WritingAudioModel(0f, 1f, 0.001f, 0f, 1f);
        fast.process(WritingAudioModel.DOWN, 0f, 0f, 0.5f, 0L, 100);
        fast.process(WritingAudioModel.MOVE, 40f, 0f, 0.5f, 2L, 100);
        check(fast.pendingGrains() > 0 && fast.pendingGrains() <= slowCount,
                "fast travel expands pixel spacing while retaining sound");
        check(fast.pendingGrains() < 20, "finite travel bounds grain count");

        WritingAudioModel veryFast = new WritingAudioModel(0f, 1f, 0f, 0f, 1f);
        veryFast.process(WritingAudioModel.DOWN, 0f, 0f, 0.5f, 0L, 1000);
        veryFast.process(WritingAudioModel.MOVE, 160f, 0f, 0.5f, 5L, 1000);
        check(veryFast.pendingGrains() <= 2,
                "very fast travel cannot outpace real-time audio consumption");

        WritingAudioModel longJump = new WritingAudioModel(0f, 1f, 0f, 0f, 1f);
        longJump.process(WritingAudioModel.DOWN, 0f, 0f, 0.5f, 0L, 1000);
        longJump.process(WritingAudioModel.MOVE, 10000f, 0f, 0.5f, 1L, 1000);
        int cappedCount = longJump.pendingGrains();
        check(cappedCount <= 32, "large jump has bounded grain work");
        longJump.process(WritingAudioModel.MOVE, 10000f, 0f, 0.5f, 1000L, 1000);
        check(longJump.pendingGrains() == cappedCount,
                "large jump cannot make a later stationary callback emit");

        WritingAudioModel changingSpeed = new WritingAudioModel(0f, 1f, 0f, 0f, 1f);
        changingSpeed.process(WritingAudioModel.DOWN, 0f, 0f, 0.5f, 0L, 100);
        changingSpeed.process(WritingAudioModel.MOVE, 6f, 0f, 0.5f, 1000L, 100);
        changingSpeed.process(WritingAudioModel.MOVE, 16f, 0f, 0.5f, 1001L, 100);
        while (changingSpeed.pollGrain(grain)) {
            check(grain.x >= 0f && grain.x <= 16f,
                    "spacing change keeps grains on sampled stroke");
        }
    }

    private static void curvatureAndStrokeBoundary() {
        WritingAudioModel model = new WritingAudioModel(0f, 1f, 0f, 0f, 1f);
        WritingAudioModel.State state = new WritingAudioModel.State();
        model.process(WritingAudioModel.DOWN, 0f, 0f, 0.7f, 0L, 100);
        model.process(WritingAudioModel.MOVE, 10f, 0f, 0.7f, 10L, 100);
        model.copyState(state);
        near(state.curvature, 0f, 0f, "straight first segment has no turn");
        model.process(WritingAudioModel.MOVE, 10f, 10f, 0.7f, 20L, 100);
        model.copyState(state);
        check(state.curvature > 0f && Math.abs(state.direction) < 0.001f,
                "right-angle turn changes curvature and horizontal direction");
        check(state.accelerationPxPerMs2 >= -8f && state.accelerationPxPerMs2 <= 8f,
                "acceleration remains finite");
        model.process(WritingAudioModel.CANCEL, 10f, 10f, 0f, 21L, 100);
        check(model.pendingGrains() == 0, "cancel clears queued texture");
        model.process(WritingAudioModel.DOWN, 0f, 0f, 0.7f, 22L, 100);
        model.process(WritingAudioModel.MOVE, 8f, 0f, 0.7f, 30L, 100);
        WritingAudioModel.Grain grain = new WritingAudioModel.Grain();
        check(model.pollGrain(grain) && grain.attack, "new stroke has new attack");
    }

    private static void stationaryJitterDeadZone() {
        WritingAudioModel model = new WritingAudioModel(0f, 1f, 0f, 0f, 1f);
        model.process(WritingAudioModel.DOWN, 100f, 100f, 0.5f, 0L, 1000);
        for (int i = 1; i <= 120; i++) {
            float jitter = (i & 1) == 0 ? 0.05f : -0.05f;
            model.process(WritingAudioModel.MOVE, 100f + jitter, 100f, 0.5f, i * 10L, 1000);
        }
        check(model.pendingGrains() == 0, "stationary coordinate jitter stays silent");
        model.process(WritingAudioModel.MOVE, 108f, 100f, 0.5f, 1208L, 1000);
        WritingAudioModel.Grain grain = new WritingAudioModel.Grain();
        check(model.pollGrain(grain) && grain.attack,
                "real movement immediately leaves the speed dead zone");
    }

    private static void boundedQueue() {
        WritingAudioEventQueue queue = new WritingAudioEventQueue(3);
        WritingAudioEventQueue.Event event = new WritingAudioEventQueue.Event();
        check(queue.offer(WritingAudioModel.DOWN, 1f, 2f, 0.5f, 1L, 100), "down accepted");
        check(queue.offer(WritingAudioModel.MOVE, 2f, 2f, 0.5f, 2L, 100), "move accepted");
        check(queue.offer(WritingAudioModel.MOVE, 3f, 2f, 0.6f, 3L, 100),
                "consecutive pending move is coalesced");
        check(queue.size() == 2 && queue.droppedCount() == 1L,
                "coalescing bounds latency and counts replaced samples");
        check(queue.poll(event) && event.action == WritingAudioModel.DOWN, "consumer receives down");
        check(queue.poll(event) && event.action == WritingAudioModel.MOVE
                        && event.x == 3f && event.pressure == 0.6f && event.timeMs == 3L,
                "consumer receives the newest coalesced move");

        WritingAudioEventQueue overloaded = new WritingAudioEventQueue(2);
        check(overloaded.offer(WritingAudioModel.DOWN, 1f, 2f, 0.5f, 1L, 100),
                "overload down accepted");
        check(overloaded.offer(WritingAudioModel.MOVE, 2f, 2f, 0.5f, 2L, 100),
                "overload move accepted");
        check(overloaded.offer(WritingAudioModel.UP, 4f, 2f, 0f, 5L, 100),
                "up survives overload");
        check(overloaded.size() == 1 && overloaded.droppedCount() == 2L,
                "terminal event discards stale full stroke");
        check(overloaded.poll(event) && event.action == WritingAudioModel.UP && event.timeMs == 5L,
                "consumer receives terminal event");
        check(!overloaded.poll(event), "overload queue drains");
        check(queue.offer(WritingAudioModel.DOWN, 9f, 7f, 0.8f, 6L, 3200), "queue remains reusable");
        check(queue.poll(event) && event.x == 9f && event.y == 7f && event.displayWidth == 3200,
                "event fields survive round trip");
    }

    private static void near(float actual, float expected, float tolerance, String message) {
        check(Math.abs(actual - expected) <= tolerance, message + ": " + actual + " vs " + expected);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
