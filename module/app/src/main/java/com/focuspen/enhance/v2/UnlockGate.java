package com.focuspen.enhance.v2;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.os.UserManager;
import de.robv.android.xposed.XposedBridge;

/** Runs a host bootstrap once, using the exact context that owns the receiver. */
final class UnlockGate extends BroadcastReceiver {
    private final Context context;
    private final Runnable action;
    private Context receiverContext;
    private boolean registered, finished;

    private UnlockGate(Context context, Runnable action) {
        this.context = context; this.action = action;
    }

    static void run(Context context, Runnable action) {
        UnlockGate gate = new UnlockGate(context, action);
        if (Looper.myLooper() == Looper.getMainLooper()) gate.start();
        else new Handler(Looper.getMainLooper()).post(gate::start);
    }

    private void start() {
        try {
            if (unlocked()) { finish(); return; }
            // LoadedApk changes the attached ContextImpl's outer context AFTER Application.attach.
            // ReceiverDispatcher keys by that outer context, so retaining the base alone is insufficient.
            // This private ContextImpl keeps its own stable outer identity throughout boot.
            receiverContext = context.createDeviceProtectedStorageContext();
            receiverContext.registerReceiver(this, new IntentFilter(Intent.ACTION_USER_UNLOCKED),
                    null, new Handler(Looper.getMainLooper()), Context.RECEIVER_NOT_EXPORTED);
            registered = true;
            // Unlock can occur between the first check and receiver registration.
            if (unlocked()) finish();
        } catch (Throwable failure) { fail(failure); }
    }

    @Override public void onReceive(Context ignored, Intent intent) {
        try {
            if (!finished && Intent.ACTION_USER_UNLOCKED.equals(intent.getAction()) && unlocked()) finish();
        } catch (Throwable failure) { fail(failure); }
    }

    private boolean unlocked() {
        UserManager manager = context.getSystemService(UserManager.class);
        return manager != null && manager.isUserUnlocked();
    }

    private void unregister() {
        if (!registered) return;
        registered = false;
        try { receiverContext.unregisterReceiver(this); }
        catch (Throwable failure) {
            XposedBridge.log("FocusPen unlock receiver cleanup failed; callback remains guarded");
            XposedBridge.log(failure);
        }
    }

    private void finish() {
        if (finished) return;
        finished = true;
        unregister();
        try { action.run(); } catch (Throwable failure) { fail(failure); }
    }

    private void fail(Throwable failure) {
        finished = true; unregister();
        XposedBridge.log("FocusPen deferred initialization failed; native behavior retained");
        XposedBridge.log(failure);
    }
}
