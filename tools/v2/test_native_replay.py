#!/usr/bin/env python3
"""Run the production replay adapter against small Android service doubles (not a device test)."""
from pathlib import Path
import os
import subprocess

root = Path(__file__).resolve().parents[2]
out = root / "work/v2/native-replay-test"
sources = {
"android/os/SystemClock.java": "package android.os; public class SystemClock { public static long uptimeMillis(){return 100;} }",
"de/robv/android/xposed/XC_MethodHook.java": """package de.robv.android.xposed;
public class XC_MethodHook { public static class MethodHookParam { public Object[] args; public Object result; public void setResult(Object value){result=value;} }
protected void beforeHookedMethod(MethodHookParam p){} public Object run(Object... args){MethodHookParam p=new MethodHookParam();p.args=args;beforeHookedMethod(p);return p.result;} }
""",
"android/os/IBinder.java": "package android.os; public interface IBinder {}",
"android/os/ServiceManager.java": """package android.os; public class ServiceManager {
 public static IBinder service; public static IBinder getService(String name) { return service; }
}""",
"android/view/InputEvent.java": "package android.view; public class InputEvent {}",
"android/view/MotionEvent.java": "package android.view; public class MotionEvent extends InputEvent {}",
"android/view/KeyEvent.java": """package android.view; public class KeyEvent extends InputEvent {
 public final int deviceId, action; public KeyEvent(int deviceId, int action) { this.deviceId=deviceId; this.action=action; }
 public int getDeviceId(){return deviceId;}public int getAction(){return action;}public int getSource(){return 257;}
 public int getDisplayId(){return 0;}public int getKeyCode(){return 93;}public int getScanCode(){return 4;}
 public int getRepeatCount(){return 0;}public int getMetaState(){return 0;}public long getDownTime(){return 100;}
 public long getEventTime(){return 100+action;}
}""",
"android/hardware/input/IInputManager.java": """package android.hardware.input; public interface IInputManager {
 class Stub { public static Object asInterface(android.os.IBinder binder) { return binder; } }
}""",
"com/android/server/input/NativeInputManagerService.java": """package com.android.server.input;
interface NativeInputManagerService { int injectInputEvent(android.view.InputEvent event, boolean targeted,
 int target, int mode, int timeout, int flags, boolean ignored, String[] names, int uid); }
""",
"com/android/server/input/InputManagerService.java": """package com.android.server.input;
public class InputManagerService implements android.os.IBinder {
 public int result, count; public android.view.InputEvent last;
 public android.os.IBinder focus;
 public long interceptKeyBeforeDispatching(android.os.IBinder focus, android.view.KeyEvent event, int flags){return 999;}
 public static class Window {
  public final Service mWmService=new Service(); public final android.os.IBinder mInputChannelToken=new android.os.IBinder(){};
  public int getOwningUid(){return 12345;}
 }
 public static class Service {public final Object mGlobalLock=new Object();}
 private final NativeInputManagerService mNative = (event,targeted,target,mode,timeout,flags,ignored,names,uid) -> {
   if(event instanceof android.view.MotionEvent) {
    if(!targeted || target!=12345 || flags!=0x08000000 || mode!=1 || timeout!=100 || ignored || names!=null || uid!=-1) throw new AssertionError("pointer target/policy/timeout lost");
    last=event;count++;return result;
   }
   if (!targeted || target != 12345 || ignored || names != null || uid != -1) throw new AssertionError("unexpected target routing");
   if ((flags & 0x04000000) == 0) throw new AssertionError("device identity would be discarded");
   if ((flags & 0x40000000) == 0 || (flags & 0x08000000) == 0) throw new AssertionError("dispatch or repeat policy lost");
   if (mode != 1 || timeout > 1000) throw new AssertionError("unbounded replay wait");
   Object decision=de.robv.android.xposed.XposedHelpers.hook.run(focus,event,flags);
   if(!Long.valueOf(0).equals(decision))return 2;
   last = event; count++; return result;
 };
}""",
"de/robv/android/xposed/XposedHelpers.java": """package de.robv.android.xposed;
public class XposedHelpers {
 public static XC_MethodHook hook;
 public static Object findAndHookMethod(Class<?> type,String name,Object... args){hook=(XC_MethodHook)args[args.length-1];return null;}
 public static Object callMethod(Object object,String name,Object... args){
  try {var method=object.getClass().getMethod(name);return method.invoke(object,args);}
  catch(Exception e){throw new RuntimeException(e);}
 }
 public static Object callStaticMethod(Class<?> type, String name, Object... args) {
  try { for (var method : type.getMethods()) if (method.getName().equals(name)) return method.invoke(null,args); }
  catch (Exception e) { throw new RuntimeException(e); } throw new IllegalArgumentException(name);
 }
 public static Object getObjectField(Object object, String name) {
  try { var field=object.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(object); }
  catch (Exception e) { throw new RuntimeException(e); }
 }
}""",
"com/focuspen/enhance/v2/NativeKeyReplayTest.java": """package com.focuspen.enhance.v2;
import android.os.ServiceManager;
import android.view.KeyEvent;
import com.android.server.input.InputManagerService;
public class NativeKeyReplayTest {
 public static void main(String[] args) throws Exception {
  NativeKeyReplay.verify(NativeKeyReplayTest.class.getClassLoader());
  InputManagerService input = new InputManagerService(); ServiceManager.service = input;
  NativeKeyReplay replay = new NativeKeyReplay();
  InputManagerService.Window window=new InputManagerService.Window();input.focus=window.mInputChannelToken;
  KeyEvent down = new KeyEvent(18,0), up = new KeyEvent(18,1);
  replay.dispatchToApplication(down,window); if (input.last != down) throw new AssertionError("DOWN replaced");
  replay.dispatchToApplication(up,window); if (input.last != up || input.count != 2) throw new AssertionError("UP replaced or duplicated");
  for (int result : new int[]{1,2,3}) {
   input.result = result; up=new KeyEvent(18+result,1);
   try { replay.dispatchToApplication(up,window); throw new AssertionError("rejection accepted"); }
   catch (IllegalStateException expected) {}
  }
  input.result=0;
  input.focus=new android.os.IBinder(){};int before=input.count;
  try {replay.dispatchToApplication(new KeyEvent(25,0),window);throw new AssertionError("cross-window dispatch accepted");}
  catch(IllegalStateException expected){}
  if(input.count!=before)throw new AssertionError("wrong window received key");
  Object unrelated=de.robv.android.xposed.XposedHelpers.hook.run(input.focus,new KeyEvent(99,0),0x04000000);
  if(unrelated!=null)throw new AssertionError("unrelated injection bypassed policy");
  android.view.MotionEvent pointer=new android.view.MotionEvent(); replay.dispatchPointer(pointer,12345);
  if(input.last!=pointer) throw new AssertionError("Pointer replaced");
  for(int result:new int[]{1,2,3}) {input.result=result;int count=input.count;
   try {replay.dispatchPointer(pointer,12345);throw new AssertionError("Pointer failure swallowed");}catch(IllegalStateException expected){}
   if(input.count!=count+1) throw new AssertionError("Retried pointer without target");
  }
  try {replay.dispatchPointer(pointer,-1);throw new AssertionError("Untargeted pointer allowed");}catch(IllegalArgumentException expected){}
  ServiceManager.service = new android.os.IBinder() {};
  try { new NativeKeyReplay(); throw new AssertionError("remote service accepted"); }
  catch (IllegalStateException expected) {}
  System.out.println("Native replay: identity, paired events, bounded wait and failure propagation PASS");
 }
}""",
}
files = []
for name, content in sources.items():
    path = out / name
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")
    files.append(str(path))
for production in ("NativeKeyReplay", "ApplicationKeyDispatch"):
    files.append(str(root / f"module/app/src/main/java/com/focuspen/enhance/v2/{production}.java"))
jdk = Path(os.environ.get("JAVA_HOME", "/usr/lib/jvm/java-21-openjdk-amd64")) / "bin"
subprocess.run([str(jdk / "javac"), "-d", str(out), *files], check=True)
subprocess.run([str(jdk / "java"), "-cp", str(out), "com.focuspen.enhance.v2.NativeKeyReplayTest"], check=True)
