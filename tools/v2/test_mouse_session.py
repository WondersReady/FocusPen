#!/usr/bin/env python3
"""Exercise production ProInput with isolated worker and mouse doubles."""

from pathlib import Path
import os
import subprocess
import tempfile


ROOT = Path(__file__).resolve().parents[2]
SOURCES = {
    "android/os/SystemClock.java": """package android.os;
public class SystemClock {public static long now;public static long uptimeMillis(){return now;}}""",
    "android/os/Handler.java": """package android.os;import java.util.ArrayDeque;
public class Handler {
 public final ArrayDeque<Runnable> queue=new ArrayDeque<>();public Runnable timer;
 public boolean post(Runnable r){queue.add(r);return true;}
 public boolean postAtTime(Runnable r,long t){timer=r;return true;}
 public void removeCallbacks(Runnable r){queue.removeIf(x->x==r);if(timer==r)timer=null;}
 public void drain(){while(!queue.isEmpty())queue.remove().run();}
}""",
    "android/view/KeyEvent.java": """package android.view;
public class KeyEvent {
 public static final int ACTION_DOWN=0,ACTION_UP=1,FLAG_CANCELED=32;
 final long down,time;final int action,code,repeat,device,flags;
 public KeyEvent(long d,long t,int a,int c,int r,int dev,int f){down=d;time=t;action=a;code=c;repeat=r;device=dev;flags=f;}
 public KeyEvent(KeyEvent e){this(e.down,e.time,e.action,e.code,e.repeat,e.device,e.flags);}
 public static KeyEvent changeFlags(KeyEvent e,int f){return new KeyEvent(e.down,e.time,e.action,e.code,e.repeat,e.device,f);}
 public long getDownTime(){return down;}public long getEventTime(){return time;}
 public int getAction(){return action;}public int getKeyCode(){return code;}
 public int getRepeatCount(){return repeat;}public int getDeviceId(){return device;}
 public int getFlags(){return flags;}public boolean isCanceled(){return (flags&FLAG_CANCELED)!=0;}
}""",
    "com/focuspen/enhance/v2/ConfigSnapshot.java": """package com.focuspen.enhance.v2;
class ConfigSnapshot {
 final String[] bindings=new String[ProBindings.KEYS.length];
 int number(String key){return key.equals(ProBindings.HOLD)?650:360;}
 String proBinding(String pkg,int slot){return bindings[slot]==null?ProBindings.DEFAULTS[slot]:bindings[slot];}
}""",
    "com/focuspen/enhance/v2/PenMouse.java": """package com.focuspen.enhance.v2;
class PenMouse {
 static boolean active;static long current=1;
 static boolean active(){return active;}static long session(){return current;}
}""",
    "com/focuspen/enhance/v2/RingContent.java": """package com.focuspen.enhance.v2;
class RingContent {static boolean validId(String s){return s.contains(".");}static boolean isAction(String s){return false;}}""",
    "com/focuspen/enhance/v2/ProInputTest.java": """package com.focuspen.enhance.v2;
import android.os.*;import android.view.*;import java.util.*;
public class ProInputTest {
 static Handler h;static KeyTarget target;static final Object lock=new Object();
 static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
 static class Host implements ProInput.Host {
  int singles;long session=-1;final List<String> actions=new ArrayList<>();
  final List<List<KeyEvent>> delivered=new ArrayList<>();final List<KeyTarget> recipients=new ArrayList<>();
  public void action(String a,boolean release,KeyTarget t,KeyEvent e,long s){actions.add(a+(release?":release":""));session=s;}
  public void single(KeyTarget t,List<KeyEvent> events){singles++;}
  public void applicationEvents(KeyTarget t,List<KeyEvent> events){recipients.add(t);delivered.add(List.copyOf(events));}
  public boolean valid(KeyTarget t){return t==target;}
 }
 static void setup(){h=new Handler();target=new KeyTarget(new Object(),1,1,"reader","none","none");PenMouse.active=false;PenMouse.current=1;}
 static void event(ProInput p,ConfigSnapshot c,int slot,int code,int action,int repeat,long down,long now){
  SystemClock.now=now;check(p.event(null,new KeyEvent(down,now,action,code,repeat,8,0),slot,true,c,target),"Pro event unclaimed");
 }
 static void pinch(ProInput p,ConfigSnapshot c,int action,long down,long now){event(p,c,0,194,action,0,down,now);}
 static void tick(long now){SystemClock.now=now;Runnable r=h.timer;if(r!=null){h.timer=null;r.run();}}
 static void defaults(){
  setup();Host host=new Host();ProInput p=new ProInput(h,host,lock);ConfigSnapshot c=new ConfigSnapshot();
  pinch(p,c,0,0,0);pinch(p,c,1,0,20);tick(380);
  check(host.singles==1&&host.delivered.isEmpty(),"single default pinch did not use host action");
  setup();host=new Host();p=new ProInput(h,host,lock);c=new ConfigSnapshot();
  pinch(p,c,0,0,0);pinch(p,c,1,0,20);pinch(p,c,0,100,100);tick(750);
  check(host.delivered.isEmpty(),"default hold replayed incomplete pair");
  p.cancel();pinch(p,c,1,100,800);
  check(host.delivered.isEmpty()&&host.actions.isEmpty(),"canceled hold leaked partial input");
  pinch(p,c,0,1000,1000);pinch(p,c,1,1000,1020);pinch(p,c,0,1100,1100);
  tick(1750);pinch(p,c,1,1100,1800);
  check(host.delivered.size()==1&&host.delivered.get(0).size()==4
    &&host.recipients.get(0)==target&&host.delivered.get(0).get(3).getAction()==1,
    "complete default hold lost original sequence");
  setup();host=new Host();p=new ProInput(h,host,lock);c=new ConfigSnapshot();c.bindings[0]="none";
  event(p,c,0,194,0,0,0,0);event(p,c,0,194,0,1,0,10);
  event(p,c,0,194,0,1,0,10);event(p,c,0,194,1,0,0,20);
  event(p,c,0,194,0,0,100,100);event(p,c,0,194,0,1,100,110);event(p,c,0,194,1,0,100,120);
  check(host.delivered.size()==1&&host.delivered.get(0).size()==6
    &&host.delivered.get(0).get(1).getRepeatCount()==1
    &&host.delivered.get(0).get(4).getRepeatCount()==1
    &&host.delivered.get(0).get(5).getAction()==1&&host.actions.isEmpty(),
    "none pinch lost pairs or duplicated repeats");
  tick(1000);check(host.delivered.size()==1,"resolved pinch replayed after timeout");
 }
 static void slides(){
  setup();Host host=new Host();ProInput p=new ProInput(h,host,lock);ConfigSnapshot c=new ConfigSnapshot();c.bindings[2]="none";
  event(p,c,2,196,0,0,0,0);event(p,c,2,196,0,1,0,10);event(p,c,2,196,0,1,0,10);event(p,c,2,196,1,0,0,20);
  event(p,c,3,197,0,0,100,100);event(p,c,3,197,0,1,100,110);event(p,c,3,197,1,0,100,120);h.drain();
  check(host.delivered.size()==6&&host.recipients.stream().allMatch(t->t==target)
    &&host.delivered.get(1).get(0).getRepeatCount()==1
    &&host.delivered.get(2).get(0).getAction()==1
    &&host.delivered.get(5).get(0).getAction()==1&&host.actions.isEmpty(),
    "none/default slides did not forward original events once");
  setup();host=new Host();p=new ProInput(h,host,lock);c=new ConfigSnapshot();
  c.bindings[2]="volumeUp";c.bindings[3]="volumeDown";
  event(p,c,2,196,0,0,0,0);event(p,c,2,196,1,0,0,20);
  event(p,c,3,197,0,0,100,100);event(p,c,3,197,1,0,100,120);h.drain();
  check(host.actions.equals(List.of("volumeUp","volumeDown")),"196 up/197 down bindings inverted");
 }
 static void bodyAndMouse(){
  setup();Host host=new Host();ProInput p=new ProInput(h,host,lock);ConfigSnapshot c=new ConfigSnapshot();
  event(p,c,1,195,0,0,0,0);event(p,c,1,195,0,1,0,10);event(p,c,1,195,1,0,0,20);
  check(host.delivered.size()==3&&host.delivered.get(0).get(0).getKeyCode()==195
    &&host.delivered.get(1).get(0).getRepeatCount()==1&&host.delivered.get(2).get(0).getAction()==1,
    "body double tap did not forward original pair and repeat");
  setup();PenMouse.active=true;host=new Host();p=new ProInput(h,host,lock);c=new ConfigSnapshot();
  event(p,c,1,195,0,0,0,0);event(p,c,1,195,1,0,0,20);
  check(host.actions.equals(List.of("exitMouse"))&&host.delivered.isEmpty()&&PenMouse.active,
    "body double tap did not request host exit");
  setup();PenMouse.active=true;host=new Host();p=new ProInput(h,host,lock);c=new ConfigSnapshot();c.bindings[7]="leftHold";
  pinch(p,c,0,2000,2000);tick(2650);
  check(host.actions.equals(List.of("leftHold"))&&host.session==1,"mouse hold lost original session");
  PenMouse.current=2;pinch(p,c,1,2000,2700);
  check(host.actions.equals(List.of("leftHold","release:release"))&&host.session==1,
    "mouse release used new session");
 }
 static void focusChangeAndMouseHold(){
  setup();PenMouse.active=true;Host host=new Host();ProInput p=new ProInput(h,host,lock);
  ConfigSnapshot c=new ConfigSnapshot();c.bindings[7]="leftHold";
  pinch(p,c,0,2000,2000);tick(2650);
  check(host.actions.equals(List.of("leftHold")),"mouse hold did not start");
  KeyEvent down=new KeyEvent(2000,2000,KeyEvent.ACTION_DOWN,194,0,8,0);
  target=new KeyTarget(new Object(),2,1,"shade","none","none");
  p.focusChanged();
  check(host.actions.equals(List.of("leftHold"))&&p.owns(down),
    "focus change released active mouse hold or lost physical UP owner");
  PenMouse.current=2;pinch(p,c,1,2000,2700);
  check(host.actions.equals(List.of("leftHold","release:release"))&&host.session==1,
    "physical UP did not release original mouse hold exactly once");
  check(!p.owns(down)&&!p.event(null,new KeyEvent(2000,2710,KeyEvent.ACTION_UP,194,0,8,0),
    0,true,c,target)&&host.actions.size()==2,"mouse hold released twice");

  setup();PenMouse.active=true;host=new Host();p=new ProInput(h,host,lock);
  c=new ConfigSnapshot();c.bindings[7]="rightHold";
  pinch(p,c,0,3000,3000);tick(3650);
  target=new KeyTarget(new Object(),2,1,"shade","none","none");p.focusChanged();p.cancel();
  check(host.actions.equals(List.of("rightHold","release:release")),
    "explicit cancel did not release preserved mouse hold");
  pinch(p,c,1,3000,3700);
  check(host.actions.size()==2,"physical UP released canceled hold again");

  setup();PenMouse.active=true;host=new Host();p=new ProInput(h,host,lock);c=new ConfigSnapshot();
  pinch(p,c,0,4000,4000);
  target=new KeyTarget(new Object(),2,1,"shade","none","none");p.focusChanged();tick(4650);
  pinch(p,c,1,4000,4700);
  check(host.actions.isEmpty(),"focus change retained an undecided pinch");
 }
 public static void main(String[] args){defaults();slides();bodyAndMouse();focusChangeAndMouseHold();
  System.out.println("Production ProInput pairing, slide bindings, host exit and session release: PASS");}
}""",
}


def main() -> None:
    jdk = Path(os.environ.get("JAVA_HOME", "/usr/lib/jvm/java-21-openjdk-amd64")) / "bin"
    with tempfile.TemporaryDirectory(prefix="focuspen-pro-input-") as folder:
        out = Path(folder)
        files = []
        for name, source in SOURCES.items():
            path = out / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(source, encoding="utf-8")
            files.append(str(path))
        for name in ["ProInput", "PinchSequence", "KeyTarget", "ProBindings"]:
            files.append(str(ROOT / f"module/app/src/main/java/com/focuspen/enhance/v2/{name}.java"))
        subprocess.run([str(jdk / "javac"), "-d", str(out), *files], check=True)
        subprocess.run([str(jdk / "java"), "-cp", str(out), "com.focuspen.enhance.v2.ProInputTest"], check=True)


if __name__ == "__main__":
    main()
