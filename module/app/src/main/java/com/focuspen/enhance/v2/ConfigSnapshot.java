package com.focuspen.enhance.v2;

import android.os.Bundle;

/** Immutable configuration; input ownership is independent of storage availability. */
final class ConfigSnapshot {
    final boolean available;
    final long revision;
    private final Bundle values;
    private final AppProfiles profiles;
    private ConfigSnapshot(boolean available, long revision, Bundle values) {
        this.available = available; this.revision = revision; this.values = new Bundle(values);
        profiles=AppProfiles.parse(values.getString(ConfigSpec.PROFILES,AppProfiles.EMPTY));
    }
    static ConfigSnapshot unavailable() { return new ConfigSnapshot(false, -1, new Bundle()); }
    static ConfigSnapshot parse(Bundle b) {
        if (b == null || b.getInt("schemaVersion", -1) != ConfigSpec.SCHEMA || b.getLong("revision", -1) < 0)
            throw new IllegalArgumentException("Unsupported or missing configuration");
        for (String key : ConfigSpec.DEFAULTS.keySet()) ConfigSpec.validate(key, b.get(key));
        return new ConfigSnapshot(true, b.getLong("revision"), b);
    }
    /**
     * Module lifetime is controlled by LSPosed's module switch now that the
     * module no longer exposes its own settings entry. Keep the persisted
     * enabled bit only for schema compatibility with older installations; a
     * valid snapshot means the statically scoped module is active.
     */
    boolean enabled() { return available; }
    boolean bootOnly() { return values.getBoolean("bootOnly", false); }
    boolean inputActionsReady() { return available && (!bootOnly() || values.getBoolean("mirrorPresent", false)); }
    boolean flag(String key) { return available && values.getBoolean(key, false); }
    boolean audioEnabled() { return ConfigSpec.WRITING_AUDIO_AVAILABLE && flag(ConfigSpec.AUDIO); }
    int number(String key){return values.getInt(key,(Integer)ConfigSpec.DEFAULTS.get(key));}
    String proBinding(String pkg,int slot){String fallback=text(ProBindings.KEYS[slot]);return flag(ConfigSpec.RULES)?profiles.value(pkg,13+slot,fallback):fallback;}
    int audioVolume() { return values.getInt(ConfigSpec.AUDIO_VOLUME, 65); }
    int longPressMs() { return values.getInt(ConfigSpec.TIMING, 250); }
    String text(String key) { return values.getString(key, String.valueOf(ConfigSpec.DEFAULTS.get(key))); }
    PenAction buttonAction(int button, boolean longPress) { return PenAction.parse(text(ConfigSpec.buttonKey(button, longPress))); }
    int doublePressMs() { return longPressMs(); }
    PenAction shortAction(String pkg,int button) {
        if(button==3) return PenAction.NATIVE;
        String value=text("button"+button+"Short");
        return PenAction.parse(flag(ConfigSpec.RULES)?profiles.value(pkg,6+button,value):value);
    }
    PenAction proAction(String pkg,int gesture) {
        String value=text(ConfigSpec.PRO_GESTURES[gesture]);
        return PenAction.parse(flag(ConfigSpec.RULES)?profiles.value(pkg,9+gesture,value):value);
    }
    PenAction action(String pkg, int button, boolean twice) {
        if(button==3) return PenAction.NATIVE;
        String key="button"+button+(twice?"Double":"Long");
        String value=text(key);
        if(flag(ConfigSpec.RULES)) value=profiles.value(pkg,(button-1)*2+(twice?1:0),value);
        return PenAction.parse(value);
    }
    String direction(String pkg,int button) {
        return flag(ConfigSpec.RULES)?profiles.value(pkg,button==1?4:5,"native"):"native";
    }
    boolean preview(String pkg) {
        if (!flag(ConfigSpec.RULES)) return flag(ConfigSpec.PREVIEW);
        return "true".equals(profiles.value(pkg,AppProfiles.PREVIEW_FIELD,flag(ConfigSpec.PREVIEW)?"true":"false"));
    }
    boolean previewPossible() {
        return flag(ConfigSpec.PREVIEW) || (flag(ConfigSpec.RULES) && profiles.enablesPreview());
    }
    boolean hasGlobalNoteBinding() {
        for(int b=1;b<=2;b++) for(String suffix:new String[]{"Short","Long","Double"}) {
            String a=text("button"+b+suffix); if(a.equals("note")||a.equals("creation")) return true;
        }
        for(String key:ConfigSpec.PRO_GESTURES) {
            String a=text(key); if(a.equals("note")||a.equals("creation")) return true;
        }
        return false;
    }
}
