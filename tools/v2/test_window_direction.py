#!/usr/bin/env python3
"""Production pointer adapter with deterministic Android/window/input doubles; not real input proof."""
from pathlib import Path
import os,subprocess
root=Path(__file__).resolve().parents[2]
out=root/'work/v2/window-direction-test'
sources={
'android/graphics/Rect.java':'''package android.graphics; public class Rect {
 public int left,top,right,bottom; public Rect(int l,int t,int r,int b){left=l;top=t;right=r;bottom=b;}
 public Rect(Rect r){this(r.left,r.top,r.right,r.bottom);} public int height(){return bottom-top;}
 public float exactCenterX(){return (left+right)/2f;} public float exactCenterY(){return (top+bottom)/2f;}
 public boolean intersect(Rect r){left=Math.max(left,r.left);top=Math.max(top,r.top);right=Math.min(right,r.right);bottom=Math.min(bottom,r.bottom);return right>left&&bottom>top;}
 public boolean equals(Object o){return o instanceof Rect r && left==r.left&&top==r.top&&right==r.right&&bottom==r.bottom;}
}''',
'android/graphics/Region.java':'''package android.graphics; public class Region {
 public Rect bounds; public boolean contains(int x,int y){return bounds!=null&&x>=bounds.left&&x<bounds.right&&y>=bounds.top&&y<bounds.bottom;}
 public boolean equals(Object o){return o instanceof Region r&&java.util.Objects.equals(bounds,r.bounds);}
}''',
'android/os/SystemClock.java':'package android.os; public class SystemClock {public static long now;public static long uptimeMillis(){return now;}}',
'android/os/Handler.java':'''package android.os; public class Handler {
 public java.util.ArrayDeque<Runnable> tasks=new java.util.ArrayDeque<>();public boolean postDelayed(Runnable r,long delay){tasks.add(r);return true;}
 public void next(long now){SystemClock.now=now;if(!tasks.isEmpty())tasks.remove().run();}
}''',
'android/view/InputDevice.java':'package android.view; public class InputDevice {public static final int SOURCE_MOUSE=8194,SOURCE_TOUCHSCREEN=4098;}',
'android/view/MotionEvent.java':'''package android.view; public class MotionEvent {
 public static final int ACTION_UP=1,ACTION_CANCEL=3,ACTION_SCROLL=8,TOOL_TYPE_MOUSE=3,TOOL_TYPE_FINGER=1,AXIS_VSCROLL=9;
 public static class PointerProperties {public int id,toolType;}
 public static class PointerCoords {public float x,y,pressure,size,scroll;public void setAxisValue(int a,float v){scroll=v;}}
 public long down,time;public int action,source,device,display;public PointerCoords point;
 public static MotionEvent obtain(long d,long t,int a,int count,PointerProperties[] p,PointerCoords[] c,int meta,int buttons,float xp,float yp,int device,int edge,int source,int flags){
  MotionEvent e=new MotionEvent();e.down=d;e.time=t;e.action=a;e.source=source;e.device=device;e.point=c[0];return e;
 } public void setDisplayId(int value){display=value;}public void recycle(){}
}''',
'android/util/Log.java':'package android.util; public class Log {public static int w(String t,String m,Throwable e){return 0;}}',
'de/robv/android/xposed/XposedHelpers.java':'''package de.robv.android.xposed; public class XposedHelpers {
 public static Object getObjectField(Object o,String n){try{var f=o.getClass().getDeclaredField(n);f.setAccessible(true);return f.get(o);}catch(Exception e){throw new RuntimeException(e);}}
 public static int getIntField(Object o,String n){return (Integer)getObjectField(o,n);}
 public static Object callMethod(Object o,String n,Object... args){try{for(var m:o.getClass().getDeclaredMethods())if(m.getName().equals(n)){m.setAccessible(true);return m.invoke(o,args);}}catch(Exception e){throw new RuntimeException(e);}throw new IllegalArgumentException(n);}
}''',
'com/focuspen/enhance/v2/NativeKeyReplay.java':'''package com.focuspen.enhance.v2; class NativeKeyReplay {
 final java.util.List<android.view.MotionEvent> sent=new java.util.ArrayList<>(); int fail=-1;
 void dispatchPointer(android.view.MotionEvent e,int uid){if(uid!=12345)throw new AssertionError("wrong owner");sent.add(e);if(e.action==fail)throw new IllegalStateException("rejected");}
}''',
'com/focuspen/enhance/v2/WindowDirectionTest.java':'''package com.focuspen.enhance.v2;
import android.graphics.*;import android.os.*;import android.view.*;
public class WindowDirectionTest {
 static class Service{Object mGlobalLock=new Object();}static class Attrs{int type=1;}static class Display{Object mCurrentFocus;}
 static class Window {
  Service mWmService=new Service();Attrs mAttrs=new Attrs();Display display=new Display();Rect rect=new Rect(100,300,1100,1500);
  boolean visible=true,hole;int displayId;
  Object getDisplayContent(){return display;}boolean isVisible(){return visible;}int getDisplayId(){return displayId;}
  Rect getFrame(){return rect;}Rect getDisplayFrame(){return new Rect(0,0,2000,2000);}int getOwningUid(){return 12345;}
  void getTouchableRegion(Region r){r.bounds=hole?new Rect(0,0,1,1):new Rect(rect);}
 }
 static Window w;static Handler handler;static NativeKeyReplay input;static WindowDirectionHost host;static KeyTarget target;static boolean valid;
 static void setup(){SystemClock.now=1000;w=new Window();w.display.mCurrentFocus=w;handler=new Handler();input=new NativeKeyReplay();host=new WindowDirectionHost(handler,input);target=new KeyTarget(w,1,1,"app.example","swipe","swipe");valid=true;WindowDirectionHost.CONTACT.observing(true);}
 static void start(String kind,boolean up){host.run(target,kind,up,()->valid);}
 static void check(boolean x,String why){if(!x)throw new AssertionError(why);}
 static void finish(){for(long t=1016;t<=1224;t+=16)handler.next(t);}
 static void cancelled(String why){check(input.sent.size()>=2&&input.sent.get(input.sent.size()-1).action==3,why);check(handler.tasks.isEmpty(),"cancel removes further work");}
 public static void main(String[] args){
  setup();start("swipe",true);finish();check(input.sent.get(0).action==0&&input.sent.get(input.sent.size()-1).action==1,"paired swipe");
  float last=-1;for(MotionEvent e:input.sent){check(e.point.x==600&&e.point.y>=684&&e.point.y<=1116&&e.point.y>=last,"window-relative path/direction");last=e.point.y;check(e.down==1000&&e.device==-1&&e.source==InputDevice.SOURCE_TOUCHSCREEN,"pointer identity");}
  setup();start("swipe",false);finish();check(input.sent.get(0).point.y>input.sent.get(input.sent.size()-1).point.y,"down reveals later content");
  setup();start("scroll",true);check(input.sent.size()==1&&input.sent.get(0).action==8&&input.sent.get(0).point.scroll==3&&input.sent.get(0).source==InputDevice.SOURCE_MOUSE,"scroll event");
  setup();start("scroll",false);check(input.sent.get(0).point.scroll==-3,"scroll down sign");
  setup();valid=false;start("swipe",true);check(input.sent.isEmpty(),"queued stale target");
  setup();start("swipe",true);valid=false;handler.next(1016);cancelled("focus/config change");
  setup();start("swipe",true);w.display.mCurrentFocus=new Object();handler.next(1016);cancelled("authoritative focus changes before callback");
  setup();start("swipe",true);w.rect.top++;handler.next(1016);cancelled("same-window move");
  setup();start("swipe",true);WindowDirectionHost.CONTACT.observe(0);WindowDirectionHost.CONTACT.observe(1);handler.next(1016);cancelled("physical touch even if released between steps");
  setup();WindowDirectionHost.CONTACT.observe(0);start("swipe",true);check(input.sent.isEmpty(),"physical contact suppresses start");
  setup();WindowDirectionHost.CONTACT.observing(false);start("swipe",true);check(input.sent.isEmpty(),"missing observer suppresses start");
  setup();start("swipe",true);handler.next(1500);cancelled("late worker cancels rather than completing stale swipe");
  setup();input.fail=2;start("swipe",true);handler.next(1016);cancelled("injection failure cancels sequence");
  setup();input.fail=0;start("swipe",true);cancelled("uncertain DOWN rejection gets bounded cancel");
  setup();start("swipe",true);start("swipe",false);check(input.sent.size()==1&&handler.tasks.size()==1,"no overlapping gestures");finish();start("scroll",false);check(input.sent.get(input.sent.size()-1).action==8,"new operation after finish");
  for(int condition=0;condition<5;condition++){setup();if(condition==0)w.mAttrs.type=2000;if(condition==1)w.displayId=1;if(condition==2)w.hole=true;if(condition==3)w.visible=false;if(condition==4)w.rect.right=110;start("swipe",true);check(input.sent.isEmpty(),"unsafe window rejected "+condition);}
  setup();WindowDirectionHost.CONTACT.observe(7);WindowDirectionHost.CONTACT.observe(9);start("swipe",true);finish();check(input.sent.get(input.sent.size()-1).action==1,"hover does not block gestures");
  System.out.println("Production window adapter: bounds, direction, UID, focus/resize/contact cancellation, failure and timing PASS");
 }
}'''
}
files=[]
for name,source in sources.items():
 path=out/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_text(source);files.append(str(path))
for name in ['WindowDirectionHost','WindowGesture','PointerContact','KeyTarget']:
 files.append(str(root/f'module/app/src/main/java/com/focuspen/enhance/v2/{name}.java'))
jdk=Path(os.environ.get('JAVA_HOME','/usr/lib/jvm/java-21-openjdk-amd64'))/'bin'
subprocess.run([str(jdk/'javac'),'-d',str(out),*files],check=True)
subprocess.run([str(jdk/'java'),'-cp',str(out),'com.focuspen.enhance.v2.WindowDirectionTest'],check=True)
