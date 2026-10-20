#!/usr/bin/env python3
"""Execute production mouse and Pro arbitration with controlled worker ordering."""
from pathlib import Path
import os
import runpy
import subprocess

root = Path(__file__).resolve().parents[2]
base = runpy.run_path(str(root / "tools/v2/test_key_target.py"))
sources = base["sources"].copy()
for name in ["PenMouse", "PenPresentation", "KeyTargetHostTest"]:
    sources.pop(f"com/focuspen/enhance/v2/{name}.java", None)
sources.update({
"android/graphics/Rect.java": '''package android.graphics;public class Rect {
 public int left,top,right,bottom;public Rect(int l,int t,int r,int b){left=l;top=t;right=r;bottom=b;}
 public Rect(Rect r){this(r.left,r.top,r.right,r.bottom);}public boolean isEmpty(){return right<=left||bottom<=top;}
 public float exactCenterX(){return (left+right)/2f;}public float exactCenterY(){return (top+bottom)/2f;}
 public boolean equals(Object o){return o instanceof Rect r&&left==r.left&&top==r.top&&right==r.right&&bottom==r.bottom;}
}''',
"android/graphics/Region.java": '''package android.graphics;public class Region {
 public int size=1000;public boolean contains(int x,int y){return x>=0&&y>=0&&x<size&&y<size;}
 public boolean equals(Object o){return o instanceof Region r&&size==r.size;}
}''',
"android/view/MotionEvent.java": '''package android.view;public class MotionEvent {
 public static final int ACTION_SCROLL=8,ACTION_HOVER_MOVE=7,ACTION_MOVE=2,TOOL_TYPE_MOUSE=3,AXIS_VSCROLL=9;
 public int action,state,changed;public float x,y;public static class PointerProperties{public int id,toolType;}
 public static class PointerCoords{public float x,y,pressure;public void setAxisValue(int a,float v){}}
 public static MotionEvent obtain(long d,long t,int a,int n,PointerProperties[] p,PointerCoords[] c,int meta,int buttons,float xp,float yp,int dev,int edge,int source,int flags){MotionEvent e=new MotionEvent();e.action=a;e.state=buttons;e.x=c[0].x;e.y=c[0].y;return e;}
 public void setDisplayId(int d){}public void setActionButton(int b){changed=b;}public void recycle(){}
}''',
"com/focuspen/enhance/v2/NativeKeyReplay.java": '''package com.focuspen.enhance.v2;class NativeKeyReplay {
 static java.util.List<String> sent=new java.util.ArrayList<>();void dispatchPointer(android.view.MotionEvent e,int uid){sent.add(e.action+":"+e.state+":"+e.changed+":"+uid);}
}''',
"com/focuspen/enhance/v2/PenPresentation.java": '''package com.focuspen.enhance.v2;class PenPresentation {
 static float x,y;static void mouseMode(boolean active,int device,long session,float px,float py){if(active){x=px;y=py;}}
}''',
"de/robv/android/xposed/XposedHelpers.java": '''package de.robv.android.xposed;public class XposedHelpers {
 public static Object getObjectField(Object o,String n){try{return o.getClass().getField(n).get(o);}catch(Exception e){throw new RuntimeException(e);}}
 public static Object callMethod(Object o,String n,Object...a){try{for(var m:o.getClass().getMethods())if(m.getName().equals(n)&&m.getParameterCount()==a.length)return m.invoke(o,a);throw new NoSuchMethodException(n);}catch(Exception e){throw new RuntimeException(e);}}
}''',
"com/focuspen/enhance/v2/MouseSessionTest.java": '''package com.focuspen.enhance.v2;
import android.os.*;import android.view.*;import android.graphics.*;import java.util.*;
public class MouseSessionTest {
 public static class Wms{public Object mGlobalLock=new Object();}
 public static class Window{public Wms mWmService=new Wms();public int uid=100;public Rect frame=new Rect(0,0,1000,1000);
 public boolean isVisible(){return true;}public int getDisplayId(){return 0;}public Rect getFrame(){return frame;}public int getOwningUid(){return uid;}public void getTouchableRegion(Region r){}}
 static Handler h;static Window w;static KeyTarget target;static Object lock=new Object();
 static void check(boolean b,String msg){if(!b)throw new AssertionError(msg);}
 static KeyEvent key(int action,long down,int code){return new KeyEvent(down,SystemClock.now,action,code,0,0,8,0,0,0);}
 static void setup(){h=new Handler();w=new Window();target=new KeyTarget(w,1,1,"reader","none","none");new PenMouse(h,new NativeKeyReplay(),()->target,t->t==target);NativeKeyReplay.sent.clear();}
 static void enter(){PenMouse.enter(8);h.drain();check(PenMouse.active(),"mouse should enter");}
 static int count(){return NativeKeyReplay.sent.size();}
 static void hold(){PenMouse.action("leftHold",false,PenMouse.session());h.drain();}
 public static void main(String[] args){
  setup();enter();check(PenPresentation.x==500&&PenPresentation.y==500,"cursor starts at injection point");
  PenMouse.key(key(0,1,93),1);h.drain();long old=PenMouse.session();PenMouse.exit();h.drain();enter();hold();int before=count();
  PenMouse.key(key(1,1,93),1);PenMouse.action("release",true,old);h.drain();check(count()==before,"stale physical/pro release affected new session");
  PenMouse.action("release",true,PenMouse.session());h.drain();check(count()==before+2,"current session did not release drag");
  setup();enter();hold();PenMouse.exit();PenMouse.enter(8);h.drain();check(PenMouse.active(),"queued reentry failed");
  check(count()==4,"old drag was not released once");hold();check(count()==6,"new session has stale held button");
  setup();PenMouse.enter(8);PenMouse.exit();h.drain();check(!PenMouse.active(),"canceled pending entry revived");
  setup();PenMouse.enter(8);target=new KeyTarget(new Window(),2,1,"other","none","none");h.drain();check(!PenMouse.active(),"queued entry followed focus to another window");
  setup();enter();hold();w.frame=new Rect(0,0,900,900);PenMouse.move(500,500);h.drain();check(!PenMouse.active()&&count()==4,"resizing did not release drag");
  setup();enter();long session=PenMouse.session();PenMouse.action("rightHold",false,session);PenMouse.exit();h.drain();check(count()==0,"canceled queued press escaped");
  pro();System.out.println("Production mouse stale releases, queued entry, drag cleanup and Pro cancellation: PASS");
 }
 static class Host implements ProInput.Host{
  int singles,nativeCount;long session=-1;List<String> actions=new ArrayList<>();
  public void action(String a,boolean release,KeyTarget t,KeyEvent e,long s){actions.add(a);session=s;}
  public void single(KeyTarget t,List<KeyEvent> events){singles++;}public void nativeEvents(Object m,List<KeyEvent> events){nativeCount++;}
  public boolean valid(KeyTarget t){return t==target;}
 }
 static void event(ProInput p,ConfigSnapshot c,int action,long down,long now){SystemClock.now=now;p.event(new Object(),key(action,down,194),0,true,c,target);}
 static void tick(long time){SystemClock.now=time;Runnable r=h.timer;if(r!=null){h.timer=null;r.run();}}
 static void pro(){
  setup();Host host=new Host();ProInput p=new ProInput(h,host,lock);ConfigSnapshot c=new ConfigSnapshot(1,"none",PenAction.RING);
  event(p,c,0,0,0);event(p,c,1,0,20);event(p,c,0,100,100);tick(750);check(host.nativeCount==0,"default hold replayed incomplete pair");
  p.cancel();event(p,c,1,100,800);check(host.nativeCount==0,"canceled default hold replayed actions");
  event(p,c,0,1000,1000);event(p,c,1,1000,1020);event(p,c,0,1100,1100);tick(1750);event(p,c,1,1100,1800);check(host.nativeCount==1,"complete default hold did not replay once");
  setup();enter();host=new Host();p=new ProInput(h,host,lock);long start=PenMouse.session();event(p,c,0,2000,2000);tick(2650);
  PenMouse.exit();h.drain();enter();event(p,c,1,2000,2700);check(host.session==start,"Pro release used the new mouse session");
 }
}''',
})
sources["android/os/Handler.java"] = sources["android/os/Handler.java"].replace("public void drain()", "public boolean postDelayed(Runnable r,long t){return true;} public void drain()")
sources["android/view/InputDevice.java"] = sources["android/view/InputDevice.java"].replace("SOURCE_KEYBOARD=257", "SOURCE_MOUSE=8194,SOURCE_KEYBOARD=257").replace("public int type", "public static InputDevice getDevice(int d){return new InputDevice();}public int type")
out = root / "work/v2/mouse-session-test"
files = []
# Only dependencies exercised by this test are compiled.
names = ["android/graphics/Rect", "android/graphics/Region", "android/os/Handler", "android/os/SystemClock", "android/content/Context", "android/content/pm/PackageManager", "android/content/Intent", "android/view/InputDevice", "android/view/KeyEvent", "android/view/MotionEvent", "android/util/Log", "de/robv/android/xposed/XposedHelpers"]
names += ["com/focuspen/enhance/v2/" + n for n in ["NativeKeyReplay", "PenPresentation", "ConfigSpec", "ConfigSnapshot", "RingContent", "MouseSessionTest"]]
for name in names:
    path = out / (name + ".java")
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(sources[name + ".java"], encoding="utf-8")
    files.append(str(path))
for name in ["PenMouse", "MouseButtons", "KeyTarget", "ProInput", "PinchSequence", "ProBindings", "PenAction"]:
    files.append(str(root / f"module/app/src/main/java/com/focuspen/enhance/v2/{name}.java"))
jdk = Path(os.environ.get("JAVA_HOME", "/usr/lib/jvm/java-21-openjdk-amd64"))
subprocess.run([str(jdk / "bin/javac"), "-d", str(out), *files], check=True)
subprocess.run([str(jdk / "bin/java"), "-cp", str(out), "com.focuspen.enhance.v2.MouseSessionTest"], check=True)
