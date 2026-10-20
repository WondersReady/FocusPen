package com.focuspen.enhance.v2;

import android.content.Context;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;
import java.util.concurrent.CopyOnWriteArrayList;

/** A cached snapshot per host. IPC stays outside input callbacks and the UI thread. */
final class ConfigClient {
    interface Listener { void changed(ConfigSnapshot snapshot); }
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper()), worker;
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private volatile ConfigSnapshot snapshot = ConfigSnapshot.unavailable();
    private boolean lastFailure;
    private final Runnable refreshTask = this::read;
    ConfigClient(Context c) {
        context = c;
        HandlerThread t = new HandlerThread("FocusPenConfig"); t.start(); worker = new Handler(t.getLooper());
        try {
            c.getContentResolver().registerContentObserver(ConfigProvider.URI, true, new ContentObserver(worker) {
                @Override public void onChange(boolean selfChange) { refresh(); }
            });
        } catch (Throwable e) { Log.w("FocusPenConfig", "Observer unavailable", e); }
        refresh();
    }
    ConfigSnapshot get() { return snapshot; }
    void add(Listener l) { listeners.addIfAbsent(l); l.changed(snapshot); }
    void remove(Listener l) { listeners.remove(l); }
    void refresh() { worker.removeCallbacks(refreshTask); worker.post(refreshTask); }
    private void read() {
        ConfigSnapshot next;
        try {
            next = ConfigSnapshot.parse(context.getContentResolver().call(ConfigProvider.URI, ConfigProvider.METHOD_GET_CONFIG, null, null));
            lastFailure = false;
        } catch (Throwable e) {
            next = ConfigSnapshot.unavailable();
            if (!lastFailure) Log.w("FocusPenConfig", "Configuration unavailable; native behavior retained", e);
            lastFailure = true; worker.postDelayed(refreshTask, 5000);
        }
        ConfigSnapshot previous = snapshot; snapshot = next;
        if (previous.revision != next.revision || previous.available != next.available) {
            Log.i("FocusPenConfig", "host=" + context.getPackageName() + " revision=" + next.revision
                    + " schema=" + ConfigSpec.SCHEMA + " enabled=" + next.enabled());
            final ConfigSnapshot update = next;
            main.post(() -> {
                for (Listener l : listeners) try { l.changed(update); } catch (Throwable e) { Log.w("FocusPenConfig", "Listener failed", e); }
            });
        }
    }
}
