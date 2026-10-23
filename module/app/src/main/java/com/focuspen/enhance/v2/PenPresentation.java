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
                output[0]=Float.isFinite(OWNER.x)?OWNER.x:OWNER.frame.exactCenterX();
                output[1]=Float.isFinite(OWNER.y)?OWNER.y:OWNER.frame.exactCenterY();
            }}
            public void fade(int transition){ /* Window changes do not own our mode. */ }
            public void unfade(int transition){ /* Motion will reveal a valid position. */ }
            public void resetPosition(){OWNER.invalidatePosition();}
            public boolean canShowPointer(){return OWNER.screenOn && OWNER.snapshot.mode()!=PresentationState.Mode.IDLE;}
            public void displayId(int id){synchronized(OWNER.positionLock){OWNER.displayId=id;OWNER.updateViewport();}}
            public void displayViewports(List<?> values){synchronized(OWNER.positionLock){OWNER.viewports=List.copyOf(values);OWNER.updateViewport();}}
            public void screenState(boolean on){OWNER.screenOn=on;if(!on)OWNER.submit(OWNER::resetNow);}
        });
    }

    static void bind(Context context,ConfigClient config,Handler worker,BooleanSupplier allowed) {
        PenPresentation o=OWNER;o.context=context;o.config=config;o.worker=worker;o.allowed=allowed;
        o.screenOn=context.getSystemService(android.os.PowerManager.class).isInteractive();
        new PenMouse(worker,source->new SystemMouseDevice(o.loader,0),o::permitted,()->o.submit(()->{
            if(o.snapshot.mode()==PresentationState.Mode.MOUSE)o.apply(o.state.reset());
        }));
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
    static void mouseMode(int device){OWNER.submit(()->{if(OWNER.permitted()){OWNER.device=device;OWNER.apply(OWNER.state.enterMouse());}});}
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
            case SINGLE -> apply(state.focusSingle());
            case DOUBLE -> apply(state.focusDouble());
            case HOLD -> {
                PresentationState.Result result=state.focusHold(effect.pressId(),"toggle".equals(config.get().text(ConfigSpec.LASER_ACTIVATION))
                        ?PresentationState.LaserActivation.TOGGLE:PresentationState.LaserActivation.HOLD);
                held=result.token();heldId=effect.pressId();apply(result);
            }
            case RELEASE_HOLD,CANCEL_HOLD -> {if(effect.pressId()==heldId){apply(state.release(held));held=null;}}
        }
    }}
    private void apply(PresentationState.Result result){
        snapshot=result.snapshot();
        for(PresentationState.Effect effect:result.effects())switch(effect.kind()){
            case CLEAR_INK -> {inkEpoch++;if(window!=null)window.clearInk(inkEpoch);}
            case START_MOUSE -> PenMouse.enter(device);
            case STOP_MOUSE -> PenMouse.exit();
            default -> { }
        }
        render();
        if(worker!=null){worker.removeCallbacks(watch);if(snapshot.mode()!=PresentationState.Mode.IDLE)worker.postDelayed(watch,150);}
    }
    private void render(){
        if(window!=null){window.setColor(COLORS[snapshot.colorIndex()]);window.render(snapshot);}
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
    private void motion(float dx,float dy){
        if(!Float.isFinite(dx)||!Float.isFinite(dy))return;
        if(snapshot.mode()==PresentationState.Mode.MOUSE){PenMouse.relative(dx,dy);return;}
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
