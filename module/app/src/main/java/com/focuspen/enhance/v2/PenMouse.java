package com.focuspen.enhance.v2;

import android.os.Handler;
import android.util.Log;
import android.view.KeyEvent;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/** Owns only the module's platform mouse device; every device call runs on its worker. */
final class PenMouse {
    interface Opener { SystemMouseDevice open(int sourceDevice) throws Exception; }

    private static final String TAG = "FocusPenMouse";
    private static volatile PenMouse instance;

    private record KeyIdentity(int device, int key, long downTime) { }
    private record KeyPress(KeyIdentity identity, int button, long session, long context) { }

    private static final class Motion {
        final long session;
        final long context;
        double dx;
        double dy;

        Motion(long session, long context, float dx, float dy) {
            this.session = session;
            this.context = context;
            this.dx = dx;
            this.dy = dy;
        }
    }

    private final Handler worker;
    private final Opener opener;
    private final BooleanSupplier allowed;
    private final Runnable onStopped;
    private final android.content.Context injector;
    private final java.util.function.Supplier<ConfigSnapshot> config;
    private boolean touchDown;
    private long touchDownTime;
    private boolean backDown;
    private long backDownTime;
    private final Object lock = new Object();
    private final Map<Integer, KeyPress> keys = new HashMap<>();
    private final LinkedHashSet<KeyIdentity> staleKeys = new LinkedHashSet<>();
    private final Object[] buttonOwners = new Object[3]; // worker only
    private final Object[] actionOwners = { null, new Object(), new Object() };
    private SystemMouseDevice mouse; // worker only
    private Motion pendingMotion;
    private long generation;
    private long context;
    private boolean wanted;
    private boolean active;
    // Dynamic pointer speed: EMA of raw input velocity (px per ms) drives a
    // log curve so slow strokes stay precise and fast flicks travel further.
    private float velocity;
    private long lastMotionAt;
    // Wheel mode: the pen hovers frozen; the side keys inject wheel notches and
    // holding either key scrolls continuously (focus-key double-tap toggles).
    private boolean wheelMode; // worker only
    private int wheelHeld; // worker only: 0 none, 1 note key (up), 2 capture key (down)
    private final Runnable wheelRepeat=this::wheelRepeatStep; // worker only

    PenMouse(Handler worker, Opener opener, BooleanSupplier allowed) {
        this(worker, opener, allowed, () -> { }, null, null);
    }

    /** onStopped runs on the worker after an unexpected open/device/permission failure. */
    PenMouse(Handler worker, Opener opener, BooleanSupplier allowed, Runnable onStopped) {
        this(worker, opener, allowed, onStopped, null, null);
    }

    /** Full configuration: context enables touch/back injection; config supplies mouse mode options. */
    PenMouse(Handler worker, Opener opener, BooleanSupplier allowed, Runnable onStopped,
             android.content.Context context, java.util.function.Supplier<ConfigSnapshot> config) {
        this.worker = Objects.requireNonNull(worker);
        this.opener = Objects.requireNonNull(opener);
        this.allowed = Objects.requireNonNull(allowed);
        this.onStopped = Objects.requireNonNull(onStopped);
        this.injector = context;
        this.config = config;
        PenMouse previous = instance;
        instance = this;
        if (previous != null) previous.requestExit();
    }

    static boolean active() {
        PenMouse m = instance;
        if (m == null) return false;
        synchronized (m.lock) { return m.wanted || m.active; }
    }

    static long session() {
        PenMouse m = instance;
        if (m == null) return 0;
        synchronized (m.lock) { return m.generation; }
    }

    static boolean isSession(long version) {
        PenMouse m = instance;
        if (m == null) return false;
        synchronized (m.lock) { return (m.wanted || m.active) && m.generation == version; }
    }

    static void enter(int sourceDevice) {
        PenMouse m = instance;
        if (m != null) m.requestEnter(sourceDevice);
    }

    static void exit() {
        PenMouse m = instance;
        if (m != null) m.requestExit();
    }

    static boolean key(KeyEvent event, int button) {
        PenMouse m = instance;
        return m != null && m.handleKey(event, button);
    }

    static void action(String action, boolean end, long version) {
        PenMouse m = instance;
        if (m != null) m.requestAction(action, end, version);
    }

    static void relative(float dx, float dy) {
        PenMouse m = instance;
        if (m != null) m.requestMotion(dx, dy);
    }

    static void contextChanged() {
        PenMouse m = instance;
        if (m != null) m.changeContext();
    }

