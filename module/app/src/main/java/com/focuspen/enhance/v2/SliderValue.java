package com.focuspen.enhance.v2;

final class SliderValue {
    static int snapTiming(int value) {
        value = Math.max(100, Math.min(400, value));
        for (int anchor : new int[]{200, 300}) if (Math.abs(value - anchor) <= 6) return anchor;
        return value;
    }
    private SliderValue() { }
}
