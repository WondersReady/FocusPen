#!/usr/bin/env python3
"""Exercise the production laser bridge against a minimal ROM and hook double."""
from pathlib import Path
import subprocess

root = Path(__file__).resolve().parents[2]
out = root / "work/v2/laser-bridge-test"
sources = {
    "android/os/Build": 'package android.os; public class Build { public static String FINGERPRINT="Xiaomi/piano/piano:17/CP2A.260605.016/OS4.0.2.0.XPYCNXM:user/release-keys"; }',
    "android/util/Log": 'package android.util; public class Log { public static int e(String t,String m,Throwable x){return 0;} public static int e(String t,String m){return 0;} }',
    "android/view/KeyEvent": 'package android.view; public class KeyEvent {}',
    "de/robv/android/xposed/XC_MethodHook": '''package de.robv.android.xposed;
public class XC_MethodHook {
  protected void beforeHookedMethod(MethodHookParam p) throws Throwable {}
  protected void afterHookedMethod(MethodHookParam p) throws Throwable {}
  public static class MethodHookParam {
    public Object thisObject,result; public Object[] args; public boolean skipped;
    public void setResult(Object x){result=x;skipped=true;}
  }
  public static class Unhook { private final Runnable remove; public Unhook(Runnable r){remove=r;} public void unhook(){remove.run();} }
}''',
    "de/robv/android/xposed/XposedHelpers": '''package de.robv.android.xposed;
import java.lang.reflect.*; import java.util.*;
public class XposedHelpers {
  public static final Map<String,List<XC_MethodHook>> hooks=new HashMap<>();
  public static int count,failAt=-1;
  public static XC_MethodHook.Unhook findAndHookMethod(Class<?> type,String name,Object... spec){
    if(++count==failAt)throw new IllegalStateException("injected hook failure");
    String key=type.getName()+"#"+name;
    List<XC_MethodHook> list=hooks.computeIfAbsent(key,k->new ArrayList<>());
    XC_MethodHook hook=(XC_MethodHook)spec[spec.length-1];list.add(hook);
    return new XC_MethodHook.Unhook(()->list.remove(hook));
  }
  public static Object call(Object owner,String name,Object... args) throws Exception {
    Class<?> type=owner.getClass(); Method method=null;
    for(Method m:type.getDeclaredMethods())if(m.getName().equals(name)&&m.getParameterCount()==args.length){method=m;break;}
    if(method==null)throw new NoSuchMethodException(name); method.setAccessible(true);
    XC_MethodHook.MethodHookParam p=new XC_MethodHook.MethodHookParam();p.thisObject=owner;p.args=args;
    List<XC_MethodHook> list=hooks.getOrDefault(type.getName()+"#"+name,List.of());
    try {for(XC_MethodHook h:list){h.beforeHookedMethod(p);if(p.skipped)break;}
      if(!p.skipped)p.result=method.invoke(owner,args);
      for(XC_MethodHook h:list)h.afterHookedMethod(p);return p.result;
    }catch(Throwable x){throw new IllegalStateException(x);}
  }
}''',
    "com/android/server/LocalServices": '''package com.android.server;
public class LocalServices {public static Object local;public static Object getService(Class<?> c){return local;}}''',
    "com/android/server/input/MiuiInputManagerInternal": '''package com.android.server.input;
public class MiuiInputManagerInternal {}''',
    "com/android/server/input/MiuiInputManagerService": '''package com.android.server.input;
import java.util.*;import com.miui.server.input.stylus.laser.*;
public class MiuiInputManagerService {
  private LaserPointerController mLaserPointerController;
  private int mPointerDisplayId=3; private List<?> mDisplayViewports;
  private boolean mIsScreenOn=true;public int creations;
  public PointerControllerInterface obtainLaserPointerController(){
    if(mLaserPointerController==null){creations++;mLaserPointerController=new LaserPointerController();}
    return mLaserPointerController;
  }
  public void updatePointerDisplayId(int id){mPointerDisplayId=id;}
  public void updateDisplayViewport(List<?> v){mDisplayViewports=v;}
  private void setScreenState(boolean on){mIsScreenOn=on;}
  public void preclaim(){mLaserPointerController=new LaserPointerController();}
  public static class LocalService extends MiuiInputManagerInternal {
    final MiuiInputManagerService this$0;public LocalService(MiuiInputManagerService s){this$0=s;}
    public PointerControllerInterface obtainLaserPointerController(){return this$0.obtainLaserPointerController();}
  }
}''',
    "com/miui/server/input/stylus/laser/PointerControllerInterface": '''package com.miui.server.input.stylus.laser;
import java.util.List;
public interface PointerControllerInterface {
  void setPosition(float x,float y);void getPosition(float[] out);void move(float dx,float dy);
  void fade(int transition);void unfade(int transition);void resetPosition();boolean canShowPointer();
  void setDisplayId(int id);void setDisplayViewPort(List<?> viewports);
}''',
    "com/miui/server/input/stylus/laser/LaserPointerController": '''package com.miui.server.input.stylus.laser;
import java.util.List;
public class LaserPointerController implements PointerControllerInterface {
  public void setPosition(float x,float y){}public void getPosition(float[] out){}public void move(float x,float y){}
  public void fade(int t){}public void unfade(int t){}public void resetPosition(){}public boolean canShowPointer(){return true;}
  public void setDisplayId(int id){}public void setDisplayViewPort(List<?> v){}
}''',
    "com/miui/server/input/stylus/MiuiStylusShortcutManager": '''package com.miui.server.input.stylus;
import android.view.KeyEvent;
public class MiuiStylusShortcutManager {
  public int calls;
  public boolean shouldInterceptKey(KeyEvent e){calls++;return true;}
  private boolean interceptLaserKey(KeyEvent e){calls++;return true;}
  private boolean interceptP81CLaserKey(KeyEvent e){calls++;return true;}
  public int onVirtualLaserDoubleClickEvent(KeyEvent e){calls++;return 0;}
  private void onVirtualLaserSettingsChanged(int n){calls++;}
}''',
    "com/focuspen/enhance/v2/LaserMotionBridgeTest": '''package com.focuspen.enhance.v2;
import java.util.*;import android.view.KeyEvent;
import com.android.server.LocalServices;import com.android.server.input.MiuiInputManagerService;
import com.miui.server.input.stylus.MiuiStylusShortcutManager;
import com.miui.server.input.stylus.laser.PointerControllerInterface;
import de.robv.android.xposed.XposedHelpers;
public class LaserMotionBridgeTest {
  static void check(boolean value){if(!value)throw new AssertionError();}
  static class Sink implements LaserMotionBridge.Sink {
    int display=-1,moveCount,fadeCount,unfadeCount,resetCount;boolean screen;List<?> viewports;
    float x,y;
    public void setPosition(float a,float b){x=a;y=b;} public void getPosition(float[] out){out[0]=x;out[1]=y;}
    public void move(float a,float b){x+=a;y+=b;moveCount++;}
    public void fade(int t){fadeCount++;}public void unfade(int t){unfadeCount++;}
    public void resetPosition(){resetCount++;}public boolean canShowPointer(){return screen;}
    public void displayId(int n){display=n;}public void displayViewports(List<?> v){viewports=List.copyOf(v);}
    public void screenState(boolean on){screen=on;}
  }
  public static void main(String[] args) throws Exception {
    MiuiInputManagerService service=new MiuiInputManagerService();
    LocalServices.local=new MiuiInputManagerService.LocalService(service);Sink sink=new Sink();
    if(args.length>0 && args[0].equals("preclaimed")){
      service.preclaim();try{LaserMotionBridge.install(LaserMotionBridgeTest.class.getClassLoader(),sink);throw new AssertionError();}
      catch(IllegalStateException expected){check(expected.getMessage().contains("already exists"));}return;
    }
    if(args.length>0 && args[0].equals("rollback")){
      XposedHelpers.failAt=4;try{LaserMotionBridge.install(LaserMotionBridgeTest.class.getClassLoader(),sink);throw new AssertionError();}
      catch(IllegalStateException expected){for(var hooks:XposedHelpers.hooks.values())check(hooks.isEmpty());}
      return;
    }
    LaserMotionBridge.Installation bridge=LaserMotionBridge.install(LaserMotionBridgeTest.class.getClassLoader(),sink);
    PointerControllerInterface pointer=(PointerControllerInterface)XposedHelpers.call(service,"obtainLaserPointerController");
    check(service.creations==0&&sink.display==3&&sink.viewports.isEmpty()&&sink.screen);
    check(pointer==XposedHelpers.call(service,"obtainLaserPointerController"));
    check(pointer.equals(pointer)&&!pointer.equals(new Object())&&pointer.hashCode()==System.identityHashCode(pointer));
    pointer.setPosition(10,20);pointer.move(2,3);float[] out=new float[2];pointer.getPosition(out);
    check(out[0]==12&&out[1]==23&&sink.moveCount==1&&pointer.canShowPointer());
    pointer.fade(0);pointer.unfade(1);pointer.resetPosition();check(sink.fadeCount==1&&sink.unfadeCount==1&&sink.resetCount==1);
    XposedHelpers.call(service,"updatePointerDisplayId",7);
    XposedHelpers.call(service,"updateDisplayViewport",List.of("viewport"));
    XposedHelpers.call(service,"setScreenState",false);
    check(sink.display==7&&sink.viewports.size()==1&&!pointer.canShowPointer());
    pointer.setDisplayId(9);pointer.setDisplayViewPort(null);check(sink.display==9&&sink.viewports.isEmpty());
    MiuiStylusShortcutManager stylus=new MiuiStylusShortcutManager();KeyEvent key=new KeyEvent();
    for(String name:new String[]{"shouldInterceptKey","interceptLaserKey","interceptP81CLaserKey"})
      check(Boolean.FALSE.equals(XposedHelpers.call(stylus,name,key)));
    check(Integer.valueOf(1).equals(XposedHelpers.call(stylus,"onVirtualLaserDoubleClickEvent",key)));
    XposedHelpers.call(stylus,"onVirtualLaserSettingsChanged",1);check(stylus.calls==0);
    try{bridge.close();throw new AssertionError();}catch(IllegalStateException expected){}
    System.out.println("bridge callback, state, isolation and lifetime OK");
  }
}''',
}
for name, source in sources.items():
    path = out / "src" / (name + ".java")
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(source, encoding="utf-8")
classes = out / "classes"
classes.mkdir(parents=True, exist_ok=True)
java = [str(path) for path in (out / "src").rglob("*.java")]
java.append(str(root / "module/app/src/main/java/com/focuspen/enhance/v2/LaserMotionBridge.java"))
java.append(str(root / "module/app/src/main/java/com/focuspen/enhance/v2/HyperOsCompatibility.java"))
subprocess.run(["javac", "-d", str(classes), *java], check=True)
for mode in ("normal", "preclaimed", "rollback"):
    subprocess.run(["java", "-cp", str(classes),
                    "com.focuspen.enhance.v2.LaserMotionBridgeTest", mode], check=True)