    static boolean wheelActive() {
        PenMouse m = instance;
        if (m == null) return false;
        synchronized (m.lock) { return m.wheelMode; }
    }

    /** 滚轮模式开关：仅在鼠标模式生效。进入后由速记/截图键滚动页面，再切一次或退出鼠标模式即恢复。 */
    static void toggleWheel() {
        PenMouse m = instance;
        if (m != null) m.requestWheelToggle();
    }

    private void requestEnter(int sourceDevice) {
        synchronized (lock) {
            if (wanted || active) return;
            wanted = true;
            long version = ++generation;
            worker.post(() -> begin(sourceDevice, version));
        }
    }

    private void begin(int sourceDevice, long version) {
        synchronized (lock) {
            if (!wanted || generation != version) return;
        }
        SystemMouseDevice opened;
        try {
            if (!allowed.getAsBoolean()) throw new IllegalStateException("Mouse no longer allowed");
            opened = Objects.requireNonNull(opener.open(sourceDevice));
        } catch (Throwable failure) {
            Log.w(TAG, "Cannot open system mouse", failure);
            fail(version);
            return;
        }
        synchronized (lock) {
            if (wanted && generation == version) {
                mouse = opened;
                active = true;
                return;
            }
        }
        try { opened.close(); } catch (Throwable failure) { Log.w(TAG, "Cannot close canceled mouse", failure); }
    }

    private void requestExit() {
        synchronized (lock) {
            if (!wanted && !active) return;
            wanted = false;
            active = false;
            generation++;
            context++;
            pendingMotion = null;
            velocity = 0;
            lastMotionAt = 0;
            wheelMode = false;
            wheelHeld = 0;
            worker.removeCallbacks(wheelRepeat);
            retireKeys();
            worker.post(this::closeDevice);
        }
    }

    private void changeContext() {
        synchronized (lock) {
            if (!active) return;
            long version = ++generation;
            long epoch = ++context;
            pendingMotion = null;
            velocity = 0;
            lastMotionAt = 0;
            wheelMode = false;
            wheelHeld = 0;
            worker.removeCallbacks(wheelRepeat);
            retireKeys();
            worker.post(() -> {
                if (current(version, epoch) && !releaseButtons()) fail(version);
            });
        }
    }

    private boolean handleKey(KeyEvent event, int button) {
        if (button != 1 && button != 2) return false;
        KeyIdentity identity = new KeyIdentity(event.getDeviceId(), event.getKeyCode(), event.getDownTime());
        synchronized (lock) {
            KeyPress old = keys.get(identity.key());
            if (event.getAction() == KeyEvent.ACTION_UP) {
                if (old != null && old.identity().equals(identity)) {
                    keys.remove(identity.key());
                    remember(identity);
                    barrier();
                    worker.post(() -> releaseKey(old));
                    return true;
                }
                return staleKeys.contains(identity);
            }
            if (event.getAction() != KeyEvent.ACTION_DOWN || (!wanted && !active)) return false;
            if (old != null && old.identity().equals(identity)) return true;
            if (event.getRepeatCount() != 0) return true;
            if (old != null) {
                remember(old.identity());
                worker.post(() -> releaseKey(old));
            }
            if (event.isCanceled()) return true;
            KeyPress next = new KeyPress(identity, button, generation, context);
            staleKeys.remove(identity);
            keys.put(identity.key(), next);
            barrier();
            worker.post(() -> pressKey(next));
            return true;
        }
    }

    private void pressKey(KeyPress press) {
        if (!ready(press.session(), press.context())) return;
        if (wheelMode) { wheelPress(press.button(), press.session()); return; }
        pressButton(press.button(), press, press.session());
    }

    private void releaseKey(KeyPress press) {
        if (!current(press.session(), press.context())) return;
        if (wheelMode && wheelHeld == press.button()) { wheelHeld = 0; worker.removeCallbacks(wheelRepeat); return; }
        releaseButton(press.button(), press, press.session());
    }

    // 滚轮模式：点一下滚一格，按住不放持续滚动。
    private void wheelPress(int button, long version) {
        wheelHeld = button;
        wheelScrollOnce(button, version);
        worker.postDelayed(wheelRepeat, 160);
    }

    private void wheelRepeatStep() {
        if (!wheelMode || wheelHeld == 0) return;
        if (!current(generation, context)) return;
        wheelScrollOnce(wheelHeld, generation);
        worker.postDelayed(wheelRepeat, 120);
    }

    private void wheelScrollOnce(int button, long version) {
        try { if (mouse != null && !mouse.scroll(button == 1 ? 1f : -1f)) fail(version); }
        catch (Throwable failure) { deviceFailure(version, failure); }
    }

