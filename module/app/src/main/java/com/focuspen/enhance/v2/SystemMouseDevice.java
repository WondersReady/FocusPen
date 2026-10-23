package com.focuspen.enhance.v2;

import android.graphics.PointF;
import android.os.Binder;
import android.os.IBinder;
import android.os.SystemClock;
import de.robv.android.xposed.XposedHelpers;

/** A platform uinput mouse. Motion, buttons and the visible pointer share one InputReader device. */
final class SystemMouseDevice implements AutoCloseable {
    private final Object device;
    private final ClassLoader loader;
    private final IBinder token = new Binder();
    private int held;
    private boolean closed;

    // Must be constructed on the module worker, never on InputManager's event thread.
    SystemMouseDevice(ClassLoader loader, int displayId) throws Exception {
        this.loader = loader;
        Object config = builder("VirtualMouseConfig");
        XposedHelpers.callMethod(config, "setInputDeviceName", "FocusPen System Mouse");
        XposedHelpers.callMethod(config, "setAssociatedDisplayId", displayId);
        // Neutral IDs deliberately do not match a Xiaomi pen. The virtual device must
        // travel through the ordinary mouse path, not the ROM laser-device classifier.
        XposedHelpers.callMethod(config, "setVendorId", 0);
        XposedHelpers.callMethod(config, "setProductId", 0);
        Object input = XposedHelpers.callStaticMethod(loader.loadClass("com.android.server.LocalServices"),
                "getService", loader.loadClass("com.android.server.input.InputManagerInternal"));
        if (input == null) throw new IllegalStateException("Local input service unavailable");
        device = XposedHelpers.callMethod(input, "createVirtualMouse", token, XposedHelpers.callMethod(config, "build"));
        if (device == null) throw new IllegalStateException("Platform mouse creation failed");
    }

    int deviceId() { return (Integer) XposedHelpers.callMethod(device, "getInputDeviceId"); }

    synchronized boolean move(float dx, float dy) {
        if (closed || !Float.isFinite(dx) || !Float.isFinite(dy)) return false;
        try {
            Object event = builder("VirtualMouseRelativeEvent");
            XposedHelpers.callMethod(event, "setRelativeX", dx);
            XposedHelpers.callMethod(event, "setRelativeY", dy);
            return send("sendMouseRelativeEvent", event);
        } catch (Exception failure) { return false; }
    }

    synchronized boolean button(int button, boolean down) {
        if (closed || (button != 1 && button != 2)) return false;
        if (((held & button) != 0) == down) return true;
        try {
            Object event = builder("VirtualMouseButtonEvent");
            XposedHelpers.callMethod(event, "setButtonCode", button);
            XposedHelpers.callMethod(event, "setAction", down ? 11 : 12);
            if (!send("sendMouseButtonEvent", event)) return false;
            held = down ? held | button : held & ~button;
            return true;
        } catch (Exception failure) { return false; }
    }

    synchronized boolean scroll(float amount) {
        if (closed || !Float.isFinite(amount)) return false;
        try {
            Object event = builder("VirtualMouseScrollEvent");
            XposedHelpers.callMethod(event, "setYAxisMovement", Math.max(-1f, Math.min(1f, amount)));
            return send("sendMouseScrollEvent", event);
        } catch (Exception failure) { return false; }
    }

    synchronized PointF position() {
        if (closed) return null;
        return (PointF) XposedHelpers.callMethod(device, "getCursorPositionInLogicalDisplay");
    }

    @Override public synchronized void close() {
        if (closed) return;
        try { button(1, false); button(2, false); }
        finally { closed = true; held = 0; XposedHelpers.callMethod(device, "close"); }
    }

    private Object builder(String type) throws Exception {
        return loader.loadClass("android.hardware.input." + type + "$Builder").getDeclaredConstructor().newInstance();
    }
    private boolean send(String method, Object builder) {
        XposedHelpers.callMethod(builder, "setEventTimeNanos", SystemClock.uptimeMillis() * 1_000_000L);
        return Boolean.TRUE.equals(XposedHelpers.callMethod(device, method, XposedHelpers.callMethod(builder, "build")));
    }
}
