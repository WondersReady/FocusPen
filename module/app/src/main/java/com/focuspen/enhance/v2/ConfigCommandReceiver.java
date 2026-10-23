package com.focuspen.enhance.v2;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.util.Log;

/** Non-exported receiver reached by a module-created immutable one-shot PendingIntent. */
public final class ConfigCommandReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        try {
            if (!ConfigProvider.ACTION_COMMIT.equals(i.getAction())
                    || i.getLongExtra("expires", -1) < SystemClock.elapsedRealtime() || i.getExtras() == null) return;
            if (!ConfigStore.write(c, i.getStringExtra("key"), i.getExtras().get("value")))
                Log.w("FocusPenConfig", "Configuration commit failed");
        } catch (Throwable e) { Log.w("FocusPenConfig", "Rejected configuration command", e); }
    }
}