    private void requestAction(String action, boolean end, long version) {
        synchronized (lock) {
            if ((!wanted && !active) || generation != version) return;
            long epoch = context;
            barrier();
            worker.post(() -> {
                if (!ready(version, epoch)) return;
                if (end) {
                    releaseButton(1, actionOwners[1], version);
                    releaseButton(2, actionOwners[2], version);
                } else if ("left".equals(action) || "right".equals(action)) {
                    int button = "left".equals(action) ? 1 : 2;
                    Object owner = actionOwners[button];
                    if (buttonOwners[button] == null) {
                        pressButton(button, owner, version);
                        releaseButton(button, owner, version);
                    }
                } else if ("leftHold".equals(action) || "rightHold".equals(action)) {
                    int button = "leftHold".equals(action) ? 1 : 2;
                    pressButton(button, actionOwners[button], version);
                } else if ("scrollUp".equals(action) || "scrollDown".equals(action)) {
                    try {
                        if (!mouse.scroll("scrollUp".equals(action) ? 1f : -1f)) fail(version);
                    } catch (Throwable failure) { deviceFailure(version, failure); }
                }
            });
        }
    }

    private void requestMotion(float dx, float dy) {
        if (!Float.isFinite(dx) || !Float.isFinite(dy) || (dx == 0 && dy == 0)) return;
        synchronized (lock) {
            if (!wanted && !active) return;
            float factor = speedFactor() * accelFactor(dx, dy);
            dx *= factor; dy *= factor;
            if (wheelMode) return; // 滚轮模式：笔悬停冻结，滚动由速记/截图键负责
            if (pendingMotion != null) {
                pendingMotion.dx += dx;
                pendingMotion.dy += dy;
                return;
            }
            Motion motion = new Motion(generation, context, dx, dy);
            pendingMotion = motion;
            worker.post(() -> {
                synchronized (lock) {
                    if (pendingMotion == motion) pendingMotion = null;
                }
                if (!ready(motion.session, motion.context)) return;
                if (!Double.isFinite(motion.dx) || !Double.isFinite(motion.dy)
                        || Math.abs(motion.dx) > Float.MAX_VALUE
                        || Math.abs(motion.dy) > Float.MAX_VALUE) return;
                try {
                    if (!mouse.move((float) motion.dx, (float) motion.dy)) fail(motion.session);
                    else touchDrag();
                } catch (Throwable failure) { deviceFailure(motion.session, failure); }
            });
        }
    }

    private void barrier() { pendingMotion = null; }

    private boolean current(long version, long epoch) {
        synchronized (lock) { return active && generation == version && context == epoch; }
    }

    private boolean ready(long version, long epoch) {
        if (!current(version, epoch)) return false;
        try {
            if (allowed.getAsBoolean()) return mouse != null;
        } catch (Throwable failure) {
            deviceFailure(version, failure);
            return false;
        }
        fail(version);
        return false;
    }

    private boolean touchMode() {
        try { return config == null || "touch".equals(config.get().text(ConfigSpec.MOUSE_LEFT)); }
        catch (Throwable failure) { return false; }
    }

    private boolean backMode() {
        try { return config != null && "back".equals(config.get().text(ConfigSpec.MOUSE_RIGHT)); }
        catch (Throwable failure) { return false; }
    }

    private float speedFactor() {
        try { return Math.max(10, Math.min(150, config == null ? 100 : config.get().number(ConfigSpec.MOUSE_SPEED))) / 100f; }
        catch (Throwable failure) { return 1f; }
    }

    private boolean accelEnabled() {
        try { return config == null || config.get().flag(ConfigSpec.MOUSE_ACCEL); }
        catch (Throwable failure) { return false; }
    }

    // Piecewise-log curve on the smoothed input velocity, scaled by the
    // user-tunable range: at rest the pointer runs at the fine-speed factor
    // for precise aiming, large flicks saturate at the boost limit, so one
    // setting covers both fine placement and quick travel.
    private float accelFactor(float dx, float dy) {
        if (!accelEnabled()) return 1f;
        long now = android.os.SystemClock.uptimeMillis();
        float dt = lastMotionAt == 0 ? 10f : Math.max(1f, now - lastMotionAt);
        float instant = (float) Math.hypot(dx, dy) / dt;
        velocity = velocity * 0.75f + instant * 0.25f;
        lastMotionAt = now;
        float low = 0.55f, high = 2.6f;
        try {
            ConfigSnapshot snapshot = config.get();
            low = Math.max(0.2f, Math.min(1f, snapshot.number(ConfigSpec.MOUSE_ACCEL_MIN) / 100f));
            high = Math.max(1f, Math.min(5f, snapshot.number(ConfigSpec.MOUSE_ACCEL_MAX) / 100f));
        } catch (Throwable failure) { }
        if (high < low) { float swap = low; low = high; high = swap; }
        float shape = (float) (Math.log1p(velocity / 0.15f) / Math.log1p(2.16f / 0.15f));
        shape = Math.max(0f, Math.min(1f, shape));
        return low + (high - low) * shape;
    }

