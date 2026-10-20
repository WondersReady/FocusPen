package com.focuspen.enhance.v2;

import android.content.Context;
import android.graphics.Rect;
import android.graphics.Region;
import android.os.Handler;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.util.Log;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Predicate;
import java.util.function.Supplier;
import de.robv.android.xposed.XposedHelpers;

/** One targeted SOURCE_MOUSE session for both pens. All injection is on the runtime worker. */
final class PenMouse {
    private static volatile PenMouse instance;
    private final Handler worker;
    private final NativeKeyReplay replay;
    private final Supplier<KeyTarget> capture;
    private final Predicate<KeyTarget> current;
    private final MouseButtons buttons=new MouseButtons();
    private record Press(KeyEvent event,long session,int button) { }
    private final Map<Integer,Press> owned=new HashMap<>();
    private volatile boolean active;
    private volatile long generation;
    private long visualSession;
    private KeyTarget target;
    private int uid,device;
    private Rect frame;
    private Region touch;
    private volatile float nextX,nextY;
    private float x,y;
    private long downTime;
    private boolean moveQueued;
    private final Runnable watch=new Runnable(){public void run(){if(!active)return;if(!valid()||InputDevice.getDevice(device)==null){stop();return;}worker.postDelayed(this,150);}};
    PenMouse(Handler worker,NativeKeyReplay replay,Supplier<KeyTarget> capture,Predicate<KeyTarget> current){this.worker=worker;this.replay=replay;this.capture=capture;this.current=current;instance=this;}
    static boolean active(){PenMouse m=instance;return m!=null&&m.active;}
    static long session(){PenMouse m=instance;return m==null?0:m.generation;}
    static boolean isSession(long version){PenMouse m=instance;return m!=null&&m.active&&m.generation==version;}
    static void enter(int device){PenMouse m=instance;if(m==null)return;
        long version=m.generation;KeyTarget candidate=m.capture.get();
        m.worker.post(()->m.begin(device,candidate,version));
    }
    static void exit(){PenMouse m=instance;if(m!=null)m.stop();}
    static void move(float x,float y){PenMouse m=instance;if(m!=null)m.position(x,y);}
    static void action(String action,boolean end,long version){PenMouse m=instance;if(m==null)return;m.worker.post(()->{
        if(!end&&!m.valid()){if(version==m.generation)m.stop();return;}
        synchronized(m){if(!m.active||version!=m.generation)return;
        if(end){m.buttons.clear(m::send);return;}
        int b=action.startsWith("right")?2:1;
        if(action.equals("left")||action.equals("right")){m.press(b);if(!m.buttons.release(b,m::send))m.stop();}
        else if(action.equals("leftHold")||action.equals("rightHold"))m.press(b);
        else if(action.equals("scrollUp")||action.equals("scrollDown"))m.event(MotionEvent.ACTION_SCROLL,m.buttons.held(),0,action.equals("scrollUp")?3:-3);
    }});}
    static boolean key(KeyEvent e,int button){PenMouse m=instance;if(m==null)return false;synchronized(m.owned){
        Press owner=m.owned.get(e.getKeyCode());
        if(owner!=null&&owner.event.getDeviceId()==e.getDeviceId()&&owner.event.getDownTime()==e.getDownTime()){
            if(e.getAction()==KeyEvent.ACTION_UP){m.owned.remove(e.getKeyCode());m.worker.post(()->{synchronized(m){if(m.active&&owner.session==m.generation&&!m.buttons.release(owner.button,m::send))m.stop();}});}return true;
        }
        if(!m.active||e.getAction()!=KeyEvent.ACTION_DOWN||e.getRepeatCount()!=0)return false;
        long version=m.generation;m.owned.put(e.getKeyCode(),new Press(new KeyEvent(e),version,button));
        m.worker.post(()->{if(!m.valid())return;synchronized(m){if(m.active&&version==m.generation)m.press(button);}});return true;
    }}
    private void begin(int inputDevice,KeyTarget candidate,long version){
        if(active||generation!=version)return;
        // A queued enter can precede the cleanup posted by exit. Release the old target first.
        if(target!=null)cleanup();
        try{
            if(candidate==null||!current.test(candidate)||candidate.window()==null)return;
            Object window=candidate.window();Object service=XposedHelpers.getObjectField(window,"mWmService");
            synchronized(XposedHelpers.getObjectField(service,"mGlobalLock")){
                if(!Boolean.TRUE.equals(XposedHelpers.callMethod(window,"isVisible"))||(Integer)XposedHelpers.callMethod(window,"getDisplayId")!=0)return;
                frame=new Rect((Rect)XposedHelpers.callMethod(window,"getFrame"));touch=new Region();XposedHelpers.callMethod(window,"getTouchableRegion",touch);
                uid=(Integer)XposedHelpers.callMethod(window,"getOwningUid");
            }
            if(frame.isEmpty()||uid<0)return;
            synchronized(this){
            if(active||generation!=version)return;
            target=candidate;device=inputDevice;x=nextX=frame.exactCenterX();y=nextY=frame.exactCenterY();generation++;active=true;
            visualSession=generation;
            PenPresentation.mouseMode(true,device,visualSession,x,y);worker.post(watch);Log.i("FocusPenMouse","Entered device="+device+" target="+target.pkg());}
        }catch(Throwable failure){Log.w("FocusPenMouse","Cannot enter",failure);stop();}
    }
    private void press(int b){if(buttons.held()==0)downTime=SystemClock.uptimeMillis();if(!buttons.press(b,this::send))stop();}
    private boolean valid(){
        if(!active||target==null||!current.test(target))return false;
        try{Object window=target.window();Object service=XposedHelpers.getObjectField(window,"mWmService");
            synchronized(XposedHelpers.getObjectField(service,"mGlobalLock")){
                Region region=new Region();XposedHelpers.callMethod(window,"getTouchableRegion",region);
                return frame.equals(XposedHelpers.callMethod(window,"getFrame"))&&touch.equals(region)
                        &&Boolean.TRUE.equals(XposedHelpers.callMethod(window,"isVisible"))
                        &&uid==(Integer)XposedHelpers.callMethod(window,"getOwningUid");
            }}
        catch(Throwable e){return false;}
    }
    private void position(float px,float py){synchronized(this){
        if(!active||!Float.isFinite(px)||!Float.isFinite(py))return;nextX=px;nextY=py;if(moveQueued)return;moveQueued=true;long version=generation;
        worker.post(()->{if(!valid()){if(version==generation)stop();return;}synchronized(this){if(version!=generation)return;moveQueued=false;if(!active)return;x=nextX;y=nextY;
            if(touch.contains((int)x,(int)y)) {if(!send(buttons.held()==0?MotionEvent.ACTION_HOVER_MOVE:MotionEvent.ACTION_MOVE,buttons.held(),0))stop();}
            else if(buttons.held()!=0)stop();
        }});
    }}
    private void stop(){synchronized(this){active=false;long version=++generation;moveQueued=false;worker.post(()->{synchronized(this){if(!active&&generation==version)cleanup();}});}}
    private void cleanup(){buttons.clear(this::send);worker.removeCallbacks(watch);PenPresentation.mouseMode(false,device,visualSession,0,0);target=null;}
    private boolean send(int action,int state,int changed){return event(action,state,changed,0);}
    private boolean event(int action,int state,int changed,float scroll){
        if(target==null)return false;
        if((action==0||action==11)&&!touch.contains((int)x,(int)y))return false;
        MotionEvent.PointerProperties p=new MotionEvent.PointerProperties();p.id=0;p.toolType=MotionEvent.TOOL_TYPE_MOUSE;
        MotionEvent.PointerCoords c=new MotionEvent.PointerCoords();c.x=x;c.y=y;c.pressure=state==0?0:1;c.setAxisValue(MotionEvent.AXIS_VSCROLL,scroll);
        MotionEvent e=MotionEvent.obtain(downTime,SystemClock.uptimeMillis(),action,1,new MotionEvent.PointerProperties[]{p},new MotionEvent.PointerCoords[]{c},0,state,1,1,-1,0,InputDevice.SOURCE_MOUSE,0);
        try{XposedHelpers.callMethod(e,"setDisplayId",0);if(changed!=0)XposedHelpers.callMethod(e,"setActionButton",changed);replay.dispatchPointer(e,uid);return true;}
        catch(Throwable failure){Log.w("FocusPenMouse","Pointer delivery failed",failure);return false;}finally{e.recycle();}
    }
}
