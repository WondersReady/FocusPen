package com.focuspen.enhance.v2;

/** Fixed-capacity input queue. Methods are synchronized for one producer and one consumer. */
final class WritingAudioEventQueue {
    static final class Event {
        int action, displayWidth;
        float x, y, pressure, tilt, orientation;
        long timeMs;
    }

    private final Event[] events;
    private int read, size;
    private long dropped;

    WritingAudioEventQueue(int capacity) {
        if (capacity < 2) throw new IllegalArgumentException("capacity must be at least two");
        events = new Event[capacity];
        for (int i = 0; i < capacity; i++) events[i] = new Event();
    }

    synchronized boolean offer(int action, float x, float y, float pressure,
            long timeMs, int displayWidth) {
        return offer(action, x, y, pressure, timeMs, displayWidth, 0f, 0f);
    }

    synchronized boolean offer(int action, float x, float y, float pressure,
            long timeMs, int displayWidth, float tilt, float orientation) {
        if (action == WritingAudioModel.MOVE && size > 0) {
            Event pending = events[(read + size - 1) % events.length];
            if (pending.action == WritingAudioModel.MOVE) {
                set(pending, action, x, y, pressure, timeMs, displayWidth, tilt, orientation);
                dropped++;
                return true;
            }
        }
        if (size == events.length) {
            if (action == WritingAudioModel.MOVE) {
                dropped++;
                return false;
            }
            dropped += size;
            read = 0;
            size = 0;
        }
        Event event = events[(read + size) % events.length];
        set(event, action, x, y, pressure, timeMs, displayWidth, tilt, orientation);
        size++;
        return true;
    }

    private static void set(Event event, int action, float x, float y, float pressure,
            long timeMs, int displayWidth, float tilt, float orientation) {
        event.action = action;
        event.x = x;
        event.y = y;
        event.pressure = pressure;
        event.tilt = tilt;
        event.orientation = orientation;
        event.timeMs = timeMs;
        event.displayWidth = displayWidth;
    }

    synchronized boolean poll(Event out) {
        if (size == 0) return false;
        Event event = events[read];
        out.action = event.action;
        out.x = event.x;
        out.y = event.y;
        out.pressure = event.pressure;
        out.tilt = event.tilt;
        out.orientation = event.orientation;
        out.timeMs = event.timeMs;
        out.displayWidth = event.displayWidth;
        read = (read + 1) % events.length;
        size--;
        return true;
    }

    synchronized int size() { return size; }
    synchronized long droppedCount() { return dropped; }

    synchronized void clear() {
        read = 0;
        size = 0;
    }
}
