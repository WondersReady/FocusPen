package com.focuspen.enhance.v2;

import android.graphics.Rect;
import android.graphics.Region;
import android.os.Handler;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.util.Log;
import java.util.function.BooleanSupplier;
import de.robv.android.xposed.XposedHelpers;

/** Opt-in pointer adapters, restricted to the focused application window and owning UID. */
final class WindowDirectionHost {
    static final PointerContact CONTACT = new PointerContact();
    private final Handler handler;
    private final NativeKeyReplay replay;
    private WindowGesture active;
    WindowDirectionHost(Handler handler, NativeKeyReplay replay) { this.handler = handler; this.replay = replay; }

    void run(KeyTarget target, String adapter, boolean up, BooleanSupplier current) {
        if (active != null && active.active() || !current.getAsBoolean()) return;
        long contact = CONTACT.idleGeneration();
        if (contact < 0) return;
        try {
            Frame frame = frame(target.window());
            long now = SystemClock.uptimeMillis();
            WindowGesture gesture = new WindowGesture(frame.bounds.left, frame.bounds.top,
                    frame.bounds.right, frame.bounds.bottom, up, now);
            if (!frame.touch.contains((int)gesture.x, (int)gesture.startY)
                    || !frame.touch.contains((int)gesture.x, (int)gesture.endY)) return;
            BooleanSupplier valid = () -> valid(target, frame, contact, current);
            if ("scroll".equals(adapter)) {
                float y = frame.bounds.exactCenterY();
                if (valid.getAsBoolean() && frame.touch.contains((int)gesture.x, (int)y))
                    send(frame, now, MotionEvent.ACTION_SCROLL, gesture.x, y, up ? 3 : -3);
                return;
            }
            if (!"swipe".equals(adapter)) return;
            active = gesture;
            WindowGesture.Sink sink = (action,x,y) -> send(frame, now, action, x,y,0);
            Runnable step = new Runnable() {
                @Override public void run() {
                    gesture.advance(SystemClock.uptimeMillis(), valid.getAsBoolean(), sink);
                    if (gesture.active()) handler.postDelayed(this,16);
                    else if (active == gesture) active = null;
                }
            };
            step.run();
        } catch (Throwable failure) { Log.w("FocusPenDirection", "Window adapter unavailable", failure); }
    }
    private boolean valid(KeyTarget target, Frame expected, long contact, BooleanSupplier current) {
        if (!current.getAsBoolean() || !CONTACT.unchanged(contact)) return false;
        try {
            Frame actual = frame(target.window());
            return actual.uid == expected.uid && actual.display == expected.display
                    && actual.bounds.equals(expected.bounds) && actual.touch.equals(expected.touch);
        } catch (Throwable failure) { return false; }
    }
    private record Frame(Rect bounds, Region touch, int uid, int display) { }
    private Frame frame(Object window) {
        Object service = XposedHelpers.getObjectField(window,"mWmService");
        Object globalLock = XposedHelpers.getObjectField(service,"mGlobalLock");
        synchronized (globalLock) {
            Object display = XposedHelpers.callMethod(window,"getDisplayContent");
            Object attrs = XposedHelpers.getObjectField(window,"mAttrs");
            int type = XposedHelpers.getIntField(attrs,"type");
            if (type != 1 && type != 2 || XposedHelpers.getObjectField(display,"mCurrentFocus") != window
                    || !Boolean.TRUE.equals(XposedHelpers.callMethod(window,"isVisible")))
                throw new IllegalStateException("No focused application window");
            int displayId = (Integer) XposedHelpers.callMethod(window,"getDisplayId");
            if (displayId != 0) throw new IllegalStateException("Unobserved display");
            Rect bounds = new Rect((Rect) XposedHelpers.callMethod(window,"getFrame"));
            if (!bounds.intersect((Rect)XposedHelpers.callMethod(window,"getDisplayFrame")))
                throw new IllegalStateException("Window outside display");
            Region touch = new Region(); XposedHelpers.callMethod(window,"getTouchableRegion",touch);
            // Exclude edges/caption by using only the central part of the actual window frame.
            for (int i=0;i<=20;i++) if (!touch.contains((int)bounds.exactCenterX(),
                    (int)(bounds.top + bounds.height() * (.32f + .36f*i/20))))
                throw new IllegalStateException("Gesture crosses a non-touchable area");
            return new Frame(bounds,touch,(Integer)XposedHelpers.callMethod(window,"getOwningUid"),displayId);
        }
    }
    private boolean send(Frame frame, long downTime, int action, float x, float y, float scroll) {
        MotionEvent.PointerProperties p = new MotionEvent.PointerProperties(); p.id = 0;
        p.toolType = action == MotionEvent.ACTION_SCROLL ? MotionEvent.TOOL_TYPE_MOUSE : MotionEvent.TOOL_TYPE_FINGER;
        MotionEvent.PointerCoords c = new MotionEvent.PointerCoords(); c.x=x; c.y=y;
        c.pressure = action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL || action == MotionEvent.ACTION_SCROLL ? 0 : 1;
        c.size=1; if (action == MotionEvent.ACTION_SCROLL) c.setAxisValue(MotionEvent.AXIS_VSCROLL,scroll);
        MotionEvent event = MotionEvent.obtain(downTime,SystemClock.uptimeMillis(),action,1,
                new MotionEvent.PointerProperties[]{p},new MotionEvent.PointerCoords[]{c},0,0,1,1,-1,0,
                action == MotionEvent.ACTION_SCROLL ? InputDevice.SOURCE_MOUSE : InputDevice.SOURCE_TOUCHSCREEN,0);
        try {
            XposedHelpers.callMethod(event,"setDisplayId",frame.display);
            replay.dispatchPointer(event,frame.uid);
            return true;
        } catch (Throwable failure) { Log.w("FocusPenDirection","Targeted input rejected",failure); return false; }
        finally { event.recycle(); }
    }
}
