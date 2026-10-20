package com.focuspen.enhance.v2;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.util.Log;
import java.util.ArrayList;
import java.util.List;
import java.lang.reflect.Method;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.XposedBridge;

/** Narrow policy hook for the verified ROM. All actions and replay execute off the input callback. */
final class PenKeyHost {
    private static final String FINGERPRINT = "Xiaomi/piano/piano:17/CP2A.260605.016/OS4.0.2.0.XPYCNXM:user/release-keys";
    private static volatile PenKeyHost instance;
    private static boolean initializationScheduled;
    private static Method stylusType;
    private static Class<?> stylusManagerType;
    private final KeyArbiter arbiter = new KeyArbiter();
    private final NativeShortcutGate nativeGate = new NativeShortcutGate();
    private final ProGestureGate proGate = new ProGestureGate();
    private final KeyEvent[] down = new KeyEvent[4], tapDown = new KeyEvent[4], tapUp = new KeyEvent[4];
    // Worker-thread state: only a DOWN actually dispatched by the replay queue owns repeats/UP.
    private final KeyEvent[] replayDown = new KeyEvent[4];
    private final boolean[] replayToApp = new boolean[4];
    private final Context context;
    private volatile Object focusedWindow;
    private volatile long focusEpoch;
    private final KeyTarget[] targets = new KeyTarget[4];
    private final ThreadLocal<Boolean> replaying = ThreadLocal.withInitial(() -> false);
    private final NativeKeyReplay replay;
    private final PowerManager power;
    private volatile Object policyInstance;
    private final ConfigClient config;
    private final RuntimeBus bus;
    private final Handler handler;
    private final WindowDirectionHost directionHost;
    private final ProInput proInput;
    private final Object lock = new Object();
    private volatile boolean uiReady;
    private volatile long uiSeen;
    private volatile boolean replayHealthy = true;
    private volatile boolean keysInteractive;
    private volatile boolean keyguardState;
    private volatile String foreground = "";
    private long configRevision = -1;
    private int device = -1;
    private final Runnable timer = () -> {
        synchronized (lock) {
            refreshInputConfig();
            if (!ownsKeys()) { arbiter.cancel(); return; }
            for (KeyArbiter.Effect effect : arbiter.tick(SystemClock.uptimeMillis())) deliver(effect, null, true);
            schedule();
        }
    };

