#!/usr/bin/env python3
"""Run the production PenKeyHost with deterministic input/config/worker doubles."""
from pathlib import Path
import os
import subprocess

root = Path(__file__).resolve().parents[2]
out = root / "work/v2/key-target-test"
sources = {
"android/os/Bundle.java": '''package android.os; public class Bundle extends java.util.HashMap<String,Object> {
 public void putString(String k,String v){put(k,v);} public String getString(String k,String d){return (String)getOrDefault(k,d);}
 public void putBoolean(String k,boolean v){put(k,v);} public boolean getBoolean(String k){return (boolean)getOrDefault(k,false);}
 public void putInt(String k,int v){put(k,v);} public int getInt(String k,int d){return (int)getOrDefault(k,d);}
}''',
"android/os/Handler.java": '''package android.os; public class Handler {
 public Handler(){} public Handler(Looper looper){} public boolean postDelayed(Runnable r,long delay){timer=r;return true;}
 public final java.util.ArrayDeque<Runnable> queue=new java.util.ArrayDeque<>(); public Runnable timer;
 public boolean post(Runnable r){queue.add(r);return true;} public boolean postAtTime(Runnable r,long t){timer=r;return true;}
 public void removeCallbacks(Runnable r){queue.removeIf(x->x==r);if(timer==r)timer=null;}
 public void removeMessages(int what){}
 public void drain(){while(!queue.isEmpty())queue.remove().run();}
}''',
"android/os/SystemClock.java": 'package android.os; public class SystemClock {public static long now; public static long uptimeMillis(){return now;}}',
"android/os/Looper.java": 'package android.os; public class Looper {public static Looper getMainLooper(){return new Looper();}}',
"android/os/PowerManager.java": 'package android.os; public class PowerManager {public static boolean interactive=true;public boolean isInteractive(){return interactive;}}',
"android/os/Build.java": 'package android.os; public class Build {public static String FINGERPRINT="test";}',
"android/content/Context.java": '''package android.content; public class Context {
 public <T>T getSystemService(Class<T> c){try{return c.getConstructor().newInstance();}catch(Exception e){throw new RuntimeException(e);}}
}''',
"android/view/InputDevice.java": '''package android.view; public class InputDevice {
 public static final int SOURCE_KEYBOARD=257; public int type=3;public int isXiaomiStylus(){return type;}
 public String getName(){return "Focus Pen test";} public int getVendorId(){return 1;}
 public int getProductId(){return 2;} public String getDescriptor(){return "test-pen";}
}''',
"android/view/MotionEvent.java": 'package android.view; public class MotionEvent {}',
"android/view/KeyEvent.java": '''package android.view; public class KeyEvent {
 public static final int ACTION_DOWN=0,ACTION_UP=1,FLAG_LONG_PRESS=128,FLAG_CANCELED=32;
 final long down,time; final int action,code,repeat,meta,device,scan,flags,source;
 public KeyEvent(long d,long t,int a,int c,int r,int m,int dev,int sc,int f,int s){down=d;time=t;action=a;code=c;repeat=r;meta=m;device=dev;scan=sc;flags=f;source=s;}
 public KeyEvent(KeyEvent e){this(e.down,e.time,e.action,e.code,e.repeat,e.meta,e.device,e.scan,e.flags,e.source);}
 public static KeyEvent changeFlags(KeyEvent e,int flags){return new KeyEvent(e.down,e.time,e.action,e.code,e.repeat,e.meta,e.device,e.scan,flags,e.source);}
 public long getDownTime(){return down;} public long getEventTime(){return time;} public int getKeyCode(){return code;} public int getDeviceId(){return device;}
 public int getRepeatCount(){return repeat;} public int getAction(){return action;} public int getMetaState(){return meta;}
 public int getSource(){return source;} public int getFlags(){return flags;} public int getScanCode(){return scan;}
 public boolean isCanceled(){return (flags&FLAG_CANCELED)!=0;} public InputDevice getDevice(){InputDevice d=new InputDevice();d.type=device==8?8:3;return d;}
}''',
"android/util/Log.java": '''package android.util; public class Log {
 public static int i(String t,String s){return 0;} public static int w(String t,String s){return 0;}
 public static int w(String t,String s,Throwable e){return 0;} public static int e(String t,String s,Throwable e){throw new AssertionError(s,e);}
}''',
"de/robv/android/xposed/XC_MethodHook.java": '''package de.robv.android.xposed; public class XC_MethodHook {
 public static class Unhook {public void unhook(){}}
 public static class MethodHookParam {public Object thisObject;public Object[] args;public void setResult(Object o){} public void setObjectExtra(String k,Object v){} public Object getObjectExtra(String k){return null;}}
 protected void beforeHookedMethod(MethodHookParam p)throws Throwable{} protected void afterHookedMethod(MethodHookParam p)throws Throwable{}
}''',
"de/robv/android/xposed/XposedHelpers.java": '''package de.robv.android.xposed; public class XposedHelpers {
 public static int policyCalls;
 public static XC_MethodHook.Unhook findAndHookMethod(Object c,String n,Object...a){return new XC_MethodHook.Unhook();}
 public static Object getObjectField(Object o,String n){try{return o.getClass().getField(n).get(o);}catch(Exception e){throw new RuntimeException(e);}}
 public static boolean getBooleanField(Object o,String n){return (Boolean)getObjectField(o,n);}
 public static int getIntField(Object o,String n){return (Integer)getObjectField(o,n);}
 public static void setBooleanField(Object o,String n,boolean v){} public static Object callMethod(Object o,String n,Object...a){if(n.equals("interceptKeyBeforeQueueing"))policyCalls++;return n.equals("keyguardOn")?Boolean.FALSE:1;}
}''',
"de/robv/android/xposed/XposedBridge.java": 'package de.robv.android.xposed; public class XposedBridge {public static void log(Object o){}}',
"com/focuspen/enhance/v2/BuildConfig.java": 'package com.focuspen.enhance.v2; class BuildConfig {static final boolean DEBUG=true;static String VERSION_NAME="test";}',
"com/focuspen/enhance/v2/ConfigSpec.java": 'package com.focuspen.enhance.v2; class ConfigSpec {static String KEYS="keys",RING="ring",CONTENT="content",SCREEN_OFF="screenOff";}',
"com/focuspen/enhance/v2/ConfigSnapshot.java": '''package com.focuspen.enhance.v2; class ConfigSnapshot {
 final long revision; final String adapter; final PenAction action; boolean available=true;
 PenAction first=PenAction.UP,second=PenAction.DOWN,pro=PenAction.RING,twice=PenAction.NONE;
 ConfigSnapshot(long r,String a,PenAction p){revision=r;adapter=a;action=p;}
 boolean enabled(){return available;} boolean inputActionsReady(){return available&&ready;} boolean bootOnly(){return !ready;}
 boolean ready=true,keys=true;boolean flag(String k){return k.equals(ConfigSpec.KEYS)?keys:true;} String text(String k){return "";}
 int longPressMs(){return 250;} int doublePressMs(){return 280;} boolean hasGlobalNoteBinding(){return false;}
 String direction(String pkg,int b){return adapter;} PenAction action(String p,int b,boolean d){return d?twice:action;}
 PenAction shortAction(String pkg,int b){return b==1?first:second;} PenAction proAction(String pkg,int slot){return pro;}
}''',
"com/focuspen/enhance/v2/ConfigClient.java": '''package com.focuspen.enhance.v2; class ConfigClient {
 interface Listener {void changed(ConfigSnapshot s);} static ConfigClient last;
 ConfigSnapshot state=new ConfigSnapshot(1,"page",PenAction.RING); Listener listener;
 ConfigClient(android.content.Context c){last=this;} ConfigSnapshot get(){return state;} void refresh(){} void close(){}
 void add(Listener l){listener=l;l.changed(state);}
}''',
"com/focuspen/enhance/v2/RuntimeBus.java": '''package com.focuspen.enhance.v2; class RuntimeBus {
 static String UI="ui",INPUT="input"; static RuntimeBus last; android.os.Handler worker=new android.os.Handler();
 interface Receiver {void receive(String e,android.os.Bundle b);} final java.util.List<String> events=new java.util.ArrayList<>();
 RuntimeBus(android.content.Context c,Receiver r,Runnable refresh){last=this;} void close(){}
 boolean available(String p){return true;} boolean send(String p,String e,android.os.Bundle b){events.add(e);return true;}
}''',
"com/focuspen/enhance/v2/NativeKeyReplay.java": '''package com.focuspen.enhance.v2; class NativeKeyReplay {
 static final java.util.List<android.view.KeyEvent> sent=new java.util.ArrayList<>(); static void verify(ClassLoader c){}
 static final java.util.List<Object> windows=new java.util.ArrayList<>();
 void dispatch(android.view.KeyEvent e){sent.add(e);}
 void dispatchToApplication(android.view.KeyEvent e,Object window){sent.add(e);windows.add(window);}
}''',
"com/focuspen/enhance/v2/OfficialPenActions.java": '''package com.focuspen.enhance.v2; class OfficialPenActions {
 static int count; static void initialize(ClassLoader c){} static String id(PenAction a){return a==PenAction.NOTE?"note":null;}
 static boolean run(android.content.Context c,String a){count++;return true;}
}''',
"com/focuspen/enhance/v2/RingContent.java": '''package com.focuspen.enhance.v2; class RingContent {
 record Item(boolean enabled){} static RingContent parse(String s){return new RingContent();} Item find(String s){return null;} static boolean isAction(String s){return false;}
}''',
"com/focuspen/enhance/v2/SystemPenMotion.java": 'package com.focuspen.enhance.v2; class SystemPenMotion {SystemPenMotion(android.content.Context c,ConfigClient f,RuntimeBus b){}}',
"com/focuspen/enhance/v2/WindowDirectionHost.java": '''package com.focuspen.enhance.v2; class WindowDirectionHost {
 static int count; static String adapter; static boolean upward; WindowDirectionHost(android.os.Handler h,NativeKeyReplay r){}
 void run(KeyTarget t,String a,boolean up,java.util.function.BooleanSupplier valid){if(valid.getAsBoolean()){count++;adapter=a;upward=up;}}
}''',
"com/focuspen/enhance/v2/UnlockGate.java": 'package com.focuspen.enhance.v2; class UnlockGate {static void run(android.content.Context c,Runnable r){r.run();}}',
"com/focuspen/enhance/v2/KeyTargetHostTest.java": '''package com.focuspen.enhance.v2;
import android.view.KeyEvent; import android.os.SystemClock; import java.lang.reflect.*;
public class KeyTargetHostTest {
 public static class StylusManager {
  public static StylusManager instance=new StylusManager();public boolean mIsGameMode;
  public static StylusManager getInstance(){return instance;}
 }
 public static class ProManager {public boolean mIsGameMode=false,mIsTouchFilmStylusConnected=true,mCurrentSlideStatus=true,mCurrentDoubleTapStatus=true;public int mCurrentPinchStatus=3,mCurrentVirtualLaserStatus=0;}
 static PenKeyHost host; static Object window; static long time;
 static Object call(String name,Class<?>[] types,Object...args)throws Exception {Method m=PenKeyHost.class.getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(host,args);}
 static void focus(Object w,String pkg)throws Exception {call("focusChanged",new Class[]{Object.class,String.class},w,pkg);}
 static void event(int action,long start,long now)throws Exception {SystemClock.now=now;call("key",new Class[]{KeyEvent.class,boolean.class},new KeyEvent(start,now,action,93,0,0,1,0,0,257),true);}
 static void directionEvent(boolean up,int action,long start,long now)throws Exception {SystemClock.now=now;call("key",new Class[]{KeyEvent.class,boolean.class},new KeyEvent(start,now,action,up?93:92,0,0,1,0,0,257),true);}
 static void repeat(long start,long now)throws Exception {SystemClock.now=now;call("key",new Class[]{KeyEvent.class,boolean.class},new KeyEvent(start,now,0,93,1,0,1,0,0,257),true);}
 static void tick(long now)throws Exception {SystemClock.now=now;Field f=PenKeyHost.class.getDeclaredField("timer");f.setAccessible(true);((Runnable)f.get(host)).run();}
 static void drain(){RuntimeBus.last.worker.drain();}
 static void setup(String adapter,PenAction action)throws Exception {
  Constructor<PenKeyHost> c=PenKeyHost.class.getDeclaredConstructor(android.content.Context.class);c.setAccessible(true);host=c.newInstance(new android.content.Context());
  Field f=PenKeyHost.class.getDeclaredField("stylusType");f.setAccessible(true);f.set(null,android.view.InputDevice.class.getMethod("isXiaomiStylus"));
  f=PenKeyHost.class.getDeclaredField("policyInstance");f.setAccessible(true);f.set(host,new Object());
  f=PenKeyHost.class.getDeclaredField("stylusManagerType");f.setAccessible(true);f.set(null,StylusManager.class);
  StylusManager.instance=new StylusManager();PenPresentation.allowed=false;PenPresentation.colorCalls=0;
  android.os.PowerManager.interactive=true;
  ConfigClient.last.state=new ConfigSnapshot(1,adapter,action);window=new Object();focus(window,"reader.example");drain();
  RuntimeBus.last.events.clear();NativeKeyReplay.sent.clear();NativeKeyReplay.windows.clear();OfficialPenActions.count=0;de.robv.android.xposed.XposedHelpers.policyCalls=0;
 }
 static void check(boolean b,String why){if(!b)throw new AssertionError(why);}
 static boolean pro(ProManager manager,int device,int code,int action,long start,long now,boolean screen)throws Exception {
  SystemClock.now=now;return (Boolean)call("proKey",new Class[]{Object.class,KeyEvent.class,boolean.class},manager,new KeyEvent(start,now,action,code,0,0,device,0,0,257),screen);
 }
 public static void main(String[] args)throws Exception {
  // Exercise the production host's eligibility boundary. Color rendering and pair ownership
  // are tested with the real PenPresentation in test_presentation.py; this probe only records routing.
  for(int code:new int[]{93,92}) {
   setup("native",PenAction.NOTE);PenPresentation.showing=true;
   boolean captured=(Boolean)call("key",new Class[]{KeyEvent.class,boolean.class},new KeyEvent(10,10,0,code,0,0,1,0,0,257),true);
   tick(600);drain();
   check(captured&&PenPresentation.allowed&&PenPresentation.colorCalls==1,"Visible presentation must own standard color DOWN");
   check(PenPresentation.direction==(code==93?-1:1),"Color key direction mismatch");
   check(NativeKeyReplay.sent.isEmpty()&&OfficialPenActions.count==0&&!RuntimeBus.last.events.contains("action"),"Color key leaked into ordinary action arbitration");
  }
  setup("native",PenAction.NOTE);PenPresentation.showing=true;
  StylusManager.instance.mIsGameMode=true;event(0,0,0);
  check(!PenPresentation.allowed,"Game mode must exclude presentation colors");
  setup("native",PenAction.NOTE);PenPresentation.showing=true;
  focus(new Object(),"com.android.camera");event(0,0,0);
  check(!PenPresentation.allowed,"Camera must exclude presentation colors");
  setup("native",PenAction.NOTE);PenPresentation.showing=true;
  android.app.KeyguardManager.locked=true;event(0,0,0);
  check(!PenPresentation.allowed,"Keyguard must exclude presentation colors");
  setup("native",PenAction.NOTE);PenPresentation.showing=true;
  ConfigClient.last.state.available=false;event(0,0,0);
  check(!PenPresentation.allowed,"Unavailable configuration must exclude presentation colors");
  setup("native",PenAction.NOTE);PenPresentation.showing=true;
  call("key",new Class[]{KeyEvent.class,boolean.class},new KeyEvent(10,10,0,93,0,0,1,0,0,257),false);
  check(!PenPresentation.allowed,"Screen-off key must not become a color action");
  setup("native",PenAction.NOTE);PenPresentation.showing=true;StylusManager.instance=null;
  event(0,0,0);check(!PenPresentation.allowed,"Missing vendor manager must retain the eligibility guard");
  setup("native",PenAction.RING);event(0,0,0);event(1,0,20);tick(300);drain();
  check(NativeKeyReplay.sent.size()==2&&NativeKeyReplay.sent.get(0).getKeyCode()==93,"Delayed short direction lost original device event");
  setup("native",PenAction.RING);ConfigClient.last.state.first=PenAction.NONE;event(0,0,0);event(1,0,20);tick(300);drain();
  check(NativeKeyReplay.sent.size()==2&&NativeKeyReplay.sent.get(0).getKeyCode()==93&&!RuntimeBus.last.events.contains("action"),"App short must preserve its physical key pair");
  setup("native",PenAction.NONE);ConfigClient.last.state.first=PenAction.NONE;event(0,0,0);drain();
  check(NativeKeyReplay.sent.size()==1&&NativeKeyReplay.sent.get(0).getAction()==0,"All-app configuration must forward DOWN immediately");
  repeat(0,100);drain();event(1,0,500);drain();
  check(NativeKeyReplay.sent.size()==3&&NativeKeyReplay.sent.get(1).getRepeatCount()==1&&NativeKeyReplay.sent.get(2).getAction()==1,"App hold must preserve repeats and release");
  check(de.robv.android.xposed.XposedHelpers.policyCalls==0,"App passthrough must bypass official shortcut policy");
  setup("native",PenAction.NONE);ConfigClient.last.state.first=PenAction.NONE;event(0,0,0);drain();
  ConfigClient.last.state=new ConfigSnapshot(2,"page",PenAction.NOTE);repeat(0,100);drain();event(1,0,200);drain();
  check(NativeKeyReplay.sent.size()==2&&NativeKeyReplay.sent.get(1).getAction()==1&&NativeKeyReplay.sent.get(1).isCanceled(),"Config change must cancel a delivered app DOWN in its unchanged window");
  check(de.robv.android.xposed.XposedHelpers.policyCalls==0,"Cancelled application UP must not enter official shortcut policy");
  setup("native",PenAction.NONE);ConfigClient.last.state.second=PenAction.NONE;
  call("key",new Class[]{KeyEvent.class,boolean.class},new KeyEvent(1000,1000,0,92,0,3,1,44,2,257),true);drain();
  ConfigClient.last.state=new ConfigSnapshot(2,"page",PenAction.NOTE);
  call("key",new Class[]{KeyEvent.class,boolean.class},new KeyEvent(1000,1300,1,92,0,3,1,44,2,257),true);drain();
  KeyEvent cleanup=NativeKeyReplay.sent.get(1);
  check(NativeKeyReplay.sent.size()==2&&cleanup.getDownTime()==1000&&cleanup.getKeyCode()==92&&cleanup.getDeviceId()==1
    &&cleanup.getScanCode()==44&&cleanup.getMetaState()==3&&cleanup.getSource()==257&&cleanup.getFlags()==(2|KeyEvent.FLAG_CANCELED),"Cancelled UP must preserve the physical pen identity and existing flags");
  setup("native",PenAction.NONE);ConfigClient.last.state.first=PenAction.NONE;event(0,0,0);drain();
  ConfigClient.last.state.available=false;event(1,0,200);drain();
  check(NativeKeyReplay.sent.size()==2&&NativeKeyReplay.sent.get(1).isCanceled(),"Unavailable config must still finish the delivered app press");
  setup("native",PenAction.NONE);ConfigClient.last.state.first=PenAction.NONE;event(0,0,0);
  ConfigClient.last.state=new ConfigSnapshot(2,"page",PenAction.NOTE);event(1,0,200);drain();
  check(NativeKeyReplay.sent.isEmpty(),"Cancelled queued DOWN must not produce an orphan UP");
  setup("native",PenAction.NONE);ConfigClient.last.state.first=PenAction.NONE;event(0,0,0);drain();
  focus(new Object(),"reader.example");event(1,0,200);drain();
  check(NativeKeyReplay.sent.size()==1,"App UP must not cross a same-package window change");
  setup("native",PenAction.NONE);ConfigClient.last.state.first=PenAction.NONE;event(0,0,0);drain();
  focus(new Object(),"other.example");focus(window,"reader.example");event(1,0,200);drain();
  check(NativeKeyReplay.sent.size()==1,"Returning to the old window must not revive an app press");
  setup("native",PenAction.NONE);ConfigClient.last.state.first=PenAction.NONE;event(0,0,0);drain();
  android.app.KeyguardManager.locked=true;event(1,0,200);drain();
  check(NativeKeyReplay.sent.size()==1,"Locked window must not receive a replayed app release");
  setup("native",PenAction.NONE);ConfigClient.last.state.first=PenAction.NONE;event(0,0,0);drain();
  android.os.PowerManager.interactive=false;event(1,0,200);drain();
  check(NativeKeyReplay.sent.size()==1,"Sleeping display must not receive a replayed app release");
  setup("native",PenAction.RING);ConfigClient.last.state.first=PenAction.NOTE;event(0,0,0);event(1,0,20);event(0,100,100);event(1,100,120);drain();
  check(NativeKeyReplay.sent.size()==4&&OfficialPenActions.count==0&&de.robv.android.xposed.XposedHelpers.policyCalls==0,"App double gets both original pairs without module short or native shortcut");
  setup("native",PenAction.NONE);ConfigClient.last.state.first=PenAction.NONE;event(0,0,0);drain();event(0,1000,1000);drain();event(1,1000,1100);drain();
  check(NativeKeyReplay.sent.size()==4&&NativeKeyReplay.sent.get(1).isCanceled(),"Missing app UP must cancel the old hold before another DOWN");
  setup("native",PenAction.RING);ConfigClient.last.state.first=PenAction.NONE;event(0,0,0);tick(250);event(1,0,400);drain();
  check(NativeKeyReplay.sent.isEmpty()&&RuntimeBus.last.events.contains("action"),"Custom long must not leak short app keys");
  setup("native",PenAction.RING);ConfigClient.last.state.first=PenAction.NONE;event(0,0,0);event(1,0,20);focus(new Object(),"other.app");drain();
  check(NativeKeyReplay.sent.isEmpty(),"Queued app key pair must not enter another window");
  setup("native",PenAction.RING);ConfigClient.last.state.first=PenAction.NOTE;event(0,0,0);event(1,0,20);tick(300);drain();
  check(OfficialPenActions.count==1&&NativeKeyReplay.sent.isEmpty(),"Short official action not dispatched once");
  setup("native",PenAction.RING);ProManager manager=new ProManager();
  check(!pro(manager,1,194,0,0,0,true),"Standard focus key intercepted as Pro");
  check(pro(manager,8,194,0,0,0,true),"Pro pinch not consumed");drain();
  check(RuntimeBus.last.events.stream().filter(e->e.equals("action")).count()==1,"Pro pinch did not open ring once");
  check(pro(manager,8,194,0,0,10,true),"Repeated pinch not consumed");drain();
  check(RuntimeBus.last.events.stream().filter(e->e.equals("action")).count()==1,"Pinch duplicated action");
  manager.mCurrentVirtualLaserStatus=1;check(pro(manager,8,194,1,0,20,true),"Captured pinch UP leaked on laser change");
  check(!pro(manager,8,194,0,100,100,true),"Laser operation intercepted");
  manager.mCurrentVirtualLaserStatus=0;manager.mIsGameMode=true;check(!pro(manager,8,195,0,200,200,true),"Game guard ignored");
  manager.mIsGameMode=false;manager.mCurrentPinchStatus=0;check(!pro(manager,8,194,0,300,300,true),"Disabled hardware intercepted");
  manager.mCurrentPinchStatus=3;check(!pro(manager,8,194,0,400,400,false),"Locked/screen-off gesture intercepted");
  setup("swipe",PenAction.RING);ConfigClient.last.state.pro=PenAction.UP;WindowDirectionHost.count=0;
  check(pro(manager,8,196,0,500,500,true),"Pro swipe not captured");drain();
  check(WindowDirectionHost.count==1&&WindowDirectionHost.upward,"Pro direction adapter not used");
  setup("native",PenAction.RING);ConfigClient.last.state.pro=PenAction.NOTE;
  pro(manager,8,195,0,600,600,true);focus(new Object(),"other.app");drain();
  check(OfficialPenActions.count==0,"Queued Pro action crossed focus change");
  setup("page",PenAction.RING);event(0,0,0);event(1,0,20);tick(300);drain();
  check(NativeKeyReplay.sent.size()==2 && NativeKeyReplay.sent.get(0).getKeyCode()==92,"unchanged target receives Page Up pair");
  setup("page",PenAction.RING);event(0,0,0);event(1,0,20);tick(300);focus(new Object(),"reader.example");drain();
  check(NativeKeyReplay.sent.isEmpty(),"queued short cannot cross same-package window switch");
  setup("native",PenAction.RING);event(0,0,0);event(1,0,20);tick(300);focus(new Object(),"reader.example");focus(window,"reader.example");drain();
  check(NativeKeyReplay.sent.isEmpty(),"native pending replay cannot revive on focus return");
  setup("page",PenAction.NOTE);event(0,0,0);tick(250);ConfigClient.last.state=new ConfigSnapshot(2,"dpad",PenAction.RING);drain();
  check(OfficialPenActions.count==0,"queued official action rejects snapshot published before listener");
  setup("page",PenAction.NOTE);event(0,0,0);ConfigClient.last.state=new ConfigSnapshot(2,"dpad",PenAction.RING);tick(250);drain();event(1,0,260);drain();
  check(OfficialPenActions.count==0 && !RuntimeBus.last.events.contains("action"),"timer detects unpublished observer update and drains old UP");
  event(0,1000,1000);event(1,1000,1020);tick(1300);drain();
  check(NativeKeyReplay.sent.size()==2 && NativeKeyReplay.sent.get(0).getKeyCode()==19,"next press uses new direction adapter");
  setup("page",PenAction.RING);event(0,0,0);tick(250);focus(new Object(),"com.android.systemui");drain();
  check(!RuntimeBus.last.events.contains("action"),"queued ring action cannot start after focus changes");
  setup("native",PenAction.RING);focus(new Object(),"com.android.systemui");drain();event(0,0,0);event(1,0,20);tick(300);drain();
  check(NativeKeyReplay.sent.isEmpty(),"shade does not receive underlying app direction replay");
  setup("native",PenAction.NONE);ConfigClient.last.state.first=PenAction.NONE;
  Object shade=new Object();focus(shade,"com.android.systemui");drain();event(0,0,0);drain();event(1,0,20);drain();
  check(NativeKeyReplay.sent.size()==2&&NativeKeyReplay.sent.get(0).getAction()==0&&NativeKeyReplay.sent.get(1).getAction()==1
    &&NativeKeyReplay.sent.get(0).getKeyCode()==93&&NativeKeyReplay.sent.get(1).getKeyCode()==93
    &&NativeKeyReplay.windows.size()==2&&NativeKeyReplay.windows.get(0)==shade&&NativeKeyReplay.windows.get(1)==shade
    &&de.robv.android.xposed.XposedHelpers.policyCalls==0,"SystemUI NONE must deliver the full physical pair to its target without official policy");
  setup("native",PenAction.NATIVE);event(0,0,0);tick(250);focus(new Object(),"other.example");drain();event(1,0,300);drain();
  check(NativeKeyReplay.sent.isEmpty(),"queued native hold must not begin in a different window or emit orphan UP");
  setup("native",PenAction.NATIVE);event(0,0,0);event(1,0,300);focus(new Object(),"other.example");drain();
  check(NativeKeyReplay.sent.isEmpty(),"native long pair when UP beats timer must recheck target");
  setup("native",PenAction.NATIVE);event(0,0,0);tick(250);drain();focus(new Object(),"other.example");drain();event(1,0,300);drain();
  // InputDispatcher cancels the old window's press on focus loss; the module must not send its UP to the new window.
  check(NativeKeyReplay.sent.size()==1&&NativeKeyReplay.sent.get(0).getAction()==0
    &&NativeKeyReplay.sent.get(0).getKeyCode()==93&&NativeKeyReplay.sent.get(0).getDeviceId()==1
    &&NativeKeyReplay.windows.size()==1&&NativeKeyReplay.windows.get(0)==window,
    "focus change must leave only the original DOWN bound to its original window");
  setup("native",PenAction.NATIVE);event(0,0,0);tick(250);event(1,0,300);drain();
  check(NativeKeyReplay.sent.size()==2,"UP queued before DOWN executes still produces a complete pair");
  setup("native",PenAction.NATIVE);event(0,0,0);tick(250);drain();repeat(0,270);focus(new Object(),"other.example");drain();event(1,0,300);drain();
  check(NativeKeyReplay.sent.size()==1&&NativeKeyReplay.sent.get(0).getAction()==0
    &&NativeKeyReplay.sent.get(0).getRepeatCount()==0&&NativeKeyReplay.windows.size()==1
    &&NativeKeyReplay.windows.get(0)==window,
    "queued native repeat and old UP must not cross the window switch");
  setup("native",PenAction.NATIVE);event(0,0,0);tick(250);ConfigClient.last.state=new ConfigSnapshot(2,"dpad",PenAction.RING);repeat(0,270);event(1,0,300);drain();
  check(NativeKeyReplay.sent.isEmpty(),"config change drops queued DOWN, repeats and orphan release");
  event(0,1000,1000);event(1,1000,1020);tick(1300);drain();
  check(NativeKeyReplay.sent.size()==2 && NativeKeyReplay.sent.get(0).getKeyCode()==19,"new gesture still works after dropped native hold");
  setup("native",PenAction.NATIVE);event(0,0,0);tick(250);drain();event(0,1000,1000);tick(1250);event(1,1000,1300);drain();
  check(NativeKeyReplay.sent.size()==4 && NativeKeyReplay.sent.get(1).isCanceled() && NativeKeyReplay.sent.get(2).getDownTime()==1000,"lost UP cancels dispatched old hold and new hold remains paired");
  WindowDirectionHost.count=0;setup("swipe",PenAction.RING);event(0,0,0);event(1,0,20);tick(300);drain();
  check(WindowDirectionHost.count==1 && NativeKeyReplay.sent.isEmpty(),"swipe routes to pointer adapter, not DPAD");
  WindowDirectionHost.count=0;setup("scroll",PenAction.RING);event(0,0,0);event(1,0,20);tick(300);focus(new Object(),"other.example");drain();
  check(WindowDirectionHost.count==0,"queued scroll drops on window switch");
  for(String kind:new String[]{"page","dpad","scroll","swipe"}) for(boolean reverse:new boolean[]{false,true}) for(boolean up:new boolean[]{false,true}) {
   String adapter=kind+(reverse?"_reverse":"");
   setup(adapter,PenAction.RING);WindowDirectionHost.count=0;
   directionEvent(up,0,0,0);directionEvent(up,1,0,20);tick(300);drain();
   boolean executionUp=up!=reverse;
   if(kind.equals("page")||kind.equals("dpad")) {
    int expected=kind.equals("page")?(executionUp?92:93):(executionUp?19:20);
    check(NativeKeyReplay.sent.size()==2 && NativeKeyReplay.sent.get(0).getKeyCode()==expected
      && NativeKeyReplay.sent.get(1).getKeyCode()==expected && NativeKeyReplay.sent.get(1).getAction()==1,"paired direction "+adapter+" up="+up);
   } else check(WindowDirectionHost.count==1 && WindowDirectionHost.adapter.equals(kind)
      && WindowDirectionHost.upward==executionUp && NativeKeyReplay.sent.isEmpty(),"pointer direction "+adapter+" up="+up);
   setup(adapter,PenAction.RING);WindowDirectionHost.count=0;
   directionEvent(up,0,0,0);directionEvent(up,1,0,20);tick(300);focus(new Object(),"other.example");drain();
   check(NativeKeyReplay.sent.isEmpty() && WindowDirectionHost.count==0,"reverse adapter retains focus guard "+adapter);
  }
  System.out.println("Production PenKeyHost delayed actions, configuration publication and window identity: PASS");
 }
}''',
}
sources.update({
"android/app/KeyguardManager.java": 'package android.app; public class KeyguardManager {public static boolean locked;public boolean isKeyguardLocked(){return locked;}}',
"android/content/Intent.java": 'package android.content; public class Intent {public static int FLAG_ACTIVITY_NEW_TASK=1;public Intent addFlags(int f){return this;}}',
"android/content/pm/PackageManager.java": 'package android.content.pm;public class PackageManager {public android.content.Intent getLaunchIntentForPackage(String p){return null;}}',
"android/hardware/input/InputManager.java": 'package android.hardware.input;public class InputManager {}',
"com/focuspen/enhance/v2/PenPresentation.java": '''package com.focuspen.enhance.v2;class PenPresentation {
 static boolean showing,allowed,colorActive;static int colorCalls,direction,focusCalls,proCalls;static void install(ClassLoader c){}
 static java.util.function.BooleanSupplier boundAllowed;
 static void bind(android.content.Context c,ConfigClient f,android.os.Handler h,java.util.function.BooleanSupplier active){boundAllowed=active;}
 static int resets;static void reset(){resets++;}static void cancelGesture(){}static void contextChanged(){}
 static void focusKey(android.view.KeyEvent e,boolean active,ConfigSnapshot s){focusCalls++;allowed=active;}
 static void proKey(android.view.KeyEvent e,int slot){proCalls++;}
 static void mouseMode(int device){}static void laserMode(int device){}
 static boolean colorRoutingActive(){return colorActive;}
 static boolean presenting(){return showing;}static boolean colorKey(android.view.KeyEvent e,int d,boolean a){allowed=a;direction=d;colorCalls++;return showing&&a;}
}''',
"com/focuspen/enhance/v2/PenDockMonitor.java": '''package com.focuspen.enhance.v2;class PenDockMonitor {
 static final int UNKNOWN=-1,DOCKED=0,DETACHED=1;static PenDockMonitor last;
 final java.util.function.IntConsumer changed;
 PenDockMonitor(android.content.Context c,android.os.Handler h,java.util.function.IntConsumer changed){
  this.changed=changed;last=this;h.post(()->changed.accept(DETACHED));
 }
 void close(){}
}''',
"com/focuspen/enhance/v2/PenMouse.java": '''package com.focuspen.enhance.v2;class PenMouse {
 PenMouse(android.os.Handler h,NativeKeyReplay r,java.util.function.Supplier<KeyTarget>s,java.util.function.Predicate<KeyTarget> p){}
 static boolean active(){return false;}static void enter(int d){}static void exit(){}static long session(){return 0;}static void action(String a,boolean r,long session){}static boolean key(android.view.KeyEvent e,int b){return active();}
}''',
})
sources["android/os/Bundle.java"]=sources["android/os/Bundle.java"].replace('public void putInt', 'public boolean getBoolean(String k,boolean d){return (boolean)getOrDefault(k,d);}public void putInt')
sources["android/content/Context.java"]=sources["android/content/Context.java"].replace('public <T>', 'public android.content.pm.PackageManager getPackageManager(){return new android.content.pm.PackageManager();}public void startActivity(Intent i){}public <T>')
sources["de/robv/android/xposed/XposedHelpers.java"]=sources["de/robv/android/xposed/XposedHelpers.java"].replace('public static Object getObjectField', 'public static Object callStaticMethod(Class<?> c,String n,Object...a){try{return c.getMethod(n).invoke(null);}catch(Exception e){throw new RuntimeException(e);}}public static Object getObjectField')
sources["com/focuspen/enhance/v2/ConfigSpec.java"]=sources["com/focuspen/enhance/v2/ConfigSpec.java"].replace('KEYS="keys"', 'SCREEN_OFF_TARGET="target",KEYS="keys"')
sources["com/focuspen/enhance/v2/ConfigSnapshot.java"]=sources["com/focuspen/enhance/v2/ConfigSnapshot.java"].replace('int longPressMs()', 'int number(String key){return key.equals(ProBindings.HOLD)?650:360;}String proBinding(String pkg,int slot){return slot==2&&pro==PenAction.UP?"scrollUp":ProBindings.DEFAULTS[slot];}int longPressMs()')
sources["com/focuspen/enhance/v2/OfficialPenActions.java"]=sources["com/focuspen/enhance/v2/OfficialPenActions.java"].replace('static int count;', 'static boolean screenOff(android.content.Context c,String a){return true;}static boolean lockAction(String a){return true;}static int count;')
sources["com/focuspen/enhance/v2/RingContent.java"]=sources["com/focuspen/enhance/v2/RingContent.java"].replace('record Item', 'static boolean validId(String s){return s.contains(".");}record Item')
sources["de/robv/android/xposed/XposedHelpers.java"] = sources["de/robv/android/xposed/XposedHelpers.java"].replace(
    'public static int policyCalls;',
    'public static int policyCalls;public static XC_MethodHook earlyHook;'
).replace(
    'return new XC_MethodHook.Unhook();',
    'if(n.equals("interceptKeyBeforeQueueing")&&c==com.android.server.input.InputManagerService.class)earlyHook=(XC_MethodHook)a[a.length-1];return new XC_MethodHook.Unhook();'
)
sources["de/robv/android/xposed/XC_MethodHook.java"] = sources["de/robv/android/xposed/XC_MethodHook.java"].replace(
    'public void setResult(Object o){}', 'public Object result;public boolean hasResult;public void setResult(Object o){result=o;hasResult=true;}'
)
sources["com/android/server/input/InputManagerService.java"] = '''package com.android.server.input;
public class InputManagerService {
 public int interceptKeyBeforeQueueing(android.view.KeyEvent event,int flags){return flags;}
}'''
sources["com/focuspen/enhance/v2/ConfigSnapshot.java"] = sources["com/focuspen/enhance/v2/ConfigSnapshot.java"].replace(
    'boolean ready=true,keys=true;', 'boolean ready=true,keys=true;'
)
sources["com/focuspen/enhance/v2/NativeKeyReplay.java"] = sources["com/focuspen/enhance/v2/NativeKeyReplay.java"].replace(
    'static final java.util.List<android.view.KeyEvent> sent=',
    'static boolean failNext;static final java.util.List<android.view.KeyEvent> sent='
).replace(
    'void dispatchToApplication(android.view.KeyEvent e,Object window){sent.add(e);windows.add(window);}',
    'void dispatchToApplication(android.view.KeyEvent e,Object window){if(failNext){failNext=false;throw new RuntimeException("injected failure");}sent.add(e);windows.add(window);}'
)
sources["com/focuspen/enhance/v2/PenMouse.java"] = sources["com/focuspen/enhance/v2/PenMouse.java"].replace(
    'static boolean active(){return false;}static void enter(int d){}static void exit(){}',
    'static boolean active;static int exits;static boolean active(){return active;}static void enter(int d){active=true;}static void exit(){active=false;exits++;}'
)
sources["com/focuspen/enhance/v2/KeyTargetHostTest.java"] = '''package com.focuspen.enhance.v2;
import android.app.KeyguardManager;import android.os.*;import android.view.*;
import de.robv.android.xposed.*;import java.lang.reflect.*;import java.util.*;
public class KeyTargetHostTest {
 static PenKeyHost host;static Object window;static XC_MethodHook hook;
 static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
 static Object field(Object receiver,String name)throws Exception{
  Field f=receiver.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(receiver);
 }
 static void set(Object receiver,String name,Object value)throws Exception{
  Field f=(receiver instanceof Class<?> c?c:receiver.getClass()).getDeclaredField(name);
  f.setAccessible(true);f.set(receiver instanceof Class<?>?null:receiver,value);
 }
 static Object call(String name,Class<?>[] types,Object...args)throws Exception{
  Method m=PenKeyHost.class.getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(host,args);
 }
 static void drain(){RuntimeBus.last.worker.drain();}
 static void setup()throws Exception{
  SystemClock.now=0;
  Constructor<PenKeyHost> c=PenKeyHost.class.getDeclaredConstructor(android.content.Context.class);
  c.setAccessible(true);host=c.newInstance(new android.content.Context());
  set(PenKeyHost.class,"stylusType",InputDevice.class.getMethod("isXiaomiStylus"));
  set(PenKeyHost.class,"instance",host);
  KeyguardManager.locked=false;PowerManager.interactive=true;
  ConfigClient.last.state=new ConfigSnapshot(1,"native",PenAction.RING);
  window=new Object();call("focusChanged",new Class[]{Object.class,String.class},window,"reader.example");drain();
  NativeKeyReplay.sent.clear();NativeKeyReplay.windows.clear();NativeKeyReplay.failNext=false;
  PenMouse.active=false;PenMouse.exits=0;OfficialPenActions.count=0;
  RuntimeBus.last.events.clear();PenPresentation.showing=false;
  Method install=PenKeyHost.class.getDeclaredMethod("installEarlyInputHook",ClassLoader.class);
  install.setAccessible(true);install.invoke(null,KeyTargetHostTest.class.getClassLoader());
  hook=XposedHelpers.earlyHook;check(hook!=null,"early hook not installed");
 }
 static KeyEvent key(int device,int code,int action,long down,long now,int repeat){
  return new KeyEvent(down,now,action,code,repeat,0,device,0,0,InputDevice.SOURCE_KEYBOARD);
 }
 static XC_MethodHook.MethodHookParam early(KeyEvent event,int policy)throws Exception{
  SystemClock.now=event.getEventTime();XC_MethodHook.MethodHookParam p=new XC_MethodHook.MethodHookParam();
  p.args=new Object[]{event,policy};
  Method before=hook.getClass().getDeclaredMethod("beforeHookedMethod",XC_MethodHook.MethodHookParam.class);
  before.setAccessible(true);before.invoke(hook,p);return p;
 }
 static void send(int device,int code,int action,long down,long now,int repeat)throws Exception{
  XC_MethodHook.MethodHookParam p=early(key(device,code,action,down,now,repeat),0);
  check(p.hasResult&&Integer.valueOf(0).equals(p.result),"known pen input escaped early owner: "+device+"/"+code);
  drain();
 }
 static int pressMapSize()throws Exception{return ((Map<?,?>)field(host,"proApplicationPresses")).size();}
 static void earlyOwnership()throws Exception{
  setup();set(PenKeyHost.class,"instance",null);
  for(int code:new int[]{93,92,193,194}){
   XC_MethodHook.MethodHookParam p=early(key(1,code,0,0,0,0),0);
   check(p.hasResult&&Integer.valueOf(0).equals(p.result),"type 3 not reserved before host: "+code);
  }
  for(int code:new int[]{194,195,196,197}){
   XC_MethodHook.MethodHookParam p=early(key(8,code,0,0,0,0),0);
   check(p.hasResult&&Integer.valueOf(0).equals(p.result),"type 8 not reserved before host: "+code);
  }
  check(!early(key(1,332,0,0,0,0),0).hasResult,"unknown 332 guessed as pen key");
  check(!early(key(8,332,0,0,0,0),0).hasResult,"unknown Pro 332 guessed as pen key");
  check(!early(key(1,93,0,0,0,0),0x04000000).hasResult,"replayed key recaptured");
 }
 static void unavailableAndLocked()throws Exception{
  setup();ConfigClient.last.state.ready=false;
  send(1,93,0,10,10,0);send(1,93,1,10,20,0);
  send(8,194,0,30,30,0);send(8,194,1,30,40,0);
  check(NativeKeyReplay.sent.isEmpty()&&OfficialPenActions.count==0
    &&!RuntimeBus.last.events.contains("action"),"missing boot mirror started an action");
  setup();KeyguardManager.locked=true;ConfigClient.last.state.first=PenAction.NOTE;
  send(1,93,0,10,10,0);send(1,93,1,10,20,0);
  send(8,195,0,30,30,0);send(8,195,1,30,40,0);
  check(OfficialPenActions.count==0&&NativeKeyReplay.sent.isEmpty(),"lock screen sent an official/app action");
 }
 static void disabledKeysAndFocus()throws Exception{
  setup();ConfigClient.last.state.keys=false;
  send(1,93,0,10,10,0);send(1,93,1,10,30,0);
  check(NativeKeyReplay.sent.size()==2&&NativeKeyReplay.sent.get(0).getAction()==0
    &&NativeKeyReplay.sent.get(1).getAction()==1&&!NativeKeyReplay.sent.get(1).isCanceled(),"KEYS=false did not replay original standard pair");
  NativeKeyReplay.sent.clear();
  send(8,195,0,40,40,0);send(8,195,1,40,60,0);
  check(NativeKeyReplay.sent.size()==2&&NativeKeyReplay.sent.get(0).getKeyCode()==195
    &&NativeKeyReplay.sent.get(1).getAction()==1&&!NativeKeyReplay.sent.get(1).isCanceled(),"KEYS=false did not replay original Pro pair");
  setup();PenMouse.active=true;Object next=new Object();
  call("focusChanged",new Class[]{Object.class,String.class},next,"other.example");drain();
  check(PenMouse.active&&PenMouse.exits==0,"window change exited mouse mode");
 }
 static void replayFailureAndLostUp()throws Exception{
  setup();ConfigClient.last.state.first=PenAction.NONE;
  NativeKeyReplay.failNext=true;send(1,93,0,10,10,0);
  send(1,93,1,10,20,0);
  check(NativeKeyReplay.sent.isEmpty(),"failed original DOWN emitted orphan UP");
  setup();ConfigClient.last.state.pro=PenAction.NONE;
  send(8,195,0,10,10,0);check(pressMapSize()==1,"Pro DOWN not tracked");
  send(8,195,0,20,20,0);
  check(pressMapSize()==1&&NativeKeyReplay.sent.size()==3
    &&NativeKeyReplay.sent.get(1).getAction()==1&&NativeKeyReplay.sent.get(1).isCanceled()
    &&NativeKeyReplay.sent.get(1).getDownTime()==10
    &&NativeKeyReplay.sent.get(2).getAction()==0&&NativeKeyReplay.sent.get(2).getDownTime()==20,
    "lost Pro UP did not cancel old press before replacement DOWN");
  send(8,195,1,20,30,0);
  check(pressMapSize()==0&&NativeKeyReplay.sent.size()==4
    &&NativeKeyReplay.sent.get(3).getAction()==1&&!NativeKeyReplay.sent.get(3).isCanceled(),
    "replacement Pro press not released");
 }
 public static void main(String[] args)throws Exception{
  earlyOwnership();unavailableAndLocked();disabledKeysAndFocus();replayFailureAndLostUp();
  delayedRepeats();queuedContextChange();lockedPresentation();docking();
  System.out.println("Production early input ownership, boot/lock guards, full replay sequences and stale-context cleanup: PASS");
 }
 static void delayedRepeats()throws Exception{
  setup();ConfigClient.last.state.first=PenAction.NONE;ConfigClient.last.state.twice=PenAction.RING;
  send(1,93,0,10,10,0);send(1,93,0,10,20,1);send(1,93,1,10,30,0);
  check(NativeKeyReplay.sent.isEmpty(),"single escaped double-click decision window");
  SystemClock.now=400;((Runnable)field(host,"timer")).run();drain();
  check(NativeKeyReplay.sent.size()==3&&NativeKeyReplay.sent.get(1).getRepeatCount()==1
    &&NativeKeyReplay.sent.get(1).getEventTime()==20&&NativeKeyReplay.sent.get(2).getAction()==1,
    "delayed NONE single lost original repeat/timestamp/UP");
  setup();ConfigClient.last.state.first=PenAction.NOTE;
  send(1,93,0,10,10,0);send(1,93,0,10,20,1);send(1,93,1,10,30,0);
  send(1,93,0,100,100,0);send(1,93,0,100,120,1);send(1,93,1,100,130,0);
  check(NativeKeyReplay.sent.size()==6&&NativeKeyReplay.sent.get(1).getRepeatCount()==1
    &&NativeKeyReplay.sent.get(4).getRepeatCount()==1&&OfficialPenActions.count==0,
    "NONE double did not replay both complete original sequences");
 }
 static void queuedContextChange()throws Exception{
  setup();ConfigClient.last.state.keys=false;
  early(key(1,93,0,10,10,0),0);
  call("focusChanged",new Class[]{Object.class,String.class},new Object(),"other.example");drain();
  send(1,93,1,10,20,0);
  check(NativeKeyReplay.sent.isEmpty(),"queued old-window DOWN/UP reached new window");
  setup();ConfigClient.last.state.keys=false;
  early(key(1,93,0,10,10,0),0);
  ConfigClient.last.state=new ConfigSnapshot(2,"native",PenAction.RING);ConfigClient.last.state.keys=false;
  drain();send(1,93,1,10,20,0);
  check(NativeKeyReplay.sent.isEmpty(),"queued old-config DOWN/UP became a new action");
 }
 static void lockedPresentation()throws Exception{
  setup();KeyguardManager.locked=true;
  send(1,193,0,10,10,0);send(1,193,1,10,30,0);
  check(PenPresentation.allowed&&PenPresentation.boundAllowed.getAsBoolean(),"lit lockscreen rejected focus presentation");
  PenMouse.active=true;RuntimeBus.last.events.clear();
  send(1,93,0,40,40,0);send(1,93,1,40,800,0);
  send(1,92,0,900,900,0);send(1,92,1,900,1500,0);
  check(!RuntimeBus.last.events.contains("action")&&OfficialPenActions.count==0
    &&NativeKeyReplay.sent.isEmpty(),"locked mouse buttons triggered lockscreen shortcut or app replay");
  int resets=PenPresentation.resets;
  KeyguardManager.locked=false;
  send(1,193,0,1520,1520,0);send(1,193,1,1520,1540,0);
  check(PenPresentation.resets==resets&&PenMouse.active,
    "first focus press after unlock reset the active mouse to idle");
  KeyguardManager.locked=true;
  send(1,93,0,1550,1550,0);send(1,93,1,1550,1580,0);
  check(PenPresentation.resets==resets&&PenMouse.active,
    "lit keyguard transition reset presentation");
  PenMouse.active=false;RuntimeBus.last.events.clear();
  send(1,93,0,1600,1600,0);send(1,93,1,1600,2100,0);
  check(RuntimeBus.last.events.contains("action"),"leaving locked mouse did not restore ring shortcut");
  PowerManager.interactive=false;
  check(!PenPresentation.boundAllowed.getAsBoolean(),"screen off still permitted presentation");
  KeyguardManager.locked=false;
 }
 static void docking()throws Exception{
  setup();ConfigClient.last.state.keys=false;
  send(1,93,0,10,10,0);
  check(NativeKeyReplay.sent.size()==1,"probe app DOWN not delivered before docking");
  SystemClock.now=20;PenDockMonitor.last.changed.accept(PenDockMonitor.DOCKED);drain();
  check(NativeKeyReplay.sent.size()==2&&NativeKeyReplay.sent.get(1).getAction()==1
    &&NativeKeyReplay.sent.get(1).isCanceled(),"docking did not cancel the application's held key");
  NativeKeyReplay.sent.clear();int calls=PenPresentation.focusCalls,resets=PenPresentation.resets;
  for(int code:new int[]{92,93,193}){send(1,code,0,30,30,0);send(1,code,1,30,40,0);}
  for(int code:new int[]{194,195,196,197}){send(8,code,0,50,50,0);send(8,code,1,50,60,0);}
  check(NativeKeyReplay.sent.isEmpty()&&calls==PenPresentation.focusCalls,
    "docked keys escaped to app or presentation");
  SystemClock.now=100;PenDockMonitor.last.changed.accept(PenDockMonitor.DETACHED);drain();
  send(1,93,0,30,101,1);send(1,93,1,30,102,0);
  check(NativeKeyReplay.sent.isEmpty(),"old held key resumed after undocking");
  send(1,93,0,110,110,0);send(1,93,1,110,120,0);
  check(NativeKeyReplay.sent.size()==2&&!NativeKeyReplay.sent.get(1).isCanceled(),
    "fresh press failed after undocking");
  check(PenPresentation.resets>resets,"dock transition did not reset presentation owner");
  NativeKeyReplay.sent.clear();
  early(key(1,93,0,130,130,0),0);
  SystemClock.now=140;PenDockMonitor.last.changed.accept(PenDockMonitor.DOCKED);
  SystemClock.now=150;PenDockMonitor.last.changed.accept(PenDockMonitor.DETACHED);drain();
  check(NativeKeyReplay.sent.isEmpty(),"queued pre-docking DOWN became a fresh action");
 }
}'''
test=sources["com/focuspen/enhance/v2/KeyTargetHostTest.java"]
test=test.replace('check(RuntimeBus.last.events.stream().filter(e->e.equals("action")).count()==1,"Pro pinch did not open ring once");', 'check(RuntimeBus.last.events.isEmpty(),"Pro single must wait for combos");')
test=test.replace('check(RuntimeBus.last.events.stream().filter(e->e.equals("action")).count()==1,"Pinch duplicated action");', 'check(RuntimeBus.last.events.isEmpty(),"Repeated pinch escaped arbitration");')
test=test.replace('manager.mCurrentVirtualLaserStatus=1;check', 'PenPresentation.showing=true;manager.mCurrentVirtualLaserStatus=1;check')
test=test.replace('manager.mCurrentVirtualLaserStatus=0;manager.mIsGameMode=true;', 'PenPresentation.showing=false;manager.mCurrentVirtualLaserStatus=0;manager.mIsGameMode=true;')
test=test.replace('ConfigClient.last.state=new ConfigSnapshot(1,adapter,action);', 'PenPresentation.showing=false;android.app.KeyguardManager.locked=false;ConfigClient.last.state=new ConfigSnapshot(1,adapter,action);')
sources["com/focuspen/enhance/v2/KeyTargetHostTest.java"]=test
files = []
for name, source in sources.items():
    path = out / name
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(source, encoding="utf-8")
    files.append(str(path))
for name in ["PenKeyHost", "PenInputClassifier", "ProInput", "PinchSequence", "ProBindings", "ProGestureGate", "KeyTarget", "KeyArbiter", "PenAction", "DirectionAdapter", "NativeShortcutGate"]:
    files.append(str(root / f"module/app/src/main/java/com/focuspen/enhance/v2/{name}.java"))
jdk = Path(os.environ.get("JAVA_HOME", "/usr/lib/jvm/java-21-openjdk-amd64"))
subprocess.run([str(jdk / "bin/javac"), "-d", str(out), *files], check=True)
subprocess.run([str(jdk / "bin/java"), "-cp", str(out), "com.focuspen.enhance.v2.KeyTargetHostTest"], check=True)