    private void pressButton(int button, Object owner, long version) {
        if (buttonOwners[button] != null) return;
        try {
            if (button == 1 && touchMode()) {
                if (!touchPress()) { fail(version); return; }
            } else if (button == 2 && backMode()) {
                if (!backKey(true)) { fail(version); return; }
            } else if (!mouse.button(button, true)) { fail(version); return; }
            buttonOwners[button] = owner;
        } catch (Throwable failure) { deviceFailure(version, failure); }
    }

    private void releaseButton(int button, Object owner, long version) {
        if (buttonOwners[button] != owner) return;
        try {
            boolean released;
            if (button == 1 && touchDown) released = touchRelease();
            else if (button == 2 && backDown) released = backKey(false);
            else released = mouse.button(button, false);
            if (!released) { fail(version); return; }
            buttonOwners[button] = null;
        } catch (Throwable failure) { deviceFailure(version, failure); }
    }

    // Touch pointer: injected touchscreen events at the cursor, so press-and-move acts
    // like a finger (touch-only UIs such as comment lists scroll), unlike a mouse drag.
    private boolean touchPress() {
        if (touchDown) return true;
        android.graphics.PointF p = cursor();
        if (p == null) return false;
        touchDownTime = android.os.SystemClock.uptimeMillis();
        if (!injectTouch(android.view.MotionEvent.ACTION_DOWN, p.x, p.y)) return false;
        touchDown = true;
        return true;
    }

    private boolean touchRelease() {
        if (!touchDown) return true;
        android.graphics.PointF p = cursor();
        touchDown = false;
        return injectTouch(android.view.MotionEvent.ACTION_UP, p == null ? 0 : p.x, p == null ? 0 : p.y);
    }

    private void touchDrag() {
        if (!touchDown) return;
        android.graphics.PointF p = cursor();
        if (p != null) injectTouch(android.view.MotionEvent.ACTION_MOVE, p.x, p.y);
    }

    private android.graphics.PointF cursor() {
        try { return mouse == null ? null : mouse.position(); } catch (Throwable failure) { return null; }
    }

    private boolean injectTouch(int action, float px, float py) {
        if (injector == null) return false;
        try {
            long now = android.os.SystemClock.uptimeMillis();
            android.view.MotionEvent.PointerProperties[] props = new android.view.MotionEvent.PointerProperties[1];
            props[0] = new android.view.MotionEvent.PointerProperties();
            props[0].id = 0;
            // Gesture navigation (edge back, home bar) only reacts to finger touches.
            // The short obtain() leaves the tool type UNKNOWN, which apps accept but
            // the gesture monitors drop, so the bar and edge back must be injected
            // with an explicit finger tool type.
            props[0].toolType = android.view.MotionEvent.TOOL_TYPE_FINGER;
            android.view.MotionEvent.PointerCoords[] coords = new android.view.MotionEvent.PointerCoords[1];
            coords[0] = new android.view.MotionEvent.PointerCoords();
            coords[0].x = px;
            coords[0].y = py;
            coords[0].pressure = 1.0f;
            coords[0].size = 0.05f;
            coords[0].touchMajor = 4.0f;
            coords[0].touchMinor = 4.0f;
            android.view.MotionEvent ev = android.view.MotionEvent.obtain(touchDownTime, now, action,
                    1, props, coords, 0, 0, 1.0f, 1.0f, 0, 0,
                    android.view.InputDevice.SOURCE_TOUCHSCREEN, 0);
            Object manager = injector.getSystemService(android.hardware.input.InputManager.class);
            boolean ok = Boolean.TRUE.equals(de.robv.android.xposed.XposedHelpers.callMethod(manager, "injectInputEvent", ev, 0));
            ev.recycle();
            return ok;
        } catch (Throwable failure) { return false; }
    }

