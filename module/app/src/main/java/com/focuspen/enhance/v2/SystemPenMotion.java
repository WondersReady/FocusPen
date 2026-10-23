package com.focuspen.enhance.v2;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.util.Log;
import android.content.Context;
import java.lang.reflect.Proxy;
import de.robv.android.xposed.XposedHelpers;

/** Observes the WMS pointer stream, including hover; never consumes or changes input. */
final class SystemPenMotion {
    private final ConfigClient config;
    private final RuntimeBus bus;
    private final WritingAudioEngine audio = new WritingAudioEngine();
    private final Object window, listener;
    private boolean registered;
    private long lastMove, lastReport;
    SystemPenMotion(Context context, ConfigClient config, RuntimeBus bus) throws Exception {
        this.config=config; this.bus=bus;
        audio.setContext(context);
        Log.i("FocusPenMotion", "initializing WMS pointer observer");
        window=XposedHelpers.callStaticMethod(Class.forName("android.os.ServiceManager"),"getService","window");
        Class<?> iface=Class.forName("android.view.WindowManagerPolicyConstants$PointerEventListener");
        listener=Proxy.newProxyInstance(iface.getClassLoader(),new Class<?>[]{iface},(p,m,args)->{
            if(m.getName().equals("hashCode")) return System.identityHashCode(p);
            if(m.getName().equals("equals")) return p==args[0];
            if(m.getName().equals("toString")) return "FocusPenPointerObserver";
            if(m.getName().equals("onPointerEvent") && args[0] instanceof MotionEvent e) {
                try { motion(e); } catch(Throwable failure) { Log.w("FocusPenMotion","Pointer update failed",failure); }
            }
            return null;
        });
        ConfigClient.Listener registration = s->{
            boolean wanted=s.enabled() && (s.flag(ConfigSpec.KEYS)||s.flag(ConfigSpec.RING)||s.previewPossible()||s.audioEnabled());
            audio.setEnabled(s.enabled() && s.audioEnabled(), s.audioVolume());
            if(wanted==registered) return;
            try {
                XposedHelpers.callMethod(window,wanted?"registerPointerEventListener":"unregisterPointerEventListener",listener,0);
                registered=wanted;
                WindowDirectionHost.CONTACT.observing(registered);
                Log.i("FocusPenMotion","WMS pointer observer registered="+registered);
            } catch(Throwable failure) { Log.e("FocusPenMotion","Pointer observer unavailable",failure); }
        };
        config.add(registration);
        // ConfigClient may have completed its first read just before this host was
        // constructed; apply the current snapshot explicitly as well.
        registration.changed(config.get());
        // System server can finish publishing the provider snapshot after the host
        // constructor returns. Retry once so the observer is not left disabled by
        // that startup race.
        new Handler(Looper.getMainLooper()).postDelayed(() -> registration.changed(config.get()), 2000);
        Log.i("FocusPenMotion", "pointer observer setup complete enabled=" + config.get().enabled());
    }
    private void motion(MotionEvent e) {
        InputDevice physical = e.getDevice();
        if (physical != null && !physical.isVirtual() && e.getPointerCount()>0
                && e.getToolType(0)!=MotionEvent.TOOL_TYPE_MOUSE)
        {
            WindowDirectionHost.CONTACT.observe(e.getActionMasked());
            if(e.getActionMasked()==MotionEvent.ACTION_DOWN)PenMouse.contextChanged();
        }
        ConfigSnapshot s=config.get();
        if(!s.enabled() || (!s.flag(ConfigSpec.RING)&&!s.previewPossible()&&!s.audioEnabled()) || e.getPointerCount()==0) return;
        int tool=e.getToolType(0);
        if(tool!=MotionEvent.TOOL_TYPE_STYLUS && tool!=MotionEvent.TOOL_TYPE_ERASER) return;
        int action=e.getActionMasked(); long now=SystemClock.uptimeMillis();
        if((action==MotionEvent.ACTION_MOVE||action==MotionEvent.ACTION_HOVER_MOVE)&&now-lastMove<8) return;
        lastMove=now;
        InputDevice device=e.getDevice();
        boolean orientation=device!=null && device.getMotionRange(MotionEvent.AXIS_ORIENTATION)!=null && Float.isFinite(e.getOrientation());
        Bundle b=new Bundle(); b.putInt("action",action); b.putFloat("x",e.getRawX()); b.putFloat("y",e.getRawY());
        b.putBoolean("orientationValid",orientation); b.putFloat("orientation",e.getOrientation());
        b.putLong("time",e.getEventTime()); b.putInt("device",e.getDeviceId());
        b.putInt("display",(Integer)XposedHelpers.callMethod(e,"getDisplayId"));
        // Pointer callbacks already run on the system input path. Send the one-way
        // Binder update directly so the preview does not wait behind another queue.
        bus.send(RuntimeBus.UI,"motion",b);
        if (s.audioEnabled()) {
            // The current standard tablet reports a 3200 px logical input width. If a future
            // device differs, the engine still remains safe and simply uses a centered pan.
            int displayWidth = 3200;
            audio.onMotion(action, e.getRawX(), e.getRawY(), e.getPressure(), e.getEventTime(), displayWidth);
        }
        if(now-lastReport>5000) {
            lastReport=now; Log.i("FocusPenMotion","Observed pen action="+action+" orientation="+orientation);
        }
    }
}
