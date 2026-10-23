package com.focuspen.enhance.v2;

import android.content.Context;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;
import java.util.concurrent.CopyOnWriteArrayList;

/** A cached snapshot per host. IPC stays outside input callbacks and the UI thread. */
final class ConfigClient implements AutoCloseable {
    interface Listener { void changed(ConfigSnapshot snapshot); }
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper()), worker;
    private final HandlerThread thread;
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private volatile ConfigSnapshot snapshot = ConfigSnapshot.unavailable();
    private boolean lastFailure;
    private ContentObserver observer;
    private BroadcastReceiver unlockReceiver;
    private volatile boolean closed;
    private final Runnable refreshTask = this::read;
    ConfigClient(Context c) {
        context = c;
        thread = new HandlerThread("FocusPenConfig"); thread.start(); worker = new Handler(thread.getLooper());
        try {
            ContentObserver candidate = new ContentObserver(worker) {
                @Override public void onChange(boolean selfChange) { refresh(); }
            };
            c.getContentResolver().registerContentObserver(ConfigProvider.URI, true, candidate);
            observer = candidate;
        } catch (Throwable e) { Log.w("FocusPenConfig", "Observer unavailable", e); }
        try {
            BroadcastReceiver candidate = new BroadcastReceiver() {
                @Override public void onReceive(Context context, Intent intent) { refresh(); }
            };
            c.registerReceiver(candidate, new IntentFilter(Intent.ACTION_USER_UNLOCKED));
            unlockReceiver = candidate;
            refresh();
        } catch (RuntimeException | Error failure) {
            close();
            throw failure;
        }
    }
    ConfigSnapshot get() { return snapshot; }
    void add(Listener l) { listeners.addIfAbsent(l); l.changed(snapshot); }
    void remove(Listener l) { listeners.remove(l); }
    void refresh() { if (closed) return; worker.removeCallbacks(refreshTask); worker.post(refreshTask); }
    private void read() {
        if (closed) return;
        ConfigSnapshot next;
        try {
            next = ConfigSnapshot.parse(context.getContentResolver().call(ConfigProvider.URI, ConfigProvider.METHOD_GET_CONFIG, null, null));
            lastFailure = false;
        } catch (Throwable e) {
            next = ConfigSnapshot.unavailable();
            if (!lastFailure) Log.w("FocusPenConfig", "Configuration unavailable; input controller remains reserved", e);
            lastFailure = true; if (!closed) worker.postDelayed(refreshTask, 5000);
        }
        if (closed) return;
        ConfigSnapshot previous = snapshot; snapshot = next;
        if (previous.revision != next.revision || previous.available != next.available) {
            Log.i("FocusPenConfig", "host=" + context.getPackageName() + " revision=" + next.revision
                    + " schema=" + ConfigSpec.SCHEMA + " enabled=" + next.enabled());
            final ConfigSnapshot update = next;
            main.post(() -> {
                if (closed) return;
                for (Listener l : listeners) try { l.changed(update); } catch (Throwable e) { Log.w("FocusPenConfig", "Listener failed", e); }
            });
        }
    }
    @Override public void close() {
        if (closed) return;
        closed = true;
        worker.removeCallbacks(refreshTask);
        if (unlockReceiver != null) try { context.unregisterReceiver(unlockReceiver); } catch (Throwable ignored) { }
        if (observer != null) try { context.getContentResolver().unregisterContentObserver(observer); } catch (Throwable ignored) { }
        listeners.clear();
        thread.quitSafely();
    }
}