    private void requestWheelToggle() {
        synchronized (lock) {
            if (!wanted && !active) return;
            wheelMode = !wheelMode;
            wheelHeld = 0;
        }
        worker.removeCallbacks(wheelRepeat);
        toast(wheelMode ? "滚轮模式：速记键上滚 · 截图键下滚（长按连滚），焦点键双击退出" : "退出滚轮模式");
    }


    private void toast(String text) {
        if (injector == null) return;
        try {
            injector.getMainExecutor().execute(() -> android.widget.Toast.makeText(injector, text, android.widget.Toast.LENGTH_SHORT).show());
        } catch (Throwable ignored) { }
    }

    /** Injected key tap (home/recents/back) for configurable focus-key actions in mouse mode. */
    static boolean keyTap(int keyCode) {
        PenMouse m = instance;
        if (m == null) return false;
        return m.injectKeyTap(keyCode);
    }

    private boolean injectKeyTap(int keyCode) {
        if (injector == null) return false;
        try {
            long now = android.os.SystemClock.uptimeMillis();
            Object manager = injector.getSystemService(android.hardware.input.InputManager.class);
            android.view.KeyEvent down = new android.view.KeyEvent(now, now,
                    android.view.KeyEvent.ACTION_DOWN, keyCode, 0, 0, -1, 0, 0,
                    android.view.InputDevice.SOURCE_KEYBOARD);
            android.view.KeyEvent up = new android.view.KeyEvent(now, now,
                    android.view.KeyEvent.ACTION_UP, keyCode, 0, 0, -1, 0, 0,
                    android.view.InputDevice.SOURCE_KEYBOARD);
            boolean ok = Boolean.TRUE.equals(de.robv.android.xposed.XposedHelpers.callMethod(manager, "injectInputEvent", down, 0));
            if (ok) ok = Boolean.TRUE.equals(de.robv.android.xposed.XposedHelpers.callMethod(manager, "injectInputEvent", up, 0));
            return ok;
        } catch (Throwable failure) { return false; }
    }

    private boolean backKey(boolean down) {
        if (injector == null) return false;
        try {
            if (down == backDown) return true;
            long now = android.os.SystemClock.uptimeMillis();
            if (down) backDownTime = now;
            android.view.KeyEvent ev = new android.view.KeyEvent(backDownTime, now,
                    down ? android.view.KeyEvent.ACTION_DOWN : android.view.KeyEvent.ACTION_UP,
                    android.view.KeyEvent.KEYCODE_BACK, 0, 0, -1, 0, 0, android.view.InputDevice.SOURCE_KEYBOARD);
            Object manager = injector.getSystemService(android.hardware.input.InputManager.class);
            boolean ok = Boolean.TRUE.equals(de.robv.android.xposed.XposedHelpers.callMethod(manager, "injectInputEvent", ev, 0));
            if (ok) backDown = down;
            return ok;
        } catch (Throwable failure) { return false; }
    }

    private boolean releaseButtons() {
        boolean okay = true;
        try { if (!touchRelease()) okay = false; } catch (Throwable failure) { okay = false; }
        try { if (!backKey(false)) okay = false; } catch (Throwable failure) { okay = false; }
        for (int button = 1; button <= 2; button++) {
            if (buttonOwners[button] == null) continue;
            try { if (mouse == null || !mouse.button(button, false)) okay = false; }
            catch (Throwable failure) { Log.w(TAG, "Cannot release mouse button", failure); okay = false; }
            buttonOwners[button] = null;
        }
        return okay;
    }

    private void closeDevice() {
        releaseButtons();
        SystemMouseDevice owned = mouse;
        mouse = null;
        if (owned != null) try { owned.close(); }
        catch (Throwable failure) { Log.w(TAG, "Cannot close system mouse", failure); }
    }

    private void fail(long version) {
        synchronized (lock) {
            if (generation != version || (!wanted && !active)) return;
            wanted = false;
            active = false;
            generation++;
            context++;
            pendingMotion = null;
            retireKeys();
        }
        closeDevice();
        try { onStopped.run(); } catch (Throwable failure) { Log.w(TAG, "Mouse stop callback failed", failure); }
    }

    private void deviceFailure(long version, Throwable failure) {
        Log.w(TAG, "System mouse operation failed", failure);
        fail(version);
    }

    private void retireKeys() {
        for (KeyPress press : keys.values()) remember(press.identity());
        keys.clear();
    }

    private void remember(KeyIdentity identity) {
        staleKeys.add(identity);
        while (staleKeys.size() > 64) {
            Iterator<KeyIdentity> oldest = staleKeys.iterator();
            oldest.next();
            oldest.remove();
        }
    }
}
