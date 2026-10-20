package com.focuspen.enhance.v2;

import android.util.Log;

import java.lang.reflect.Method;

/**
 * 系统 API 调用器：在**系统应用进程内**（`com.xiaomi.touchservice`，uid 1000 / FLAG_SYSTEM）
 * 调用 MIUI 框架的笔能力接口。
 *
 * <h3>为什么必须在这里调用</h3>
 * {@code miui.hardware.input.MiuiInputManager} 的客户端方法大部分没有鉴权，但
 * system_server 里的 {@code MiuiInputManagerService} 会拒绝非系统应用（实测）：
 * <pre>
 * E/MiuiInputManagerService: Not support normal application enable stylus posture
 * E/MiuiInputManagerService: Not support normal application enable stylus touch film
 * </pre>
 * 而在 touchservice 进程内，调用方 ApplicationInfo 带 FLAG_SYSTEM 且 uid=1000，
 * 因此能通过校验。这是本项目**不 Hook system_server** 的技术根据。
 *
 * <h3>设计约束</h3>
 * 本类**只做反射调用，不持有任何状态**，且每个方法都吞掉所有 Throwable ——
 * 因为一旦异常逃逸到 touchservice 主线程，会导致官方笔功能崩溃（红线：不能影响原版行为）。
 */
final class SystemStylusApi {

    private static final String TAG = "FocusPenApi";
    private static final String CLS_INPUT_MANAGER = "miui.hardware.input.MiuiInputManager";

    private static volatile Object sInstance;
    private static volatile boolean sInitTried;
    private static volatile boolean sAvailable;

    private SystemStylusApi() { }

    /** 幂等初始化。返回 MiuiInputManager 实例或 null。 */
    static synchronized Object getInstance() {
        if (sInitTried) {
            return sInstance;
        }
        sInitTried = true;
        try {
            Class<?> c = Class.forName(CLS_INPUT_MANAGER);
            Method m = c.getMethod("getInstance");
            sInstance = m.invoke(null);
            sAvailable = (sInstance != null);
            Log.i(TAG, "MiuiInputManager available=" + sAvailable + " instance=" + sInstance);
        } catch (Throwable t) {
            Log.w(TAG, "MiuiInputManager unavailable: " + t);
            sInstance = null;
            sAvailable = false;
        }
        return sInstance;
    }

    static boolean isAvailable() {
        return getInstance() != null;
    }

    /**
     * 笔姿态开关（F1 的数据源开关）。
     * 对应 {@code PencilPostureController} 内部的 {@code ITouchFeature.setTouchMode(0, 20036, ...)} 语义。
     *
     * @param appPackage 目标应用包名；传 null 时用当前进程包名
     * @param enable     true 开启
     * @return 调用是否真的发出去（不代表服务端接受）
     */
    static boolean setStylusPosture(String appPackage, boolean enable) {
        Object inst = getInstance();
        if (inst == null) {
            Log.w(TAG, "setStylusPosture skipped: no MiuiInputManager");
            return false;
        }
        String pkg = (appPackage != null) ? appPackage : currentPackage();
        try {
            Method m = inst.getClass().getMethod("enableStylusPostureForApp", String.class, boolean.class);
            m.invoke(inst, pkg, enable);
            Log.i(TAG, "setStylusPosture pkg=" + pkg + " enable=" + enable + " invoked");
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "setStylusPosture failed pkg=" + pkg + " enable=" + enable, t);
            return false;
        }
    }

    /** 书写膜开关（F5 相关，官方沿此路径启用笔迹图层）。 */
    static boolean setStylusTouchFilm(String appPackage, boolean enable) {
        Object inst = getInstance();
        if (inst == null) {
            return false;
        }
        String pkg = (appPackage != null) ? appPackage : currentPackage();
        try {
            Method m = inst.getClass().getMethod("enableStylusTouchFilmForApp", String.class, boolean.class);
            m.invoke(inst, pkg, enable);
            Log.i(TAG, "setStylusTouchFilm pkg=" + pkg + " enable=" + enable + " invoked");
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "setStylusTouchFilm failed pkg=" + pkg, t);
            return false;
        }
    }

    /** 虚拟激光笔（F1 的降级表现形态之一，仅用于能力探测）。 */
    static boolean setVirtualLaser(boolean on) {
        Object inst = getInstance();
        if (inst == null) {
            return false;
        }
        try {
            Method m = inst.getClass().getMethod("setStylusVirtualLaserStatus", boolean.class);
            m.invoke(inst, on);
            Log.i(TAG, "setVirtualLaser on=" + on + " invoked");
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "setVirtualLaser failed", t);
            return false;
        }
    }

    /** 隐藏系统鼠标指针（解决"笔悬停被映射为鼠标指针"的叠加冲突）。 */
    static boolean hideCursor() {
        Object inst = getInstance();
        if (inst == null) {
            return false;
        }
        try {
            Method m = inst.getClass().getMethod("hideCursor");
            Object r = m.invoke(inst);
            Log.i(TAG, "hideCursor result=" + r);
            return Boolean.TRUE.equals(r);
        } catch (Throwable t) {
            Log.e(TAG, "hideCursor failed", t);
            return false;
        }
    }

    /** 手写是否被系统启用（用于 F5 的前置判断）。 */
    static boolean isHandwritingEnabled() {
        Object inst = getInstance();
        if (inst == null) {
            return false;
        }
        try {
            Method m = inst.getClass().getMethod("isHandwritingEnabled");
            Object r = m.invoke(inst);
            Log.i(TAG, "isHandwritingEnabled=" + r);
            return Boolean.TRUE.equals(r);
        } catch (Throwable t) {
            Log.w(TAG, "isHandwritingEnabled failed", t);
            return false;
        }
    }

    private static String currentPackage() {
        try {
            // 避免依赖 Context；在系统进程里 ActivityThread.currentPackageName() 可用
            Class<?> at = Class.forName("android.app.ActivityThread");
            Method m = at.getMethod("currentPackageName");
            Object p = m.invoke(null);
            if (p != null) {
                return p.toString();
            }
        } catch (Throwable ignored) { }
        return "com.xiaomi.touchservice";
    }
}
