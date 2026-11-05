package com.focuspen.enhance.v2;

import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.InputDevice;
import android.view.KeyEvent;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import de.robv.android.xposed.XposedHelpers;

/** Owns presentation state. The ROM contributes motion transport, never gesture decisions. */
final class PenPresentation {
    static final int[] COLORS={0xffff001f,0xffff9500,0xffffcc00,0xff34c759,0xff32ade6,0xff007aff,0xffaf52de,0xffffffff};
    static final String[] COLOR_NAMES={"红色","橙色","黄色","绿色","青色","蓝色","紫色","白色"};
    private static final PenPresentation OWNER=new PenPresentation();
    private final PresentationState state=new PresentationState(COLORS.length,0);
    private final FocusGesture gesture=new FocusGesture();
    private final Map<Integer,Press> colors=new HashMap<>();
    private record Press(int device,long downTime) { }
    private final Object positionLock=new Object();
    private volatile PresentationState.Snapshot snapshot=state.snapshot();
    private volatile Handler worker;
    private Context context;
    private ConfigClient config;
    private ClassLoader loader;
    private BooleanSupplier allowed=()->false;
    private PortedPresentationWindow window;
    private LaserMotionBridge.Installation bridge;
    private PresentationState.PressToken held;
    private Press proDrawing;
    private long heldId,inkEpoch;
    private int device=-1,streamMode=-1;
    private boolean nativeDrawing;
    private volatile boolean screenOn;
    private int displayId;
    private List<?> viewports=List.of();
    private Rect frame=new Rect();
    private float x=Float.NaN,y=Float.NaN;
    private boolean arrowCursor;
    private final Runnable tick=()->{
        if(!permitted()){resetNow();return;}
        effects(gesture.tick(SystemClock.uptimeMillis()));schedule();
    };
    private final Runnable watch=new Runnable(){public void run(){
        if(snapshot.mode()==PresentationState.Mode.IDLE)return;
        if(!permitted() || device>=0 && InputDevice.getDevice(device)==null){resetNow();return;}
        worker.postDelayed(this,150);
    }};

    static void install(ClassLoader loader) throws Exception {
        OWNER.loader=loader;
        OWNER.bridge=LaserMotionBridge.install(loader,new LaserMotionBridge.Sink(){
            public void move(float dx,float dy){OWNER.motion(dx,dy);}
            public void setPosition(float x,float y){OWNER.position(x,y);}
            public void getPosition(float[] output){synchronized(OWNER.positionLock){
                // 鼠标模式：ROM 指针贴图若显示会与系统虚拟鼠标光标叠成双指针、移动时
                // 来回切换造成闪烁。贴图位置由这里供给，给个屏幕外坐标即可隐形，
                // 同时 canShowPointer 保持 true 以免 ROM 停发相对位移。
                if(OWNER.snapshot.mode()==PresentationState.Mode.MOUSE){output[0]=-5000f;output[1]=-5000f;return;}
                output[0]=Float.isFinite(OWNER.x)?OWNER.x:OWNER.frame.exactCenterX();
                output[1]=Float.isFinite(OWNER.y)?OWNER.y:OWNER.frame.exactCenterY();
            }}
            public void fade(int transition){ /* Window changes do not own our mode. */ }
            public void unfade(int transition){ /* Motion will reveal a valid position. */ }
            public void resetPosition(){OWNER.invalidatePosition();}
            // canShowPointer 不能返回 false：ROM 的指针控制器以它为开关，为 false 时
            // 连隔空相对位移都不再转发，鼠标模式下光标完全不动。鼠标模式下的
            // 双指针"闪烁"改由 getPosition 把贴图画到屏幕外来解决（见下）。
            public boolean canShowPointer(){return OWNER.screenOn && OWNER.snapshot.mode()!=PresentationState.Mode.IDLE;}
            public void displayId(int id){synchronized(OWNER.positionLock){OWNER.displayId=id;OWNER.updateViewport();}}
            public void displayViewports(List<?> values){synchronized(OWNER.positionLock){OWNER.viewports=List.copyOf(values);OWNER.updateViewport();}}
            public void screenState(boolean on){OWNER.screenOn=on;if(!on)OWNER.submit(OWNER::resetNow);}
        });
    }

