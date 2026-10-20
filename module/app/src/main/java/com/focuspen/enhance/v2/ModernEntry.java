package com.focuspen.enhance.v2;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam;
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam;

/**
 * Static-scope bootstrap. The feature hooks remain in the legacy helper classes because the
 * target ROM is currently implemented against the legacy Xposed reflection API; this adapter
 * supplies the modern lifecycle entry required for an immutable scope declaration.
 */
public final class ModernEntry extends XposedModule {
    private String processName = "";

    @Override public void onModuleLoaded(ModuleLoadedParam param) {
        processName = param.getProcessName();
    }

    @Override public void onSystemServerStarting(SystemServerStartingParam param) {
        XposedEntry.installSystemServer(param.getClassLoader());
    }

    @Override public void onPackageReady(PackageReadyParam param) {
        String process = processName;
        if ("com.miui.securitycore".equals(process) || "com.android.systemui".equals(process)
                || "com.xiaomi.touchservice".equals(process)) {
            XposedEntry.installHostBootstrap(param.getClassLoader(), process);
        }
    }
}
