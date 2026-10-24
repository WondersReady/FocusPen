package com.focuspen.enhance.v2;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Persisted schema with explicit defaults and a closed set of accepted writes. */
final class ConfigSpec {
    // Suspended after device listening feedback. Keep stored fields for backup compatibility.
    static final boolean WRITING_AUDIO_AVAILABLE = false;
    static final int SCHEMA = 15;
    static final String SCREEN_OFF_TARGET = "screenOffTarget";
    static final String LASER_ACTIVATION = "laserActivation";
    static final String[] PRO_GESTURES = {"proPinch", "proDouble", "proSlideUp", "proSlideDown"};
    static final String PROFILES = "applicationProfiles", SCREEN_OFF = "screenOffNote";
    static final String CONTENT = "ringContent";
    static final String ENABLED = "enabled", RING = "ringEnabled", PREVIEW = "previewEnabled", AUDIO = "writingAudioEnabled", AUDIO_VOLUME = "writingAudioVolume";
    static final String KEYS = "keysEnabled", RULES = "rulesEnabled";
    static final String TIMING = "gestureTimingMs";
    static final String LONG_PRESS = "longPressMs", DOUBLE_PRESS = "doublePressMs"; // Schema 14 migration only.
    static final String MATERIAL = "ringMaterial", TEXTURE = "texture", GLASS = "glass";
    static final Map<String, Object> DEFAULTS;
    static {
        Map<String, Object> v = new LinkedHashMap<>();
        // Kept as a migration field. The actual module switch is LSPosed's
        // static-scope enable/disable state, not a hidden in-app preference.
        v.put(ENABLED, true);
        v.put(RING, true); v.put(PREVIEW, true); v.put(AUDIO, false); v.put(AUDIO_VOLUME, 65); v.put(KEYS, true); v.put(RULES, true);
        v.put(TIMING, 250);
        v.put(MATERIAL, TEXTURE);
        v.put(CONTENT, RingContent.DEFAULT);
        v.put(PROFILES, AppProfiles.EMPTY); v.put(SCREEN_OFF, false);
        v.put(SCREEN_OFF_TARGET, "note"); v.put(LASER_ACTIVATION, "hold");
        for(int i=0;i<ProBindings.KEYS.length;i++)v.put(ProBindings.KEYS[i],ProBindings.DEFAULTS[i]);
        v.put(ProBindings.INTERVAL,360);v.put(ProBindings.HOLD,650);
        v.put("button1Double", "none"); v.put("button2Double", "none");
        v.put(buttonKey(1, false), "none"); v.put(buttonKey(1, true), "ring");
        v.put(buttonKey(2, false), "none"); v.put(buttonKey(2, true), "recognition");
        v.put(PRO_GESTURES[0], "ring"); v.put(PRO_GESTURES[1], "none");
        v.put(PRO_GESTURES[2], "up"); v.put(PRO_GESTURES[3], "down");
        v.put(buttonKey(3, false), "native"); v.put(buttonKey(3, true), "native");
        DEFAULTS = Collections.unmodifiableMap(v);
    }
    static void validate(String key, Object value) {
        Object expected = DEFAULTS.get(key);
        if (expected == null || value == null || !expected.getClass().equals(value.getClass()))
            throw new IllegalArgumentException("Unknown setting or incorrect value type");
        if (TIMING.equals(key) && ((Integer) value < 100 || (Integer) value > 400))
            throw new IllegalArgumentException("Gesture timing must be between 100 and 400 ms");
        if (AUDIO_VOLUME.equals(key) && ((Integer)value < 0 || (Integer)value > 100)) throw new IllegalArgumentException("Audio volume must be between 0 and 100");
        if (PROFILES.equals(key)) AppProfiles.parse((String)value);
        if (MATERIAL.equals(key) && !TEXTURE.equals(value) && !GLASS.equals(value))
            throw new IllegalArgumentException("Unknown ring material");
        if (key.startsWith("button") || java.util.Arrays.asList(PRO_GESTURES).contains(key)) {
            PenAction.parse((String) value);
            if (key.startsWith("button3") && !value.equals("native")) throw new IllegalArgumentException("Fixed pen input");
        }
        if (CONTENT.equals(key)) RingContent.parse((String) value);
        if (SCREEN_OFF_TARGET.equals(key) && !"note".equals(value) && !"creation".equals(value))
            throw new IllegalArgumentException("Unknown screen-off target");
        if (LASER_ACTIVATION.equals(key) && !"hold".equals(value) && !"toggle".equals(value))
            throw new IllegalArgumentException("Unknown laser activation");
        int pro=java.util.Arrays.asList(ProBindings.KEYS).indexOf(key);if(pro>=0)ProBindings.validate(pro,(String)value);
        if(ProBindings.INTERVAL.equals(key)&&((Integer)value<220||(Integer)value>600))throw new IllegalArgumentException("Invalid pinch interval");
        if(ProBindings.HOLD.equals(key)&&((Integer)value<300||(Integer)value>1500))throw new IllegalArgumentException("Invalid pinch hold");
    }
    static String buttonKey(int button, boolean longPress) {
        if (button < 1 || button > 3) throw new IllegalArgumentException("Unknown button");
        return "button" + button + (longPress ? "Long" : "Short");
    }
    static int timingFromLegacy(Object longPress) {
        if (!(longPress instanceof Integer)) return 250;
        int ms = (Integer) longPress;
        if (ms < 150 || ms > 800) return 250;
        if (ms < 200) return 150;
        return ms <= 300 ? 250 : 350;
    }
    private ConfigSpec() { }
}
