#!/usr/bin/env python3
"""Execute production action routing against a strict ROM-helper contract double."""
import os
from pathlib import Path
import subprocess
import tempfile

root = Path(__file__).resolve().parents[2]
sources = {
"android/os/SystemClock.java": "package android.os; public class SystemClock {public static long uptimeMillis(){return 100;}}",
"android/os/Bundle.java": "package android.os; public class Bundle extends java.util.HashMap<String,Object> {}",
"android/os/PowerManager.java": "package android.os; public class PowerManager { public boolean on=true;public int wakes; public boolean isInteractive(){return on;} public void wakeUp(long t,int reason,String detail){wakes++;} }",
"android/app/KeyguardManager.java": "package android.app; public class KeyguardManager { public boolean locked; public boolean isKeyguardLocked(){return locked;} }",
"android/app/ActivityOptions.java": """package android.app; public class ActivityOptions {
 public final android.os.Bundle bundle=new android.os.Bundle(); public android.os.Bundle toBundle(){return bundle;}
}""",
"android/content/ComponentName.java": "package android.content; public record ComponentName(String pkg,String name) {}",
"android/content/Intent.java": """package android.content; public class Intent {
 public static final int FLAG_ACTIVITY_NEW_TASK=0x10000000,FLAG_ACTIVITY_CLEAR_TOP=0x04000000;
 public static final String ACTION_ASSIST="assist";
 public String action,pkg,type,component; public int flags; public java.util.Map<String,Object> extras=new java.util.HashMap<>();
 public Intent(String a){action=a;} public Intent setPackage(String p){pkg=p;return this;} public String getPackage(){return pkg;}
 public Intent setType(String t){type=t;return this;} public Intent setClassName(String p,String c){pkg=p;component=c;return this;}
 public Intent putExtra(String k,String v){extras.put(k,v);return this;} public Intent addFlags(int f){flags|=f;return this;}
 public Intent putExtra(String k,boolean v){extras.put(k,v);return this;}
 public ComponentName resolveActivity(android.content.pm.PackageManager pm){return pm.resolveActivity(this,0)==null?null:new ComponentName(pkg,"Editor");}
}""",
"android/graphics/drawable/Drawable.java": "package android.graphics.drawable; public class Drawable {}",
"android/content/pm/PackageManager.java": """package android.content.pm; public class PackageManager {
 public final java.util.Set<String> installed=new java.util.HashSet<>(java.util.List.of("com.miui.notes","com.miui.creation"));
 public Object resolveActivity(android.content.Intent i,int flags){return installed.contains(i.pkg)?new Object():null;}
 public Object resolveService(android.content.Intent i,int flags){return new Object();}
 public android.content.Intent getLaunchIntentForPackage(String p){return new android.content.Intent("launcher").setPackage(p);}
 public Object getApplicationInfo(String p,int flags){return p;} public CharSequence getApplicationLabel(Object info){return info.toString();}
 public android.graphics.drawable.Drawable getApplicationIcon(String p){return new android.graphics.drawable.Drawable();}
}""",
"android/content/Context.java": """package android.content; public class Context {
 public final android.os.PowerManager power=new android.os.PowerManager();
 public final android.app.KeyguardManager guard=new android.app.KeyguardManager();
 public final android.content.pm.PackageManager pm=new android.content.pm.PackageManager();
 public <T>T getSystemService(Class<T> c){return c.cast(c==android.os.PowerManager.class?power:guard);}
 public android.content.pm.PackageManager getPackageManager(){return pm;}
}""",
"android/util/Log.java": "package android.util; public class Log {public static int w(String t,String m,Throwable e){return 0;}}",
"de/robv/android/xposed/XposedHelpers.java": """package de.robv.android.xposed; public class XposedHelpers {
 public static Object callStaticMethod(Class<?> c,String name,Object... args){return invoke(c,null,name,args);}
 public static Object callMethod(Object target,String name,Object... args){return invoke(target.getClass(),target,name,args);}
 private static Object invoke(Class<?> c,Object target,String name,Object[] args){
  try {for(java.lang.reflect.Method m:c.getDeclaredMethods()) if(m.getName().equals(name)&&m.getParameterCount()==args.length){m.setAccessible(true);return m.invoke(target,args);}}
  catch(ReflectiveOperationException e){throw new IllegalStateException(e);} throw new AssertionError("Unexpected ROM call "+name);
 }
}""",
"com/miui/server/input/util/ShortCutActionsUtils.java": """package com.miui.server.input.util;
import android.content.*; import android.os.Bundle; import android.app.ActivityOptions;
public class ShortCutActionsUtils {
 public static final ShortCutActionsUtils instance=new ShortCutActionsUtils();
 public String scene="app",optionsTarget; public Intent launched; public Bundle delivered;
 public ActivityOptions options=new ActivityOptions(); public boolean success=true,fail; public int calls;
 public static ShortCutActionsUtils getInstance(Context c){return instance;}
 private String getCurrentScene(){return scene;}
 private ActivityOptions getActivityOptions(String pkg){optionsTarget=pkg;return options;}
 private boolean launchAppSimple(Intent i,Bundle b){if(fail)throw new IllegalStateException("unavailable");launched=i;delivered=b;calls++;return success;}
 private boolean triggerFunction(String action,String source,Bundle extras,boolean haptic){return "stylus_partial_screenshot".equals(action)&&!haptic;}
 private boolean launchXiaoaiScreenIdentification(String source){return true;}
 public void reset(){scene="app";optionsTarget=null;launched=null;delivered=null;options=new ActivityOptions();success=true;fail=false;calls=0;}
}""",
"com/focuspen/enhance/v2/OfficialActionsTest.java": """package com.focuspen.enhance.v2;
import android.content.*; import com.miui.server.input.util.ShortCutActionsUtils;
public class OfficialActionsTest {
 static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
 public static void main(String[] args)throws Exception {
  OfficialPenActions.initialize(OfficialActionsTest.class.getClassLoader());
  Context c=new Context(); ShortCutActionsUtils u=ShortCutActionsUtils.instance;
  for(String id:new String[]{RingContent.NOTES,RingContent.CREATION}) {
   u.reset();String pkg=id.equals(RingContent.NOTES)?"com.miui.notes":"com.miui.creation";
   check(OfficialPenActions.run(c,id),"official launch accepted");
   check(pkg.equals(u.launched.pkg)&&pkg.equals(u.optionsTarget),"target and small-window options agree with both apps installed");
   check(u.delivered==u.options.bundle,"use official options unchanged");
   check("app".equals(u.launched.extras.get("scene"))&&"stylus".equals(u.launched.extras.get("shortcut")),"preserve stylus scene");
   check(u.launched.component==null,"unlocked scene keeps native bridge");
   check(u.launched.flags==0x14000000,"original stylus task flags");
   check(u.launched.action.equals(id.equals(RingContent.NOTES)?"android.intent.action.INSERT_OR_EDIT":"com.miui.creation.action.INSERT_CREATION"),"official editor bridge action, not legacy pad list or launcher");
   check(u.launched.type.equals(id.equals(RingContent.NOTES)?"vnd.android.cursor.item/text_note":"vnd.android.cursor.item/create_creation"),"official MIME type");
   u.reset();u.scene="home";check(OfficialPenActions.run(c,id)&&u.delivered==null&&u.optionsTarget==null,"home retains native fullscreen policy");
   u.reset();c.pm.installed.remove(pkg);check(!OfficialPenActions.run(c,id)&&u.calls==0,"missing selected target never falls back");c.pm.installed.add(pkg);
  }
  u.reset();u.options=null;check(OfficialPenActions.run(c,RingContent.NOTES)&&u.delivered==null,"native null options accepted");
  u.reset();u.scene="unknown";check(!OfficialPenActions.run(c,RingContent.NOTES)&&u.calls==0,"unknown scene rejected");
  for(String id:new String[]{RingContent.NOTES,RingContent.CREATION}) {
   u.reset();c.guard.locked=true;check(OfficialPenActions.run(c,id),"locked quick note accepted");
   check("keyguard".equals(u.launched.extras.get("scene"))&&Boolean.TRUE.equals(u.launched.extras.get("StartActivityWhenLocked"))&&u.delivered==null,"official lockscreen scene and flags without floating options");
   check((id.equals(RingContent.NOTES)?"com.miui.notes":"com.miui.creation").equals(u.launched.pkg),"lockscreen keeps selected target");
   if(id.equals(RingContent.NOTES))check(u.launched.component.endsWith("NoteEditorActivity")&&Boolean.TRUE.equals(u.launched.extras.get("miui.intent.extra.START_ACTIVITY_WHEN_LOCKED"))&&Boolean.TRUE.equals(u.launched.extras.get("extra_from_new_note")),"Notes direct new editor uses recognized lock marker");
   else check(u.launched.component==null,"Creation retains official route");
  }
  check(OfficialPenActions.run(c,RingContent.SCREENSHOT)&&!OfficialPenActions.run(c,RingContent.RECOGNITION),"lockscreen action allowlist");
  c.guard.locked=false;u.reset();c.power.on=false;
  check(!OfficialPenActions.run(c,RingContent.CREATION)&&u.calls==0,"ordinary actions cannot wake device");
  for(String target:new String[]{"note","creation"}) {
   u.reset();check(OfficialPenActions.screenOff(c,target),"dedicated screen-off action accepted");
   check("off_screen".equals(u.launched.extras.get("scene"))&&Boolean.TRUE.equals(u.launched.extras.get("StartActivityWhenLocked")),"off-screen launch marker");
   check((target.equals("note")?"com.miui.notes":"com.miui.creation").equals(u.launched.pkg),"screen-off keeps selected target");
   if(target.equals("note"))check(u.launched.component.endsWith("NoteEditorActivity")&&Boolean.TRUE.equals(u.launched.extras.get("from_keyguard_shortcut")),"off-screen Notes direct keyguard route");
  }
  check(c.power.wakes==1,"Only successful screen-off Notes explicitly wakes once");
  c.power.on=true;check(!OfficialPenActions.screenOff(c,"note"),"stale screen-off request rejected after wake");
  u.reset();u.success=false;check(!OfficialPenActions.run(c,RingContent.NOTES),"launch failure reaches caller");
  u.reset();u.fail=true;check(!OfficialPenActions.run(c,RingContent.NOTES),"helper exception contained");
  u.reset();check(OfficialPenActions.run(c,RingContent.SCREENSHOT)&&OfficialPenActions.run(c,RingContent.RECOGNITION),"other official routes retained");
  check(!OfficialPenActions.run(c,"unknown")&&u.calls==0,"unknown action rejected");
  System.out.println("Production official actions: fixed targets, scene/window policy, locked/missing/failure paths PASS");
 }
}""",
}
with tempfile.TemporaryDirectory(prefix="focuspen-official-") as temp:
    out = Path(temp)
    files = []
    for name, source in sources.items():
        path = out / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(source, encoding="utf-8")
        files.append(str(path))
    for name in ["OfficialPenActions", "MenuEntry", "RingContent", "PenAction"]:
        files.append(str(root / f"module/app/src/main/java/com/focuspen/enhance/v2/{name}.java"))
    jdk = Path(os.environ.get("JAVA_HOME", "/usr/lib/jvm/java-21-openjdk-amd64"))
    subprocess.run([str(jdk / "bin/javac"), "-d", str(out), *files], check=True)
    subprocess.run([str(jdk / "bin/java"), "-cp", str(out), "com.focuspen.enhance.v2.OfficialActionsTest"], check=True)
