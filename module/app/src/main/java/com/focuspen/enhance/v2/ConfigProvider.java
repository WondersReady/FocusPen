package com.focuspen.enhance.v2;

import android.app.PendingIntent;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Process;
import android.os.SystemClock;

/** Read-only cross-UID snapshots; immutable edit commands run in the module process. */
public final class ConfigProvider extends ContentProvider {
    public static final String AUTHORITY = "com.focuspen.enhance.v2.config";
    public static final Uri URI = Uri.parse("content://" + AUTHORITY + "/snapshot");
    public static final Uri UI_URI = Uri.parse("content://" + AUTHORITY + "/ui-model");
    public static final String PREFS = "focuspen_config", METHOD_GET_CONFIG = "getConfig";
    public static final String KEY_ENABLED = ConfigSpec.ENABLED, METHOD_PREPARE_EDIT = "prepareEdit";
    @Override public boolean onCreate() { return true; }
    @Override public Bundle call(String method, String arg, Bundle extras) {
        String caller = getCallingPackage(); // Android verifies the package belongs to Binder's UID.
        int uid = Binder.getCallingUid();
        boolean own = uid == Process.myUid();
        long identity = Binder.clearCallingIdentity();
        try {
        if (!own && !isTrustedHost(caller, uid)) throw new SecurityException("Untrusted caller");
        if (METHOD_GET_CONFIG.equals(method)) return ConfigStore.read(getContext());
        if ("runtimeStatus".equals(method) && own) return RuntimeBus.status();
        if ("uiModel".equals(method)) {
            android.content.SharedPreferences ui=getContext().getSharedPreferences("focuspen_ui_preview",0);
            if(arg!=null) {
                if(!own || !java.util.List.of("auto","standard","pro").contains(arg)) throw new SecurityException("Invalid display preference");
                if(!ui.edit().putString("model",arg).commit())throw new IllegalStateException("Cannot save display preference");
                getContext().getContentResolver().notifyChange(UI_URI,null);
            }
            Bundle out=new Bundle();out.putString("model",ui.getString("model","auto"));return out;
        }
        if (RuntimeBus.REGISTER.equals(method)) return RuntimeBus.register(caller, extras);
        if ("previewRing".equals(method)) {
            if (!own && !"com.miui.securitycore".equals(caller)) throw new SecurityException("Settings host required");
            Bundle out = new Bundle(); out.putBoolean("sent", RuntimeBus.preview()); return out;
        }
        if (METHOD_PREPARE_EDIT.equals(method)) {
            if (!own && !"com.miui.securitycore".equals(caller)) throw new SecurityException("Settings host required");
            Object value = extras == null ? null : extras.get("value");
            ConfigSpec.validate(arg, value);
            Bundle payload = new Bundle(); ConfigStore.put(payload, "value", value);
            Intent command = new Intent(getContext(), ConfigCommandReceiver.class)
                    .setAction("com.focuspen.enhance.v2.COMMIT").setData(Uri.parse("focuspen://setting/" + arg))
                    .putExtras(payload).putExtra("key", arg).putExtra("expires", SystemClock.elapsedRealtime() + 10000);
            PendingIntent token = PendingIntent.getBroadcast(getContext(), 0, command,
                    PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_IMMUTABLE);
            Bundle out = new Bundle(); out.putParcelable("command", token); return out;
        }
        throw new SecurityException("Unsupported method"); // Includes the old setConfig write API.
        } finally { Binder.restoreCallingIdentity(identity); }
    }
    private boolean isTrustedHost(String pkg, int uid) {
        if (!"com.miui.securitycore".equals(pkg) && !"com.android.systemui".equals(pkg)
                && !"com.xiaomi.touchservice".equals(pkg) && !"android".equals(pkg)) return false;
        try {
            ApplicationInfo info = getContext().getPackageManager().getApplicationInfo(pkg, 0);
            return info.uid == uid && (info.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
        } catch (Exception e) { return false; }
    }
    @Override public Cursor query(Uri u, String[] p, String s, String[] a, String sort) { throw new SecurityException("Use snapshots"); }
    @Override public String getType(Uri u) { return "vnd.android.cursor.item/vnd.focuspen.snapshot"; }
    @Override public Uri insert(Uri u, ContentValues v) { throw new SecurityException("Read-only provider"); }
    @Override public int delete(Uri u, String s, String[] a) { throw new SecurityException("Read-only provider"); }
    @Override public int update(Uri u, ContentValues v, String s, String[] a) { throw new SecurityException("Read-only provider"); }
}