    static void install(ClassLoader cl) throws Exception {
        if (!FINGERPRINT.equals(android.os.Build.FINGERPRINT)) { Log.w("FocusPenKeys", "Unsupported ROM; native keys retained"); return; }
        Class<?> stylus = cl.loadClass("com.miui.server.input.stylus.MiuiStylusShortcutManager");
        stylusManagerType=stylus;
        if (stylus.getDeclaredMethod("shouldInterceptKey", KeyEvent.class).getReturnType() != boolean.class
                || !Handler.class.isAssignableFrom(stylus.getDeclaredField("mHandler").getType())
                || stylus.getDeclaredField("mQuickNoteKeyFunctionTriggered").getType() != boolean.class
                || stylus.getDeclaredField("mScreenShotKeyFunctionTriggered").getType() != boolean.class)
            throw new NoSuchMethodException("Unexpected native stylus policy signature");
        stylusType = InputDevice.class.getDeclaredMethod("isXiaomiStylus");
        stylusType.setAccessible(true);
        NativeKeyReplay.verify(cl);
        OfficialPenActions.initialize(cl);
        Class<?> policy = cl.loadClass("com.android.server.policy.BaseMiuiPhoneWindowManager");
        if (policy.getDeclaredMethod("interceptKeyBeforeQueueingInternal", KeyEvent.class, int.class, boolean.class).getReturnType() != int.class)
            throw new NoSuchMethodException("Unexpected queue policy signature");
        List<XC_MethodHook.Unhook> hooks = new ArrayList<>();
        try {
        hooks.add(XposedHelpers.findAndHookMethod(cl.loadClass("com.android.server.policy.MiuiPhoneWindowManager"), "systemBooted", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (instance != null) return;
                try { initialize((Context) XposedHelpers.getObjectField(p.thisObject, "mContext")); }
                catch (Throwable failure) { XposedBridge.log("FocusPen boot initialization unavailable"); XposedBridge.log(failure); }
            }
        }));
        hooks.add(XposedHelpers.findAndHookMethod(stylus, "shouldInterceptKey", KeyEvent.class, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                PenKeyHost host = instance; if (host == null) return;
                KeyEvent event = (KeyEvent) p.args[0];
                int candidate = keyButton(event.getKeyCode()); if (candidate == 0 || candidate == 3) return;
                try {
                    ConfigSnapshot snapshot = host.config.get();
                    boolean block = host.nativeGate.observe(candidate, button(event) != 0, host.ownsKeys(),
                            host.resolved(snapshot.action(host.foreground,candidate,false), snapshot));
                    if (!block) return;
                    // Focus-key taps change the native laser mode. Keep that tap handler and guard
                    // only its long timer below, so a native short/custom long mapping still works.
                    if (candidate == 3) return;
                    // Replayed native taps still reach applications, but cannot start the ROM's long timer.
                    Handler nativeHandler = (Handler) XposedHelpers.getObjectField(p.thisObject, "mHandler");
                    if (candidate <= 2) {
                        nativeHandler.removeMessages(candidate);
                        XposedHelpers.setBooleanField(p.thisObject,
                                candidate == 1 ? "mQuickNoteKeyFunctionTriggered" : "mScreenShotKeyFunctionTriggered", false);
                    }
                    p.setResult(false);
                } catch (Throwable failure) { Log.w("FocusPenKeys", "Native timer guard unavailable", failure); }
            }
        }));
        for (String method : new String[]{"addStylusMaskWindow", "triggerQuickNoteKeyFunction", "triggerScreenShotKeyFunction"}) {
            if (stylus.getDeclaredMethod(method).getReturnType() != void.class)
                throw new NoSuchMethodException("Unexpected native action signature: " + method);
            int candidate = "triggerScreenShotKeyFunction".equals(method) ? 2 : 1;
            hooks.add(XposedHelpers.findAndHookMethod(stylus, method, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    PenKeyHost host = instance;
                    if (host != null && host.blocksNative(candidate)) p.setResult(null);
                }
            }));
        }
        Class<?> laser = cl.loadClass("com.miui.server.input.stylus.laser.LaserPointerController");
        if (laser.getDeclaredMethod("interceptLaserKey", KeyEvent.class).getReturnType() != boolean.class
                || laser.getDeclaredMethod("onLaserKeyLongPressed").getReturnType() != void.class
                || !Handler.class.isAssignableFrom(laser.getDeclaredField("mHandler").getType()))
            throw new NoSuchMethodException("Unexpected native laser policy signature");
        hooks.add(XposedHelpers.findAndHookMethod(laser, "interceptLaserKey", KeyEvent.class, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                PenKeyHost host = instance;
                if (host == null || !host.blocksNative(3)) return;
                try { ((Handler) XposedHelpers.getObjectField(p.thisObject, "mHandler")).removeMessages(5); }
                catch (Throwable failure) { Log.w("FocusPenKeys", "Native laser timer cleanup unavailable", failure); }
            }
        }));
        hooks.add(XposedHelpers.findAndHookMethod(laser, "onLaserKeyLongPressed", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                PenKeyHost host = instance;
                if (host != null && host.blocksNative(3)) p.setResult(null);
            }
        }));
        hooks.add(XposedHelpers.findAndHookMethod(policy, "interceptKeyBeforeQueueingInternal", KeyEvent.class, int.class, boolean.class, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                PenKeyHost host = instance; if (host == null) return;
                if (host.replaying.get()) return;
                host.policyInstance = p.thisObject;
                KeyEvent event = (KeyEvent) p.args[0];
                if (event.getKeyCode() != 93 && event.getKeyCode() != 92 && (event.getKeyCode() < 193 || event.getKeyCode() > 197)) return;
                try { if (host.key(event, (Boolean) p.args[2])) p.setResult(0); }
                catch (Throwable e) { host.replayHealthy = false; host.cancel(); Log.e("FocusPenKeys", "Arbitration failed; native fallback", e); }
            }
        }));
        hooks.add(XposedHelpers.findAndHookMethod(stylus, "onDefaultDisplayFocusChangedLw", cl.loadClass("com.android.server.policy.WindowManagerPolicy$WindowState"), new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (instance == null) {
                    try { initialize((Context) XposedHelpers.getObjectField(p.thisObject, "mContext")); }
                    catch (Throwable e) { Log.w("FocusPenKeys", "Deferred initialization unavailable", e); }
                }
                PenKeyHost host = instance; if (host == null) return;
                try {
                    String pkg = p.args[0] == null ? "" : (String) XposedHelpers.callMethod(p.args[0], "getOwningPackage");
                    host.focusChanged(p.args[0], pkg);
                } catch (Throwable e) { host.cancel(); }
            }
        }));
        hooks.add(XposedHelpers.findAndHookMethod(stylus, "updateScreenState", boolean.class, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (instance != null && !(Boolean) p.args[0]) {
                    instance.keysInteractive = false; instance.nativeGate.clear(); instance.cancel();
                }
            }
        }));
        hooks.add(XposedHelpers.findAndHookMethod(stylus, "triggerNoteWhenScreenOff", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                PenKeyHost host=instance; if(host==null) return;
                ConfigSnapshot cfg=host.config.get();
                if (!cfg.enabled()) return;
                p.setResult(null);
                if (cfg.flag(ConfigSpec.SCREEN_OFF)) host.handler.post(() -> {
                    ConfigSnapshot latest = host.config.get();
                    if (latest.enabled() && latest.revision == cfg.revision && latest.flag(ConfigSpec.SCREEN_OFF)
                            && !OfficialPenActions.screenOff(host.context, latest.text(ConfigSpec.SCREEN_OFF_TARGET))) host.busError();
                });
            }
        }));
        // Override the decision for this motion only; the user's official persisted switch is preserved.
        hooks.add(XposedHelpers.findAndHookMethod(stylus,"processMotionEventForQuickNote",android.view.MotionEvent.class,new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                PenKeyHost host=instance; if(host==null) return;
                ConfigSnapshot cfg=host.config.get();
                if(!cfg.enabled()) return;
                boolean allowed=cfg.flag(ConfigSpec.SCREEN_OFF);
                if(!allowed) { p.setResult(null); return; }
                p.setObjectExtra("focuspen.originalScreenOff",XposedHelpers.getBooleanField(p.thisObject,"mIsScreenOffQuickNoteOn"));
                XposedHelpers.setBooleanField(p.thisObject,"mIsScreenOffQuickNoteOn",true);
            }
            @Override protected void afterHookedMethod(MethodHookParam p) {
                Object old=p.getObjectExtra("focuspen.originalScreenOff");
                if(old instanceof Boolean) XposedHelpers.setBooleanField(p.thisObject,"mIsScreenOffQuickNoteOn",(Boolean)old);
            }
        }));
        XposedBridge.log("FocusPen key hooks installed nativeTimerGuard=true nativeActionGuard=true version=" + BuildConfig.VERSION_NAME);
        } catch (Throwable failure) {
            for (XC_MethodHook.Unhook hook : hooks) try { hook.unhook(); } catch (Throwable ignored) { }
            throw new IllegalStateException("Key hooks rolled back", failure);
        }
        installPro(cl);
        try { PenPresentation.install(cl); }
        catch (Throwable e) { Log.w("FocusPenKeys", "Presentation extension unavailable; official laser retained", e); }
    }

    private static void installPro(ClassLoader cl) {
        try {
            Class<?> pro=cl.loadClass("com.miui.server.input.stylus.MiuiStylusTouchFilmManager");
            if(pro.getDeclaredMethod("interceptKeyBeforeQueueing",KeyEvent.class,boolean.class).getReturnType()!=boolean.class)
                throw new NoSuchMethodException("Unexpected Pro gesture policy");
            for(String name:new String[]{"mIsGameMode","mIsTouchFilmStylusConnected","mCurrentSlideStatus","mCurrentDoubleTapStatus"})
                if(pro.getDeclaredField(name).getType()!=boolean.class)throw new NoSuchFieldException(name);
            for(String name:new String[]{"mCurrentPinchStatus","mCurrentVirtualLaserStatus"})
                if(pro.getDeclaredField(name).getType()!=int.class)throw new NoSuchFieldException(name);
            XposedHelpers.findAndHookMethod(pro,"interceptKeyBeforeQueueing",KeyEvent.class,boolean.class,new XC_MethodHook(){
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    PenKeyHost host=instance;
                    if(host==null||host.replaying.get())return;
                    try { if(host.proKey(p.thisObject,(KeyEvent)p.args[0],(Boolean)p.args[1]))p.setResult(true); }
                    catch(Throwable e){Log.e("FocusPenKeys","Pro gesture unavailable; official handling retained",e);}
                }
            });
            XposedBridge.log("FocusPen Pro gesture hook installed type=8 keys=194-197 version="+BuildConfig.VERSION_NAME);
        }catch(Throwable e){Log.w("FocusPenKeys","Pro policy unavailable; standard pen remains active",e);}
    }

    private boolean proKey(Object manager,KeyEvent e,boolean screenOn) throws Exception {
        InputDevice input=e.getDevice();
        int type=input==null?0:(Integer)stylusType.invoke(input);
        int slot=ProGestureGate.gesture(type,e.getKeyCode());
        if(slot<0)return false;
        synchronized(lock){
            updateLockState();
            keysInteractive=screenOn&&power.isInteractive();
            ConfigSnapshot s=refreshInputConfig();
            PenAction action=keyguardState ? (slot==0?PenAction.RING:PenAction.NATIVE)
                    : s.available?s.proAction(foreground,slot):PenAction.NATIVE;
            boolean hardware=slot==0?XposedHelpers.getIntField(manager,"mCurrentPinchStatus")!=0:
                    XposedHelpers.getBooleanField(manager,slot==1?"mCurrentDoubleTapStatus":"mCurrentSlideStatus");
            boolean connected=hardware&&ownsKeys()&&XposedHelpers.getBooleanField(manager,"mIsTouchFilmStylusConnected")&&!XposedHelpers.getBooleanField(manager,"mIsGameMode");
            KeyTarget proTarget=new KeyTarget(focusedWindow,focusEpoch,s.revision,foreground,s.direction(foreground,1),s.direction(foreground,2));
            if(slot>=2&&PenPresentation.colorKey(e,slot==2?-1:1,connected))return true;
            if(proInput.owns(e)||!keyguardState && (slot==1 || !PenPresentation.presenting())) {
                if(proInput.event(manager,e,slot,connected&&!keyguardState,s,proTarget))return true;
                if(slot==1 || slot>=2)return false;
            }
            if(PenPresentation.presenting()&&!keyguardState)return false;
            boolean eligible=ownsKeys()&&action!=PenAction.NATIVE&&hardware
                    &&XposedHelpers.getBooleanField(manager,"mIsTouchFilmStylusConnected")
                    &&!XposedHelpers.getBooleanField(manager,"mIsGameMode")
                    &&XposedHelpers.getIntField(manager,"mCurrentVirtualLaserStatus")==0;
            ProGestureGate.Decision decision=proGate.event(slot,e.getDeviceId(),e.getDownTime(),e.getAction(),
                    e.getRepeatCount(),e.isCanceled(),eligible);
            if(decision==ProGestureGate.Decision.PASS)return false;
            if(decision==ProGestureGate.Decision.DISPATCH){
                KeyTarget target=new KeyTarget(focusedWindow,focusEpoch,s.revision,foreground,s.direction(foreground,1),s.direction(foreground,2));
                // A disabled ring consumes its configured gesture without opening the official panel.
                if(action==PenAction.RING&&!s.flag(ConfigSpec.RING))action=PenAction.NONE;
                deliver(new KeyArbiter.Effect(KeyArbiter.Kind.ACTION,1,action),null,false,new KeyEvent(e),target);
                Log.i("FocusPenKeys","Pro gesture="+slot+" action="+action.id);
            }
            return true;
        }
    }

    private static synchronized void initialize(Context context) {
        if (instance != null || initializationScheduled) return;
        initializationScheduled = true;
        UnlockGate.run(context, () -> {
            try {
                instance = new PenKeyHost(context);
                XposedBridge.log("FocusPen key arbitration ready version=" + BuildConfig.VERSION_NAME);
            } catch (Throwable failure) {
                XposedBridge.log("FocusPen key initialization failed"); XposedBridge.log(failure);
                // Fail closed for this process; partial construction must not leak repeated workers.
            }
        });
    }

    private PenKeyHost(Context c) throws Exception {
        context=c;
        replay = new NativeKeyReplay();
        power = c.getSystemService(PowerManager.class);
        config = new ConfigClient(c);
        PenPresentation.bind(c, config);
        bus = new RuntimeBus(c, (event, b) -> {
            if ("officialAction".equals(event) && config.get().enabled()) {
                String id=b.getString("id", "");
                boolean locked=locked();
                if (locked != b.getBoolean("locked", false)) return;
                RingContent.Item item=RingContent.parse(config.get().text(ConfigSpec.CONTENT)).find(id);
                boolean allowed=locked ? OfficialPenActions.lockAction(id) : item!=null && item.enabled() && RingContent.isAction(id);
                if(allowed && !OfficialPenActions.run(c,id))
                    busError();
            }
            if ("uiState".equals(event)) {
                uiSeen = SystemClock.uptimeMillis(); uiReady = b.getBoolean("ready");
                synchronized (lock) { arbiter.ring(b.getBoolean("ring"), b.getBoolean("hold"), b.getInt("button", 1)); }
            }
        }, config::refresh);
        handler = bus.worker;
        new PenMouse(handler,replay,()->{ConfigSnapshot s=config.get();return new KeyTarget(focusedWindow,focusEpoch,s.revision,foreground,s.direction(foreground,1),s.direction(foreground,2));},t->current(t)&&power.isInteractive()&&!locked()&&presentationAllowed());
        directionHost = new WindowDirectionHost(handler,replay);
        proInput=new ProInput(handler,new ProInput.Host(){
            public boolean valid(KeyTarget target){return current(target)&&!locked();}
            public void single(KeyTarget target,List<KeyEvent> events){
                PenAction action=config.get().proAction(target.pkg(),0);
                if(action==PenAction.NONE){handler.post(()->{if(current(target)&&target.supportsDirection())for(KeyEvent e:events)replayKey(e,PenAction.NONE);});return;}
                deliver(new KeyArbiter.Effect(KeyArbiter.Kind.ACTION,1,action),null,false,events.isEmpty()?null:events.get(0),target);
            }
            public void nativeEvents(Object manager,List<KeyEvent> events){KeyTarget captured=new KeyTarget(focusedWindow,focusEpoch,config.get().revision,foreground,"none","none");handler.post(()->{if(!current(captured)||locked())return;try{replaying.set(true);for(KeyEvent event:events)XposedHelpers.callMethod(manager,"interceptKeyBeforeQueueing",event,power.isInteractive());}finally{replaying.remove();}});}
            public void action(String action,boolean release,KeyTarget target,KeyEvent event,long mouseSession){handler.post(()->proAction(action,release,target,event,mouseSession));}
        },lock);
        try { new SystemPenMotion(context,config,bus); }
        catch(Throwable failure) { Log.e("FocusPenMotion","Cannot initialize pointer observer",failure); }
        config.add(s -> {
            synchronized (lock) { refreshInputConfig(); }
        });
        Log.i("FocusPenKeys", "Verified ROM key arbitration ready");
    }
    private boolean ownsKeys() {
        ConfigSnapshot s = config.get();
        return NativeShortcutGate.ownsInput(s.available, s.enabled(), s.flag(ConfigSpec.KEYS), keysInteractive);
    }
    private void focusChanged(Object window, String pkg) {
        synchronized (lock) {
            if (focusedWindow == window) return;
            focusedWindow = window; foreground = pkg; focusEpoch++;
            // Update identity and cancel old gestures atomically with respect to input/timers.
            cancel(!"com.android.systemui".equals(pkg));
            Bundle b = new Bundle(); b.putString("package", pkg);
            handler.post(() -> bus.send(RuntimeBus.UI, "focus", b));
        }
    }
    /** Called under lock, including before timer/input work; observer delivery may lag the snapshot. */
    private ConfigSnapshot refreshInputConfig() {
        ConfigSnapshot s = config.get();
        if (configRevision != s.revision) { configRevision = s.revision; cancel(); }
        if (!s.enabled() || !s.flag(ConfigSpec.KEYS)) nativeGate.clear();
        return s;
    }
    private boolean current(KeyTarget target) {
        synchronized (lock) {
            updateLockState();
            ConfigSnapshot s = config.get();
            return target != null && s.available && target.matches(focusedWindow, focusEpoch, s.revision)
                    && ownsKeys();
        }
    }
    private boolean locked() {
        return context.getSystemService(android.app.KeyguardManager.class).isKeyguardLocked();
    }
    private void updateLockState() {
        boolean value=locked();
        if (value != keyguardState) { keyguardState=value; focusEpoch++; cancel(); PenPresentation.reset(); }
    }
    private boolean uiAvailable() {
        return uiReady && SystemClock.uptimeMillis() - uiSeen < 4500 && bus.available(RuntimeBus.UI);
    }
    private boolean blocksNative(int button) {
        ConfigSnapshot snapshot = config.get();
        return button != 3 && nativeGate.blocks(button, ownsKeys(), resolved(snapshot.action(foreground,button,false), snapshot));
    }
    private boolean key(KeyEvent e, boolean screenOn) {
        int button = button(e); if (button == 0 || button == 3) return false;
        synchronized (lock) {
            updateLockState();
            keysInteractive = screenOn;
            long now = SystemClock.uptimeMillis();
            if (device != -1 && device != e.getDeviceId()) {
                cancel();
                for (int oldButton = 1; oldButton <= 3; oldButton++) abandon(oldButton, now);
            }
            device = e.getDeviceId();
            ConfigSnapshot s = refreshInputConfig();
            boolean take = ownsKeys();
            if(PenMouse.key(e,button==1?1:2))return true;
            if (PenPresentation.colorKey(e,button==1?-1:1,take && presentationAllowed())) return true;
            if (!take) arbiter.cancel();
            if (e.getAction() == KeyEvent.ACTION_DOWN) {
                KeyEvent pending = down[button];
                if (pending != null && (pending.getDownTime() != e.getDownTime() || pending.getKeyCode() != e.getKeyCode())) {
                    // A disconnected pen or lost UP must not keep the next physical press cancelled forever.
                    abandon(button, now);
                }
                if (e.getRepeatCount() > 0 && down[button] == null) return false;
                for(KeyArbiter.Effect due:arbiter.tick(now)) deliver(due,null,false);
                if (down[button] == null)
                    targets[button] = new KeyTarget(focusedWindow, focusEpoch, s.revision, foreground,
                            s.direction(foreground, 1), s.direction(foreground, 2));
                PenAction shortAction = keyguardState ? PenAction.NONE : resolved(s.shortAction(foreground,button),s);
                PenAction longAction = keyguardState ? (button==1?PenAction.RING:PenAction.SCREENSHOT) : resolved(s.action(foreground,button,false), s);
                PenAction twice = keyguardState ? PenAction.NONE : resolved(s.action(foreground,button,true),s);
                nativeGate.observe(button, true, take, longAction);
                if (e.getRepeatCount() == 0)
                    Log.i("FocusPenKeys", "button=" + button + " takeover=" + take + " ui=" + uiAvailable()
                            + " short=" + shortAction.id + " long=" + longAction.id + " replay=" + replayHealthy);
                int doubleMs=keyguardState||shortAction==PenAction.NONE&&twice==PenAction.NONE?0:s.doublePressMs();
                KeyArbiter.Result result = arbiter.down(button, now, keyguardState?380:s.longPressMs(), shortAction, longAction, twice, doubleMs, take);
                if (result.consumed() && down[button] == null) down[button] = new KeyEvent(e);
                for (KeyArbiter.Effect effect : result.effects()) deliver(effect, e, false);
                schedule(); return result.consumed();
            }
            if (e.getAction() != KeyEvent.ACTION_UP) return false;
            KeyEvent pending = down[button];
            if (pending != null && (pending.getDownTime() != e.getDownTime() || pending.getKeyCode() != e.getKeyCode())) return false;
            KeyArbiter.Result result = arbiter.up(button, now, e.isCanceled());
            boolean isLong = result.effects().stream().anyMatch(effect -> effect.kind() == KeyArbiter.Kind.RELEASE);
            for (KeyArbiter.Effect effect : result.effects()) deliver(effect, e, isLong);
            if(arbiter.waitingTap(button)) { tapDown[button]=down[button]; tapUp[button]=new KeyEvent(e); }
            down[button] = null; schedule(); return result.consumed();
        }
    }
    private PenAction resolved(PenAction action, ConfigSnapshot s) {
        // App rules are introduced in P5. An absent direction mapping always preserves native handling.
        if (action == PenAction.RING && !s.flag(ConfigSpec.RING)) return PenAction.NATIVE;
        return action;
    }
    private boolean presentationAllowed() {
        if (keyguardState || "com.android.camera".equals(foreground)) return false;
        try {
            Object manager = XposedHelpers.callStaticMethod(stylusManagerType, "getInstance");
            return !XposedHelpers.getBooleanField(manager, "mIsGameMode");
        } catch (Throwable e) { return false; }
    }
    private void deliver(KeyArbiter.Effect effect, KeyEvent up, boolean longPress) {
        KeyEvent original = down[effect.button()];
        KeyTarget target = targets[effect.button()];
        if(effect.kind()==KeyArbiter.Kind.ACTION&&!effect.longPress()&&tapDown[effect.button()]!=null) {
            original=tapDown[effect.button()];tapDown[effect.button()]=null;tapUp[effect.button()]=null;
        }
        deliver(effect,up,longPress,original,target);
    }
    private void deliver(KeyArbiter.Effect effect,KeyEvent up,boolean longPress,KeyEvent original,KeyTarget target) {
        longPress=effect.longPress();
        if(effect.kind()==KeyArbiter.Kind.REPLAY_PENDING_TAP) {
            KeyEvent start=tapDown[effect.button()], end=tapUp[effect.button()];
            tapDown[effect.button()]=null; tapUp[effect.button()]=null;
            handler.post(()->{ if(current(target) && target.supportsDirection()) { replayKey(start,effect.action()); replayKey(end,effect.action()); } }); return;
        }
        if(effect.kind()==KeyArbiter.Kind.DOUBLE_ACTION && (effect.action()==PenAction.NATIVE||effect.action()==PenAction.NONE)) {
            KeyEvent firstDown=tapDown[effect.button()], firstUp=tapUp[effect.button()];
            KeyEvent secondDown=original, secondUp=up==null?null:new KeyEvent(up);
            tapDown[effect.button()]=null; tapUp[effect.button()]=null;
            handler.post(()->{if(current(target) && target.supportsDirection()) {replayKey(firstDown,effect.action());replayKey(firstUp,effect.action());replayKey(secondDown,effect.action());replayKey(secondUp,effect.action());}});
            return;
        }
        if(effect.kind()==KeyArbiter.Kind.DOUBLE_ACTION) { tapDown[effect.button()]=null; tapUp[effect.button()]=null; longPress=false; }
        if(effect.kind()==KeyArbiter.Kind.ACTION || effect.kind()==KeyArbiter.Kind.DOUBLE_ACTION) {
            if (!current(target)) return;
            if (effect.action()==PenAction.NONE) return;
            String official=OfficialPenActions.id(effect.action());
            if(official!=null) { handler.post(()->{ if(current(target) && !OfficialPenActions.run(context,official)) busError(); }); return; }
            if(effect.action()==PenAction.UP || effect.action()==PenAction.DOWN) {
                boolean upAction=effect.action()==PenAction.UP;
                DirectionAdapter adapter=DirectionAdapter.parse(target.direction(upAction));
                boolean executionUp=adapter.executionUp(upAction);
                if(adapter==DirectionAdapter.NONE) return;
                if (adapter.pointer()) {
                    handler.post(() -> directionHost.run(target,adapter.kind,executionUp,() -> current(target)
                            && target.supportsDirection() && power.isInteractive()));
                    return;
                }
                boolean nativeDirection=adapter==DirectionAdapter.NATIVE;
                if(nativeDirection && original==null) return;
                // ROM stylus UP/DOWN are 93/92; generic keyboard PAGE_UP/DOWN are 92/93.
                boolean proDirection=nativeDirection&&original!=null&&original.getKeyCode()>=194&&original.getKeyCode()<=197;
                int code=nativeDirection?(proDirection?(executionUp?197:196):(executionUp?93:92)):"page".equals(adapter.kind)?(executionUp?92:93):(executionUp?19:20);
                handler.post(()->{ if(current(target) && target.supportsDirection()) {
                    long now=SystemClock.uptimeMillis();
                    int device=nativeDirection?original.getDeviceId():-1;
                    int source=nativeDirection?original.getSource():android.view.InputDevice.SOURCE_KEYBOARD;
                    int flags=nativeDirection?original.getFlags()&~KeyEvent.FLAG_LONG_PRESS:0;
                    int meta=nativeDirection?original.getMetaState():0;
                    inject(new KeyEvent(now,now,0,code,0,meta,device,0,flags,source));
                    inject(new KeyEvent(now,now,1,code,0,meta,device,0,flags,source));
                } }); return;
            }
        }
        if (effect.kind() == KeyArbiter.Kind.REPLAY_EVENT) {
            KeyEvent event = up == null ? null : new KeyEvent(up);
            handler.post(() -> continueReplay(effect.button(), event, target));
            return;
        }
        if (effect.kind() == KeyArbiter.Kind.REPLAY_TAP || effect.kind() == KeyArbiter.Kind.REPLAY_DOWN) {
            KeyEvent start = original == null ? null : new KeyEvent(original), end = up == null ? null : new KeyEvent(up);
            handler.post(() -> {
                if (!current(target) || !target.supportsDirection() || !replayKey(start,effect.action())) return;
                if (effect.kind() == KeyArbiter.Kind.REPLAY_TAP) replayKey(end,effect.action());
                else {replayDown[effect.button()] = start;replayToApp[effect.button()]=effect.action()==PenAction.NONE;}
            });
            return;
        }
        String event = switch (effect.kind()) {
            case ACTION, DOUBLE_ACTION -> "action"; case RELEASE -> "release"; case CANCEL -> "cancel";
            default -> "cancel";
        };
        Bundle b = new Bundle(); b.putInt("button", effect.button()); b.putString("action", effect.action().id); b.putBoolean("long", longPress);
        if ((effect.kind() == KeyArbiter.Kind.ACTION || effect.kind() == KeyArbiter.Kind.DOUBLE_ACTION) && effect.action() == PenAction.RING)
            arbiter.ring(true, false, effect.button());
        handler.post(() -> {
            if(!event.equals("cancel") && !current(target)) return;
            if (!bus.send(RuntimeBus.UI, event, b)) {
                synchronized (lock) { arbiter.ring(false, false, effect.button()); }
                Log.w("FocusPenKeys", "Action unavailable; managed gesture retained button=" + effect.button() + " action=" + effect.action().id);
            }
        });
    }
    private void continueReplay(int button, KeyEvent event, KeyTarget target) {
        KeyEvent start = replayDown[button];
        if (event == null || start == null || start.getDownTime() != event.getDownTime()
                || start.getDeviceId() != event.getDeviceId() || start.getKeyCode() != event.getKeyCode()) return;
        if (event.getAction() == KeyEvent.ACTION_UP) {
            replayDown[button] = null;
            if(replayToApp[button]){if(current(target))replayKey(event,PenAction.NONE);}
            else inject(event); // Release a dispatched native DOWN even if its original window has gone away.
        } else if (current(target) && target.supportsDirection()) replayKey(event,replayToApp[button]?PenAction.NONE:PenAction.NATIVE);
    }
    private boolean replayKey(KeyEvent event,PenAction action){
        if(action!=PenAction.NONE)return inject(event);
        if(event==null||locked()||!power.isInteractive())return false;
        try{replay.dispatch(event);return true;}
        catch(Throwable failure){Log.w("FocusPenKeys","Application key delivery failed",failure);return false;}
    }
    private boolean inject(KeyEvent e) {
        if (e == null) return false;
        try {
            Object policy = policyInstance;
            if (policy == null) throw new IllegalStateException("No verified key policy instance");
            int result;
            replaying.set(true);
            try {
                result = (Integer) XposedHelpers.callMethod(policy, "interceptKeyBeforeQueueing", e,
                        0x01000000 | 0x02000000 | (power.isInteractive() ? 0x20000000 : 0));
            } finally { replaying.remove(); }
            if ((result & 1) != 0) replay.dispatch(e);
            if (e.getAction() == KeyEvent.ACTION_UP)
                Log.i("FocusPenKeys", "Native replay paired button=" + keyButton(e.getKeyCode()) + " policyPass=" + ((result & 1) != 0));
            return true;
        } catch (Throwable failure) {
            replayHealthy = false;
            Log.w("FocusPenKeys", "Native replay failed", failure);
            return false;
        }
    }
    private static int keyButton(int key) { return key == 93 ? 1 : key == 92 ? 2 : key == 193 || key == 194 ? 3 : 0; }
    private static int button(KeyEvent e) {
        int candidate = keyButton(e.getKeyCode()); if (candidate == 0) return 0;
        InputDevice d = e.getDevice();
        try { return d != null && Integer.valueOf(3).equals(stylusType.invoke(d)) ? candidate : 0; }
        catch (Throwable failure) { return 0; }
    }
    private void schedule() {
        handler.removeCallbacks(timer); long next = arbiter.nextDeadline();
        if (next != Long.MAX_VALUE) handler.postAtTime(timer, next);
    }
    private void abandon(int button, long now) {
        KeyEvent original = down[button];
        if (original != null && arbiter.nativeHeld(button)) {
            KeyEvent end = new KeyEvent(original.getDownTime(), now, KeyEvent.ACTION_UP, original.getKeyCode(),
                    0, original.getMetaState(), original.getDeviceId(), original.getScanCode(),
                    original.getFlags() | KeyEvent.FLAG_CANCELED, original.getSource());
            KeyTarget target = targets[button];
            handler.post(() -> continueReplay(button, end, target));
        }
        arbiter.forget(button); down[button] = null;
    }
    private void busError() { bus.send(RuntimeBus.UI,"actionError",new Bundle()); }
    private void cancel() { cancel(true); }
    private void cancel(boolean closeRing) {
        PenMouse.exit();
        if(proInput!=null)proInput.cancel();
        synchronized (lock) {
            arbiter.cancel(!closeRing); java.util.Arrays.fill(tapDown,null); java.util.Arrays.fill(tapUp,null);
            if (handler != null) handler.removeCallbacks(timer);
        }
        if (closeRing && handler != null) handler.post(() -> bus.send(RuntimeBus.UI, "cancel", new Bundle()));
    }
    private void proAction(String action,boolean release,KeyTarget target,KeyEvent event,long mouseSession){
        if(release){PenMouse.action(action,true,mouseSession);return;}
        if(!current(target)||locked()||mouseSession!=PenMouse.session())return;
        switch(action){
            case "mouse"->PenMouse.enter(event.getDeviceId());
            case "laser"->PenPresentation.laserMode();
            case "left","right","leftHold","rightHold"->PenMouse.action(action,false,mouseSession);
            case "scrollUp","scrollDown"->{if(PenMouse.active())PenMouse.action(action,false,mouseSession);else directionHost.run(target,"scroll",action.equals("scrollUp"),()->current(target));}
            case "volumeUp","volumeDown","back","home","recents"->{int code=switch(action){case "volumeUp"->24;case "volumeDown"->25;case "back"->4;case "home"->3;default->187;};long now=SystemClock.uptimeMillis();
                try{android.hardware.input.InputManager manager=context.getSystemService(android.hardware.input.InputManager.class);for(int a:new int[]{0,1})XposedHelpers.callMethod(manager,"injectInputEvent",new KeyEvent(now,now,a,code,0,0,-1,0,0,InputDevice.SOURCE_KEYBOARD),0);}catch(Throwable failure){Log.w("FocusPenPro","System action unavailable",failure);}}
            default->{if(action.startsWith("app:")){android.content.Intent intent=context.getPackageManager().getLaunchIntentForPackage(action.substring(4));if(intent!=null)context.startActivity(intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));}}
        }
    }
}
