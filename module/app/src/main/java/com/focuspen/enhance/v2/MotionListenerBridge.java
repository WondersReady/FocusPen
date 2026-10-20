package com.focuspen.enhance.v2;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.MotionEvent;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * 全局笔事件监听（F1 光圈 / F2 按键的真实数据源）。
 *
 * <h3>机制</h3>
 * 通过 {@code miui.hardware.input.MiuiInputManager.registerMiuiMotionEventListener(
 * MiuiMotionEventListener, Handler)} 注册监听器。这是 MIUI 官方暴露的**全局 MotionEvent 观测通道**，
 * 纯 Binder 回调，不是 Hook、不改任何系统代码、不在 system_server 里跑我们的逻辑。
 *
 * <h3>类型事实（已反编译 + 反射双重确认）</h3>
 * <pre>
 * // miui/hardware/input/MiuiInputManager.java:90
 * public interface MiuiMotionEventListener {
 *     void onMotionEvent(MotionEvent motionEvent);
 * }
 * </pre>
 * 它是 **interface**，因此可以用 {@link Proxy} 动态实现，无需编译期依赖 MIUI 私有类。
 *
 * <p>⚠️ 踩过的坑：早先实测报 {@code "MiuiInputManager$MotionEventListener is not an interface"}，
 * 原因是按 {@code getSimpleName().contains("MotionEventListener")} 遍历内部类时，
 * 先匹配到了同名的**私有实现类** {@code MiuiInputManager$MotionEventListener}
 * （第 1052 行 {@code private final class MotionEventListener extends IMiuiMotionEventListener.Stub}）
 * 之外的其它类。正确做法是**按 {@code isInterface()} 过滤**，并用全限定名精确查找。</p>
 *
 * <h3>为什么必须在 touchservice 进程里注册</h3>
 * {@code registerMiuiMotionEventListener} 客户端侧有 {@code isLegalPerPermission()}
 * （检查 {@code ApplicationInfo.isSystemApp()}），普通应用实测被拒。
 * touchservice 是 uid 1000 / FLAG_SYSTEM，通过。
 */
final class MotionListenerBridge {

    private static final String TAG = "FocusPenMotion";
    private static final String CLS_INPUT_MANAGER = "miui.hardware.input.MiuiInputManager";
    private static final String CLS_LISTENER = "miui.hardware.input.MiuiInputManager$MiuiMotionEventListener";

    private static volatile boolean sRegistered;
    private static volatile int sEventCount;
    private static volatile int sLastAction = -1;
    private static volatile int sLastToolType = -1;
    private static volatile float sLastX, sLastY, sLastPressure;
    private static volatile long sDownCount, sMoveCount, sUpCount, sHoverCount;
    private static volatile Object sListenerProxy;
    private static Object sManager;
    private static Class<?> sListenerClass;

    private MotionListenerBridge() { }

    /** 事件回调接口，由 F1/F2 的实现注入。 */
    interface Sink {
        void onStylusMotion(MotionEvent e);
    }

    private static volatile Sink sSink;

    static void setSink(Sink sink) {
        sSink = sink;
    }

