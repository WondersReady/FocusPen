package com.focuspen.enhance.v2;

import android.content.Context;
import android.database.ContentObserver;
import android.os.Handler;
import android.provider.Settings;
import android.util.Log;
import java.util.function.IntConsumer;

/** Reads the ROM hall sensor state, independently of Bluetooth connection status. */
final class PenDockMonitor implements AutoCloseable {
    static final int UNKNOWN=-1, DOCKED=0, DETACHED=1;
    private static final String SETTING="stylus_hall_status";
    private final Context context;
    private final Handler worker;
    private final IntConsumer changed;
    private final ContentObserver observer;
    private volatile int state=UNKNOWN;
    private volatile boolean closed;
    private final Runnable refresh=this::read;

    PenDockMonitor(Context context,Handler worker,IntConsumer changed) {
        this.context=context;this.worker=worker;this.changed=changed;
        observer=new ContentObserver(worker) {
            @Override public void onChange(boolean selfChange){read();}
        };
        context.getContentResolver().registerContentObserver(Settings.System.getUriFor(SETTING),false,observer);
        worker.post(refresh);
    }

    int state(){return state;}

    private int unknownReads;
    private synchronized void read() {
        if(closed)return;
        int next=UNKNOWN;
        try {
            int value=Settings.System.getInt(context.getContentResolver(),SETTING,UNKNOWN);
            if(value==DOCKED||value==DETACHED)next=value;
        } catch(RuntimeException failure){Log.w("FocusPenDock","Hall state unavailable",failure);}
        if(closed)return;
        // HyperOS 4 (e.g. Xiaomi Pad 7S Pro) never publishes the hall setting.
        // An absent sensor cannot justify keeping every pen key blocked, so after a
        // short grace period treat the state as detached and keep observing in case
        // the ROM starts reporting it later (a real dock event then still applies).
        if(next==UNKNOWN){if(++unknownReads>=5)next=DETACHED;}
        else unknownReads=0;
        if(next!=state){state=next;Log.i("FocusPenDock","hall="+next);changed.accept(next);}
        worker.removeCallbacks(refresh);
        if(next==UNKNOWN)worker.postDelayed(refresh,1000);
    }

    @Override public synchronized void close() {
        if(closed)return;
        closed=true;worker.removeCallbacks(refresh);
        context.getContentResolver().unregisterContentObserver(observer);
    }
}
