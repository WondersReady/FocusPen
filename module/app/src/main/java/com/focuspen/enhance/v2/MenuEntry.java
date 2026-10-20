package com.focuspen.enhance.v2;

import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import java.util.ArrayList;
import java.util.List;

final class MenuEntry {
    final String id, label;
    final Drawable icon;
    MenuEntry(String id, String label, Drawable icon) { this.id = id; this.label = label; this.icon = icon; }
    static String label(Context c, String id) {
        if (RingContent.NOTES.equals(id)) return "灵感速记：笔记";
        if (RingContent.CREATION.equals(id)) return "灵感速记：小米创作";
        if (RingContent.SCREENSHOT.equals(id)) return "区域截屏";
        if (RingContent.RECOGNITION.equals(id)) return "小爱识屏";
        try { return c.getPackageManager().getApplicationLabel(c.getPackageManager().getApplicationInfo(id, 0)).toString(); }
        catch (Exception e) { return id; }
    }
    static Intent noteIntent(String id) {
        boolean creation = RingContent.CREATION.equals(id);
        // Current Notes resolves INSERT_OR_EDIT to its official text-note bridge.
        // The older pad action still resolves, but the AI list ignores it.
        return new Intent(creation ? "com.miui.creation.action.INSERT_CREATION" : "android.intent.action.INSERT_OR_EDIT")
                .setPackage(creation ? "com.miui.creation" : "com.miui.notes")
                .setType(creation ? "vnd.android.cursor.item/create_creation" : "vnd.android.cursor.item/text_note");
    }
    static boolean available(Context c, String id) {
        if (RingContent.SCREENSHOT.equals(id)) return true;
        if (RingContent.RECOGNITION.equals(id)) return c.getPackageManager().resolveService(recognitionIntent(), 0) != null;
        if (RingContent.isAction(id)) return c.getPackageManager().resolveActivity(noteIntent(id), 0) != null;
        return c.getPackageManager().getLaunchIntentForPackage(id) != null;
    }
    static List<MenuEntry> resolve(Context c, String content) {
        List<MenuEntry> result = new ArrayList<>();
        for (RingContent.Item item : RingContent.parse(content).items) {
            if (!item.enabled() || !available(c, item.id())) continue;
            String pkg = RingContent.NOTES.equals(item.id()) ? "com.miui.notes" : RingContent.CREATION.equals(item.id()) ? "com.miui.creation" : RingContent.RECOGNITION.equals(item.id()) ? "com.miui.voiceassist" : item.id();
            Drawable icon = null;
            try { if (!RingContent.SCREENSHOT.equals(item.id())) icon = c.getPackageManager().getApplicationIcon(pkg); } catch (Exception ignored) { }
            result.add(new MenuEntry(item.id(), label(c, item.id()), icon));
        }
        return result;
    }
    static List<MenuEntry> locked(Context c) {
        List<MenuEntry> result = new ArrayList<>();
        for (String id : new String[]{RingContent.NOTES, RingContent.CREATION, RingContent.SCREENSHOT}) {
            Drawable icon = null;
            try {
                if (!RingContent.SCREENSHOT.equals(id)) icon = c.getPackageManager().getApplicationIcon(
                        RingContent.NOTES.equals(id) ? "com.miui.notes" : "com.miui.creation");
            } catch (Exception ignored) { }
            // Keep all three slots even if an application is temporarily unavailable.
            result.add(new MenuEntry(id, label(c, id), icon));
        }
        return result;
    }
    static Intent recognitionIntent() {
        return new Intent(Intent.ACTION_ASSIST).setClassName("com.miui.voiceassist", "com.xiaomi.voiceassistant.VoiceService")
                .putExtra("voice_assist_start_from_key", "long_press_home_key")
                .putExtra("voice_assist_function_key", "start_screen_recognition");
    }
}