    /**
     * 注册全局 MotionEvent 监听。幂等。
     *
     * @return true 表示客户端注册调用已返回；收到实际事件须单独验证。
     */
    static synchronized boolean register() {
        if (sRegistered) {
            return true;
        }
        try {
            Class<?> imCls = Class.forName(CLS_INPUT_MANAGER);
            Object im = imCls.getMethod("getInstance").invoke(null);
            if (im == null) {
                Log.w(TAG, "register: MiuiInputManager unavailable");
                return false;
            }

            // 精确按接口名查找；退化为 isInterface 过滤（避免匹配到私有实现类）
            Class<?> listenerIface = null;
            try {
                listenerIface = Class.forName(CLS_LISTENER);
            } catch (Throwable ignored) { }
            if (listenerIface == null || !listenerIface.isInterface()) {
                for (Class<?> inner : imCls.getDeclaredClasses()) {
                    if (inner.isInterface() && inner.getSimpleName().contains("MotionEventListener")) {
                        listenerIface = inner;
                        break;
                    }
                }
            }
            if (listenerIface == null || !listenerIface.isInterface()) {
                Log.w(TAG, "register: MiuiMotionEventListener interface not found");
                return false;
            }
            Log.i(TAG, "using listener interface = " + listenerIface.getName());

            // 看一眼接口方法签名，确认是 onMotionEvent(MotionEvent)
            for (Method m : listenerIface.getDeclaredMethods()) {
                Log.i(TAG, "  listener method: " + m);
            }

            Object proxy = Proxy.newProxyInstance(
                    listenerIface.getClassLoader(),
                    new Class<?>[]{listenerIface},
                    new ListenerHandler());

            Handler handler = new Handler(Looper.getMainLooper());
            Method reg = imCls.getMethod("registerMiuiMotionEventListener", listenerIface, Handler.class);
            reg.invoke(im, proxy, handler);

            sManager = im; sListenerClass = listenerIface;
            sListenerProxy = proxy;
            sRegistered = true;
            Log.i(TAG, "Motion listener registration call completed");
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "register failed", t);
            return false;
        }
    }

    static synchronized void unregister() {
        if (!sRegistered) return;
        try {
            sManager.getClass().getMethod("unregisterMiuiMotionEventListener", sListenerClass)
                    .invoke(sManager, sListenerProxy);
            sRegistered = false; sListenerProxy = null; sManager = null; sListenerClass = null;
        } catch (Throwable failure) { Log.w(TAG, "Motion listener cleanup failed", failure); }
    }

    /** 动态代理处理器：把 Binder 回调转成我们的计数 + Sink。 */
    private static final class ListenerHandler implements InvocationHandler {
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            try {
                String name = method.getName();
                if ("onMotionEvent".equals(name) && args != null && args.length == 1
                        && args[0] instanceof MotionEvent) {
                    MotionListenerBridge.dispatch((MotionEvent) args[0]);
                    return null;
                }
                // Object 方法兜底
                if ("toString".equals(name)) return "FocusPenMotionListener";
                if ("hashCode".equals(name)) return System.identityHashCode(proxy);
                if ("equals".equals(name)) return proxy == args[0];
                Log.d(TAG, "listener callback (ignored): " + name);
                return null;
            } catch (Throwable t) {
                // 绝不向外抛：会打断 MIUI 输入分发链
                Log.e(TAG, "listener handler failed", t);
                return null;
            }
        }
    }

    /** 统一事件入口：计数 + 打日志 + 分发。 */
    static void dispatch(MotionEvent e) {
        try {
            if (e == null) {
                return;
            }
            int action = e.getActionMasked();
            int tool = e.getToolType(0);
            float x = e.getX();
            float y = e.getY();
            float p = e.getPressure();

            sEventCount++;
            sLastAction = action;
            sLastToolType = tool;
            sLastX = x; sLastY = y; sLastPressure = p;

            switch (action) {
                case MotionEvent.ACTION_DOWN: sDownCount++; break;
                case MotionEvent.ACTION_MOVE: sMoveCount++; break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: sUpCount++; break;
                case MotionEvent.ACTION_HOVER_MOVE: sHoverCount++; break;
                default: break;
            }

            Sink s = sSink;
            if (s != null) {
                s.onStylusMotion(e);
            }
        } catch (Throwable t) {
            Log.e(TAG, "dispatch failed", t);
        }
    }

    static boolean isRegistered() { return sRegistered; }
    static int getEventCount() { return sEventCount; }
    static int getLastAction() { return sLastAction; }
    static int getLastToolType() { return sLastToolType; }
    static float getLastX() { return sLastX; }
    static float getLastY() { return sLastY; }
    static float getLastPressure() { return sLastPressure; }
    static long getDownCount() { return sDownCount; }
    static long getMoveCount() { return sMoveCount; }
    static long getUpCount() { return sUpCount; }
    static long getHoverCount() { return sHoverCount; }

    /** 打一行汇总，供自动化断言读取。 */
    static void logSummary() {
        Log.i(TAG, "summary count=" + sEventCount
                + " down=" + sDownCount + " move=" + sMoveCount
                + " up=" + sUpCount + " hover=" + sHoverCount
                + " lastAction=" + sLastAction + " lastTool=" + sLastToolType
                + " lastX=" + round1(sLastX) + " lastY=" + round1(sLastY)
                + " lastP=" + round3(sLastPressure));
    }

    private static double round1(float v) {
        return Math.round(v * 10.0) / 10.0;
    }

    private static double round3(float v) {
        return Math.round(v * 1000.0) / 1000.0;
    }
}
