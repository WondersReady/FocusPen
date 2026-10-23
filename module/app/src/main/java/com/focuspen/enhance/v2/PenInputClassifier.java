package com.focuspen.enhance.v2;

/** ROM-backed Java key mapping. Evdev scan codes are deliberately not aliases here. */
final class PenInputClassifier {
    enum Input {
        UNKNOWN, NOTE, SCREENSHOT, FOCUS, PINCH, BODY_DOUBLE_TAP, SLIDE_DOWN, SLIDE_UP
    }

    static boolean targetType(int type) { return type == 3 || type == 8; }

    static Input classify(int type, int keyCode) {
        if (type == 3) return switch (keyCode) {
            case 93 -> Input.NOTE;
            case 92 -> Input.SCREENSHOT;
            case 193, 194 -> Input.FOCUS;
            default -> Input.UNKNOWN;
        };
        if (type == 8) return switch (keyCode) {
            case 194 -> Input.PINCH;
            case 195 -> Input.BODY_DOUBLE_TAP;
            case 196 -> Input.SLIDE_UP;
            case 197 -> Input.SLIDE_DOWN;
            default -> Input.UNKNOWN;
        };
        return Input.UNKNOWN;
    }

    static int standardButton(int type, int keyCode) {
        return switch (classify(type, keyCode)) {
            case NOTE -> 1;
            case SCREENSHOT -> 2;
            case FOCUS -> 3;
            default -> 0;
        };
    }

    static int proSlot(int type, int keyCode) {
        return switch (classify(type, keyCode)) {
            case PINCH -> 0;
            case BODY_DOUBLE_TAP -> 1;
            case SLIDE_UP -> 2;
            case SLIDE_DOWN -> 3;
            default -> -1;
        };
    }

    private PenInputClassifier() { }
}
