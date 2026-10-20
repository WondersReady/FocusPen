package com.focuspen.enhance.v2;

import android.os.Bundle;
import org.json.JSONObject;
import java.util.LinkedHashMap;
import java.util.Map;

/** Portable, bounded backup. Runtime state and preview model are deliberately excluded. */
final class ConfigBackup {
    static final int MAX_BYTES = 512 * 1024;
    static String encode(Bundle snapshot) throws Exception {
        JSONObject settings = new JSONObject();
        for (String key : ConfigSpec.DEFAULTS.keySet()) settings.put(key, snapshot.get(key));
        return new JSONObject().put("format", "focuspen-settings").put("formatVersion", 1)
                .put("schemaVersion", ConfigSpec.SCHEMA).put("appVersion", BuildConfig.VERSION_NAME)
                .put("settings", settings).toString(2);
    }
    static Map<String, Object> decode(String source) throws Exception {
        if (source.length() > MAX_BYTES) throw new IllegalArgumentException("备份文件过大");
        JSONObject root = new JSONObject(source);
        if (!"focuspen-settings".equals(root.optString("format"))
                || !Integer.valueOf(1).equals(root.opt("formatVersion")))
            throw new IllegalArgumentException("这不是焦点笔增强的设置备份");
        if (!Integer.valueOf(ConfigSpec.SCHEMA).equals(root.opt("schemaVersion")))
            throw new IllegalArgumentException("备份的配置版本不兼容，当前支持版本 " + ConfigSpec.SCHEMA);
        JSONObject settings = root.getJSONObject("settings");
        if (settings.length() != ConfigSpec.DEFAULTS.size()) throw new IllegalArgumentException("备份包含缺失或未知的设置");
        Map<String, Object> result = new LinkedHashMap<>();
        for (String key : ConfigSpec.DEFAULTS.keySet()) {
            Object value = settings.get(key);
            ConfigSpec.validate(key, value);
            result.put(key, value);
        }
        return result;
    }
    private ConfigBackup() { }
}
