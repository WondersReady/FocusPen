package com.focuspen.enhance.v2;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

/** Keeps the existing motion host alive only while a visual pen feature needs it. */
final class PenMotionConnection implements ServiceConnection {
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean wanted, bound, unsupported;
    private final Runnable reconnect = this::connect;

    PenMotionConnection(Context context, ConfigClient config) {
        this.context = context.createDeviceProtectedStorageContext();
        config.add(s -> {
            boolean next = s.enabled() && (s.flag(ConfigSpec.RING) || s.flag(ConfigSpec.PREVIEW));
            if (next == wanted) return;
            wanted = next; unsupported = false;
            main.removeCallbacks(reconnect);
            if (wanted) connect(); else disconnect();
        });
    }
    private void connect() {
        if (!wanted || bound || unsupported) return;
        try {
            Intent service = new Intent().setClassName(RuntimeBus.MOTION,
                    "com.xiaomi.touchservice.pencilengine.PencilEngineManagerService");
            bound = context.bindService(service, this, Context.BIND_AUTO_CREATE);
            if (!bound) main.postDelayed(reconnect, 10000);
        } catch (Throwable failure) {
            unsupported = true;
            Log.w("FocusPenMotion", "Motion host binding unavailable", failure);
        }
    }
    private void disconnect() {
        if (!bound) return;
        bound = false;
        try { context.unbindService(this); }
        catch (Throwable failure) { Log.w("FocusPenMotion", "Motion host unbind failed", failure); }
    }
    @Override public void onServiceConnected(ComponentName name, IBinder service) {
        // Binding owns the process lifetime; no proprietary pencil-engine features are enabled.
        Log.i("FocusPenMotion", "Motion host bound");
    }
    @Override public void onServiceDisconnected(ComponentName name) { /* Android reconnects this binding. */ }
    @Override public void onBindingDied(ComponentName name) {
        disconnect(); if (wanted) main.postDelayed(reconnect, 10000);
    }
    @Override public void onNullBinding(ComponentName name) {
        unsupported = true; disconnect();
        Log.w("FocusPenMotion", "Official motion service does not support this device");
    }
}
