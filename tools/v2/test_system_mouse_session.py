#!/usr/bin/env python3
"""Run production PenMouse against a queued worker and a fake platform device."""

from pathlib import Path
import os
import subprocess
import tempfile


ROOT = Path(__file__).resolve().parents[2]
SOURCES = {
    "android/os/Handler.java": """package android.os;
import java.util.*;
public final class Handler {
 public final ArrayDeque<Runnable> queue=new ArrayDeque<>();
 public boolean post(Runnable task){queue.add(task);return true;}
 public void drain(){while(!queue.isEmpty())queue.remove().run();}
}
""",
    "android/util/Log.java": """package android.util;
public final class Log {
 public static int w(String tag,String message,Throwable error){return 0;}
}
""",
    "android/view/KeyEvent.java": """package android.view;
public final class KeyEvent {
 public static final int ACTION_DOWN=0,ACTION_UP=1;
 private final int device,key,action,repeat;private final long downTime;private final boolean canceled;
 public KeyEvent(int device,int key,long downTime,int action,int repeat,boolean canceled){
  this.device=device;this.key=key;this.downTime=downTime;this.action=action;this.repeat=repeat;this.canceled=canceled;
 }
 public int getDeviceId(){return device;}public int getKeyCode(){return key;}
 public long getDownTime(){return downTime;}public int getAction(){return action;}
 public int getRepeatCount(){return repeat;}public boolean isCanceled(){return canceled;}
}
""",
    "com/focuspen/enhance/v2/SystemMouseDevice.java": """package com.focuspen.enhance.v2;
import java.util.*;
class SystemMouseDevice implements AutoCloseable {
 final List<String> calls=new ArrayList<>();boolean failMove,failButton,failScroll;int closes;
 boolean move(float dx,float dy){calls.add("move:"+dx+":"+dy);return !failMove;}
 boolean button(int button,boolean down){calls.add("button:"+button+":"+down);return !failButton;}
 boolean scroll(float amount){calls.add("scroll:"+amount);return !failScroll;}
 public void close(){calls.add("close");closes++;}
}
""",
    "com/focuspen/enhance/v2/SystemMouseSessionTest.java": """package com.focuspen.enhance.v2;
import android.os.Handler;import android.view.KeyEvent;import java.util.*;
public final class SystemMouseSessionTest {
 static Handler worker;static List<SystemMouseDevice> devices;static int[] stopped;static boolean[] allowed;
 static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
 static KeyEvent key(int action,long time,int device,int code){return new KeyEvent(device,code,time,action,0,false);}
 static void setup(){
  worker=new Handler();devices=new ArrayList<>();stopped=new int[1];allowed=new boolean[]{true};
  new PenMouse(worker,source->{SystemMouseDevice d=new SystemMouseDevice();devices.add(d);return d;},
      ()->allowed[0],()->stopped[0]++);
 }
 static SystemMouseDevice enter(){PenMouse.enter(8);worker.drain();check(PenMouse.active(),"entry failed");return devices.get(devices.size()-1);}
 static void checkCalls(SystemMouseDevice d,String... expected){
  check(d.calls.equals(List.of(expected)),"calls "+d.calls+" != "+List.of(expected));
 }
 static void crossWindowAndOldUp(){
  setup();SystemMouseDevice d=enter();long before=PenMouse.session();
  check(PenMouse.key(key(0,10,8,93),1),"first DOWN not owned");worker.drain();
  PenMouse.contextChanged();worker.drain();
  check(PenMouse.active()&&PenMouse.session()!=before,"context exited mouse");
  check(PenMouse.key(key(0,20,8,93),1),"second DOWN not owned");worker.drain();
  check(PenMouse.key(key(1,10,8,93),1),"stale UP escaped");worker.drain();
  checkCalls(d,"button:1:true","button:1:false","button:1:true");
  check(PenMouse.key(key(1,20,8,93),1),"new UP not owned");worker.drain();
  checkCalls(d,"button:1:true","button:1:false","button:1:true","button:1:false");
  check(d.closes==0,"context closed mouse");
 }
 static void queuedEntryAndSession(){
  setup();PenMouse.enter(8);PenMouse.exit();worker.drain();
  check(!PenMouse.active()&&devices.isEmpty(),"queued entry revived after exit");
  setup();PenMouse.enter(8);long pending=PenMouse.session();
  check(PenMouse.active()&&PenMouse.isSession(pending),"pending entry is not owned");
  check(PenMouse.key(key(0,5,8,93),1),"pending-entry key leaked");
  check(PenMouse.key(key(1,5,8,93),1),"pending-entry UP leaked");worker.drain();
  checkCalls(devices.get(0),"button:1:true","button:1:false");
  PenMouse.exit();worker.drain();
  SystemMouseDevice d=enter();long old=PenMouse.session();
  PenMouse.exit();PenMouse.enter(8);worker.drain();
  check(d.closes==1&&PenMouse.active()&&PenMouse.session()!=old,"reentry session failed");
  PenMouse.action("leftHold",false,old);worker.drain();
  checkCalls(devices.get(devices.size()-1));
 }
 static void lostUpAndQueueOrder(){
  setup();SystemMouseDevice d=enter();
  PenMouse.relative(1,2);PenMouse.relative(3,4);
  check(PenMouse.key(key(0,10,8,93),1),"key not taken");
  PenMouse.relative(5,6);worker.drain();
  checkCalls(d,"move:4.0:6.0","button:1:true","move:5.0:6.0");
  check(PenMouse.key(key(0,20,8,93),1),"replacement not owned");worker.drain();
  checkCalls(d,"move:4.0:6.0","button:1:true","move:5.0:6.0","button:1:false","button:1:true");
  check(PenMouse.key(key(1,10,8,93),1),"old UP leaked");worker.drain();
  check(d.calls.get(d.calls.size()-1).equals("button:1:true"),"old UP released new press");
  check(PenMouse.key(key(1,20,8,93),1),"replacement UP not owned");worker.drain();
  check(d.calls.get(d.calls.size()-1).equals("button:1:false"),"replacement UP did not release");
 }
 static void rapidTapAndButtonFailure(){
  setup();SystemMouseDevice d=enter();
  check(PenMouse.key(key(0,10,8,93),1),"rapid DOWN not owned");
  check(PenMouse.key(key(1,10,8,93),1),"rapid UP not owned");worker.drain();
  checkCalls(d,"button:1:true","button:1:false");
  PenMouse.action("leftHold",false,PenMouse.session());worker.drain();
  d.failButton=true;
  PenMouse.action("rightHold",false,PenMouse.session());worker.drain();
  check(!PenMouse.active()&&d.closes==1&&stopped[0]==1,"button failure did not stop");
  checkCalls(d,"button:1:true","button:1:false","button:1:true","button:2:true",
      "button:1:false","close");
 }
 static void failureReleasesAndNotifies(){
  setup();SystemMouseDevice d=enter();PenMouse.action("leftHold",false,PenMouse.session());worker.drain();
  d.failScroll=true;PenMouse.action("scrollUp",false,PenMouse.session());worker.drain();
  check(!PenMouse.active()&&d.closes==1&&stopped[0]==1,"device failure did not stop");
  checkCalls(d,"button:1:true","scroll:1.0","button:1:false","close");
  setup();d=enter();allowed[0]=false;PenMouse.relative(1,1);worker.drain();
  check(!PenMouse.active()&&d.closes==1&&stopped[0]==1,"permission loss did not stop");
  setup();PenMouse.enter(8);allowed[0]=false;worker.drain();
  check(!PenMouse.active()&&devices.isEmpty()&&stopped[0]==1,"failed open was not reported");
 }
 public static void main(String[] args){
  crossWindowAndOldUp();queuedEntryAndSession();lostUpAndQueueOrder();rapidTapAndButtonFailure();failureReleasesAndNotifies();
  System.out.println("System mouse session: PASS");
 }
}
""",
}


def main():
    java_home = Path(os.environ.get("JAVA_HOME", "/usr/lib/jvm/java-21-openjdk-amd64"))
    with tempfile.TemporaryDirectory(prefix="focuspen-system-mouse-session-") as scratch:
        out = Path(scratch)
        files = []
        for name, source in SOURCES.items():
            path = out / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(source, encoding="utf-8")
            files.append(str(path))
        files.append(str(ROOT / "module/app/src/main/java/com/focuspen/enhance/v2/PenMouse.java"))
        subprocess.run([str(java_home / "bin/javac"), "--release", "17", "-d", str(out), *files], check=True)
        subprocess.run([str(java_home / "bin/java"), "-cp", str(out),
                        "com.focuspen.enhance.v2.SystemMouseSessionTest"], check=True)


if __name__ == "__main__":
    main()
