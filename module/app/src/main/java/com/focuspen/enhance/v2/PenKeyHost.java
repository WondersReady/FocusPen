package com.focuspen.enhance.v2;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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

/** The verified ROM's early input owner; all recognition and actions run on its worker. */
final class PenKeyHost {
    private static volatile PenKeyHost instance;
    private static boolean initializationScheduled;
    private static volatile Object initialWindow;
    private static volatile String initialPackage="";
    private static Method stylusType;
    private static Class<?> stylusManagerType;
    private final KeyArbiter arbiter = new KeyArbiter();
    private final ProGestureGate proGate = new ProGestureGate();
    private final KeyEvent[] down = new KeyEvent[4], tapDown = new KeyEvent[4], tapUp = new KeyEvent[4];
    @SuppressWarnings("unchecked")
    private final List<KeyEvent>[] pressEvents=new List[4], tapEvents=new List[4];
    // Worker-thread state: only a DOWN actually dispatched by the replay queue owns repeats/UP.
    private final KeyEvent[] replayDown = new KeyEvent[4];
    private final boolean[] replayToApp = new boolean[4];
    private record ApplicationPress(int device, int code, long downTime) { }
    private record ApplicationDelivery(KeyTarget target,KeyEvent down) { }
    private final java.util.Map<ApplicationPress,ApplicationDelivery> proApplicationPresses = new java.util.HashMap<>();
    private final Context context;
    private volatile Object focusedWindow;
    private volatile long focusEpoch;
    private final KeyTarget[] targets = new KeyTarget[4];
    private final NativeKeyReplay replay;
    private final PowerManager power;
    private final ConfigClient config;
    private final RuntimeBus bus;
    private final Handler handler;
    private final WindowDirectionHost directionHost;
    private final ProInput proInput;
    private final PenDockMonitor dock;
    private final WritingAudioGate writingAudioGate = new WritingAudioGate();
    private final Object lock = new Object();
    private volatile boolean uiReady;
    private volatile long uiSeen;
    private volatile boolean replayHealthy = true;
    private volatile boolean keysInteractive;
    private volatile boolean keyguardState;
    private volatile boolean dockBlocked=true;
    private volatile long dockSince;
    private volatile String foreground = "";
    private long configRevision = -1;
    private int device = -1;
    private final Runnable timer = () -> {
        synchronized (lock) {
            refreshScreen();
            updateLockState();
            refreshInputConfig();
            if (!ownsKeys() || !keysInteractive) { arbiter.cancel(); return; }
            for (KeyArbiter.Effect effect : arbiter.tick(SystemClock.uptimeMillis())) deliver(effect, null, true);
            schedule();
        }
    };

