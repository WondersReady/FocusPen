package com.focuspen.enhance.v2;

import android.app.KeyguardManager;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.PowerManager;
import android.view.KeyEvent;
import android.view.InputDevice;
import android.view.View;
import android.util.Log;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Verified ROM's laser lifetime and per-path solid colors. Runs on its existing handler. */
final class PenPresentation {
    static final int[] COLORS = {0xffff001f, 0xffff9500, 0xffffcc00, 0xff34c759, 0xff32ade6, 0xff007aff, 0xffaf52de, 0xffffffff};
    static final String[] COLOR_NAMES = {"红色", "橙色", "黄色", "绿色", "青色", "蓝色", "紫色", "白色"};
    private static volatile Object controller;
    private static volatile Context context;
    private static volatile ConfigClient config;
    private static volatile boolean latched;
    private static boolean latchedMotion;
    private static boolean completedLong;
    private static int focusDevice=-1;
    private static volatile boolean standardFocusDown;
    private static volatile boolean mouseVisual;
    private static long mouseVisualSession;
    // Retain the canceled owner until an explicit new vendor activation. Late queued
    // settings/long-press callbacks must not revive a mouse session that already ended.
    private static volatile long virtualMouseOwner;
    private static final ThreadLocal<Boolean> mouseActivation = ThreadLocal.withInitial(() -> false);
    private static volatile long session;
    private static final ThreadLocal<Boolean> releasing = ThreadLocal.withInitial(() -> false);
    private static final Map<View, Ink> inks = new WeakHashMap<>();
    private static final Map<Integer, Press> presses = new java.util.HashMap<>();
    private static int laserColor, penColor;
    private record Press(int device, long time) { }
    private static final class Ink {
        final Map<Path, Integer> colors = new IdentityHashMap<>();
    }

    static void bind(Context c, ConfigClient client) {
        context=c; config=client;
        client.add(snapshot -> reset());
    }
    private static boolean enabled() {
        ConfigClient client=config; Context c=context;
        return client!=null && client.get().enabled() && client.get().flag(ConfigSpec.KEYS)
                && c.getSystemService(PowerManager.class).isInteractive()
                && !c.getSystemService(KeyguardManager.class).isKeyguardLocked();
    }
    private static Object state(Object c) { return XposedHelpers.getObjectField(c,"mLaserState"); }
    private static int mode(Object c) { return XposedHelpers.getIntField(state(c),"mCurrentMode"); }
    private static Handler handler(Object c) { return (Handler)XposedHelpers.getObjectField(c,"mHandler"); }
    private static boolean active(Object c) {
        return !PenMouse.active() && c!=null && (mode(c)==1 || XposedHelpers.getBooleanField(state(c),"mPresentationVisible"));
    }
    static boolean presenting(){return active(controller);}
    /** True while the standard side keys are reserved for presentation color changes. */
    static boolean colorRoutingActive(){return controller!=null&&enabled()&&active(controller);}
    // The standard pen stops sending air-motion samples after key-up in mode 0.
    // Keep the official continuous BLE stream while leaving the renderer in laser mode.
    private static void latchMotion(Object c,boolean value) {
        if(latchedMotion==value)return;
        latchedMotion=value;
        XposedHelpers.callMethod(c,"sendModeToBluetooth",value?1:mode(c));
    }
    static void mouseMode(boolean value,int device,long version,float x,float y) {
        Object c=controller;if(c==null){if(value)PenMouse.exit();return;}
        handler(c).post(()->{try{synchronized(XposedHelpers.getObjectField(c,"mLock")){
            if(value&&!PenMouse.isSession(version))return;
            if(!value&&(!mouseVisual||mouseVisualSession!=version))return;
            latchMotion(c,false);
            mouseVisual=value;mouseVisualSession=version;latched=false;session++;
            if(value){
                InputDevice input=InputDevice.getDevice(device);
                if(input==null){PenMouse.exit();return;}
                if(Integer.valueOf(8).equals(XposedHelpers.callMethod(input,"isXiaomiStylus"))){
                    virtualMouseOwner=version;
                    mouseActivation.set(true);
                    try{XposedHelpers.callMethod(touchFilm(c),"enableVirtualLaser");}
                    finally{mouseActivation.remove();}
                }
            }else if(virtualMouseOwner==version){
                XposedHelpers.callMethod(c,"turnoffvirtuallaser",1);
            }
            XposedHelpers.callMethod(c,"setKeepPathLocked",false);
            XposedHelpers.callMethod(c,"setModeLocked",value?1:0);
            if(value){XposedHelpers.callMethod(c,"unfadePresentationLocked",0);XposedHelpers.callMethod(c,"setPosition",x,y);}
            else XposedHelpers.callMethod(c,"fadePresentationLocked",0);
        }}catch(Throwable e){mouseVisual=false;PenMouse.exit();Log.w("FocusPenMouse","Native cursor unavailable",e);}});
    }
    private static Object touchFilm(Object c) throws ClassNotFoundException {
        return XposedHelpers.callStaticMethod(Class.forName("com.miui.server.input.stylus.MiuiStylusTouchFilmManager",false,c.getClass().getClassLoader()),"getInstance");
    }
    static void laserMode() {
        Object c=controller;if(c==null)return;
        handler(c).post(()->{try{
            if(!enabled())return;
            XposedHelpers.callMethod(touchFilm(c),"enableVirtualLaser");
        }catch(Throwable e){Log.w("FocusPenLaser","Cannot activate",e);}});
    }

