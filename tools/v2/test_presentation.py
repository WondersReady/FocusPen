#!/usr/bin/env python3
"""Exercise the production presentation owner with deterministic worker/main queues."""

from pathlib import Path
import os
import subprocess
import tempfile


ROOT = Path(__file__).resolve().parents[2]
SOURCES = {
    "android/os/Looper.java": """package android.os;
public final class Looper {
 public static final Looper MAIN=new Looper(),WORKER=new Looper();
 public static Looper current=WORKER;
 public static Looper getMainLooper(){return MAIN;}
 public static Looper myLooper(){return current;}
}
""",
    "android/os/Handler.java": """package android.os;
import java.util.*;
public final class Handler {
 public static final class Job {public final Runnable task;public final long when;Job(Runnable r,long t){task=r;when=t;}}
 public static final ArrayList<Job> main=new ArrayList<>(),worker=new ArrayList<>();
 private final Looper looper;
 public Handler(Looper looper){this.looper=looper;}
 public Looper getLooper(){return looper;}
 private ArrayList<Job> queue(){return looper==Looper.MAIN?main:worker;}
 public boolean post(Runnable task){queue().add(new Job(task,SystemClock.now));return true;}
 public boolean postDelayed(Runnable task,long delay){queue().add(new Job(task,SystemClock.now+delay));return true;}
 public boolean postAtTime(Runnable task,long when){queue().add(new Job(task,when));return true;}
 public void removeCallbacks(Runnable task){queue().removeIf(j->j.task==task);}
 public static void drain(Looper target){
  ArrayList<Job> q=target==Looper.MAIN?main:worker;Looper previous=Looper.current;Looper.current=target;
  try{for(;;){int found=-1;for(int i=0;i<q.size();i++)if(q.get(i).when<=SystemClock.now){found=i;break;}
   if(found<0)break;q.remove(found).task.run();}}
  finally{Looper.current=previous;}
 }
 public static void clear(){main.clear();worker.clear();}
}
""",
    "android/os/SystemClock.java": """package android.os;
public final class SystemClock {public static long now;public static long uptimeMillis(){return now;}}
""",
    "android/os/PowerManager.java": """package android.os;
public final class PowerManager {public boolean interactive=true;public boolean isInteractive(){return interactive;}}
""",
    "android/content/Context.java": """package android.content;
import android.os.PowerManager;import java.util.*;
public final class Context {
 public final PowerManager power=new PowerManager();public final List<Intent> broadcasts=new ArrayList<>();
 public <T>T getSystemService(Class<T> type){return type.cast(power);}
 public void sendBroadcast(Intent intent){broadcasts.add(intent);}
}
""",
    "android/content/Intent.java": """package android.content;
public final class Intent {
 public final String action;public String pkg;public int mode;
 public Intent(String action){this.action=action;}
 public Intent setPackage(String pkg){this.pkg=pkg;return this;}
 public Intent putExtra(String key,int value){mode=value;return this;}
 public Intent addFlags(int value){return this;}
}
""",
    "android/graphics/Rect.java": """package android.graphics;
public final class Rect {
 public int left,top,right,bottom;
 public Rect(){}public Rect(int left,int top,int right,int bottom){this.left=left;this.top=top;this.right=right;this.bottom=bottom;}
 public Rect(Rect other){this(other.left,other.top,other.right,other.bottom);}
 public boolean isEmpty(){return right<=left||bottom<=top;}
 public float exactCenterX(){return (left+right)/2f;}public float exactCenterY(){return (top+bottom)/2f;}
 public boolean equals(Object other){return other instanceof Rect r&&left==r.left&&top==r.top&&right==r.right&&bottom==r.bottom;}
}
""",
    "android/util/Log.java": """package android.util;
public final class Log {
 public static int w(String tag,String message,Throwable error){return 0;}
 public static int e(String tag,String message,Throwable error){return 0;}
}
""",
    "android/view/InputDevice.java": """package android.view;
public final class InputDevice {public static InputDevice getDevice(int id){return new InputDevice();}}
""",
    "android/view/KeyEvent.java": """package android.view;
public final class KeyEvent {
 public static final int ACTION_DOWN=0,ACTION_UP=1;
 private final int device,key,action,repeat;private final long down,event;private final boolean canceled;
 public KeyEvent(int device,int key,long down,long event,int action,int repeat,boolean canceled){
  this.device=device;this.key=key;this.down=down;this.event=event;this.action=action;this.repeat=repeat;this.canceled=canceled;
 }
 public int getDeviceId(){return device;}public int getKeyCode(){return key;}
 public long getDownTime(){return down;}public long getEventTime(){return event;}
 public int getAction(){return action;}public int getRepeatCount(){return repeat;}
 public boolean isCanceled(){return canceled;}
}
""",
    "de/robv/android/xposed/XposedHelpers.java": """package de.robv.android.xposed;
public final class XposedHelpers {
 public static Object getObjectField(Object target,String field){try{var f=target.getClass().getField(field);return f.get(target);}catch(Exception e){throw new RuntimeException(e);}}
 public static int getIntField(Object target,String field){return (Integer)getObjectField(target,field);}
 public static Object callStaticMethod(Class<?> target,String method,Object...args){return new Object();}
 public static Object callMethod(Object target,String method,Object...args){return null;}
}
""",
    "com/focuspen/enhance/v2/ConfigSpec.java": """package com.focuspen.enhance.v2;
final class ConfigSpec {static final String KEYS="keys",LASER_ACTIVATION="laserActivation";}
""",
    "com/focuspen/enhance/v2/ConfigSnapshot.java": """package com.focuspen.enhance.v2;
final class ConfigSnapshot {
 final String activation;ConfigSnapshot(String value){activation=value;}
 boolean inputActionsReady(){return true;}boolean flag(String key){return true;}
 int longPressMs(){return 200;}int doublePressMs(){return 280;}
 String text(String key){return activation;}
}
""",
    "com/focuspen/enhance/v2/ConfigClient.java": """package com.focuspen.enhance.v2;
final class ConfigClient {ConfigSnapshot snapshot;ConfigClient(ConfigSnapshot value){snapshot=value;}
 ConfigSnapshot get(){return snapshot;}}
""",
    "com/focuspen/enhance/v2/SystemMouseDevice.java": """package com.focuspen.enhance.v2;
class SystemMouseDevice {SystemMouseDevice(ClassLoader loader,int display){} }
""",
    "com/focuspen/enhance/v2/PenMouse.java": """package com.focuspen.enhance.v2;
import android.os.Handler;import android.view.KeyEvent;import java.util.function.BooleanSupplier;
final class PenMouse {
 interface Opener {SystemMouseDevice open(int source) throws Exception;}
 static int enters,exits,contexts;static boolean active;
 PenMouse(Handler worker,Opener opener,BooleanSupplier allowed,Runnable stopped){}
 static void enter(int device){enters++;active=true;}static void exit(){exits++;active=false;}
 static void contextChanged(){contexts++;}static void relative(float dx,float dy){}
 static void reset(){enters=exits=contexts=0;active=false;}
}
""",
    "com/focuspen/enhance/v2/PortedPresentationWindow.java": """package com.focuspen.enhance.v2;
import android.content.Context;import android.os.*;import java.util.*;import java.util.function.LongConsumer;
final class PortedPresentationWindow {
 static PortedPresentationWindow latest;final LongConsumer stored;
 final List<String> log=new ArrayList<>();PresentationState.Snapshot snapshot;
 boolean valid;boolean ink;long epoch;float x=Float.NaN,y=Float.NaN;
 PortedPresentationWindow(Context context,LongConsumer stored){this.stored=stored;latest=this;}
 void render(PresentationState.Snapshot state){new Handler(Looper.MAIN).post(()->{snapshot=state;log.add("mode:"+state.mode());});}
 void setColor(int color){new Handler(Looper.MAIN).post(()->log.add("color:"+color));}
 void position(float nextX,float nextY){new Handler(Looper.MAIN).post(()->{
  valid=Float.isFinite(nextX)&&Float.isFinite(nextY)&&nextX>=0&&nextY>=0;
  if(valid){x=nextX;y=nextY;}
  log.add("position:"+valid);
  if(valid&&snapshot!=null&&snapshot.mode()==PresentationState.Mode.ANNOTATION&&snapshot.drawing()&&!ink){
   ink=true;log.add("stored:"+epoch);stored.accept(epoch);
  }
 });}
 void clearInk(long nextEpoch){new Handler(Looper.MAIN).post(()->{epoch=nextEpoch;ink=false;log.add("clear:"+epoch);});}
}
""",
    "com/focuspen/enhance/v2/LaserMotionBridge.java": """package com.focuspen.enhance.v2;
import java.util.*;
final class LaserMotionBridge {
 interface Sink {
  void move(float dx,float dy);void setPosition(float x,float y);void getPosition(float[] out);
  void fade(int transition);void unfade(int transition);void resetPosition();boolean canShowPointer();
  void displayId(int id);void displayViewports(List<?> values);void screenState(boolean on);
 }
 static Sink sink;static final class Installation {}
 static Installation install(ClassLoader loader,Sink next){sink=next;return new Installation();}
}
""",
    "com/focuspen/enhance/v2/PresentationOwnerTest.java": """package com.focuspen.enhance.v2;
import android.content.*;import android.graphics.Rect;import android.os.*;import android.view.*;
import java.util.*;
public final class PresentationOwnerTest {
 static Context context;static ConfigClient config;
 public static final class Viewport {public int displayId=0;public Rect logicalFrame=new Rect(0,0,1000,800);}
 static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
 static void worker(){Handler.drain(Looper.WORKER);}static void main(){Handler.drain(Looper.MAIN);}
 static void time(long now){SystemClock.now=now;worker();main();worker();main();}
 static KeyEvent key(int action,long down,long when){return new KeyEvent(3,193,down,when,action,0,false);}
 static KeyEvent proKey(int action,long down,long when,int repeat){
  return new KeyEvent(3,194,down,when,action,repeat,false);
 }
 static void pro(int slot,int action,long down,long when,int repeat){
  SystemClock.now=when;
  PenPresentation.proKey(slot==0?proKey(action,down,when,repeat)
      :new KeyEvent(3,195,down,when,action,repeat,false),slot);
  worker();main();
 }
 static void focusWorker(int action,long down,long when){
  SystemClock.now=when;PenPresentation.focusKey(key(action,down,when),true,config.get());worker();
 }
 static void focus(int action,long down,long when){focusWorker(action,down,when);main();}
 static PresentationState.Snapshot state(){return PenPresentationTestAccess.state();}
 static void mode(PresentationState.Mode expected){check(state().mode()==expected,"mode "+state().mode()+" != "+expected);}
 static void setup(String activation) throws Exception {
  Handler.clear();Looper.current=Looper.WORKER;PenPresentation.reset();Handler.clear();
  SystemClock.now=0;PenMouse.reset();
  context=new Context();config=new ConfigClient(new ConfigSnapshot(activation));
  PenPresentation.install(PresentationOwnerTest.class.getClassLoader());
  PenPresentation.bind(context,config,new Handler(Looper.WORKER),()->true);
  main();worker();main();LaserMotionBridge.sink.displayViewports(List.of(new Viewport()));
  worker();main();
 }
 static void shortDoubleLong() throws Exception {
  setup("hold");focus(0,10,10);focus(1,10,40);time(319);mode(PresentationState.Mode.IDLE);
  time(320);mode(PresentationState.Mode.ANNOTATION);
  focus(0,400,400);focus(1,400,420);focus(0,500,500);focus(1,500,520);
  mode(PresentationState.Mode.ANNOTATION);check(PenMouse.enters==0,"clear entered mouse");
  focus(0,1000,1000);time(1200);mode(PresentationState.Mode.ANNOTATION);
  check(state().drawing(),"annotation hold did not start ink");
  focus(1,1000,1220);check(!state().drawing(),"release did not stop ink");
 }
 static void idleDoubleMouse() throws Exception {
  setup("hold");focus(0,10,10);focus(1,10,40);focus(0,100,100);focus(1,100,130);
  mode(PresentationState.Mode.MOUSE);check(PenMouse.enters==1,"idle double did not enter mouse");
  PenPresentation.contextChanged();worker();main();mode(PresentationState.Mode.MOUSE);
  check(PenMouse.contexts==0&&PenMouse.exits==0,"keyboard focus canceled the active mouse drag");
  focus(0,500,500);focus(1,500,530);time(900);
  mode(PresentationState.Mode.MOUSE);
  check(PenMouse.exits==0,"single focus press after context change left mouse");
  focus(0,1000,1000);focus(1,1000,1030);focus(0,1100,1100);focus(1,1100,1130);
  mode(PresentationState.Mode.IDLE);
  check(PenMouse.exits==1,"double focus press after context change failed to exit mouse");
 }
 static void holdAndToggle() throws Exception {
  setup("hold");focus(0,10,10);time(210);mode(PresentationState.Mode.LASER);
  focus(1,10,230);mode(PresentationState.Mode.IDLE);
  setup("toggle");focus(0,10,10);time(210);mode(PresentationState.Mode.LASER);
  focus(1,10,230);mode(PresentationState.Mode.LASER);
  focus(0,500,500);time(700);mode(PresentationState.Mode.IDLE);
  focus(1,500,710);mode(PresentationState.Mode.IDLE);
 }
 static void invalidAndScreen() throws Exception {
  setup("hold");PenPresentation.laserMode(3);worker();main();mode(PresentationState.Mode.LASER);
  check(!PortedPresentationWindow.latest.valid,"unpositioned pointer visible");
  LaserMotionBridge.sink.setPosition(50,60);worker();main();
  check(PortedPresentationWindow.latest.valid&&PortedPresentationWindow.latest.x==50,"position lost");
  LaserMotionBridge.sink.resetPosition();worker();main();
  check(!PortedPresentationWindow.latest.valid,"invalid pointer stayed visible");
  LaserMotionBridge.sink.screenState(false);worker();main();mode(PresentationState.Mode.IDLE);
 }
 static void proDrawAndExit() throws Exception {
  setup("toggle");PenPresentation.laserMode(3);worker();main();mode(PresentationState.Mode.LASER);
  pro(0,0,10,10,0);mode(PresentationState.Mode.ANNOTATION);
  check(state().drawing(),"Pro pinch did not draw immediately");
  long firstGeneration=state().generation();
  pro(0,0,10,20,1);pro(0,0,10,30,0);
  check(state().drawing()&&state().generation()==firstGeneration,"repeat restarted Pro drawing");
  pro(0,0,40,40,0);mode(PresentationState.Mode.ANNOTATION);
  check(state().drawing()&&state().generation()>firstGeneration,"new pinch did not replace old press");
  long replacementGeneration=state().generation();
  pro(0,1,10,50,0);
  check(state().drawing()&&state().generation()==replacementGeneration,"stale UP ended new Pro drawing");
  pro(0,1,40,60,0);mode(PresentationState.Mode.LASER);
  check(!state().drawing(),"Pro release did not restore laser");
  pro(1,0,70,70,0);mode(PresentationState.Mode.IDLE);
  pro(1,1,70,80,0);mode(PresentationState.Mode.IDLE);

  setup("hold");PenPresentation.mouseMode(3);worker();main();mode(PresentationState.Mode.MOUSE);
  pro(1,0,90,90,1);mode(PresentationState.Mode.MOUSE);
  pro(1,0,90,100,0);mode(PresentationState.Mode.IDLE);
  check(PenMouse.exits==1,"Pro body double did not close mouse");
 }
 static void inkAckOrdering() throws Exception {
  setup("hold");focus(0,10,10);focus(1,10,40);time(320);mode(PresentationState.Mode.ANNOTATION);
  LaserMotionBridge.sink.setPosition(10,10);worker();main();
  focus(0,500,500);time(700);check(state().drawing(),"drawing not active");
  LaserMotionBridge.sink.setPosition(20,20);worker();
  check(!Handler.main.isEmpty(),"ink sample was not queued on main");
  focusWorker(1,500,710);
  focusWorker(0,800,800);focusWorker(1,800,820);
  SystemClock.now=1100;worker();mode(PresentationState.Mode.IDLE);
  focusWorker(0,1200,1200);focusWorker(1,1200,1220);
  focusWorker(0,1300,1300);focusWorker(1,1300,1320);
  mode(PresentationState.Mode.IDLE);
  check(PenMouse.enters==0&&!state().hasInk(),"queued ink clear entered mouse");
  main();worker();main();
  check(PortedPresentationWindow.latest.log.stream().anyMatch(s->s.startsWith("stored:")),
      "old ink acknowledgement did not fire");
  check(!state().hasInk(),"late ink callback revived clear");
 }
 public static void main(String[] args) throws Exception {
  shortDoubleLong();idleDoubleMouse();holdAndToggle();invalidAndScreen();proDrawAndExit();inkAckOrdering();
  System.out.println("Production presentation owner: PASS");
 }
}
""",
    "com/focuspen/enhance/v2/PenPresentationTestAccess.java": """package com.focuspen.enhance.v2;
final class PenPresentationTestAccess {
 static PresentationState.Snapshot state(){try{
  var owner=PenPresentation.class.getDeclaredField("OWNER");owner.setAccessible(true);
  var snapshot=PenPresentation.class.getDeclaredField("snapshot");snapshot.setAccessible(true);
  return (PresentationState.Snapshot)snapshot.get(owner.get(null));
 }catch(Exception e){throw new RuntimeException(e);}}
}
""",
}


def main():
    java_home = Path(os.environ.get("JAVA_HOME", "/usr/lib/jvm/java-21-openjdk-amd64"))
    with tempfile.TemporaryDirectory(prefix="focuspen-presentation-") as scratch:
        out = Path(scratch)
        files = []
        for name, source in SOURCES.items():
            path = out / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(source, encoding="utf-8")
            files.append(str(path))
        source_dir = ROOT / "module/app/src/main/java/com/focuspen/enhance/v2"
        files.extend(str(source_dir / name) for name in
                     ("PenPresentation.java", "PresentationState.java", "FocusGesture.java"))
        subprocess.run([str(java_home / "bin/javac"), "--release", "17", "-d", str(out), *files], check=True)
        subprocess.run([str(java_home / "bin/java"), "-cp", str(out),
                        "com.focuspen.enhance.v2.PresentationOwnerTest"], check=True)


if __name__ == "__main__":
    main()
