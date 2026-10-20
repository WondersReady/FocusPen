#!/usr/bin/env python3
"""Run production presentation hooks against queued ROM/Android doubles, not Pro hardware."""
from pathlib import Path
import os
import subprocess

root = Path(__file__).resolve().parents[2]
out = root / 'work/v2/presentation-test'
sources = {
'android/os/Handler': '''package android.os;public class Handler {
 public java.util.ArrayDeque<Runnable> queue=new java.util.ArrayDeque<>();
 public boolean post(Runnable r){queue.add(r);return true;}public void drain(){while(!queue.isEmpty())queue.remove().run();}
}''',
'android/os/PowerManager': 'package android.os;public class PowerManager {public boolean isInteractive(){return true;}}',
'android/app/KeyguardManager': 'package android.app;public class KeyguardManager {public boolean isKeyguardLocked(){return false;}}',
'android/content/Context': '''package android.content;public class Context {
 public <T>T getSystemService(Class<T> c){try{return c.getConstructor().newInstance();}catch(Exception e){throw new RuntimeException(e);}}
}''',
'android/content/res/Resources': 'package android.content.res;public class Resources {public android.util.DisplayMetrics getDisplayMetrics(){return new android.util.DisplayMetrics();}}',
'android/util/DisplayMetrics': 'package android.util;public class DisplayMetrics {public float density=1;}',
'android/util/Log': 'package android.util;public class Log {public static int w(String t,String s,Throwable e){throw new AssertionError(s,e);}}',
'android/view/InputDevice': '''package android.view;public class InputDevice {
 int type;public static InputDevice getDevice(int n){InputDevice d=new InputDevice();d.type=n;return d;}public int isXiaomiStylus(){return type;}
}''',
'android/view/KeyEvent': '''package android.view;public class KeyEvent {
 public static final int ACTION_DOWN=0,ACTION_UP=1;public int action,device,code=193,repeat;public long time=1;
 public KeyEvent(int a,int d){action=a;device=d;}public KeyEvent(int a,int d,int k,long t,int r){action=a;device=d;code=k;time=t;repeat=r;}
 public int getAction(){return action;}public int getDeviceId(){return device;}
 public int getKeyCode(){return code;}public long getDownTime(){return time;}public int getRepeatCount(){return repeat;}
 public InputDevice getDevice(){return InputDevice.getDevice(device);}
}''',
'android/view/View': 'package android.view;public class View {public android.content.res.Resources getResources(){return new android.content.res.Resources();}public void invalidate(){}}',
'android/graphics/PointF': 'package android.graphics;public class PointF {}',
'android/graphics/Canvas': 'package android.graphics;public class Canvas {public int color;public void drawPath(Path p,Paint b){color=b.getColor();}}',
'android/graphics/Path': 'package android.graphics;public class Path {public float x,y;public void lineTo(float x,float y){this.x=x;this.y=y;}public void moveTo(float x,float y){this.x=x;this.y=y;}public void close(){}}',
'android/graphics/Paint': '''package android.graphics;public class Paint {
 public static int ANTI_ALIAS_FLAG=1;public enum Style{FILL,STROKE}int color;
 public Paint(){}public Paint(int f){}public void setColor(int c){color=c;}public int getColor(){return color;}
 public void setStyle(Style s){}public void setStrokeWidth(float w){}
}''',
'android/graphics/drawable/Drawable': 'package android.graphics.drawable;public class Drawable {public void setTint(int c){}}',
'de/robv/android/xposed/XC_MethodHook': '''package de.robv.android.xposed;public class XC_MethodHook {
 public static class MethodHookParam {public Object thisObject;public Object[] args;public Object result;public boolean early;
  java.util.Map<String,Object> extras=new java.util.HashMap<>();public void setResult(Object r){result=r;early=true;}
  public void setObjectExtra(String k,Object v){extras.put(k,v);}public Object getObjectExtra(String k){return extras.get(k);}}
 public static class Unhook {Runnable remove;public Unhook(Runnable r){remove=r;}public void unhook(){remove.run();}}
 protected void beforeHookedMethod(MethodHookParam p)throws Throwable{}protected void afterHookedMethod(MethodHookParam p)throws Throwable{}
}''',
'de/robv/android/xposed/XposedHelpers': '''package de.robv.android.xposed;import java.util.*;import java.lang.reflect.*;public class XposedHelpers {
 public static Map<String,List<XC_MethodHook>> hooks=new HashMap<>();public static int installs,failAt=-1;
 public static XC_MethodHook.Unhook findAndHookMethod(Object c,String n,Object...a){
  if(++installs==failAt)throw new IllegalStateException("injected hook failure");
  List<XC_MethodHook> h=hooks.computeIfAbsent(((Class<?>)c).getName()+"#"+n,k->new ArrayList<>());XC_MethodHook hook=(XC_MethodHook)a[a.length-1];h.add(hook);return new XC_MethodHook.Unhook(()->h.remove(hook));}
 static Field field(Object o,String n)throws Exception{Field f=o.getClass().getDeclaredField(n);f.setAccessible(true);return f;}
 public static Object getObjectField(Object o,String n){try{return field(o,n).get(o);}catch(Exception e){throw new RuntimeException(e);}}
 public static boolean getBooleanField(Object o,String n){return (Boolean)getObjectField(o,n);}public static int getIntField(Object o,String n){return (Integer)getObjectField(o,n);}
 public static float getFloatField(Object o,String n){return (Float)getObjectField(o,n);}public static void setBooleanField(Object o,String n,boolean v){set(o,n,v);}
 public static void setLongField(Object o,String n,long v){set(o,n,v);}static void set(Object o,String n,Object v){try{field(o,n).set(o,v);}catch(Exception e){throw new RuntimeException(e);}}
 public static Object callMethod(Object o,String n,Object...a){return invoke(o.getClass(),o,n,a);}
 public static Object callStaticMethod(Class<?> c,String n,Object...a){return invoke(c,null,n,a);}
 static Object invoke(Class<?> c,Object o,String n,Object[] a){try{
  XC_MethodHook.MethodHookParam p=new XC_MethodHook.MethodHookParam();p.thisObject=o;p.args=a;
  List<XC_MethodHook> list=hooks.getOrDefault(c.getName()+"#"+n,List.of());int ran=0;
  for(XC_MethodHook h:list){h.beforeHookedMethod(p);ran++;if(p.early)break;}
  if(!p.early){Method found=null;for(Method m:c.getDeclaredMethods())if(m.getName().equals(n)&&m.getParameterCount()==a.length){found=m;break;}
   if(found==null)throw new NoSuchMethodException(n);found.setAccessible(true);p.result=found.invoke(o,a);}
  for(int i=ran-1;i>=0;i--)list.get(i).afterHookedMethod(p);return p.result;
 }catch(Throwable t){throw new RuntimeException(c+"."+n,t);}}
 public static void constructed(Object o){for(XC_MethodHook h:hooks.getOrDefault(o.getClass().getName()+"#<init>",List.of()))try{
  XC_MethodHook.MethodHookParam p=new XC_MethodHook.MethodHookParam();p.thisObject=o;p.args=new Object[0];h.afterHookedMethod(p);
 }catch(Throwable t){throw new RuntimeException(t);}}
}''',
'de/robv/android/xposed/XposedBridge': '''package de.robv.android.xposed;public class XposedBridge {
 public static void log(Object o){}public static java.util.Set<XC_MethodHook.Unhook> hookAllConstructors(Class<?> c,XC_MethodHook h){return java.util.Set.of(XposedHelpers.findAndHookMethod(c,"<init>",h));}
}''',
'com/focuspen/enhance/v2/ConfigSpec': 'package com.focuspen.enhance.v2;class ConfigSpec {static String KEYS="keys",LASER_ACTIVATION="laser";}',
'com/focuspen/enhance/v2/ConfigClient': '''package com.focuspen.enhance.v2;class ConfigClient {
 static class Snapshot {long revision=1;boolean enabled=true;boolean enabled(){return enabled;}boolean flag(String k){return true;}String text(String k){return "toggle";}}
 final Snapshot snapshot=new Snapshot();Snapshot get(){return snapshot;}void add(java.util.function.Consumer<Snapshot> c){}
}''',
'com/focuspen/enhance/v2/PenMouse': '''package com.focuspen.enhance.v2;class PenMouse {
 static boolean on;static long generation;static boolean active(){return on;}static boolean isSession(long v){return on&&generation==v;}
 static int enters,lastDevice;static void enter(int d){enters++;lastDevice=d;on=true;}static void exit(){on=false;}static void move(float x,float y){}
}''',
'com/miui/server/input/stylus/laser/LaserView': '''package com.miui.server.input.stylus.laser;import android.graphics.*;public class LaserView extends android.view.View {
 public java.util.List<Path> mPathList=new java.util.ArrayList<>();public Paint mPathPaint=new Paint(),mOutBlurPaint=new Paint(),mOutFillPaint=new Paint(),mInPaint=new Paint();
 public android.graphics.drawable.Drawable mLaserPenImage=new android.graphics.drawable.Drawable();public boolean mKeepPath;public float mX,mY;public int mMode;
 public void lambda$onDraw$0(Canvas c,Path p){c.drawPath(p,mPathPaint);}public void setPosition(float x,float y){mX=x;mY=y;}public void setKeepPath(boolean v){mKeepPath=v;}public void clearPath(){mPathList.clear();}public void onDraw(Canvas c){}
}''',
'com/miui/server/input/stylus/laser/LaserPointerController': '''package com.miui.server.input.stylus.laser;import android.view.*;import android.os.*;import de.robv.android.xposed.XposedHelpers;public class LaserPointerController {
 public static class State {public int mCurrentMode;public boolean mPresentationVisible,mKeepPath;}
 public State mLaserState=new State();public LaserView mLaserView;public Handler mHandler=new Handler();public Object mLock=new Object();
 public long mLastDownTime;public boolean mHandleByLongPressing,mIsKeyDown,mCanShowLaser;public int officialLongs,starts,stops,bluetoothMode;
 public void sendModeToBluetooth(int mode){bluetoothMode=mode;}
 public void onLaserKeyLongPressed(){officialLongs++;mHandleByLongPressing=true;mCanShowLaser=true;mLaserState.mPresentationVisible=true;}
 public void interceptLaserKeyUp(){mIsKeyDown=false;XposedHelpers.callMethod(this,"hideLaserOrStopDrawingLocked");}
 public void hideLaserOrStopDrawingLocked(){mLaserState.mPresentationVisible=false;mCanShowLaser=false;}
 public void onLaserKeyPressed(int count){}public void setModeLocked(int mode){mLaserState.mCurrentMode=mode;}
 public void fade(int t){}public void unfadePresentationLocked(int t){mLaserState.mPresentationVisible=true;}public void fadePresentationLocked(int t){mLaserState.mPresentationVisible=false;}
 public boolean interceptLaserKey(KeyEvent e){mIsKeyDown=e.getAction()==0;return true;}public void setKeepPathLocked(boolean v){mLaserState.mKeepPath=v;}
 public void setPosition(float x,float y){}
 public void turnonvirtuallaser(){starts++;mIsKeyDown=true;mCanShowLaser=true;mHandler.post(()->XposedHelpers.callMethod(this,"onLaserKeyLongPressed"));}
 public void turnoffvirtuallaser(int all){stops++;XposedHelpers.callMethod(this,"turnoffvirtuallasershow");}
 public void turnoffvirtuallasershow(){mIsKeyDown=false;mCanShowLaser=false;mLaserState.mPresentationVisible=false;com.miui.server.input.stylus.MiuiStylusTouchFilmManager.getInstance().setting=0;}
}''',
'com/miui/server/input/stylus/MiuiStylusTouchFilmManager': '''package com.miui.server.input.stylus;import android.os.*;import de.robv.android.xposed.XposedHelpers;public class MiuiStylusTouchFilmManager {
 static final MiuiStylusTouchFilmManager instance=new MiuiStylusTouchFilmManager();public static MiuiStylusTouchFilmManager getInstance(){return instance;}
 public Handler mHandler=new Handler();public int setting,requests;public com.miui.server.input.stylus.laser.LaserPointerController controller;
 public void enableVirtualLaser(){requests++;mHandler.post(()->XposedHelpers.callMethod(this,"lambda$enableVirtualLaser$3"));}
 public void lambda$enableVirtualLaser$3(){setting=1;mHandler.post(()->{if(setting==1)XposedHelpers.callMethod(controller,"turnonvirtuallaser");});}
}''',
'com/focuspen/enhance/v2/PresentationTest': '''package com.focuspen.enhance.v2;
import android.view.*;import de.robv.android.xposed.*;import com.miui.server.input.stylus.*;import com.miui.server.input.stylus.laser.*;
public class PresentationTest {
 static LaserPointerController c;static MiuiStylusTouchFilmManager pro=MiuiStylusTouchFilmManager.getInstance();
 static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
 static void call(String name,Object...args){XposedHelpers.callMethod(c,name,args);}
 static void setup(){c=new LaserPointerController();pro.controller=c;pro.mHandler.queue.clear();pro.setting=0;PenMouse.on=false;XposedHelpers.constructed(c);}
 static void begin(int device,long session){PenMouse.generation=session;PenMouse.on=true;PenPresentation.mouseMode(true,device,session,500,500);c.mHandler.drain();}
 static void end(long session){PenMouse.on=false;PenPresentation.mouseMode(false,8,session,0,0);c.mHandler.drain();}
 static void colorTests(ConfigClient config){
  setup();call("interceptLaserKey",new KeyEvent(0,3));int entries=PenMouse.enters;
  call("onLaserKeyPressed",1);check(PenMouse.enters==entries,"single focus press entered mouse");
  call("onLaserKeyPressed",2);check(PenMouse.on&&PenMouse.enters==entries+1&&PenMouse.lastDevice==3,"standard double focus press did not enter mouse");
  call("onLaserKeyPressed",2);check(!PenMouse.on,"standard double focus press did not exit mouse");
  entries=PenMouse.enters;c.mLaserState.mPresentationVisible=true;call("onLaserKeyPressed",2);
  c.mLaserState.mPresentationVisible=false;c.mLaserState.mCurrentMode=1;call("onLaserKeyPressed",2);
  c.mLaserState.mCurrentMode=0;c.mLaserState.mKeepPath=true;call("onLaserKeyPressed",2);
  check(PenMouse.enters==entries,"mouse stole active presentation double press");
  setup();c.mLaserView=new LaserView();LaserView view=c.mLaserView;
  view.mOutFillPaint.setColor(0x88ff001f);
  call("interceptLaserKey",new KeyEvent(0,3));call("onLaserKeyLongPressed");call("interceptLaserKey",new KeyEvent(1,3));call("interceptLaserKeyUp");
  check(PenPresentation.colorKey(new KeyEvent(0,3,93,100,0),1,true),"active laser did not own color down");
  check(PenPresentation.colorKey(new KeyEvent(0,3,93,100,1),1,true),"color repeat escaped");c.mHandler.drain();
  check(view.mOutFillPaint.getColor()==0x88ff9500,"repeat changed more than one color or lost alpha");
  check(c.mLaserState.mPresentationVisible&&c.mCanShowLaser&&c.bluetoothMode==1&&!c.mIsKeyDown,"color change ended released laser/motion");
  check(PenPresentation.colorKey(new KeyEvent(1,3,93,100,0),1,false),"color UP escaped after permission changed");
  int unchanged=view.mOutFillPaint.getColor();
  check(!PenPresentation.colorKey(new KeyEvent(0,3,92,101,0),1,false),"ineligible down was consumed");
  PenPresentation.colorKey(new KeyEvent(0,3,92,102,0),1,true);config.snapshot.revision++;c.mHandler.drain();
  check(view.mOutFillPaint.getColor()==unchanged,"stale revision recolored view");PenPresentation.colorKey(new KeyEvent(1,3,92,102,0),1,false);
  PenPresentation.colorKey(new KeyEvent(0,3,92,103,0),1,true);call("setModeLocked",1);view.mMode=1;c.mHandler.drain();
  check(view.mOutFillPaint.getColor()==unchanged,"queued laser color crossed mode boundary");PenPresentation.colorKey(new KeyEvent(1,3,92,103,0),1,true);
  android.graphics.Path old=new android.graphics.Path();view.mPathList.add(old);view.mX=42;view.mY=83;view.mKeepPath=true;
  PenPresentation.colorKey(new KeyEvent(0,3,92,104,0),1,true);c.mHandler.drain();PenPresentation.colorKey(new KeyEvent(1,3,92,104,0),1,true);
  check(view.mPathList.size()==2,"active stroke was not split");android.graphics.Path next=view.mPathList.get(0);
  check(old.x==42&&old.y==83&&next.x==42&&next.y==83,"color boundary introduced a stroke gap");
  android.graphics.Canvas canvas=new android.graphics.Canvas();view.mPathPaint.setColor(123);
  XposedHelpers.callMethod(view,"lambda$onDraw$0",canvas,old);check(canvas.color==PenPresentation.COLORS[0],"old ink recolored");
  XposedHelpers.callMethod(view,"lambda$onDraw$0",canvas,next);check(canvas.color==PenPresentation.COLORS[1],"new ink did not use new color");
  check(view.mPathPaint.getColor()==123,"per-path rendering leaked brush color");
  PenPresentation.colorKey(new KeyEvent(0,3,92,105,0),1,true);config.snapshot.enabled=false;c.mHandler.drain();
  check(view.mPathList.size()==2,"disabled queued color split stroke");check(PenPresentation.colorKey(new KeyEvent(1,3,92,105,0),1,false),"disabled UP escaped");config.snapshot.enabled=true;
  call("setModeLocked",0);c.mLaserState.mPresentationVisible=false;
  check(!PenPresentation.colorKey(new KeyEvent(0,3,93,106,0),1,true),"inactive presentation stole normal shortcut");
  c.mLaserState.mPresentationVisible=true;PenMouse.on=true;
  check(!PenPresentation.colorKey(new KeyEvent(0,3,93,107,0),1,true),"presentation stole mouse button");PenMouse.on=false;
 }
 public static void main(String[]args)throws Exception{
  ConfigClient config=new ConfigClient();PenPresentation.bind(new android.content.Context(),config);PenPresentation.install(PresentationTest.class.getClassLoader());
  setup();call("interceptLaserKey",new KeyEvent(0,3));call("onLaserKeyLongPressed");call("interceptLaserKey",new KeyEvent(1,3));call("interceptLaserKeyUp");
  check(c.mLaserState.mPresentationVisible&&c.officialLongs==0,"standard toggle did not remain latched");
  check(c.bluetoothMode==1&&!c.mIsKeyDown&&c.mLaserState.mCurrentMode==0,"released laser did not retain continuous motion without pretending key-down");
  call("interceptLaserKey",new KeyEvent(0,3));call("onLaserKeyLongPressed");check(!c.mLaserState.mPresentationVisible,"second standard hold did not close");
  check(c.bluetoothMode==0,"second hold left pen transmitting");
  call("interceptLaserKey",new KeyEvent(0,3));call("onLaserKeyLongPressed");call("fade",0);check(c.bluetoothMode==0,"screen-off/fade left pen transmitting");
  call("interceptLaserKey",new KeyEvent(0,3));call("onLaserKeyLongPressed");call("setModeLocked",1);check(c.bluetoothMode==1,"annotation handoff stopped continuous motion");
  call("setModeLocked",0);
  call("interceptLaserKey",new KeyEvent(0,3));call("onLaserKeyLongPressed");PenPresentation.reset();c.mHandler.drain();check(c.bluetoothMode==0,"config reset left pen transmitting");
  colorTests(config);
  setup();XposedHelpers.callMethod(pro,"enableVirtualLaser");pro.mHandler.drain();c.mHandler.drain();
  check(c.officialLongs==1,"standard toggle intercepted official Pro activation");
  setup();int prior=pro.requests;begin(3,10);check(pro.requests==prior,"standard mouse enabled Pro hardware pipeline");end(10);
  setup();begin(8,20);pro.mHandler.drain();c.mHandler.drain();check(pro.setting==1&&c.starts==1,"Pro mouse skipped virtual laser pipeline");
  check(c.officialLongs==0&&!c.mLaserState.mKeepPath,"mouse startup started annotation");end(20);check(pro.setting==0&&c.stops==1,"exit did not close virtual laser");
  setup();begin(8,30);end(30);pro.mHandler.drain();c.mHandler.drain();check(pro.setting==0&&c.starts==0,"queued enable revived canceled mouse");
  setup();begin(8,40);pro.mHandler.drain();end(40);call("turnonvirtuallaser");c.mHandler.drain();check(!c.mLaserState.mPresentationVisible,"late observer revived canceled mouse");
  XposedHelpers.callMethod(pro,"enableVirtualLaser");pro.mHandler.drain();c.mHandler.drain();check(c.officialLongs==1,"cancellation blocked later explicit official activation");
  setup();begin(8,50);end(50);begin(8,51);PenPresentation.mouseMode(false,8,50,0,0);c.mHandler.drain();pro.mHandler.drain();c.mHandler.drain();
  check(PenMouse.on&&pro.setting==1&&c.mLaserState.mPresentationVisible,"old cleanup damaged new mouse");
  call("turnoffvirtuallasershow");check(!PenMouse.on,"official close left mouse active");
  setup();PenMouse.on=true;PenMouse.generation=60;PenPresentation.mouseMode(true,8,60,0,0);PenMouse.on=false;c.mHandler.drain();check(pro.setting==0,"queued visual entry ignored cancellation");
  XposedHelpers.hooks.clear();XposedHelpers.installs=0;XposedHelpers.failAt=8;
  try{PenPresentation.install(PresentationTest.class.getClassLoader());throw new AssertionError("failure was not reported");}catch(IllegalStateException expected){}
  check(XposedHelpers.hooks.values().stream().allMatch(java.util.List::isEmpty),"partial hooks were not rolled back");
  System.out.println("Production presentation hooks: standard toggle / color ownership / preserved ink / Pro isolation / virtual lifecycle / stale callbacks / rollback PASS");
 }
}''',
}
files=[]
for name,source in sources.items():
    path=out/(name+'.java')
    path.parent.mkdir(parents=True,exist_ok=True)
    path.write_text(source,encoding='utf-8')
    files.append(str(path))
files.append(str(root/'module/app/src/main/java/com/focuspen/enhance/v2/PenPresentation.java'))
jdk=Path(os.environ.get('JAVA_HOME','/usr/lib/jvm/java-21-openjdk-amd64'))
subprocess.run([str(jdk/'bin/javac'),'-d',str(out),*files],check=True)
subprocess.run([str(jdk/'bin/java'),'-cp',str(out),'com.focuspen.enhance.v2.PresentationTest'],check=True)
