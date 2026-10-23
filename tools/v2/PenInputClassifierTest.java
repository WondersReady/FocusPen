package com.focuspen.enhance.v2;

public final class PenInputClassifierTest {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    public static void main(String[] args) {
        check(PenInputClassifier.targetType(3) && PenInputClassifier.targetType(8), "both pen types");
        for (int type : new int[] {0, 1, 2, 4, 7, 9}) {
            check(!PenInputClassifier.targetType(type), "do not take other keyboards/pens");
            for (int code : new int[] {92, 93, 193, 194, 195, 196, 197, 332})
                check(PenInputClassifier.classify(type, code) == PenInputClassifier.Input.UNKNOWN, "wrong device captured");
        }
        check(PenInputClassifier.standardButton(3, 93) == 1, "note");
        check(PenInputClassifier.standardButton(3, 92) == 2, "screenshot");
        check(PenInputClassifier.standardButton(3, 193) == 3, "standard focus observed at IMS on 2026-10-23");
        check(PenInputClassifier.standardButton(3, 194) == 3, "standard focus overlap");
        check(PenInputClassifier.standardButton(8, 194) == 0, "Pro pinch is not standard focus");
        for (int slot = 0; slot < 4; slot++)
            check(PenInputClassifier.proSlot(8, 194 + slot) == slot, "all four Pro logical inputs");
        check(PenInputClassifier.proSlot(3, 194) == -1, "standard focus is not Pro pinch");
        check(PenInputClassifier.classify(8,196)==PenInputClassifier.Input.SLIDE_UP,"ROM gesture name is up, independent of application page mapping");
        check(PenInputClassifier.classify(8,197)==PenInputClassifier.Input.SLIDE_DOWN,"ROM gesture name is down");
        check(PenInputClassifier.classify(3, 195) == PenInputClassifier.Input.UNKNOWN, "Pro code on standard device");
        check(PenInputClassifier.classify(8, 93) == PenInputClassifier.Input.UNKNOWN, "standard code on Pro device");
        check(PenInputClassifier.classify(3, 332) == PenInputClassifier.Input.UNKNOWN,
                "raw F19 arrived as Java 193 in the capture; 332 remains unsupported");
        System.out.println("Pen input classification: ROM mappings and device isolation PASS (not hardware validation)");
    }
}
