package com.focuspen.enhance.v2;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Process;
import android.util.Log;
import java.util.Map;

/** The module process is the sole writer; hosts never open its preferences. */
final class ConfigStore {
    private static SharedPreferences prefs(Context c) {
        if (!BuildConfig.APPLICATION_ID.equals(c.getPackageName())) throw new SecurityException("Module writer required");
        return c.getSharedPreferences(ConfigProvider.PREFS, Context.MODE_PRIVATE);
    }
    static synchronized Bundle read(Context c) {
        SharedPreferences p = prefs(c);
        int schema = p.getInt("schemaVersion", 1);
        if (schema < 1 || schema > ConfigSpec.SCHEMA) throw new IllegalStateException("Unsupported schema");
        if (schema < ConfigSpec.SCHEMA) {
            SharedPreferences.Editor e = p.edit();
            for (String key : ConfigSpec.DEFAULTS.keySet())
                if (!p.contains(key)) put(e, key, ConfigSpec.DEFAULTS.get(key));
            // Before schema 12 these were fixed directions, never user assignments.
            if (schema < 12) e.putString("button1Short", "none").putString("button2Short", "none");
            e.putString("button3Short", "native").putString("button3Long", "native");
            // Replace only the previous seeded/preview list, preserving custom menus.
            RingContent content=RingContent.parse(p.getString(ConfigSpec.CONTENT,RingContent.DEFAULT));
            java.util.List<String> ids=new java.util.ArrayList<>();
            for(RingContent.Item item:content.items) if(item.enabled()) ids.add(item.id());
            if(schema < 10 && content.excluded.isEmpty() && ids.size()==content.items.size()
                    && (ids.equals(java.util.List.of("com.miui.notes","com.miui.creation"))
                    || ids.equals(java.util.List.of("com.miui.notes","com.miui.creation",RingContent.NOTES,RingContent.CREATION,RingContent.SCREENSHOT))))
                e.putString(ConfigSpec.CONTENT,RingContent.DEFAULT);
            e.putString(ConfigSpec.PROFILES,AppProfiles.parse(p.getString(ConfigSpec.PROFILES,AppProfiles.EMPTY)).encode());
            if (!e.putInt("schemaVersion", ConfigSpec.SCHEMA).putLong("revision", p.getLong("revision", 0)).commit())
                throw new IllegalStateException("Cannot migrate configuration");
        }
        Bundle b = new Bundle();
        Map<String, ?> all = p.getAll();
        for (String key : ConfigSpec.DEFAULTS.keySet()) {
            Object v = all.get(key); ConfigSpec.validate(key, v); put(b, key, v);
        }
        b.putInt("schemaVersion", ConfigSpec.SCHEMA); b.putLong("revision", p.getLong("revision", 0));
        b.putString("ver", BuildConfig.VERSION_NAME);
        return b;
    }
    static synchronized boolean write(Context c, String key, Object v) {
        ConfigSpec.validate(key, v);
        Bundle old = read(c);
        if (v.equals(old.get(key))) return true;
        SharedPreferences.Editor e = prefs(c).edit(); put(e, key, v);
        long revision = old.getLong("revision") + 1;
        boolean ok = e.putLong("revision", revision).commit();
        if (ok) {
            c.getContentResolver().notifyChange(ConfigProvider.URI, null);
            Log.i("FocusPenConfig", "committed revision=" + revision + " schema=" + ConfigSpec.SCHEMA + " writerUid=" + Process.myUid());
        }
        return ok;
    }
    /** Validate the entire backup before touching storage; readers see one revision. */
    static synchronized boolean restore(Context c, Map<String, Object> values) {
        if (!values.keySet().equals(ConfigSpec.DEFAULTS.keySet()))
            throw new IllegalArgumentException("Incomplete configuration");
        for (String key : ConfigSpec.DEFAULTS.keySet()) ConfigSpec.validate(key, values.get(key));
        Bundle old = read(c);
        SharedPreferences.Editor editor = prefs(c).edit();
        for (String key : ConfigSpec.DEFAULTS.keySet()) put(editor, key, values.get(key));
        boolean ok = editor.putLong("revision", old.getLong("revision") + 1).commit();
        if (ok) c.getContentResolver().notifyChange(ConfigProvider.URI, null);
        return ok;
    }
    static void put(Bundle b, String k, Object v) {
        if (v instanceof Boolean) b.putBoolean(k, (Boolean) v);
        else if (v instanceof Integer) b.putInt(k, (Integer) v);
        else b.putString(k, (String) v);
    }
    private static void put(SharedPreferences.Editor e, String k, Object v) {
        if (v instanceof Boolean) e.putBoolean(k, (Boolean) v);
        else if (v instanceof Integer) e.putInt(k, (Integer) v);
        else e.putString(k, (String) v);
    }
    private ConfigStore() { }
}
