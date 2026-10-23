#!/usr/bin/env python3
"""Exercise SystemMouseDevice against isolated platform API doubles."""

from pathlib import Path
import os
import shutil
import subprocess
import tempfile


ROOT = Path(__file__).resolve().parents[2]
SOURCES = {
    "android/graphics/PointF.java": """package android.graphics;
public class PointF {
 public final float x,y;
 public PointF(float x,float y){this.x=x;this.y=y;}
}""",
    "android/os/IBinder.java": "package android.os; public interface IBinder {}",
    "android/os/Binder.java": "package android.os; public class Binder implements IBinder {}",
    "android/os/SystemClock.java": """package android.os;
public class SystemClock {
 public static long now=123;
 public static long uptimeMillis(){return now;}
}""",
    "android/hardware/input/VirtualMouseConfig.java": """package android.hardware.input;
public class VirtualMouseConfig {
 public String name;public int displayId,vendorId,productId;
 public static class Builder {
  final VirtualMouseConfig value=new VirtualMouseConfig();
  public Builder setInputDeviceName(String v){value.name=v;return this;}
  public Builder setAssociatedDisplayId(int v){value.displayId=v;return this;}
  public Builder setVendorId(int v){value.vendorId=v;return this;}
  public Builder setProductId(int v){value.productId=v;return this;}
  public VirtualMouseConfig build(){return value;}
 }
}""",
    "android/hardware/input/VirtualMouseRelativeEvent.java": """package android.hardware.input;
public class VirtualMouseRelativeEvent {
 public float x,y;public long time;
 public static class Builder {
  final VirtualMouseRelativeEvent value=new VirtualMouseRelativeEvent();
  public Builder setRelativeX(float v){value.x=v;return this;}
  public Builder setRelativeY(float v){value.y=v;return this;}
  public Builder setEventTimeNanos(long v){value.time=v;return this;}
  public VirtualMouseRelativeEvent build(){return value;}
 }
}""",
    "android/hardware/input/VirtualMouseButtonEvent.java": """package android.hardware.input;
public class VirtualMouseButtonEvent {
 public int code,action;public long time;
 public static class Builder {
  final VirtualMouseButtonEvent value=new VirtualMouseButtonEvent();
  public Builder setButtonCode(int v){value.code=v;return this;}
  public Builder setAction(int v){value.action=v;return this;}
  public Builder setEventTimeNanos(long v){value.time=v;return this;}
  public VirtualMouseButtonEvent build(){return value;}
 }
}""",
    "android/hardware/input/VirtualMouseScrollEvent.java": """package android.hardware.input;
public class VirtualMouseScrollEvent {
 public float y;public long time;
 public static class Builder {
  final VirtualMouseScrollEvent value=new VirtualMouseScrollEvent();
  public Builder setYAxisMovement(float v){value.y=v;return this;}
  public Builder setEventTimeNanos(long v){value.time=v;return this;}
  public VirtualMouseScrollEvent build(){return value;}
 }
}""",
    "com/android/server/LocalServices.java": """package com.android.server;
public class LocalServices {
 public static com.android.server.input.InputManagerInternal service;
 public static Object getService(Class<?> type){
  if(type!=com.android.server.input.InputManagerInternal.class)throw new AssertionError(type);
  return service;
 }
}""",
    "com/android/server/input/InputManagerInternal.java": """package com.android.server.input;
import android.hardware.input.*;import android.os.IBinder;
public class InputManagerInternal {
 public IBinder token;public VirtualMouseConfig config;
 public final FakeDevice device=new FakeDevice();
 public FakeDevice createVirtualMouse(IBinder t,VirtualMouseConfig c){token=t;config=c;return device;}
}""",
    "com/android/server/input/FakeDevice.java": """package com.android.server.input;
import android.graphics.PointF;import android.hardware.input.*;import java.util.*;
public class FakeDevice {
 public final List<VirtualMouseRelativeEvent> moves=new ArrayList<>();
 public final List<VirtualMouseButtonEvent> buttons=new ArrayList<>();
 public final List<VirtualMouseScrollEvent> scrolls=new ArrayList<>();
 public boolean failNextButton,closed;public int closes;
 public PointF position=new PointF(321.5f,654.25f);
 public int getInputDeviceId(){return 47;}
 public boolean sendMouseRelativeEvent(VirtualMouseRelativeEvent e){moves.add(e);return true;}
 public boolean sendMouseButtonEvent(VirtualMouseButtonEvent e){
  buttons.add(e);if(failNextButton){failNextButton=false;return false;}return true;
 }
 public boolean sendMouseScrollEvent(VirtualMouseScrollEvent e){scrolls.add(e);return true;}
 public PointF getCursorPositionInLogicalDisplay(){return position;}
 public void close(){closed=true;closes++;}
}""",
    "de/robv/android/xposed/XposedHelpers.java": """package de.robv.android.xposed;
import java.lang.reflect.*;
public class XposedHelpers {
 public static Object callMethod(Object object,String name,Object...args){
  return invoke(object.getClass(),object,name,args);
 }
 public static Object callStaticMethod(Class<?> type,String name,Object...args){
  return invoke(type,null,name,args);
 }
 private static Object invoke(Class<?> type,Object target,String name,Object[] args){
  try {
   for(Method method:type.getMethods())
    if(method.getName().equals(name)&&method.getParameterCount()==args.length)
     return method.invoke(target,args);
   throw new NoSuchMethodException(type.getName()+"."+name);
  } catch(Exception error){throw new RuntimeException(error);}
 }
}""",
    "com/focuspen/enhance/v2/SystemMouseDeviceTest.java": """package com.focuspen.enhance.v2;
import android.graphics.PointF;import android.os.*;import android.hardware.input.*;
import com.android.server.LocalServices;import com.android.server.input.*;

public class SystemMouseDeviceTest {
 static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
 static SystemMouseDevice fresh() throws Exception {
  LocalServices.service=new InputManagerInternal();
  return new SystemMouseDevice(SystemMouseDeviceTest.class.getClassLoader(),0);
 }
 static FakeDevice device(){return LocalServices.service.device;}

 static void creationAndRouting() throws Exception {
  SystemMouseDevice mouse=fresh();InputManagerInternal service=LocalServices.service;
  check(service.token instanceof Binder,"creation token missing");
  check(service.config.displayId==0,"mouse not associated with display 0");
  check(service.config.vendorId==0&&service.config.productId==0,"mouse identity is not neutral");
  check("FocusPen System Mouse".equals(service.config.name),"mouse name changed");
  check(mouse.deviceId()==47,"device id did not come from platform device");
  check(mouse.move(2.5f,-3f),"relative move failed");
  check(mouse.button(1,true),"left press failed");
  check(device()==service.device&&device().moves.size()==1&&device().buttons.size()==1,
    "move and button did not reach created device");
  VirtualMouseRelativeEvent move=device().moves.get(0);
  check(move.x==2.5f&&move.y==-3f&&move.time==123_000_000L,"relative event contents wrong");
  check(device().buttons.get(0).code==1&&device().buttons.get(0).action==11,
    "button press contents wrong");
  PointF platformPosition=device().position;
  check(mouse.position()==platformPosition&&mouse.position().x==321.5f&&mouse.position().y==654.25f,
    "cursor position did not come from platform device");
  mouse.close();
 }

 static void buttonLifecycle() throws Exception {
  SystemMouseDevice mouse=fresh();FakeDevice platform=device();
  check(mouse.button(1,true)&&mouse.button(1,true),"repeated press failed");
  check(platform.buttons.size()==1,"repeated press sent a second event");
  check(mouse.button(1,false)&&mouse.button(1,false),"repeated release failed");
  check(platform.buttons.size()==2&&platform.buttons.get(1).action==12,"release not sent once");
  check(!mouse.button(3,true)&&platform.buttons.size()==2,"invalid button was sent");
  mouse.close();

  mouse=fresh();platform=device();platform.failNextButton=true;
  check(!mouse.button(2,true),"failed press reported success");
  check(mouse.button(2,false)&&platform.buttons.size()==1,
    "failed press incorrectly owned a release");
  check(mouse.button(2,true)&&mouse.button(2,false),"retry after failed press failed");
  check(platform.buttons.size()==3&&platform.buttons.get(1).action==11
    &&platform.buttons.get(2).action==12,"retry did not send press/release");
  mouse.close();
 }

 static void closeAndScroll() throws Exception {
  SystemMouseDevice mouse=fresh();FakeDevice platform=device();
  check(mouse.button(1,true),"left hold failed");
  platform.failNextButton=true;
  check(!mouse.button(2,true),"right press should fail");
  int beforeClose=platform.buttons.size();
  mouse.close();mouse.close();
  check(platform.closes==1&&platform.closed,"platform close was not idempotent");
  check(platform.buttons.size()==beforeClose+1,"close released a button without a successful hold");
  VirtualMouseButtonEvent release=platform.buttons.get(beforeClose);
  check(release.code==1&&release.action==12,"close did not release the successful hold");
  check(!mouse.move(1f,1f)&&!mouse.button(1,true)&&!mouse.scroll(1f)
    &&mouse.position()==null,"closed mouse accepted activity");

  mouse=fresh();platform=device();
  check(mouse.scroll(2f)&&mouse.scroll(-3f)&&mouse.scroll(0.25f),"finite scroll failed");
  check(platform.scrolls.size()==3&&platform.scrolls.get(0).y==1f
    &&platform.scrolls.get(1).y==-1f&&platform.scrolls.get(2).y==0.25f,
    "scroll values were not clamped to platform range");
  check(platform.scrolls.get(0).time==123_000_000L,"scroll timestamp missing");
  check(!mouse.scroll(Float.NaN)&&!mouse.scroll(Float.POSITIVE_INFINITY)
    &&platform.scrolls.size()==3,"nonfinite scroll reached platform");
  check(!mouse.move(Float.NaN,0f)&&!mouse.move(0f,Float.NEGATIVE_INFINITY)
    &&platform.moves.isEmpty(),"nonfinite move reached platform");
  mouse.close();
 }

 public static void main(String[] args) throws Exception {
  creationAndRouting();buttonLifecycle();closeAndScroll();
  System.out.println("System mouse platform adapter: PASS");
 }
}""",
}


def main() -> None:
    jdk = Path(os.environ["JAVA_HOME"]) / "bin" if os.environ.get("JAVA_HOME") else None
    javac = str(jdk / "javac") if jdk else shutil.which("javac")
    java = str(jdk / "java") if jdk else shutil.which("java")
    if not javac or not java:
        raise SystemExit("Java compiler/runtime unavailable; run in the repository's JDK environment")
    with tempfile.TemporaryDirectory(prefix="focuspen-system-mouse-") as folder:
        out = Path(folder)
        files = []
        for name, source in SOURCES.items():
            path = out / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(source, encoding="utf-8")
            files.append(str(path))
        files.append(str(ROOT / "module/app/src/main/java/com/focuspen/enhance/v2/SystemMouseDevice.java"))
        subprocess.run([javac, "-d", str(out), *files], check=True)
        subprocess.run([java, "-cp", str(out), "com.focuspen.enhance.v2.SystemMouseDeviceTest"], check=True)


if __name__ == "__main__":
    main()