    static void reset() {
        session++;
        Object c=controller; if(c==null)return;
        handler(c).post(() -> {
            try {
                synchronized(XposedHelpers.getObjectField(c,"mLock")) {
                    if(latched) { latched=false; XposedHelpers.callMethod(c,"hideLaserOrStopDrawingLocked"); }
                    latchMotion(c,false);
                }
            } catch(Throwable e) { Log.w("FocusPenLaser","Reset unavailable",e); }
        });
    }
    /** Pair ownership survives mode changes so a released color key cannot become a shortcut. */
    static synchronized boolean colorKey(KeyEvent e, int direction, boolean allowed) {
        Press old=presses.get(e.getKeyCode());
        if(old!=null && old.device==e.getDeviceId() && old.time==e.getDownTime()) {
            if(e.getAction()==KeyEvent.ACTION_UP)presses.remove(e.getKeyCode());
            return true;
        }
        if(e.getAction()!=KeyEvent.ACTION_DOWN || e.getRepeatCount()!=0)return false;
        Object c=controller;
        if(!allowed || !enabled() || c==null || !active(c))return false;
        presses.put(e.getKeyCode(),new Press(e.getDeviceId(),e.getDownTime()));
        long revision=config.get().revision, expectedSession=session;int expectedMode=mode(c);
        handler(c).post(() -> {
            if(!enabled() || config.get().revision!=revision || !active(c)||session!=expectedSession||mode(c)!=expectedMode)return;
            try {
                View view=(View)XposedHelpers.getObjectField(c,"mLaserView");
                if(view!=null)tag(view);
                int current=mode(c)==1 ? penColor : laserColor;
                int next=Math.floorMod(current+direction,COLORS.length);
                if(mode(c)==1)penColor=next;else laserColor=next;
                if(view!=null){split(view);apply(view);view.invalidate();}
            }catch(Throwable failure){Log.w("FocusPenLaser","Color change unavailable",failure);}
        });
        return true;
    }
    static void install(ClassLoader cl) throws Exception {
        Class<?> type=cl.loadClass("com.miui.server.input.stylus.laser.LaserPointerController");
        Class<?> view=cl.loadClass("com.miui.server.input.stylus.laser.LaserView");
        Class<?> pro=cl.loadClass("com.miui.server.input.stylus.MiuiStylusTouchFilmManager");
        pro.getDeclaredMethod("enableVirtualLaser");
        pro.getDeclaredMethod("lambda$enableVirtualLaser$3");
        type.getDeclaredMethod("turnonvirtuallaser");
        type.getDeclaredMethod("turnoffvirtuallaser",int.class);
        type.getDeclaredMethod("turnoffvirtuallasershow");
        for(String name:new String[]{"onLaserKeyLongPressed","interceptLaserKeyUp","hideLaserOrStopDrawingLocked"})type.getDeclaredMethod(name);
        for(String name:new String[]{"onLaserKeyPressed","setModeLocked","fade","unfadePresentationLocked","fadePresentationLocked"})type.getDeclaredMethod(name,int.class);
        type.getDeclaredMethod("interceptLaserKey",KeyEvent.class);
        type.getDeclaredMethod("setKeepPathLocked",boolean.class);
        type.getDeclaredMethod("setPosition",float.class,float.class);
        type.getDeclaredMethod("sendModeToBluetooth",int.class);
        for(String field:new String[]{"mLaserState","mLaserView","mHandler","mLock","mLastDownTime","mHandleByLongPressing","mIsKeyDown","mCanShowLaser"})type.getDeclaredField(field);
        Class<?> stateType=type.getDeclaredField("mLaserState").getType();
        for(String field:new String[]{"mCurrentMode","mPresentationVisible","mKeepPath"})stateType.getDeclaredField(field);
        view.getDeclaredMethod("lambda$onDraw$0",Canvas.class,Path.class);
        view.getDeclaredMethod("setPosition",float.class,float.class);
        view.getDeclaredMethod("setKeepPath",boolean.class);
        view.getDeclaredMethod("clearPath");view.getDeclaredMethod("onDraw",Canvas.class);
        for(String field:new String[]{"mPathList","mPathPaint","mOutBlurPaint","mOutFillPaint","mInPaint","mLaserPenImage","mKeepPath","mX","mY","mMode"})view.getDeclaredField(field);
        List<XC_MethodHook.Unhook> installed=new ArrayList<>();
        try {
        installed.addAll(XposedBridge.hookAllConstructors(type,new XC_MethodHook(){
            @Override protected void afterHookedMethod(MethodHookParam p){controller=p.thisObject;}
        }));
        installed.add(XposedHelpers.findAndHookMethod(pro,"enableVirtualLaser",new XC_MethodHook(){
            @Override protected void beforeHookedMethod(MethodHookParam p){
                if(!mouseActivation.get())virtualMouseOwner=0;
            }
        }));
        installed.add(XposedHelpers.findAndHookMethod(pro,"lambda$enableVirtualLaser$3",new XC_MethodHook(){
            @Override protected void beforeHookedMethod(MethodHookParam p){
                long owner=virtualMouseOwner;
                if(owner!=0&&!PenMouse.isSession(owner))p.setResult(null);
            }
        }));
        installed.add(XposedHelpers.findAndHookMethod(type,"turnonvirtuallaser",new XC_MethodHook(){
            @Override protected void beforeHookedMethod(MethodHookParam p){
                standardFocusDown=false;
                long owner=virtualMouseOwner;
                if(owner!=0&&!PenMouse.isSession(owner))p.setResult(null);
            }
        }));
        installed.add(XposedHelpers.findAndHookMethod(type,"turnoffvirtuallasershow",new XC_MethodHook(){
            @Override protected void beforeHookedMethod(MethodHookParam p){
                if(mouseVisual)PenMouse.exit();
            }
        }));
        installed.add(XposedHelpers.findAndHookMethod(type,"interceptLaserKey",KeyEvent.class,new XC_MethodHook(){
            @Override protected void beforeHookedMethod(MethodHookParam p){
                controller=p.thisObject;
                KeyEvent event=(KeyEvent)p.args[0];
                focusDevice=event.getDeviceId();
                InputDevice input=event.getDevice();
                standardFocusDown=event.getAction()==KeyEvent.ACTION_DOWN && input!=null
                        && Integer.valueOf(3).equals(XposedHelpers.callMethod(input,"isXiaomiStylus"));
                if(enabled() && event.getAction()==KeyEvent.ACTION_DOWN && event.getRepeatCount()==0 && completedLong){
                    XposedHelpers.setLongField(p.thisObject,"mLastDownTime",0);completedLong=false;
                }
            }
        }));
        installed.add(XposedHelpers.findAndHookMethod(type,"onLaserKeyLongPressed",new XC_MethodHook(){
            @Override protected void beforeHookedMethod(MethodHookParam p){
                controller=p.thisObject;
                if(!enabled())return;
                if(PenMouse.active()){XposedHelpers.setBooleanField(p.thisObject,"mHandleByLongPressing",true);p.setResult(null);return;}
                if(!standardFocusDown){
                    if(virtualMouseOwner!=0&&!PenMouse.isSession(virtualMouseOwner))p.setResult(null);
                    return;
                }
                completedLong=true;
                if(!"toggle".equals(config.get().text(ConfigSpec.LASER_ACTIVATION)) || mode(p.thisObject)!=0)return;
                Object c=p.thisObject;
                synchronized(XposedHelpers.getObjectField(c,"mLock")) {
                    if(!XposedHelpers.getBooleanField(c,"mIsKeyDown"))return;
                    XposedHelpers.setBooleanField(c,"mHandleByLongPressing",true);
                    latched=!latched;
                    if(latched){
                        latchMotion(c,true);
                        XposedHelpers.setBooleanField(c,"mCanShowLaser",true);
                        XposedHelpers.callMethod(c,"unfadePresentationLocked",0);
                    }else XposedHelpers.callMethod(c,"hideLaserOrStopDrawingLocked");
                }
                p.setResult(null);
            }
        }));
        installed.add(XposedHelpers.findAndHookMethod(type,"onLaserKeyPressed",int.class,new XC_MethodHook(){
            @Override protected void beforeHookedMethod(MethodHookParam p){
                if(!enabled())return;int count=(Integer)p.args[0];
                if(PenMouse.active()){if(count==2)PenMouse.exit();p.setResult(null);return;}
                Object c=p.thisObject;
                if(count==2&&mode(c)==0&&!XposedHelpers.getBooleanField(state(c),"mPresentationVisible")&&!XposedHelpers.getBooleanField(state(c),"mKeepPath")){
                    PenMouse.enter(focusDevice);p.setResult(null);
                }
            }
        }));
        installed.add(XposedHelpers.findAndHookMethod(type,"interceptLaserKeyUp",new XC_MethodHook(){
            @Override protected void beforeHookedMethod(MethodHookParam p){releasing.set(true);}
            @Override protected void afterHookedMethod(MethodHookParam p){releasing.remove();}
        }));
        installed.add(XposedHelpers.findAndHookMethod(type,"hideLaserOrStopDrawingLocked",new XC_MethodHook(){
            @Override protected void beforeHookedMethod(MethodHookParam p){
                if(mouseVisual&&releasing.get()){p.setResult(null);return;}
                if(enabled()&&latched&&mode(p.thisObject)==0&&releasing.get())p.setResult(null);
                else {latched=false;latchMotion(p.thisObject,false);}
            }
        }));
        installed.add(XposedHelpers.findAndHookMethod(type,"setModeLocked",int.class,new XC_MethodHook(){
            @Override protected void beforeHookedMethod(MethodHookParam p){latched=false;session++;}
            @Override protected void afterHookedMethod(MethodHookParam p){latchMotion(p.thisObject,false);}
        }));
        installed.add(XposedHelpers.findAndHookMethod(type,"fade",int.class,new XC_MethodHook(){
            @Override protected void beforeHookedMethod(MethodHookParam p){
                // The system fades the laser when the pointer crosses a system
                // surface (for example the status bar). That is not a mouse-mode
                // exit; the mouse session owns its visual until the user toggles
                // it off or the focused window becomes invalid.
                if(mouseVisual){p.setResult(null);return;}
                latched=false;latchMotion(p.thisObject,false);session++;PenMouse.exit();
            }
        }));
        installed.add(XposedHelpers.findAndHookMethod(view,"setKeepPath",boolean.class,new XC_MethodHook(){
            @Override protected void afterHookedMethod(MethodHookParam p){if(enabled())tag((View)p.thisObject);}
        }));
        installed.add(XposedHelpers.findAndHookMethod(view,"clearPath",new XC_MethodHook(){
            @Override protected void afterHookedMethod(MethodHookParam p){inks.remove((View)p.thisObject);}
        }));
        installed.add(XposedHelpers.findAndHookMethod(view,"onDraw",Canvas.class,new XC_MethodHook(){
            @Override protected void beforeHookedMethod(MethodHookParam p){
                if(mouseVisual){drawMouse((View)p.thisObject,(Canvas)p.args[0]);p.setResult(null);return;}
                if(enabled())apply((View)p.thisObject);
            }
        }));
        installed.add(XposedHelpers.findAndHookMethod(view,"setPosition",float.class,float.class,new XC_MethodHook(){
            @Override protected void afterHookedMethod(MethodHookParam p){if(mouseVisual)PenMouse.move((Float)p.args[0],(Float)p.args[1]);}
        }));
        installed.add(XposedHelpers.findAndHookMethod(view,"lambda$onDraw$0",Canvas.class,Path.class,new XC_MethodHook(){
            @Override protected void beforeHookedMethod(MethodHookParam p){
                Ink ink=inks.get((View)p.thisObject);if(ink==null)return;
                Integer color=ink.colors.get((Path)p.args[1]);if(color==null)return;
                Paint brush=(Paint)XposedHelpers.getObjectField(p.thisObject,"mPathPaint");
                p.setObjectExtra("focuspen.color",brush.getColor());brush.setColor(color);
            }
            @Override protected void afterHookedMethod(MethodHookParam p){
                Object color=p.getObjectExtra("focuspen.color");if(color instanceof Integer)
                    ((Paint)XposedHelpers.getObjectField(p.thisObject,"mPathPaint")).setColor((Integer)color);
            }
        }));
        } catch(Throwable failure) {
            for(int i=installed.size()-1;i>=0;i--)installed.get(i).unhook();
            controller=null;
            throw new IllegalStateException("Presentation hook installation rolled back",failure);
        }
        XposedBridge.log("FocusPen presentation hooks ready perPathColor=true");
    }
    @SuppressWarnings("unchecked") private static List<Path> paths(View view){return (List<Path>)XposedHelpers.getObjectField(view,"mPathList");}
    private static void tag(View view){
        Ink ink=inks.computeIfAbsent(view,v->new Ink());
        for(Path path:paths(view))ink.colors.putIfAbsent(path,COLORS[penColor]);
    }
    private static void split(View view){
        tag(view);
        if(!XposedHelpers.getBooleanField(view,"mKeepPath"))return;
        List<Path> paths=paths(view);
        float x=XposedHelpers.getFloatField(view,"mX"),y=XposedHelpers.getFloatField(view,"mY");
        if(!paths.isEmpty())paths.get(0).lineTo(x,y);
        Path next=new Path();next.moveTo(x,y);paths.add(0,next);
        inks.get(view).colors.put(next,COLORS[penColor]);
    }
    private static void apply(View view){
        int color=COLORS[XposedHelpers.getIntField(view,"mMode")==1?penColor:laserColor];
        for(String name:new String[]{"mOutBlurPaint","mOutFillPaint","mInPaint"}) {
            Paint paint=(Paint)XposedHelpers.getObjectField(view,name);
            paint.setColor((paint.getColor()&0xff000000)|(color&0x00ffffff));
        }
        ((Drawable)XposedHelpers.getObjectField(view,"mLaserPenImage")).setTint(COLORS[penColor]);
        tag(view);
    }
    private static void drawMouse(View view,Canvas canvas){
        float x=XposedHelpers.getFloatField(view,"mX"),y=XposedHelpers.getFloatField(view,"mY"),d=view.getResources().getDisplayMetrics().density;
        Path pointer=new Path();pointer.moveTo(x,y);pointer.lineTo(x+4*d,y+22*d);pointer.lineTo(x+9*d,y+16*d);pointer.lineTo(x+17*d,y+15*d);pointer.close();
        Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);paint.setColor(0xff202020);paint.setStyle(Paint.Style.FILL);canvas.drawPath(pointer,paint);
        paint.setColor(0xffffffff);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(1.3f*d);canvas.drawPath(pointer,paint);
    }
    private PenPresentation() { }
}
