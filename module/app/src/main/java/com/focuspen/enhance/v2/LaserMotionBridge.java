package com.focuspen.enhance.v2;

import android.util.Log;
import android.view.KeyEvent;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

/** Binds the ROM's native laser motion callback to an interface-only Java owner. */
final class LaserMotionBridge {
    private static final String TAG = "FocusPenLaserBridge";
    private static final String ROM = "Xiaomi/piano/piano:17/CP2A.260605.016/OS4.0.2.0.XPYCNXM:user/release-keys";
    private static volatile Installation installed;

    interface Sink {
        void setPosition(float x, float y);
        void getPosition(float[] out);
        void move(float dx, float dy);
        void fade(int transition);
        void unfade(int transition);
        void resetPosition();
        boolean canShowPointer();
        void displayId(int id);
        void displayViewports(List<?> viewports);
        void screenState(boolean on);
    }

    static final class Installation implements AutoCloseable {
        private final Sink sink;
        private final Object proxy;
        private final Field officialController;
        private final Field pointerDisplayId;
        private final Field displayViewports;
        private final Field isScreenOn;
        private final Method getLocalService;
        private final Field serviceFromLocal;
        private final Class<?> internalType;
        private final List<XC_MethodHook.Unhook> hooks = new ArrayList<>();
        private volatile boolean active;
        private volatile boolean served;
        private volatile boolean healthy = true;

        private Installation(Sink sink, Class<?> pointerApi, Class<?> serviceType,
                Class<?> internalType, Class<?> localType, Class<?> localServices) throws Exception {
            this.sink = sink;
            this.internalType = internalType;
            officialController = field(serviceType, "mLaserPointerController");
            pointerDisplayId = field(serviceType, "mPointerDisplayId");
            displayViewports = field(serviceType, "mDisplayViewports");
            isScreenOn = field(serviceType, "mIsScreenOn");
            serviceFromLocal = field(localType, "this$0");
            getLocalService = localServices.getDeclaredMethod("getService", Class.class);
            getLocalService.setAccessible(true);
            proxy = Proxy.newProxyInstance(pointerApi.getClassLoader(), new Class<?>[]{pointerApi},
                    (self, method, args) -> invoke(self, method, args));
        }

        private Object currentService() throws Exception {
            Object local = getLocalService.invoke(null, internalType);
            return local == null ? null : serviceFromLocal.get(local);
        }

        private void requireUnclaimed(Object service) throws Exception {
            if (service != null && officialController.get(service) != null)
                throw new IllegalStateException("Official laser controller already exists; restart system_server");
        }

        private void sync(Object service) {
            try {
                sink.displayId(pointerDisplayId.getInt(service));
                sink.displayViewports(viewports(displayViewports.get(service)));
                sink.screenState(isScreenOn.getBoolean(service));
            } catch (Throwable failure) {
                unhealthy("Initial laser display state unavailable", failure);
            }
        }

        private void unhealthy(String message, Throwable failure) {
            healthy = false;
            Log.e(TAG, message + "; restart required after native binding", failure);
        }

        private Object invoke(Object self, Method method, Object[] args) {
            String name = method.getName();
            if (method.getDeclaringClass() == Object.class) {
                if ("equals".equals(name)) return self == args[0];
                if ("hashCode".equals(name)) return System.identityHashCode(self);
                if ("toString".equals(name)) return "FocusPenLaserPointerProxy";
            }
            if (!active || !healthy) return "canShowPointer".equals(name) ? false : null;
            try {
                switch (name) {
                    case "setPosition": sink.setPosition((Float) args[0], (Float) args[1]); return null;
                    case "getPosition": sink.getPosition((float[]) args[0]); return null;
                    case "move": sink.move((Float) args[0], (Float) args[1]); return null;
                    case "fade": sink.fade((Integer) args[0]); return null;
                    case "unfade": sink.unfade((Integer) args[0]); return null;
                    case "resetPosition": sink.resetPosition(); return null;
                    case "canShowPointer": return sink.canShowPointer();
                    case "setDisplayId": sink.displayId((Integer) args[0]); return null;
                    case "setDisplayViewPort": sink.displayViewports(viewports(args[0])); return null;
                    default: throw new AbstractMethodError(name);
                }
            } catch (Throwable failure) {
                unhealthy("Laser callback failed: " + name, failure);
                return "canShowPointer".equals(name) ? false : null;
            }
        }

        private synchronized void claim(XC_MethodHook.MethodHookParam p) {
            if (!active) return;
            try {
                if (officialController.get(p.thisObject) != null) {
                    active = false;
                    Log.e(TAG, "Official laser controller appeared before proxy binding; bridge disabled");
                    return;
                }
                if (!served) {
                    served = true;
                    sync(p.thisObject);
                }
                p.setResult(proxy);
            } catch (Throwable failure) {
                unhealthy("Cannot bind laser proxy", failure);
                // Preserve a valid interface object for native even if state inspection failed.
                served = true;
                p.setResult(proxy);
            }
        }

        private synchronized void update(Object service, int kind) {
            if (!active || !served || !healthy) return;
            try {
                if (kind == 0) sink.displayId(pointerDisplayId.getInt(service));
                else if (kind == 1) sink.displayViewports(viewports(displayViewports.get(service)));
                else sink.screenState(isScreenOn.getBoolean(service));
            } catch (Throwable failure) {
                unhealthy("Laser display state update failed", failure);
            }
        }

