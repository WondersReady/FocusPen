package com.focuspen.enhance.v2;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.UserManager;
import java.util.LinkedHashMap;
import java.util.Map;

/** Only the input settings needed before credential-protected storage unlocks. */
final class BootInputConfig {
    private static final String PREFS = "focuspen_boot_input";
    private static final String REVISION = "sourceRevision";
    private static final String[] KEYS = {
            ConfigSpec.KEYS, ConfigSpec.SCREEN_OFF, ConfigSpec.SCREEN_OFF_TARGET,
            ConfigSpec.RING, ConfigSpec.TIMING,
            ConfigSpec.LASER_ACTIVATION
    };

    static boolean unlocked(Context context) {
        UserManager manager = context.getSystemService(UserManager.class);
        return manager != null && manager.isUserUnlocked();
    }

    static Bundle read(Context context) {
        SharedPreferences preferences = prefs(context);
        Map<String, ?> stored = preferences.getAll();
        Bundle result = new Bundle();
        for (Map.Entry<String, Object> entry : ConfigSpec.DEFAULTS.entrySet())
            ConfigStore.put(result, entry.getKey(), entry.getValue());
        // On a first boot without a mirror, keys still belong to the controller.
        // Screen-off actions require a setting that was actually saved after unlock.
        result.putBoolean(ConfigSpec.SCREEN_OFF, false);
        result.putBoolean(ConfigSpec.PREVIEW, false);
        Object revision = stored.get(REVISION);
        boolean mirrorPresent = revision instanceof Long && (Long) revision >= 0;
        for (String key : KEYS) {
            Object value = stored.get(key);
            if (ConfigSpec.TIMING.equals(key) && value == null
                    && stored.get(ConfigSpec.LONG_PRESS) instanceof Integer
                    && stored.get(ConfigSpec.DOUBLE_PRESS) instanceof Integer
                    && (Integer) stored.get(ConfigSpec.LONG_PRESS) >= 150
                    && (Integer) stored.get(ConfigSpec.LONG_PRESS) <= 800
                    && (Integer) stored.get(ConfigSpec.DOUBLE_PRESS) >= 180
                    && (Integer) stored.get(ConfigSpec.DOUBLE_PRESS) <= 450)
                value = ConfigSpec.timingFromLegacy(stored.get(ConfigSpec.LONG_PRESS));
            if (value == null) { mirrorPresent = false; continue; }
            try {
                ConfigSpec.validate(key, value);
                ConfigStore.put(result, key, value);
            } catch (IllegalArgumentException ignored) { mirrorPresent = false; }
        }
        if (!mirrorPresent) result.putBoolean(ConfigSpec.SCREEN_OFF, false);
        long sourceRevision = mirrorPresent ? (Long) revision : 0;
        result.putInt("schemaVersion", ConfigSpec.SCHEMA);
        result.putLong("revision", Long.MAX_VALUE - Math.max(0, sourceRevision));
        result.putString("ver", BuildConfig.VERSION_NAME);
        result.putBoolean("bootOnly", true);
        result.putBoolean("mirrorPresent", mirrorPresent);
        return result;
    }

    static boolean mirror(Context context, Bundle source) {
        SharedPreferences preferences = prefs(context);
        Map<String, ?> current = preferences.getAll();
        Map<String, Object> desired = new LinkedHashMap<>();
        boolean changed = !current.containsKey(REVISION)
                || current.containsKey(ConfigSpec.LONG_PRESS) || current.containsKey(ConfigSpec.DOUBLE_PRESS);
        for (String key : KEYS) {
            Object value = source.get(key);
            ConfigSpec.validate(key, value);
            desired.put(key, value);
            if (!value.equals(current.get(key))) changed = true;
        }
        if (!changed) return true;
        SharedPreferences.Editor editor = preferences.edit();
        for (Map.Entry<String, Object> entry : desired.entrySet()) put(editor, entry.getKey(), entry.getValue());
        return editor.remove(ConfigSpec.LONG_PRESS).remove(ConfigSpec.DOUBLE_PRESS)
                .putLong(REVISION, source.getLong("revision", 0)).commit();
    }

    private static SharedPreferences prefs(Context context) {
        if (!BuildConfig.APPLICATION_ID.equals(context.getPackageName()))
            throw new SecurityException("Module writer required");
        return context.createDeviceProtectedStorageContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static void put(SharedPreferences.Editor editor, String key, Object value) {
        if (value instanceof Boolean) editor.putBoolean(key, (Boolean) value);
        else if (value instanceof Integer) editor.putInt(key, (Integer) value);
        else editor.putString(key, (String) value);
    }

    private BootInputConfig() { }
}
