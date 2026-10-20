package com.focuspen.enhance.v2;

import android.content.Context;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Process;
import android.util.Log;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/** Authenticated endpoint directory in the module; event traffic uses one-way Binder calls. */
final class RuntimeBus {
    static final String UI = "com.android.systemui", INPUT = "android", MOTION = "com.xiaomi.touchservice";
    static final String REGISTER = "registerRuntime", DESCRIPTOR = "com.focuspen.enhance.v2.Runtime.v1";
    private static final int EVENT = IBinder.FIRST_CALL_TRANSACTION;
    private static final Map<String, IBinder> directory = new ConcurrentHashMap<>();
    private static final Map<String, String> versions = new ConcurrentHashMap<>();
    static final String SETTINGS = "com.miui.securitycore";
    private volatile ConcurrentHashMap<String, IBinder> peers = new ConcurrentHashMap<>();
    private final Context context;
    final Handler worker;
    private final IBinder endpoint;
    private final Runnable heartbeat;

    static synchronized Bundle register(String host, Bundle request) {
        if (!UI.equals(host) && !INPUT.equals(host) && !MOTION.equals(host) && !SETTINGS.equals(host)) throw new SecurityException("Runtime host required");
        IBinder binder = request == null ? null : request.getBinder("endpoint");
        if (binder == null || !binder.isBinderAlive()) throw new IllegalArgumentException("Live endpoint required");
        directory.put(host, binder);
        versions.put(host, request.getString("version", ""));
        Bundle result = new Bundle();
        directory.forEach((name, value) -> { if (value.isBinderAlive()) result.putBinder(name, value); });
        return result;
    }
    static boolean preview() { return transmit(directory.get(UI), "preview", new Bundle()); }
    static synchronized Bundle status() {
        Bundle b = new Bundle();
        String[] hosts = {INPUT, UI, MOTION, SETTINGS}, names = {"input", "ui", "motion", "settings"};
        for (int i = 0; i < hosts.length; i++) {
            IBinder endpoint = directory.get(hosts[i]);
            boolean alive = endpoint != null && endpoint.isBinderAlive();
            b.putBoolean(names[i], alive);
            b.putString(names[i] + "Version", alive ? versions.getOrDefault(hosts[i], "") : "");
        }
        return b;
    }

    RuntimeBus(Context c, BiConsumer<String, Bundle> receiver, Runnable onHeartbeat) throws Exception {
        context = c;
        int uiUid = c.getPackageManager().getApplicationInfo(UI, 0).uid;
        int moduleUid = c.getPackageManager().getApplicationInfo(BuildConfig.APPLICATION_ID, 0).uid;
        HandlerThread thread = new HandlerThread("FocusPenRuntime"); thread.start(); worker = new Handler(thread.getLooper());
        endpoint = new Binder() {
            @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
                if (code != EVENT) return false;
                int uid = Binder.getCallingUid();
                if (uid != Process.SYSTEM_UID && uid != uiUid && uid != moduleUid) return false;
                try {
                    data.enforceInterface(DESCRIPTOR);
                    String event = data.readString(); Bundle payload = data.readBundle(getClass().getClassLoader());
                    if (event == null || payload == null || data.dataAvail() != 0) return false;
                    if (UI.equals(context.getPackageName()) && "motion".equals(event)) {
                        // RingHost only copies the latest pose and posts one main-thread update.
                        // Avoid waiting behind heartbeat and other runtime events here.
                        receiver.accept(event, payload);
                    } else {
                        worker.post(() -> { try { receiver.accept(event, payload); } catch (Throwable e) { Log.w("FocusPenRuntime", "Rejected runtime event", e); } });
                    }
                    return true;
                } catch (Throwable e) { return false; }
            }
        };
        heartbeat = new Runnable() {
            @Override public void run() {
                try {
                    Bundle request = new Bundle(); request.putBinder("endpoint", endpoint);
                    request.putString("version", BuildConfig.VERSION_NAME);
                    Bundle result = c.getContentResolver().call(ConfigProvider.URI, REGISTER, null, request);
                    if (result == null) throw new IllegalStateException("No runtime directory");
                    ConcurrentHashMap<String, IBinder> next = new ConcurrentHashMap<>();
                    for (String name : new String[]{UI, INPUT, MOTION}) {
                        IBinder peer = result.getBinder(name); if (peer != null) next.put(name, peer);
                    }
                    // Publish a complete directory. Clearing the live map first creates a false
                    // disconnect for concurrent key/motion deliveries on every heartbeat.
                    peers = next;
                    onHeartbeat.run();
                } catch (Throwable e) {
                    // Authenticated Binder references remain valid during a directory refresh
                    // failure; process death is checked below and on every transaction.
                }
                worker.postDelayed(this, 2000);
            }
        };
        worker.post(heartbeat);
    }
    boolean available(String host) {
        IBinder peer = peers.get(host);
        return peer != null && peer.isBinderAlive();
    }
    boolean send(String host, String event, Bundle payload) {
        IBinder peer = peers.get(host);
        if (peer == null || !peer.isBinderAlive()) return false;
        boolean sent = transmit(peer, event, payload);
        if (!sent) peers.remove(host, peer);
        return sent;
    }
    private static boolean transmit(IBinder peer, String event, Bundle payload) {
        if (peer == null || !peer.isBinderAlive()) return false;
        Parcel data = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR); data.writeString(event); data.writeBundle(payload);
            return peer.transact(EVENT, data, null, IBinder.FLAG_ONEWAY);
        } catch (Throwable e) { return false; }
        finally { data.recycle(); }
    }
}
