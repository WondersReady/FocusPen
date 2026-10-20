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
 public final java.util.ArrayDeque<Runnable> queue=new java.util.ArrayDeque<>(); public Runnable timer;
 public boolean post(Runnable r){queue.add(r);return true;} public boolean postAtTime(Runnable r,long t){timer=r;return true;}
 public void removeCallbacks(Runnable r){queue.removeIf(x->x==r);if(timer==r)timer=null;}
 public void removeMessages(int what){}
 public void drain(){while(!queue.isEmpty())queue.remove().run();}
}''',
"android/os/SystemClock.java": 'package android.os; public class SystemClock {public static long now; public static long uptimeMillis(){return now;}}',
"android/os/PowerManager.java": 'package android.os; public class PowerManager {public static boolean interactive=true;public boolean isInteractive(){return interactive;}}',
"android/os/Build.java": 'package android.os; public class Build {public static String FINGERPRINT="test";}',
"android/content/Context.java": '''package android.content; public class Context {
 public <T>T getSystemService(Class<T> c){try{return c.getConstructor().newInstance();}catch(Exception e){throw new RuntimeException(e);}}
}''',
"android/view/InputDevice.java": '''package android.view; public class InputDevice {
 public static final int SOURCE_KEYBOARD=257; public int type=3;public int isXiaomiStylus(){return type;}
}''',
"android/view/MotionEvent.java": 'package android.view; public class MotionEvent {}',
"android/view/KeyEvent.java": '''package android.view; public class KeyEvent {
 public static final int ACTION_DOWN=0,ACTION_UP=1,FLAG_LONG_PRESS=128,FLAG_CANCELED=32;
 final long down,time; final int action,code,repeat,meta,device,scan,flags,source;
 public KeyEvent(long d,long t,int a,int c,int r,int m,int dev,int sc,int f,int s){down=d;time=t;action=a;code=c;repeat=r;meta=m;device=dev;scan=sc;flags=f;source=s;}
 public KeyEvent(KeyEvent e){this(e.down,e.time,e.action,e.code,e.repeat,e.meta,e.device,e.scan,e.flags,e.source);}
 public static KeyEvent changeFlags(KeyEvent e,int flags){return new KeyEvent(e.down,e.time,e.action,e.code,e.repeat,e.meta,e.device,e.scan,flags,e.source);}
 public long getDownTime(){return down;} public int getKeyCode(){return code;} public int getDeviceId(){return device;}
 public int getRepeatCount(){return repeat;} public int getAction(){return action;} public int getMetaState(){return meta;}
 public int getSource(){return source;} public int getFlags(){return flags;} public int getScanCode(){return scan;}
 public boolean isCanceled(){return (flags&FLAG_CANCELED)!=0;} public InputDevice getDevice(){InputDevice d=new InputDevice();d.type=device==8?8:3;return d;}
}''',
"android/util/Log.java": '''package android.util; public class Log {
 public static int i(String t,String s){return 0;} public static int w(String t,String s){return 0;}
 public static int w(String t,String s,Throwable e){throw new AssertionError(s,e);} public static int e(String t,String s,Throwable e){throw new AssertionError(s,e);}
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
"com/focuspen/enhance/v2/BuildConfig.java": 'package com.focuspen.enhance.v2; class BuildConfig {static String VERSION_NAME="test";}',
"com/focuspen/enhance/v2/ConfigSpec.java": 'package com.focuspen.enhance.v2; class ConfigSpec {static String KEYS="keys",RING="ring",CONTENT="content",SCREEN_OFF="screenOff";}',
"com/focuspen/enhance/v2/ConfigSnapshot.java": '''package com.focuspen.enhance.v2; class ConfigSnapshot {
 final long revision; final String adapter; final PenAction action; boolean available=true;
 PenAction first=PenAction.UP,second=PenAction.DOWN,pro=PenAction.RING;
 ConfigSnapshot(long r,String a,PenAction p){revision=r;adapter=a;action=p;}
 boolean enabled(){return available;} boolean flag(String k){return true;} String text(String k){return "";}
 int longPressMs(){return 250;} int doublePressMs(){return 280;} boolean hasGlobalNoteBinding(){return false;}
 String direction(String pkg,int b){return adapter;} PenAction action(String p,int b,boolean d){return d?PenAction.NONE:action;}
 PenAction shortAction(String pkg,int b){return b==1?first:second;} PenAction proAction(String pkg,int slot){return pro;}
}''',
"com/focuspen/enhance/v2/ConfigClient.java": '''package com.focuspen.enhance.v2; class ConfigClient {
 interface Listener {void changed(ConfigSnapshot s);} static ConfigClient last;
 ConfigSnapshot state=new ConfigSnapshot(1,"page",PenAction.RING); Listener listener;
 ConfigClient(android.content.Context c){last=this;} ConfigSnapshot get(){return state;} void refresh(){}
 void add(Listener l){listener=l;l.changed(state);}
}''',
"com/focuspen/enhance/v2/RuntimeBus.java": '''package com.focuspen.enhance.v2; class RuntimeBus {
 static String UI="ui",INPUT="input"; static RuntimeBus last; android.os.Handler worker=new android.os.Handler();
 interface Receiver {void receive(String e,android.os.Bundle b);} final java.util.List<String> events=new java.util.ArrayList<>();
 RuntimeBus(android.content.Context c,Receiver r,Runnable refresh){last=this;}
 boolean available(String p){return true;} boolean send(String p,String e,android.os.Bundle b){events.add(e);return true;}
}''',
"com/focuspen/enhance/v2/NativeKeyReplay.java": '''package com.focuspen.enhance.v2; class NativeKeyReplay {
 static final java.util.List<android.view.KeyEvent> sent=new java.util.ArrayList<>(); static void verify(ClassLoader c){}
 void dispatch(android.view.KeyEvent e){sent.add(e);}
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
  RuntimeBus.last.events.clear();NativeKeyReplay.sent.clear();OfficialPenActions.count=0;de.robv.android.xposed.XposedHelpers.policyCalls=0;
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
  setup("native",PenAction.NATIVE);event(0,0,0);tick(250);focus(new Object(),"other.example");drain();event(1,0,300);drain();
  check(NativeKeyReplay.sent.isEmpty(),"queued native hold must not begin in a different window or emit orphan UP");
  setup("native",PenAction.NATIVE);event(0,0,0);event(1,0,300);focus(new Object(),"other.example");drain();
  check(NativeKeyReplay.sent.isEmpty(),"native long pair when UP beats timer must recheck target");
  setup("native",PenAction.NATIVE);event(0,0,0);tick(250);drain();focus(new Object(),"other.example");drain();event(1,0,300);drain();
  check(NativeKeyReplay.sent.size()==2 && NativeKeyReplay.sent.get(1).getAction()==1,"already dispatched native DOWN retains paired release after focus change");
  setup("native",PenAction.NATIVE);event(0,0,0);tick(250);event(1,0,300);drain();
  check(NativeKeyReplay.sent.size()==2,"UP queued before DOWN executes still produces a complete pair");
  setup("native",PenAction.NATIVE);event(0,0,0);tick(250);drain();repeat(0,270);focus(new Object(),"other.example");drain();event(1,0,300);drain();
  check(NativeKeyReplay.sent.size()==2,"queued native repeats stop after focus change but release survives");
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
 static boolean showing,allowed,colorActive;static int colorCalls,direction;static void install(ClassLoader c){}static void bind(android.content.Context c,ConfigClient f){}static void reset(){}static void laserMode(){}
 static boolean colorRoutingActive(){return colorActive;}
 static boolean presenting(){return showing;}static boolean colorKey(android.view.KeyEvent e,int d,boolean a){allowed=a;direction=d;colorCalls++;return showing&&a;}
}''',
"com/focuspen/enhance/v2/PenMouse.java": '''package com.focuspen.enhance.v2;class PenMouse {
 PenMouse(android.os.Handler h,NativeKeyReplay r,java.util.function.Supplier<KeyTarget>s,java.util.function.Predicate<KeyTarget> p){}
 static boolean active(){return false;}static void enter(int d){}static void exit(){}static long session(){return 0;}static void action(String a,boolean r,long session){}static boolean key(android.view.KeyEvent e,int b){return false;}
}''',
})
sources["android/os/Bundle.java"]=sources["android/os/Bundle.java"].replace('public void putInt', 'public boolean getBoolean(String k,boolean d){return (boolean)getOrDefault(k,d);}public void putInt')
sources["android/content/Context.java"]=sources["android/content/Context.java"].replace('public <T>', 'public android.content.pm.PackageManager getPackageManager(){return new android.content.pm.PackageManager();}public void startActivity(Intent i){}public <T>')
sources["de/robv/android/xposed/XposedHelpers.java"]=sources["de/robv/android/xposed/XposedHelpers.java"].replace('public static Object getObjectField', 'public static Object callStaticMethod(Class<?> c,String n,Object...a){try{return c.getMethod(n).invoke(null);}catch(Exception e){throw new RuntimeException(e);}}public static Object getObjectField')
sources["com/focuspen/enhance/v2/ConfigSpec.java"]=sources["com/focuspen/enhance/v2/ConfigSpec.java"].replace('KEYS="keys"', 'SCREEN_OFF_TARGET="target",KEYS="keys"')
sources["com/focuspen/enhance/v2/ConfigSnapshot.java"]=sources["com/focuspen/enhance/v2/ConfigSnapshot.java"].replace('int longPressMs()', 'int number(String key){return key.equals(ProBindings.HOLD)?650:360;}String proBinding(String pkg,int slot){return slot==2&&pro==PenAction.UP?"scrollUp":ProBindings.DEFAULTS[slot];}int longPressMs()')
sources["com/focuspen/enhance/v2/OfficialPenActions.java"]=sources["com/focuspen/enhance/v2/OfficialPenActions.java"].replace('static int count;', 'static boolean screenOff(android.content.Context c,String a){return true;}static boolean lockAction(String a){return true;}static int count;')
sources["com/focuspen/enhance/v2/RingContent.java"]=sources["com/focuspen/enhance/v2/RingContent.java"].replace('record Item', 'static boolean validId(String s){return s.contains(".");}record Item')
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
for name in ["PenKeyHost", "ProInput", "PinchSequence", "ProBindings", "ProGestureGate", "KeyTarget", "KeyArbiter", "PenAction", "DirectionAdapter", "NativeShortcutGate"]:
    files.append(str(root / f"module/app/src/main/java/com/focuspen/enhance/v2/{name}.java"))
jdk = Path(os.environ.get("JAVA_HOME", "/usr/lib/jvm/java-21-openjdk-amd64"))
subprocess.run([str(jdk / "bin/javac"), "-d", str(out), *files], check=True)
subprocess.run([str(jdk / "bin/java"), "-cp", str(out), "com.focuspen.enhance.v2.KeyTargetHostTest"], check=True)
