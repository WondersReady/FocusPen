package com.focuspen.enhance.v2;

import android.app.Application;
import android.content.Context;
import android.util.Log;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

/** Process-isolated bootstrap. Each feature must opt in through a validated snapshot. */
public final class XposedEntry implements IXposedHookLoadPackage {
    static final String TAG = "FocusPen";
    static final String VERSION = BuildConfig.VERSION_NAME;
    private static ConfigClient client;
    private static boolean bootstrapScheduled, hostAttempted;
    @Override public void handleLoadPackage(LoadPackageParam p) {
        String process = p.processName;
        if ("android".equals(p.packageName))
            XposedBridge.log("FocusPen android entry process=" + process + " version=" + VERSION);
        if ("android".equals(p.packageName) && "android".equals(process)) {
            installSystemServer(p.classLoader);
            return;
        }
        if (!"com.miui.securitycore".equals(process) && !"com.android.systemui".equals(process)
                && !"com.xiaomi.touchservice".equals(process)) return;
        installHostBootstrap(p.classLoader, process);
    }

    /** Entry used by the modern API adapter when this APK is loaded with static scope. */
    static void installSystemServer(ClassLoader classLoader) {
        try { PenKeyHost.install(classLoader); }
        catch (Throwable e) {
            Log.e(TAG, "Cannot install key arbitration; native retained", e);
            XposedBridge.log("FocusPen key install failed");
            XposedBridge.log(e);
        }
    }

    /**
     * Hosts need an Application context, so install the same attach hook from either
     * the legacy callback or the modern package lifecycle callback.
     */
    static void installHostBootstrap(ClassLoader classLoader, String process) {
        try {
            XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (bootstrapScheduled) return;
                    try {
                        Application app = (Application) param.thisObject;
                        if (!process.equals(app.getPackageName())) return;
                        bootstrapScheduled = true;
                        // LoadedApk changes this context's outer identity after attach returns.
                        Context attachedContext = (Context) param.args[0];
                        UnlockGate.run(attachedContext, () -> startHost(app, classLoader, process));
                    } catch (Throwable e) { Log.e(TAG, "Host initialization failed: " + process, e); }
                }
            });
        } catch (Throwable e) { Log.e(TAG, "Cannot install host bootstrap: " + process, e); }
    }
    private static void startHost(Application app, ClassLoader cl, String process) {
        if (hostAttempted) return;
        // A partially constructed host must not install another set of hooks or worker threads.
        // Initialization failure retains native handling until the process is restarted.
        hostAttempted = true;
        try {
            ConfigClient candidate = new ConfigClient(app);
            if ("com.miui.securitycore".equals(process)) SettingsHooks.install(app, cl, candidate);
            if ("com.android.systemui".equals(process)) new RingHost(app, cl, candidate);
            if ("com.xiaomi.touchservice".equals(process)) new PenMotionHost(app, candidate);
            client = candidate;
            Log.i(TAG, "host ready=" + process + " version=" + VERSION);
            XposedBridge.log("FocusPen host ready=" + process + " version=" + VERSION);
        } catch (Throwable e) {
            Log.e(TAG, "Host initialization failed: " + process, e);
            XposedBridge.log("FocusPen host failed=" + process); XposedBridge.log(e);
        }
    }
}
