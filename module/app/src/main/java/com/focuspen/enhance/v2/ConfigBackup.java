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
        Object schema = root.opt("schemaVersion");
        boolean legacy = Integer.valueOf(14).equals(schema);
        if (!legacy && !Integer.valueOf(ConfigSpec.SCHEMA).equals(schema))
            throw new IllegalArgumentException("备份的配置版本不兼容，当前支持版本 " + ConfigSpec.SCHEMA);
        JSONObject settings = root.getJSONObject("settings");
        if (settings.length() != ConfigSpec.DEFAULTS.size() + (legacy ? 1 : 0)
                || (legacy && settings.has(ConfigSpec.TIMING)))
            throw new IllegalArgumentException("备份包含缺失或未知的设置");
        Map<String, Object> result = new LinkedHashMap<>();
        for (String key : ConfigSpec.DEFAULTS.keySet()) {
            if (legacy && ConfigSpec.TIMING.equals(key)) continue;
            Object value = settings.get(key);
            ConfigSpec.validate(key, value);
            result.put(key, value);
        }
        if (legacy) {
            Object longPress = settings.get(ConfigSpec.LONG_PRESS);
            Object doublePress = settings.get(ConfigSpec.DOUBLE_PRESS);
            if (!(longPress instanceof Integer) || (Integer) longPress < 150 || (Integer) longPress > 800
                    || !(doublePress instanceof Integer) || (Integer) doublePress < 180 || (Integer) doublePress > 450)
                throw new IllegalArgumentException("旧版手势时序无效");
            int timing = ConfigSpec.timingFromLegacy(longPress);
            ConfigSpec.validate(ConfigSpec.TIMING, timing);
            result.put(ConfigSpec.TIMING, timing);
        }
        return result;
    }
    private ConfigBackup() { }
}
