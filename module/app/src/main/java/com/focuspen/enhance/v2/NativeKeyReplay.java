package com.focuspen.enhance.v2;

import android.os.IBinder;
import android.os.SystemClock;
import android.view.InputEvent;
import android.view.KeyEvent;
import android.view.MotionEvent;
import java.lang.reflect.Method;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.XC_MethodHook;

/** Re-dispatch only already-arbitrated keys, preserving the physical pen's device identity. */
final class NativeKeyReplay {
    private static final int FILTERED = 0x04000000, NO_REPEAT = 0x08000000, PASS_TO_USER = 0x40000000;
    private static Class<?> serviceClass, nativeClass;
    private static final ApplicationKeyDispatch applicationKeys = new ApplicationKeyDispatch();
    private static boolean dispatchHookInstalled;
    private final Object nativeInput;
    private final Method inject;

    static synchronized void verify(ClassLoader loader) throws Exception {
        Class<?> service = loader.loadClass("com.android.server.input.InputManagerService");
        Class<?> nativeService = loader.loadClass("com.android.server.input.NativeInputManagerService");
        if (!nativeService.isAssignableFrom(service.getDeclaredField("mNative").getType())
                || injectionMethod(nativeService).getReturnType() != int.class)
            throw new NoSuchMethodException("Unexpected native input replay signature");
        serviceClass = service;
        nativeClass = nativeService;
        if (service.getDeclaredMethod("interceptKeyBeforeDispatching", IBinder.class, KeyEvent.class, int.class).getReturnType() != long.class)
            throw new NoSuchMethodException("Unexpected key dispatch policy signature");
        if (!dispatchHookInstalled) {
            XposedHelpers.findAndHookMethod(service, "interceptKeyBeforeDispatching", IBinder.class,
                    KeyEvent.class, int.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (((Integer) p.args[2] & FILTERED) == 0) return;
                    ApplicationKeyDispatch.Decision decision = applicationKeys.beforeDispatch(
                            identity((KeyEvent) p.args[1]), p.args[0],
                            ((Integer) p.args[2] & FILTERED) != 0, SystemClock.uptimeMillis());
                    if (decision == ApplicationKeyDispatch.Decision.PASS) p.setResult(0L);
                    else if (decision == ApplicationKeyDispatch.Decision.DROP) p.setResult(-1L);
                }
            });
            dispatchHookInstalled = true;
        }
    }
    NativeKeyReplay() throws Exception {
        Class<?> manager = Class.forName("android.os.ServiceManager");
        IBinder binder = (IBinder) XposedHelpers.callStaticMethod(manager, "getService", "input");
        Object service = XposedHelpers.callStaticMethod(Class.forName("android.hardware.input.IInputManager$Stub"), "asInterface", binder);
        if (serviceClass == null || !serviceClass.isInstance(service))
            throw new IllegalStateException("Input replay requires the local system service");
        nativeInput = XposedHelpers.getObjectField(service, "mNative");
        inject = injectionMethod(nativeClass);
        inject.setAccessible(true);
    }
    void dispatchToApplication(KeyEvent event, Object window) throws Exception {
        if (window == null) throw new IllegalArgumentException("Application window required");
        Object service = XposedHelpers.getObjectField(window, "mWmService");
        Object token;
        int uid;
        synchronized (XposedHelpers.getObjectField(service, "mGlobalLock")) {
            token = XposedHelpers.getObjectField(window, "mInputChannelToken");
            uid = (Integer) XposedHelpers.callMethod(window, "getOwningUid");
        }
        if (!(token instanceof IBinder) || uid < 0)
            throw new IllegalStateException("Application input channel unavailable");
        ApplicationKeyDispatch.Permit permit = applicationKeys.authorize(identity(event), token, SystemClock.uptimeMillis());
        try {
            // FILTERED skips queue policy, while the permit skips dispatch policy only for
            // this event and this exact input channel. UID targeting adds a second boundary.
            int result = (Integer) inject.invoke(nativeInput, event, true, uid, 1, 1000,
                    FILTERED | NO_REPEAT | PASS_TO_USER, false, null, -1);
            if (result != 0) throw new IllegalStateException("Application key rejected: " + result);
        } catch (Exception failure) {
            applicationKeys.reject(permit);
            throw failure;
        }
    }
    private static ApplicationKeyDispatch.Identity identity(KeyEvent event) {
        return new ApplicationKeyDispatch.Identity(event.getDeviceId(), event.getSource(), (Integer) XposedHelpers.callMethod(event, "getDisplayId"),
                event.getKeyCode(), event.getScanCode(), event.getAction(), event.getRepeatCount(),
                event.getMetaState(), event.getDownTime(), event.getEventTime());
    }
    private static Method injectionMethod(Class<?> type) throws NoSuchMethodException {
        return type.getDeclaredMethod("injectInputEvent", InputEvent.class, boolean.class, int.class,
                int.class, int.class, int.class, boolean.class, String[].class, int.class);
    }
    void dispatchPointer(MotionEvent event, int uid) throws Exception {
        if (uid < 0) throw new IllegalArgumentException("An owning UID is required");
        // New pointer input goes through normal policy. Never retry untargeted on mismatch.
        int result = (Integer) inject.invoke(nativeInput,event,true,uid,1,100,NO_REPEAT,false,null,-1);
        if (result != 0) throw new IllegalStateException("Targeted pointer input rejected: " + result);
    }
}