        @Override public synchronized void close() {
            if (served) throw new IllegalStateException("Native may retain the proxy; restart system_server to remove it");
            active = false;
            for (XC_MethodHook.Unhook hook : hooks) try { hook.unhook(); } catch (Throwable ignored) { }
            hooks.clear();
            if (installed == this) installed = null;
        }
    }

    static synchronized Installation install(ClassLoader loader, Sink sink) throws Exception {
        if (sink == null) throw new IllegalArgumentException("sink");
        if (installed != null) throw new IllegalStateException("Laser bridge already installed");
        if (!ROM.equals(android.os.Build.FINGERPRINT)) throw new IllegalStateException("Unsupported ROM");
        Class<?> service = loader.loadClass("com.android.server.input.MiuiInputManagerService");
        Class<?> internal = loader.loadClass("com.android.server.input.MiuiInputManagerInternal");
        Class<?> local = loader.loadClass("com.android.server.input.MiuiInputManagerService$LocalService");
        Class<?> localServices = loader.loadClass("com.android.server.LocalServices");
        Class<?> pointerApi = loader.loadClass("com.miui.server.input.stylus.laser.PointerControllerInterface");
        Class<?> laser = loader.loadClass("com.miui.server.input.stylus.laser.LaserPointerController");
        Class<?> stylus = loader.loadClass("com.miui.server.input.stylus.MiuiStylusShortcutManager");

        require(pointerApi, "setPosition", void.class, float.class, float.class);
        require(pointerApi, "getPosition", void.class, float[].class);
        require(pointerApi, "move", void.class, float.class, float.class);
        require(pointerApi, "fade", void.class, int.class);
        require(pointerApi, "unfade", void.class, int.class);
        require(pointerApi, "resetPosition", void.class);
        require(pointerApi, "canShowPointer", boolean.class);
        require(pointerApi, "setDisplayId", void.class, int.class);
        require(pointerApi, "setDisplayViewPort", void.class, List.class);
        if (pointerApi.getDeclaredMethods().length != 9 || !pointerApi.isInterface())
            throw new NoSuchMethodException("Unexpected pointer interface");
        require(service, "obtainLaserPointerController", pointerApi);
        require(local, "obtainLaserPointerController", pointerApi);
        require(service, "updatePointerDisplayId", void.class, int.class);
        require(service, "updateDisplayViewport", void.class, List.class);
        require(service, "setScreenState", void.class, boolean.class);
        require(stylus, "shouldInterceptKey", boolean.class, KeyEvent.class);
        require(stylus, "interceptLaserKey", boolean.class, KeyEvent.class);
        require(stylus, "interceptP81CLaserKey", boolean.class, KeyEvent.class);
        require(stylus, "onVirtualLaserDoubleClickEvent", int.class, KeyEvent.class);
        require(stylus, "onVirtualLaserSettingsChanged", void.class, int.class);
        if (field(service, "mLaserPointerController").getType() != laser
                || field(service, "mPointerDisplayId").getType() != int.class
                || field(service, "mDisplayViewports").getType() != List.class
                || field(service, "mIsScreenOn").getType() != boolean.class
                || field(local, "this$0").getType() != service)
            throw new NoSuchFieldException("Unexpected laser service fields");

        Installation result = new Installation(sink, pointerApi, service, internal, local, localServices);
        result.requireUnclaimed(result.currentService());
        synchronized (result) {
        try {
            result.hooks.add(XposedHelpers.findAndHookMethod(service, "updatePointerDisplayId", int.class, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) { result.update(p.thisObject, 0); }
            }));
            result.hooks.add(XposedHelpers.findAndHookMethod(service, "updateDisplayViewport", List.class, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) { result.update(p.thisObject, 1); }
            }));
            result.hooks.add(XposedHelpers.findAndHookMethod(service, "setScreenState", boolean.class, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) { result.update(p.thisObject, 2); }
            }));
            for (String name : new String[]{"shouldInterceptKey", "interceptLaserKey", "interceptP81CLaserKey"})
                result.hooks.add(XposedHelpers.findAndHookMethod(stylus, name, KeyEvent.class, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        if (result.active) p.setResult(false);
                    }
                }));
            result.hooks.add(XposedHelpers.findAndHookMethod(stylus, "onVirtualLaserDoubleClickEvent", KeyEvent.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (result.active) p.setResult(1);
                }
            }));
            result.hooks.add(XposedHelpers.findAndHookMethod(stylus, "onVirtualLaserSettingsChanged", int.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (result.active) p.setResult(null);
                }
            }));
            result.requireUnclaimed(result.currentService());
            result.active = true;
            result.hooks.add(XposedHelpers.findAndHookMethod(service, "obtainLaserPointerController", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) { result.claim(p); }
            }));
            installed = result;
            return result;
        } catch (Throwable failure) {
            result.close();
            throw new IllegalStateException("Laser bridge hooks rolled back", failure);
        }
        }
    }

    private static Field field(Class<?> type, String name) throws Exception {
        Field value = type.getDeclaredField(name);
        value.setAccessible(true);
        return value;
    }

    private static List<?> viewports(Object value) {
        return value == null ? List.of() : (List<?>) value;
    }

    private static void require(Class<?> type, String name, Class<?> result, Class<?>... args) throws Exception {
        if (type.getDeclaredMethod(name, args).getReturnType() != result)
            throw new NoSuchMethodException("Unexpected signature: " + type.getName() + "." + name);
    }
}
