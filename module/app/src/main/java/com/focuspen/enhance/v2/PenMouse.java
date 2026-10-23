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

    PenMouse(Handler worker, Opener opener, BooleanSupplier allowed) {
        this(worker, opener, allowed, () -> { });
    }

    /** onStopped runs on the worker after an unexpected open/device/permission failure. */
    PenMouse(Handler worker, Opener opener, BooleanSupplier allowed, Runnable onStopped) {
        this.worker = Objects.requireNonNull(worker);
        this.opener = Objects.requireNonNull(opener);
        this.allowed = Objects.requireNonNull(allowed);
        this.onStopped = Objects.requireNonNull(onStopped);
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
        pressButton(press.button(), press, press.session());
    }

    private void releaseKey(KeyPress press) {
        if (!current(press.session(), press.context())) return;
        releaseButton(press.button(), press, press.session());
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

    private void pressButton(int button, Object owner, long version) {
        if (buttonOwners[button] != null) return;
        try {
            if (!mouse.button(button, true)) { fail(version); return; }
            buttonOwners[button] = owner;
        } catch (Throwable failure) { deviceFailure(version, failure); }
    }

    private void releaseButton(int button, Object owner, long version) {
        if (buttonOwners[button] != owner) return;
        try {
            if (!mouse.button(button, false)) { fail(version); return; }
            buttonOwners[button] = null;
        } catch (Throwable failure) { deviceFailure(version, failure); }
    }

    private boolean releaseButtons() {
        boolean okay = true;
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
