package com.focuspen.enhance.v2;

import android.content.Context;
import android.os.Bundle;
import android.os.SystemClock;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import de.robv.android.xposed.XposedHelpers;

/** TouchService observes pen motion and sends bounded updates to the single SystemUI view. */
final class PenMotionHost {
    private long lastMove;
    private boolean wanted, wasStylus;
    private final Handler main = new Handler(Looper.getMainLooper());
    private int attempts;
    private final Runnable register = new Runnable() {
        @Override public void run() {
            if (wanted && !MotionListenerBridge.register() && ++attempts < 3)
                main.postDelayed(this, 5000);
        }
    };
    PenMotionHost(Context c, ConfigClient config) throws Exception {
        RuntimeBus bus = new RuntimeBus(c, (event, payload) -> { }, config::refresh);
        MotionListenerBridge.setSink(e -> {
            ConfigSnapshot s = config.get();
            if (!s.enabled() || (!s.flag(ConfigSpec.RING) && !s.previewPossible()) || !bus.available(RuntimeBus.UI)) return;
            if (e.getPointerCount() == 0) return;
            boolean stylus = e.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS;
            if (!stylus && !wasStylus) return;
            wasStylus = stylus;
            int action = e.getActionMasked(); long now = SystemClock.uptimeMillis();
            if (stylus && (action == MotionEvent.ACTION_HOVER_MOVE || action == MotionEvent.ACTION_MOVE) && now - lastMove < 8) return;
            lastMove = now;
            Bundle b = new Bundle(); b.putInt("action", stylus ? action : MotionEvent.ACTION_CANCEL);
            b.putFloat("x", e.getRawX()); b.putFloat("y", e.getRawY());
            android.view.InputDevice device = e.getDevice();
            boolean hasOrientation = device != null && device.getMotionRange(MotionEvent.AXIS_ORIENTATION, e.getSource()) != null;
            b.putBoolean("orientationValid", hasOrientation && Float.isFinite(e.getOrientation()));
            b.putFloat("orientation", e.getOrientation());
            b.putLong("time", e.getEventTime()); b.putInt("device", e.getDeviceId());
            b.putInt("display", (Integer) XposedHelpers.callMethod(e, "getDisplayId"));
            bus.send(RuntimeBus.UI, "motion", b);
        });
        config.add(s -> {
            wanted = s.enabled() && (s.flag(ConfigSpec.RING) || s.previewPossible());
            attempts = 0; main.removeCallbacks(register);
            if (wanted) main.post(register);
            else { wasStylus = false; MotionListenerBridge.unregister(); }
        });
    }
}