    static void install(ClassLoader cl) throws Exception {
        if (!HyperOsCompatibility.supported()) { Log.w("FocusPenKeys", "Unsupported HyperOS major; requires 3.x or 4.x"); return; }
        Class<?> stylus = cl.loadClass("com.miui.server.input.stylus.MiuiStylusShortcutManager");
        stylusManagerType=stylus;
        stylusType = InputDevice.class.getDeclaredMethod("isXiaomiStylus");
        if (stylusType.getReturnType() != int.class)
            throw new NoSuchMethodException("Unexpected Xiaomi stylus type signature");
        stylusType.setAccessible(true);
        NativeKeyReplay.verify(cl);
        OfficialPenActions.initialize(cl);
        List<XC_MethodHook.Unhook> hooks = new ArrayList<>();
        try {
        hooks.add(XposedHelpers.findAndHookMethod(cl.loadClass("com.android.server.policy.MiuiPhoneWindowManager"), "systemBooted", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                initialize((Context) XposedHelpers.getObjectField(p.thisObject, "mContext"));
            }
        }));
        Class<?> pro = cl.loadClass("com.miui.server.input.stylus.MiuiStylusTouchFilmManager");
        hooks.add(XposedHelpers.findAndHookMethod(pro, "interceptKeyBeforeQueueing", KeyEvent.class, boolean.class, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                // Shared hardware/posture services remain alive; only shortcut recognition is bypassed.
                p.setResult(false);
            }
        }));
        hooks.add(XposedHelpers.findAndHookMethod(stylus, "onDefaultDisplayFocusChangedLw", cl.loadClass("com.android.server.policy.WindowManagerPolicy$WindowState"), new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                try {
                    String pkg = p.args[0] == null ? "" : (String) XposedHelpers.callMethod(p.args[0], "getOwningPackage");
                    initialWindow=p.args[0];initialPackage=pkg;
                    PenKeyHost host=instance;
                    if(host!=null)host.focusChanged(p.args[0], pkg);
                } catch (Throwable e) { if(instance!=null)instance.cancel(); }
            }
        }));
        hooks.add(XposedHelpers.findAndHookMethod(stylus, "updateScreenState", boolean.class, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (instance != null && !(Boolean) p.args[0]) {
                    instance.handler.post(() -> instance.screenChanged(false));
                }
            }
        }));
        hooks.add(XposedHelpers.findAndHookMethod(stylus, "triggerNoteWhenScreenOff", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                p.setResult(null);
                PenKeyHost host=instance; if(host==null) return;
                ConfigSnapshot cfg=host.config.get();
                if (!cfg.inputActionsReady()) return;
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
                PenKeyHost host=instance; if(host==null) {p.setResult(null);return;}
                ConfigSnapshot cfg=host.config.get();
                if(!cfg.inputActionsReady()) {p.setResult(null);return;}
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
        hooks.add(installEarlyInputHook(cl));
        PenPresentation.install(cl);
        XposedBridge.log("FocusPen controller installed at IMS queue version=" + BuildConfig.VERSION_NAME);
        } catch (Throwable failure) {
            for (XC_MethodHook.Unhook hook : hooks) try { hook.unhook(); } catch (Throwable ignored) { }
            throw new IllegalStateException("Key hooks rolled back", failure);
        }
    }

    /** One ownership decision before Android and vendor shortcut recognition. */
    private static XC_MethodHook.Unhook installEarlyInputHook(ClassLoader cl) throws Exception {
        Class<?> ims = cl.loadClass("com.android.server.input.InputManagerService");
        if (ims.getDeclaredMethod("interceptKeyBeforeQueueing", KeyEvent.class, int.class).getReturnType() != int.class)
            throw new NoSuchMethodException("Unexpected input manager queue signature");
        return XposedHelpers.findAndHookMethod(ims, "interceptKeyBeforeQueueing", KeyEvent.class, int.class,
                new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        KeyEvent event = (KeyEvent) p.args[0];
                        int flags = (Integer) p.args[1];
                        if ((flags & 0x04000000) != 0) return;
                        InputDevice input = event.getDevice();
                        int type = inputType(input);
                        if (PenInputClassifier.targetType(type) || input != null && input.getName().contains("Focus Pen"))
                            recordEarlyEvidence(event, input, type, flags);
                        if (PenInputClassifier.classify(type,event.getKeyCode()) == PenInputClassifier.Input.UNKNOWN) return;
                        // Configuration and screen state affect actions, never ownership.
                        p.setResult(0);
                        PenKeyHost host = instance;
                        if (host == null) return;
                        KeyEvent copy = new KeyEvent(event);
                        long epoch=host.focusEpoch, revision=host.config.get().revision;
                        host.handler.post(() -> {
                            if(copy.getAction()==KeyEvent.ACTION_DOWN && (epoch!=host.focusEpoch || revision!=host.config.get().revision))return;
                            host.input(copy,type);
                        });
                    }
                });
    }

    private void input(KeyEvent event,int type) {
        try {
            screenChanged(power.isInteractive());
            synchronized(lock) {
                updateLockState();
                ConfigSnapshot snapshot=refreshInputConfig();
                if(dockBlocked || event.getDownTime()<dockSince || !snapshot.inputActionsReady() || !keysInteractive) return;
                if(!snapshot.flag(ConfigSpec.KEYS)) {
                    if(!keyguardState) {
                        KeyTarget target=captureTarget(snapshot);
                        handler.post(()->dispatchApplicationEvents(target,List.of(event)));
                    }
                    return;
                }
                if(type==8) proKey(event);
                else key(event,keysInteractive);
            }
        } catch(Throwable failure) {
            replayHealthy=false; cancel(); PenPresentation.reset();
            Log.e("FocusPenKeys","Managed input failed; gesture canceled",failure);
        }
    }
    private KeyTarget captureTarget(ConfigSnapshot snapshot) {
        return new KeyTarget(focusedWindow,focusEpoch,snapshot.revision,foreground,
                snapshot.direction(foreground,1),snapshot.direction(foreground,2));
    }
    private void screenChanged(boolean interactive) {
        synchronized(lock) {
            writingAudioGate.updateScreen(interactive);
            if(keysInteractive==interactive)return;
            keysInteractive=interactive;
            cancel(); PenPresentation.reset();
        }
    }
    private void refreshScreen(){screenChanged(power.isInteractive());}
    private static int inputType(InputDevice input) {
        try {
            return input == null ? 0 : (Integer) stylusType.invoke(input);
        } catch (Throwable ignored) { return 0; }
    }

    private static void recordEarlyEvidence(KeyEvent event, InputDevice input, int type, int policyFlags) {
        if (!BuildConfig.DEBUG) return;
        // Each DOWN/repeat/UP is needed to align Java events with one evdev operation.
        // Do not deduplicate by device/key: that loses the release and subsequent presses.
        Log.i("FocusPenInputEvidence", "earlyQueue device=" + event.getDeviceId()
                + " name=" + (input == null ? "missing" : input.getName()) + " type=" + type
                + " vendor=" + (input == null ? -1 : input.getVendorId())
                + " product=" + (input == null ? -1 : input.getProductId())
                + " descriptor=" + (input == null ? "missing" : input.getDescriptor())
                + " keyCode=" + event.getKeyCode() + " scanCode=" + event.getScanCode()
                + " classified=" + PenInputClassifier.classify(type, event.getKeyCode())
                + " source=0x" + Integer.toHexString(event.getSource())
                + " action=" + event.getAction() + " repeat=" + event.getRepeatCount()
                + " downTime=" + event.getDownTime() + " eventTime=" + event.getEventTime()
                + " observedAt=" + SystemClock.uptimeMillis()
                + " canceled=" + event.isCanceled() + " flags=0x" + Integer.toHexString(event.getFlags())
                + " policy=0x" + Integer.toHexString(policyFlags));
    }

    private boolean proKey(KeyEvent e) {
        int slot=ProGestureGate.gesture(8,e.getKeyCode());
        if(slot<0)return true;
        ConfigSnapshot s=config.get();
        KeyTarget target=captureTarget(s);
        if(slot>=2 && PenPresentation.colorKey(e,slot==2?-1:1,presentationAllowed()))return true;
        if(keyguardState) {
            ProGestureGate.Decision decision=proGate.event(slot,e.getDeviceId(),e.getDownTime(),
                    e.getAction(),e.getRepeatCount(),e.isCanceled(),slot==0);
            if(decision==ProGestureGate.Decision.DISPATCH && s.flag(ConfigSpec.RING))
                deliver(new KeyArbiter.Effect(KeyArbiter.Kind.ACTION,1,PenAction.RING),null,false,e,target);
            return true;
        }
        if(proInput.owns(e) || !PenPresentation.presenting())
            proInput.event(null,e,slot,true,s,target);
        else PenPresentation.proKey(e,slot);
        return true;
    }

    private static synchronized void initialize(Context context) {
        if(instance!=null || initializationScheduled)return;
        initializationScheduled=true;
        PenKeyHost candidate;
        try {
            candidate=new PenKeyHost(context);
        } catch(Throwable failure) {
            initializationScheduled=false;
            XposedBridge.log("FocusPen input initialization failed; recognized keys remain reserved");
            XposedBridge.log(failure);
            new Handler(Looper.getMainLooper()).postDelayed(()->initialize(context),5000);
            return;
        }
        instance=candidate;
        try { candidate.focusChanged(initialWindow,initialPackage); }
        catch(Throwable failure) { Log.w("FocusPenKeys","Initial focus unavailable",failure); }
        XposedBridge.log("FocusPen input controller ready before user unlock version="+BuildConfig.VERSION_NAME);
    }
    private PenKeyHost(Context c) throws Exception {
        ConfigClient pendingConfig=null;
        RuntimeBus pendingBus=null;
        PenDockMonitor pendingDock=null;
        try {
        context=c;
        replay = new NativeKeyReplay();
        power = c.getSystemService(PowerManager.class);
        if(power==null)throw new IllegalStateException("Power service unavailable");
        config = pendingConfig = new ConfigClient(c);
        bus = pendingBus = new RuntimeBus(c, (event, b) -> {
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
        directionHost = new WindowDirectionHost(handler,replay);
        proInput=new ProInput(handler,new ProInput.Host(){
            public boolean valid(KeyTarget target){return current(target)&&!locked();}
            public void single(KeyTarget target,List<KeyEvent> events){
                PenAction action=config.get().proAction(target.pkg(),0);
                if(action==PenAction.NONE||action==PenAction.NATIVE){handler.post(()->dispatchApplicationEvents(target,events));return;}
                deliver(new KeyArbiter.Effect(KeyArbiter.Kind.ACTION,1,action),null,false,events.isEmpty()?null:events.get(0),target);
            }
            public void applicationEvents(KeyTarget target,List<KeyEvent> events){
                handler.post(()->dispatchApplicationEvents(target,events));
            }
            public void action(String action,boolean release,KeyTarget target,KeyEvent event,long mouseSession){handler.post(()->proAction(action,release,target,event,mouseSession));}
        },lock);
        config.add(s -> {
            synchronized (lock) { refreshInputConfig(); }
        });
        dock=pendingDock=new PenDockMonitor(c,handler,this::dockChanged);
        writingAudioGate.updateScreen(power.isInteractive());
        writingAudioGate.updateLocked(locked());
        PenPresentation.bind(c,config,handler,()->power.isInteractive()&&!dockBlocked);
        try { new SystemPenMotion(context,config,bus,writingAudioGate); }
        catch(Throwable failure) { Log.e("FocusPenMotion","Cannot initialize pointer observer",failure); }
        Log.i("FocusPenKeys", "Verified ROM key arbitration ready");
        } catch(Exception | Error failure) {
            if(pendingDock!=null)pendingDock.close();
            if(pendingBus!=null)pendingBus.close();
            if(pendingConfig!=null)pendingConfig.close();
            throw failure;
        }
    }
    private boolean ownsKeys() {
        ConfigSnapshot s = config.get();
        return !dockBlocked && s.inputActionsReady() && s.flag(ConfigSpec.KEYS);
    }
    private void dockChanged(int state) {
        synchronized(lock) {
            writingAudioGate.updateDock(state);
            boolean blocked=state!=PenDockMonitor.DETACHED;
            if(blocked==dockBlocked)return;
            dockBlocked=blocked;dockSince=SystemClock.uptimeMillis();
            cancel();
            proGate.reset();proInput.docked();
            // End delivered app presses at their original window before retiring its epoch.
            for(ApplicationDelivery delivery:List.copyOf(proApplicationPresses.values())) {
                KeyEvent down=delivery.down();
                releaseAppKey(new KeyEvent(down.getDownTime(),SystemClock.uptimeMillis(),KeyEvent.ACTION_UP,
                        down.getKeyCode(),0,down.getMetaState(),down.getDeviceId(),down.getScanCode(),
                        down.getFlags()|KeyEvent.FLAG_CANCELED,down.getSource()),delivery.target());
            }
            proApplicationPresses.clear();focusEpoch++;
            for(int button=1;button<=3;button++) {
                arbiter.forget(button);down[button]=null;targets[button]=null;
                pressEvents[button]=null;replayDown[button]=null;replayToApp[button]=false;
            }
            PenPresentation.reset();
        }
    }
    private void focusChanged(Object window, String pkg) {
        synchronized (lock) {
            if (focusedWindow == window) return;
            focusedWindow = window; foreground = pkg; focusEpoch++;
            // Update identity and cancel old gestures atomically with respect to input/timers.
            cancel(!"com.android.systemui".equals(pkg),true);
            PenPresentation.contextChanged();
            long epoch=focusEpoch;
            handler.post(()->proApplicationPresses.entrySet().removeIf(entry->entry.getValue().target().focusEpoch()!=epoch));
            Bundle b = new Bundle(); b.putString("package", pkg);
            handler.post(() -> bus.send(RuntimeBus.UI, "focus", b));
        }
    }
    /** Called under lock, including before timer/input work; observer delivery may lag the snapshot. */
    private ConfigSnapshot refreshInputConfig() {
        ConfigSnapshot s = config.get();
        if (configRevision != s.revision) { configRevision = s.revision; cancel(); PenPresentation.reset(); }
        return s;
    }
    private boolean current(KeyTarget target) {
        synchronized (lock) {
            updateLockState();
            ConfigSnapshot s = config.get();
            return !dockBlocked && target != null && s.available && target.matches(focusedWindow, focusEpoch, s.revision)
                    && keysInteractive;
        }
    }
    private boolean locked() {
        return context.getSystemService(android.app.KeyguardManager.class).isKeyguardLocked();
    }
    private void updateLockState() {
        boolean value=locked();
        writingAudioGate.updateLocked(value);
        if (value != keyguardState) {
            keyguardState=value; focusEpoch++;
            // Both lit lock states permit presentation. Retire shortcut gestures,
            // but do not reinterpret the next mouse key as an idle-mode gesture.
            cancel(); PenPresentation.contextChanged();
        }
    }
    private boolean uiAvailable() {
        return uiReady && SystemClock.uptimeMillis() - uiSeen < 4500 && bus.available(RuntimeBus.UI);
    }
    private boolean key(KeyEvent e, boolean screenOn) {
        int button = button(e); if (button == 0) return false;
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
            if(button==3) {
                PenPresentation.focusKey(e,take&&presentationAllowed(),s);
                return true;
            }
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
                if (down[button] == null) {
                    targets[button] = new KeyTarget(focusedWindow, focusEpoch, s.revision, foreground,
                            s.direction(foreground, 1), s.direction(foreground, 2));
                    pressEvents[button]=new ArrayList<>();
                }
                pressEvents[button].add(new KeyEvent(e));
                PenAction shortAction = keyguardState ? PenAction.NONE : resolved(s.shortAction(foreground,button),s);
                PenAction longAction = keyguardState ? (button==1?PenAction.RING:PenAction.SCREENSHOT) : resolved(s.action(foreground,button,false), s);
                PenAction twice = keyguardState ? PenAction.NONE : resolved(s.action(foreground,button,true),s);
                if (e.getRepeatCount() == 0)
                    Log.i("FocusPenKeys", "button=" + button + " takeover=" + take + " ui=" + uiAvailable()
                            + " short=" + shortAction.id + " long=" + longAction.id + " replay=" + replayHealthy);
                int doubleMs=keyguardState||shortAction==PenAction.NONE&&twice==PenAction.NONE?0:s.doublePressMs();
                KeyArbiter.Result result = arbiter.down(button, now, s.longPressMs(), shortAction, longAction, twice, doubleMs, take);
                if (result.consumed() && down[button] == null) down[button] = new KeyEvent(e);
                for (KeyArbiter.Effect effect : result.effects()) deliver(effect, e, false);
                schedule(); return result.consumed();
            }
            if (e.getAction() != KeyEvent.ACTION_UP) return false;
            KeyEvent pending = down[button];
            if (pending != null && (pending.getDownTime() != e.getDownTime() || pending.getKeyCode() != e.getKeyCode())) return false;
            if(pressEvents[button]!=null)pressEvents[button].add(new KeyEvent(e));
            KeyArbiter.Result result = arbiter.up(button, now, e.isCanceled());
            boolean isLong = result.effects().stream().anyMatch(effect -> effect.kind() == KeyArbiter.Kind.RELEASE);
            for (KeyArbiter.Effect effect : result.effects()) deliver(effect, e, isLong);
            if(arbiter.waitingTap(button)) { tapDown[button]=down[button]; tapUp[button]=new KeyEvent(e); tapEvents[button]=pressEvents[button]; }
            pressEvents[button]=null;
            down[button] = null; schedule(); return result.consumed();
        }
    }
    private PenAction resolved(PenAction action, ConfigSnapshot s) {
        if (action == PenAction.NATIVE) return PenAction.NONE;
        if (action == PenAction.RING && !s.flag(ConfigSpec.RING)) return PenAction.NONE;
        return action;
    }
    private boolean presentationAllowed() {
        return keysInteractive;
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
            List<KeyEvent> events=copyEvents(tapEvents[effect.button()],start,end);
            tapEvents[effect.button()]=null;
            tapDown[effect.button()]=null; tapUp[effect.button()]=null;
            handler.post(()->{ if(current(target)) dispatchApplicationEvents(target,events); }); return;
        }
        if(effect.kind()==KeyArbiter.Kind.DOUBLE_ACTION && (effect.action()==PenAction.NATIVE||effect.action()==PenAction.NONE)) {
            KeyEvent firstDown=tapDown[effect.button()], firstUp=tapUp[effect.button()];
            KeyEvent secondDown=original, secondUp=up==null?null:new KeyEvent(up);
            List<KeyEvent> first=copyEvents(tapEvents[effect.button()],firstDown,firstUp);
            List<KeyEvent> second=copyEvents(pressEvents[effect.button()],secondDown,secondUp);
            tapEvents[effect.button()]=null;
            tapDown[effect.button()]=null; tapUp[effect.button()]=null;
            handler.post(()->{if(current(target)){dispatchApplicationEvents(target,first);dispatchApplicationEvents(target,second);}});
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
                    replayPair(new KeyEvent(now,now,0,code,0,meta,device,0,flags,source),
                            new KeyEvent(now,now,1,code,0,meta,device,0,flags,source),target);
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
            List<KeyEvent> events=copyEvents(pressEvents[effect.button()],start,
                    effect.kind()==KeyArbiter.Kind.REPLAY_TAP?end:null);
            handler.post(() -> {
                if (!current(target) || start==null) return;
                dispatchApplicationEvents(target,events);
                if (effect.kind() == KeyArbiter.Kind.REPLAY_DOWN) {replayDown[effect.button()] = start;replayToApp[effect.button()]=true;}
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
            dispatchApplicationEvents(target,List.of(event));
        } else if (current(target)) dispatchApplicationEvents(target,List.of(event));
    }
    private static List<KeyEvent> copyEvents(List<KeyEvent> events,KeyEvent start,KeyEvent end) {
        if(events!=null)return List.copyOf(events);
        if(start==null)return List.of();
        return end==null?List.of(start):List.of(start,end);
    }
    private void releaseAppKey(KeyEvent event, KeyTarget target) {
        boolean cancelled;
        synchronized (lock) {
            updateLockState();
            if (target == null || !target.sameWindow(focusedWindow, focusEpoch) || !keysInteractive) return;
            // A config change may stop repeats, but the unchanged app still owns its delivered DOWN.
            // Finish it as cancelled; never turn this cleanup into a new action or a new window's UP.
            cancelled = dockBlocked || config.get().revision != target.revision() || !config.get().inputActionsReady();
        }
        replayKey(cancelled ? KeyEvent.changeFlags(event,event.getFlags() | KeyEvent.FLAG_CANCELED) : event,PenAction.NONE,target);
    }
    private void dispatchApplicationEvents(KeyTarget target,List<KeyEvent> events) {
        for(KeyEvent event:events) {
            ApplicationPress press=new ApplicationPress(event.getDeviceId(),event.getKeyCode(),event.getDownTime());
            if(event.getAction()==KeyEvent.ACTION_DOWN && event.getRepeatCount()==0) {
                var iterator=proApplicationPresses.entrySet().iterator();
                while(iterator.hasNext()) {
                    var old=iterator.next();
                    if(old.getKey().device()!=press.device() || old.getKey().code()!=press.code() || old.getKey().equals(press))continue;
                    ApplicationDelivery previous=old.getValue();iterator.remove();
                    KeyEvent start=previous.down();
                    releaseAppKey(new KeyEvent(start.getDownTime(),event.getEventTime(),KeyEvent.ACTION_UP,start.getKeyCode(),0,
                            start.getMetaState(),start.getDeviceId(),start.getScanCode(),start.getFlags()|KeyEvent.FLAG_CANCELED,start.getSource()),previous.target());
                }
                if(!proApplicationPresses.containsKey(press) && current(target)
                        && replayKey(event,PenAction.NONE,target)) proApplicationPresses.put(press,new ApplicationDelivery(target,new KeyEvent(event)));
            } else {
                ApplicationDelivery delivered=proApplicationPresses.get(press);
                if(delivered==null)continue;
                KeyTarget owner=delivered.target();
                if(event.getAction()==KeyEvent.ACTION_UP) {
                    proApplicationPresses.remove(press);
                    releaseAppKey(event,owner);
                } else if(current(owner)) replayKey(event,PenAction.NONE,owner);
            }
        }
    }
    private boolean replayKey(KeyEvent event,PenAction action,KeyTarget target){
        if(event==null||locked()||!power.isInteractive())return false;
        try{if(target==null)return false;replay.dispatchToApplication(event,target.window());return true;}
        catch(Throwable failure){Log.w("FocusPenKeys","Application key delivery failed",failure);return false;}
    }
    private boolean replayPair(KeyEvent start, KeyEvent end, KeyTarget target) {
        // A failed DOWN must not be followed by a new window's orphan UP.
        return replayKey(start, PenAction.NONE, target) && replayKey(end, PenAction.NONE, target);
    }
    private static int button(KeyEvent e) {
        return PenInputClassifier.standardButton(inputType(e.getDevice()), e.getKeyCode());
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
        arbiter.forget(button); down[button] = null;pressEvents[button]=null;
    }
    private void busError() { bus.send(RuntimeBus.UI,"actionError",new Bundle()); }
    private void cancel() { cancel(true); }
    private void cancel(boolean closeRing) {
        cancel(closeRing,false);
    }
    private void cancel(boolean closeRing,boolean focusOnly) {
        PenPresentation.cancelGesture();
        if(proInput!=null){if(focusOnly)proInput.focusChanged();else proInput.cancel();}
        synchronized (lock) {
            arbiter.cancel(!closeRing); java.util.Arrays.fill(tapDown,null); java.util.Arrays.fill(tapUp,null);java.util.Arrays.fill(tapEvents,null);
            if (handler != null) handler.removeCallbacks(timer);
        }
        if (closeRing && handler != null) handler.post(() -> bus.send(RuntimeBus.UI, "cancel", new Bundle()));
    }
    private void proAction(String action,boolean release,KeyTarget target,KeyEvent event,long mouseSession){
        if(release){PenMouse.action(action,true,mouseSession);return;}
        if(!current(target)||locked()||mouseSession!=PenMouse.session())return;
        switch(action){
            case "exitMouse"->PenPresentation.reset();
            case "mouse"->{writingAudioGate.reset();PenPresentation.mouseMode(event.getDeviceId());}
            case "laser"->PenPresentation.laserMode(event.getDeviceId());
            case "left","right","leftHold","rightHold"->PenMouse.action(action,false,mouseSession);
            case "scrollUp","scrollDown"->{if(PenMouse.active())PenMouse.action(action,false,mouseSession);else directionHost.run(target,"scroll",action.equals("scrollUp"),()->current(target));}
            case "volumeUp","volumeDown","back","home","recents"->{int code=switch(action){case "volumeUp"->24;case "volumeDown"->25;case "back"->4;case "home"->3;default->187;};long now=SystemClock.uptimeMillis();
                try{android.hardware.input.InputManager manager=context.getSystemService(android.hardware.input.InputManager.class);for(int a:new int[]{0,1})XposedHelpers.callMethod(manager,"injectInputEvent",new KeyEvent(now,now,a,code,0,0,-1,0,0,InputDevice.SOURCE_KEYBOARD),0);}catch(Throwable failure){Log.w("FocusPenPro","System action unavailable",failure);}}
            default->{if(action.startsWith("app:")){android.content.Intent intent=context.getPackageManager().getLaunchIntentForPackage(action.substring(4));if(intent!=null)context.startActivity(intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));}}
        }
    }
}