    static void bind(Context context,ConfigClient config,Handler worker,BooleanSupplier allowed) {
        PenPresentation o=OWNER;o.context=context;o.config=config;o.worker=worker;o.allowed=allowed;
        // PenMotionConnection 原本是死代码（无人实例化），导致触控服务进程从不启动、
        // 主页"触控服务"永远显示未连接；这里在演示层绑定时创建它，由它按需拉起触控服务。
        new PenMotionConnection(context, config);
        o.screenOn=context.getSystemService(android.os.PowerManager.class).isInteractive();
        new PenMouse(worker,source->source<0?null:new SystemMouseDevice(o.loader,0),o::permitted,()->o.submit(()->{
            if(o.snapshot.mode()==PresentationState.Mode.MOUSE)o.apply(o.state.reset());
        }),context,config::get);
        PenMouse.observe(new PenMouse.CursorObserver(){
            public void moved(float dx,float dy){o.arrowDelta(dx,dy);}
            public void synced(float px,float py){o.arrowSync(px,py);}
        });
        new Handler(Looper.getMainLooper()).post(()->{
            try {
                PortedPresentationWindow created=new PortedPresentationWindow(context,epoch->o.submit(()->{
                    if(epoch==o.inkEpoch){o.state.inkStored();o.snapshot=o.state.snapshot();}
                }));
                o.submit(()->{o.window=created;o.render();});
            } catch(Throwable failure){Log.e("FocusPenPresentation","Ported window unavailable",failure);o.submit(o::resetNow);}
        });
    }
    private boolean permitted(){return config!=null && config.get().inputActionsReady()
            && config.get().flag(ConfigSpec.KEYS) && screenOn && allowed.getAsBoolean();}
    private void submit(Runnable work){Handler h=worker;if(h==null)return;
        if(Looper.myLooper()==h.getLooper())work.run();else h.post(work);
    }
    static boolean presenting(){PresentationState.Mode mode=OWNER.snapshot.mode();return mode==PresentationState.Mode.LASER||mode==PresentationState.Mode.ANNOTATION;}
    /** A real pen tip touching the panel always returns presentation control to normal writing. */
    static void physicalTipDown(){
        PenPresentation o=OWNER;
        if(o.snapshot.mode()!=PresentationState.Mode.IDLE)o.submit(o::resetNow);
    }
    static boolean colorRoutingActive(){return presenting();}
    static void focusKey(KeyEvent event,boolean eligible,ConfigSnapshot config){
        OWNER.submit(()->{
            if(!eligible||!OWNER.permitted()){OWNER.cancelNow();return;}
            OWNER.device=event.getDeviceId();
            OWNER.effects(OWNER.gesture.event(event.getDeviceId(),event.getKeyCode(),event.getDownTime(),event.getAction(),
                    event.getRepeatCount(),event.isCanceled(),event.getEventTime(),config.longPressMs(),config.doublePressMs()));
            OWNER.schedule();
        });
    }
    static boolean colorKey(KeyEvent event,int steps,boolean eligible){
        PenPresentation o=OWNER;
        synchronized(o.colors){
            Press old=o.colors.get(event.getKeyCode());
            if(old!=null && old.device()==event.getDeviceId() && old.downTime()==event.getDownTime()){
                if(event.getAction()==KeyEvent.ACTION_UP)o.colors.remove(event.getKeyCode());
                return true;
            }
            if(!eligible||!presenting()||event.getAction()!=KeyEvent.ACTION_DOWN||event.getRepeatCount()!=0)return false;
            o.colors.put(event.getKeyCode(),new Press(event.getDeviceId(),event.getDownTime()));
        }
        o.submit(()->{if(o.permitted())o.apply(o.state.shiftColor(steps));});return true;
    }
    static void proKey(KeyEvent event,int slot){
        OWNER.submit(()->{
            if(slot==1 && event.getAction()==KeyEvent.ACTION_DOWN && event.getRepeatCount()==0)OWNER.resetNow();
            if(slot!=0 || !OWNER.permitted())return;
            Press identity=new Press(event.getDeviceId(),event.getDownTime());
            if(event.getAction()==KeyEvent.ACTION_UP){
                if(identity.equals(OWNER.proDrawing)){OWNER.proDrawing=null;OWNER.apply(OWNER.state.proDraw(false));}
            }else if(event.getRepeatCount()==0 && !event.isCanceled() && !identity.equals(OWNER.proDrawing)){
                if(OWNER.proDrawing!=null)OWNER.apply(OWNER.state.proDraw(false));
                OWNER.proDrawing=identity;OWNER.apply(OWNER.state.proDraw(true));
            }
        });
    }
    static android.graphics.PointF cursorPoint(){
        PenPresentation o=OWNER;
        synchronized(o.positionLock){
            if(o.frame.isEmpty())return null;
            float px=Float.isFinite(o.x)?o.x:o.frame.exactCenterX();
            float py=Float.isFinite(o.y)?o.y:o.frame.exactCenterY();
            return new android.graphics.PointF(px,py);
        }
    }
    static void mouseMode(int device){OWNER.submit(()->{if(OWNER.permitted()){OWNER.device=device;OWNER.apply(OWNER.state.enterMouse());}});}
    static void toggleMouse(){OWNER.submit(()->{if(!OWNER.permitted())return;if(OWNER.snapshot.mode()==PresentationState.Mode.MOUSE)OWNER.resetNow();else{OWNER.device=-1;OWNER.apply(OWNER.state.enterMouse());}});}
    static void laserMode(int device){OWNER.submit(()->{if(OWNER.permitted()){OWNER.device=device;OWNER.apply(OWNER.state.enterLaser());}});}
    static void cancelGesture(){OWNER.submit(OWNER::cancelNow);}
    // Keyboard focus can move to the shade while the same mouse drag is in flight.
    // InputDispatcher owns the pointer target; only physical UP or explicit cancellation releases it.
    static void contextChanged(){OWNER.submit(()->{OWNER.cancelNow();OWNER.apply(OWNER.state.contextChanged());});}
    static void reset(){OWNER.submit(OWNER::resetNow);}
    private void cancelNow(){effects(gesture.cancel());if(proDrawing!=null){proDrawing=null;apply(state.proDraw(false));}if(worker!=null)worker.removeCallbacks(tick);}
    private void resetNow(){cancelNow();held=null;synchronized(colors){colors.clear();}apply(state.reset());}
    private void schedule(){worker.removeCallbacks(tick);long next=gesture.nextDeadline();if(next!=Long.MAX_VALUE)worker.postAtTime(tick,next);}
    private void effects(List<FocusGesture.Effect> effects){for(FocusGesture.Effect effect:effects){
        switch(effect.kind()){
            case SINGLE -> {
                if(snapshot.mode()==PresentationState.Mode.MOUSE){
                    if(!PenMouse.wheelActive())mouseFocus(ConfigSpec.MOUSE_FOCUS_SINGLE);
                }
                else apply(state.focusSingle());
            }
            case DOUBLE -> {
                // 鼠标模式下双击焦点键默认进入/退出滚轮模式，也可在设置中改为其他动作。
                if(snapshot.mode()==PresentationState.Mode.MOUSE){
                    if(PenMouse.wheelActive())PenMouse.toggleWheel();
                    else{
                        String action;
                        try{action=config.get().text(ConfigSpec.MOUSE_FOCUS_DOUBLE);}catch(Throwable failure){action="wheel";}
                        if("wheel".equals(action))PenMouse.toggleWheel();
                        else mouseFocus(ConfigSpec.MOUSE_FOCUS_DOUBLE);
                    }
                }
                else apply(state.focusDouble());
            }
            case HOLD -> {
                if(snapshot.mode()==PresentationState.Mode.MOUSE){mouseFocus(ConfigSpec.MOUSE_FOCUS_HOLD);}
                else{
                PresentationState.Result result=state.focusHold(effect.pressId(),"toggle".equals(config.get().text(ConfigSpec.LASER_ACTIVATION))
                        ?PresentationState.LaserActivation.TOGGLE:PresentationState.LaserActivation.HOLD);
                held=result.token();heldId=effect.pressId();apply(result);
                }
            }
            case RELEASE_HOLD,CANCEL_HOLD -> {if(effect.pressId()==heldId){apply(state.release(held));held=null;}}
        }
    }}
    private void mouseFocus(String key){
        String action;
        try{action=config.get().text(key);}catch(Throwable failure){return;}
        switch(action){
            case "home" -> PenMouse.keyTap(android.view.KeyEvent.KEYCODE_HOME);
            case "recents" -> PenMouse.keyTap(android.view.KeyEvent.KEYCODE_APP_SWITCH);
            case "back" -> PenMouse.keyTap(android.view.KeyEvent.KEYCODE_BACK);
            case "exitMouse" -> apply(state.reset());
            default -> { }
        }
    }
    private void apply(PresentationState.Result result){
        snapshot=result.snapshot();
        for(PresentationState.Effect effect:result.effects())switch(effect.kind()){
            case CLEAR_INK -> {inkEpoch++;if(window!=null)window.clearInk(inkEpoch);}
            case START_MOUSE -> {
                // 单光标方案：不创建系统虚拟鼠标，自绘箭头即唯一光标。
                PenMouse.enter(-1);
                arrowCursor=true;
                synchronized(positionLock){
                    if(!frame.isEmpty()){x=frame.exactCenterX();y=frame.exactCenterY();position(x,y);}
                }
            }
            case STOP_MOUSE -> { PenMouse.exit(); arrowCursor=false; }
            default -> { }
        }
        render();
        if(worker!=null){worker.removeCallbacks(watch);if(snapshot.mode()!=PresentationState.Mode.IDLE)worker.postDelayed(watch,150);}
    }
    private void render(){
        if(window!=null){window.setArrowCursorEnabled(arrowCursor);window.setColor(COLORS[snapshot.colorIndex()]);window.render(snapshot);}
        if(context==null)return;
        int wanted=snapshot.mode()==PresentationState.Mode.IDLE?0:1;
        try {
            if(streamMode!=wanted){
                // Ported from LaserPointerController.sendModeToBluetooth. Mode 1
                // preserves the continuous BLE stream after physical key release.
                Intent intent=new Intent("com.android.input.laser.LASER_STATE").setPackage("com.xiaomi.bluetooth")
                        .putExtra("mode",wanted).addFlags(0x10000000);
                context.sendBroadcast(intent);streamMode=wanted;
            }
            if(nativeDrawing!=snapshot.drawing()){
                Object common=XposedHelpers.callStaticMethod(loader.loadClass("com.android.server.input.config.InputCommonConfig"),"getInstance");
                XposedHelpers.callMethod(common,"setLaserIsDrawing",snapshot.drawing());
                XposedHelpers.callMethod(common,"flushToNative");nativeDrawing=snapshot.drawing();
            }
        } catch(Throwable failure){Log.w("FocusPenPresentation","Motion capability update failed",failure);}
    }
    // 自绘光标：鼠标模式下把系统光标贴图换成空图标，消除系统贴图与 ROM
    // 指针互斥造成的闪烁；箭头由 PortedPresentationWindow 绘制，跟随与隐藏
    // 系统光标完全相同的位移流，点击分发与触摸注入不受影响。设置失败则
    // 保持原生光标（不出现双光标）。
    private void hideSystemCursor(){
        arrowCursor=false;
        if(context==null||loader==null)return;
        try{
            Object local=XposedHelpers.callStaticMethod(loader.loadClass("com.android.server.LocalServices"),"getService",
                    loader.loadClass("com.android.server.input.InputManagerInternal"));
            if(local==null)return;
            Object service=XposedHelpers.getObjectField(local,"this$0");
            int nullType=0;
            try{Object nt=XposedHelpers.getStaticObjectField(android.view.PointerIcon.class,"TYPE_NULL");if(nt instanceof Number)nullType=((Number)nt).intValue();}catch(Throwable ignored){}
            Object icon=null;
            try{icon=XposedHelpers.callStaticMethod(android.view.PointerIcon.class,"getSystemIcon",context,nullType);}catch(Throwable ignored){}
            boolean blank=false;
            if(icon!=null){try{Object t=XposedHelpers.callMethod(icon,"getType");blank=t instanceof Number&&((Number)t).intValue()==nullType;}catch(Throwable ignored){}}
            if(!blank){
                android.graphics.Bitmap bitmap=android.graphics.Bitmap.createBitmap(1,1,android.graphics.Bitmap.Config.ARGB_8888);
                icon=XposedHelpers.callStaticMethod(android.view.PointerIcon.class,"createCustomIcon",bitmap,0f,0f);
            }
            XposedHelpers.callMethod(service,"setCustomPointerIcon",icon);
            arrowCursor=true;
        }catch(Throwable failure){Log.w("FocusPenPresentation","System cursor hide failed; keeping native cursor",failure);}
    }
    private void restoreSystemCursor(){
        if(!arrowCursor)return;
        arrowCursor=false;
        try{
            Object local=XposedHelpers.callStaticMethod(loader.loadClass("com.android.server.LocalServices"),"getService",
                    loader.loadClass("com.android.server.input.InputManagerInternal"));
            if(local==null)return;
            Object service=XposedHelpers.getObjectField(local,"this$0");
            XposedHelpers.callMethod(service,"setCustomPointerIcon",new Object[]{null});
        }catch(Throwable ignored){}
    }
    private void arrowDelta(float dx,float dy){
        if(!arrowCursor)return;
        if(PenMouse.wheelActive())return;
        if(!Float.isFinite(dx)||!Float.isFinite(dy))return;
        synchronized(positionLock){
            if(frame.isEmpty())return;
            if(!Float.isFinite(x)||!Float.isFinite(y)){x=frame.exactCenterX();y=frame.exactCenterY();}
            position(x+dx,y+dy);
        }
        PenMouse.dragCursor();
    }
    private void arrowSync(float px,float py){
        if(!arrowCursor)return;
        if(!Float.isFinite(px)||!Float.isFinite(py))return;
        synchronized(positionLock){
            if(frame.isEmpty())return;
            position(px,py);
        }
    }
    private void motion(float dx,float dy){
        if(!Float.isFinite(dx)||!Float.isFinite(dy))return;
        if(snapshot.mode()==PresentationState.Mode.MOUSE){arrowDelta(dx,dy);return;}
        synchronized(positionLock){
            if(frame.isEmpty())return;
            if(!Float.isFinite(x)||!Float.isFinite(y)){x=frame.exactCenterX();y=frame.exactCenterY();}
            position(x+dx,y+dy);
        }
    }
    private void position(float px,float py){
        synchronized(positionLock){
            if(!Float.isFinite(px)||!Float.isFinite(py)||frame.isEmpty())return;
            x=Math.max(frame.left,Math.min(frame.right-1,px));y=Math.max(frame.top,Math.min(frame.bottom-1,py));
            float nx=x,ny=y;submit(()->{if(window!=null){state.inkQueued();window.position(nx,ny);}});
        }
    }
    private void invalidatePosition(){synchronized(positionLock){x=y=Float.NaN;}submit(()->{if(window!=null)window.position(Float.NaN,Float.NaN);});}
    private void updateViewport(){
        Rect next=null;
        for(Object viewport:viewports){
            int id=XposedHelpers.getIntField(viewport,"displayId");
            if(id==displayId){next=new Rect((Rect)XposedHelpers.getObjectField(viewport,"logicalFrame"));break;}
        }
        if(next==null)next=new Rect();
        if(!frame.equals(next)){frame=next;invalidatePosition();submit(PenMouse::contextChanged);}
    }
}
