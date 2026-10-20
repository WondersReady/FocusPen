package com.focuspen.enhance.v2;

import android.app.ActivityOptions;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.PowerManager;
import de.robv.android.xposed.XposedHelpers;

/** Runs inside system_server using the verified ROM's action helper and window options. */
final class OfficialPenActions {
    private static Class<?> helper;
    static void initialize(ClassLoader hostLoader) throws ClassNotFoundException {
        helper=hostLoader.loadClass("com.miui.server.input.util.ShortCutActionsUtils");
    }
    static boolean run(Context c,String id) {
        if(!c.getSystemService(PowerManager.class).isInteractive()) return false;
        boolean locked = c.getSystemService(KeyguardManager.class).isKeyguardLocked();
        if (locked && !lockAction(id)) return false;
        try {
            Object utils=XposedHelpers.callStaticMethod(helper,"getInstance",c);
            if(RingContent.SCREENSHOT.equals(id)) return Boolean.TRUE.equals(XposedHelpers.callMethod(utils,"triggerFunction","stylus_partial_screenshot","long_press_page_up_key",null,false));
            if (RingContent.RECOGNITION.equals(id))
                return Boolean.TRUE.equals(XposedHelpers.callMethod(utils,"launchXiaoaiScreenIdentification","focuspen_ring"));
            if (RingContent.NOTES.equals(id) || RingContent.CREATION.equals(id)) return quickNote(c, utils, id, locked ? "keyguard" : null);
            return false;
        } catch(Throwable failure) { android.util.Log.w("FocusPenActions","Official action unavailable: "+id,failure); return false; }
    }
    static boolean lockAction(String id) {
        return RingContent.NOTES.equals(id) || RingContent.CREATION.equals(id) || RingContent.SCREENSHOT.equals(id);
    }
    static boolean screenOff(Context c, String target) {
        if (c.getSystemService(PowerManager.class).isInteractive()) return false;
        try {
            Object utils = XposedHelpers.callStaticMethod(helper, "getInstance", c);
            return quickNote(c, utils, "creation".equals(target) ? RingContent.CREATION : RingContent.NOTES, "off_screen");
        } catch (Throwable failure) {
            android.util.Log.w("FocusPenActions", "Screen-off note unavailable", failure); return false;
        }
    }
    private static boolean quickNote(Context context, Object utils, String id, String forcedScene) {
        // Match the stylus quick-note route's scene, flags and window options, while
        // keeping the requested target. launchMiNotes promotes Notes to Creation
        // when given scene extras; launchMiCreation uses a different fullscreen route.
        Intent intent = MenuEntry.noteIntent(id);
        String scene = forcedScene != null ? forcedScene : (String) XposedHelpers.callMethod(utils, "getCurrentScene");
        boolean overLock = "keyguard".equals(scene) || "off_screen".equals(scene);
        if (!overLock && !"app".equals(scene) && !"home".equals(scene)) return false;
        intent.putExtra("shortcut", "stylus").putExtra("scene", scene)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        if (overLock) {
            intent.putExtra("StartActivityWhenLocked", true);
            if (RingContent.NOTES.equals(id)) {
                // The tablet INSERT_OR_EDIT bridge opens the AI list, which cannot
                // complete a locked launch. The exported editor handles keyguard itself.
                intent.setClassName("com.miui.notes", "com.miui.notes.notesui.feature.note.presentation.editor.webview.NoteEditorActivity")
                        .putExtra("miui.intent.extra.START_ACTIVITY_WHEN_LOCKED", true)
                        .putExtra("from_keyguard_shortcut", true)
                        .putExtra("extra_from_new_note", true);
            }
        }
        if (intent.resolveActivity(context.getPackageManager()) == null) return false;
        Bundle options = null;
        if ("app".equals(scene)) {
            ActivityOptions nativeOptions = (ActivityOptions) XposedHelpers.callMethod(utils,
                    "getActivityOptions", intent.getPackage());
            if (nativeOptions != null) options = nativeOptions.toBundle();
        }
        // The helper retains the ROM's current-user launch behavior and error handling.
        boolean launched=Boolean.TRUE.equals(XposedHelpers.callMethod(utils,"launchAppSimple",intent,options));
        // This Notes editor does not setTurnScreenOn like Creation. Wake once after
        // a successful screen-off launch; never acquire a keep-awake lock.
        if(launched&&"off_screen".equals(scene)&&RingContent.NOTES.equals(id))
            XposedHelpers.callMethod(context.getSystemService(PowerManager.class),"wakeUp",android.os.SystemClock.uptimeMillis(),2,"focuspen:off_screen_note");
        return launched;
    }
    static String id(PenAction a) { return a==PenAction.NOTE?RingContent.NOTES:a==PenAction.CREATION?RingContent.CREATION:a==PenAction.SCREENSHOT?RingContent.SCREENSHOT:null; }
}
