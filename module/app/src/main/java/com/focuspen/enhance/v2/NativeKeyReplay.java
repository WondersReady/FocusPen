package com.focuspen.enhance.v2;

import android.os.IBinder;
import android.view.InputEvent;
import android.view.KeyEvent;
import android.view.MotionEvent;
import java.lang.reflect.Method;
import de.robv.android.xposed.XposedHelpers;

/** Re-dispatch only already-arbitrated keys, preserving the physical pen's device identity. */
final class NativeKeyReplay {
    private static final int FILTERED = 0x04000000, NO_REPEAT = 0x08000000, PASS_TO_USER = 0x40000000;
    private static Class<?> serviceClass, nativeClass;
    private final Object nativeInput;
    private final Method inject;

    static void verify(ClassLoader loader) throws Exception {
        Class<?> service = loader.loadClass("com.android.server.input.InputManagerService");
        Class<?> nativeService = loader.loadClass("com.android.server.input.NativeInputManagerService");
        if (!nativeService.isAssignableFrom(service.getDeclaredField("mNative").getType())
                || injectionMethod(nativeService).getReturnType() != int.class)
            throw new NoSuchMethodException("Unexpected native input replay signature");
        serviceClass = service;
        nativeClass = nativeService;
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
    void dispatch(KeyEvent event) throws Exception {
        // The caller has arbitrated this captured key. FILTERED bypasses the ROM shortcut
        // policy and preserves deviceId, source, scanCode and timestamps in InputDispatcher.
        int result = (Integer) inject.invoke(nativeInput, event, false, -1, 1, 1000,
                FILTERED | NO_REPEAT | PASS_TO_USER, false, null, -1);
        if (result != 0) throw new IllegalStateException("Native input replay rejected: " + result);
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
