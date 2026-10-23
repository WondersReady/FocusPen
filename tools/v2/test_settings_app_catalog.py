#!/usr/bin/env python3
"""Compile the real settings catalog against controlled Android doubles."""
from pathlib import Path
import os
import shutil
import subprocess
import tempfile


ROOT = Path(__file__).resolve().parents[2]
SOURCES = {
    "android/R.java": "package android; public final class R { public static final class drawable { public static final int sym_def_app_icon=1; } }",
    "android/util/Log.java": "package android.util; public class Log { public static int w(String a,String b,Throwable t){return 0;} }",
    "android/os/Build.java": "package android.os; public class Build { public static final class VERSION { public static int SDK_INT=33; } }",
    "android/os/Looper.java": "package android.os; public class Looper { public static final Looper MAIN=new Looper(); public static Looper getMainLooper(){return MAIN;} }",
    "android/os/Handler.java": """package android.os;
import java.util.*;
public class Handler {
 public static final Map<Looper,ArrayDeque<Runnable>> queues=new IdentityHashMap<>();
 public static final Map<Looper,ArrayDeque<Delayed>> delayed=new IdentityHashMap<>();
 public static final Map<Looper,Long> time=new IdentityHashMap<>();
 private record Delayed(long due,Runnable task) {}
 private final Looper loop;
 public Handler(Looper l){loop=l;queues.computeIfAbsent(l,k->new ArrayDeque<>());
  delayed.computeIfAbsent(l,k->new ArrayDeque<>());time.putIfAbsent(l,0L);}
 public boolean post(Runnable r){queues.get(loop).add(r);return true;}
 public boolean postDelayed(Runnable r,long delay){delayed.get(loop).add(new Delayed(time.get(loop)+delay,r));return true;}
 public void removeCallbacks(Runnable r){queues.get(loop).removeIf(task->task==r);
  delayed.get(loop).removeIf(task->task.task()==r);}
 public static int pending(Looper l){return queues.get(l).size();}
 public static int pendingDelayed(Looper l){return delayed.get(l).size();}
 public static long nextDelay(Looper l){return delayed.get(l).peek().due()-time.get(l);}
 public static void advance(Looper l,long millis){time.put(l,time.get(l)+millis);
  var it=delayed.get(l).iterator();while(it.hasNext()){Delayed task=it.next();
   if(task.due()<=time.get(l)){queues.get(l).add(task.task());it.remove();}}}
 public static void drain(Looper l){Runnable r;while((r=queues.get(l).poll())!=null)r.run();}
}""",
    "android/os/HandlerThread.java": "package android.os; public class HandlerThread { public static Looper worker; public HandlerThread(String n){} public void start(){worker=new Looper();} public Looper getLooper(){return worker;} }",
    "android/content/res/Configuration.java": "package android.content.res; public class Configuration { public int value; public Configuration(){} public Configuration(Configuration c){value=c.value;} public boolean equals(Object o){return o instanceof Configuration c&&value==c.value;} public int hashCode(){return value;} }",
    "android/content/res/Resources.java": "package android.content.res; public class Resources { public final Configuration config=new Configuration(); public Configuration getConfiguration(){return config;} }",
    "android/graphics/drawable/Drawable.java": """package android.graphics.drawable;
public class Drawable {
 public final int id; public Drawable(int id){this.id=id;}
 public Drawable mutate(){return this;}
 public ConstantState getConstantState(){return new ConstantState(id);}
 public static class ConstantState {
  private final int id; public ConstantState(int id){this.id=id;}
  public Drawable newDrawable(android.content.res.Resources r,Object theme){return new Drawable(id);}
 }
}""",
    "android/content/Intent.java": """package android.content;
public class Intent {
 public static final String ACTION_MAIN="main",CATEGORY_LAUNCHER="launcher";
 public static final String ACTION_PACKAGE_ADDED="add",ACTION_PACKAGE_REMOVED="remove",ACTION_PACKAGE_CHANGED="change",ACTION_PACKAGE_REPLACED="replace";
 public Intent(String action){} public Intent addCategory(String category){return this;}
}""",
    "android/content/IntentFilter.java": "package android.content; public class IntentFilter { public void addAction(String a){} public void addDataScheme(String s){} }",
    "android/content/BroadcastReceiver.java": "package android.content; public abstract class BroadcastReceiver { public abstract void onReceive(Context c,Intent i); }",
    "android/content/ComponentCallbacks.java": "package android.content; public interface ComponentCallbacks { void onConfigurationChanged(android.content.res.Configuration c); void onLowMemory(); }",
    "android/content/Context.java": """package android.content;
public abstract class Context {
 public static final int RECEIVER_NOT_EXPORTED=4;
 public abstract Context getApplicationContext(); public abstract android.content.pm.PackageManager getPackageManager();
 public abstract android.content.res.Resources getResources(); public Object getTheme(){return null;}
 public android.graphics.drawable.Drawable getDrawable(int id){return new android.graphics.drawable.Drawable(id);}
 public abstract void registerReceiver(BroadcastReceiver r,IntentFilter f,int flags);
 public void registerReceiver(BroadcastReceiver r,IntentFilter f){registerReceiver(r,f,0);}
 public abstract void registerComponentCallbacks(ComponentCallbacks c);
}""",
    "android/content/pm/ApplicationInfo.java": "package android.content.pm; public class ApplicationInfo { public final String packageName; public ApplicationInfo(String p){packageName=p;} }",
    "android/content/pm/ActivityInfo.java": "package android.content.pm; public class ActivityInfo { public String packageName; public ActivityInfo(String p){packageName=p;} }",
    "android/content/pm/ResolveInfo.java": "package android.content.pm; public class ResolveInfo { public ActivityInfo activityInfo; public ResolveInfo(String p){activityInfo=new ActivityInfo(p);} }",
    "android/content/pm/PackageManager.java": """package android.content.pm;
import java.util.*; import android.content.Intent; import android.graphics.drawable.Drawable;
public abstract class PackageManager {
 public static class NameNotFoundException extends Exception {}
 public abstract List<ResolveInfo> queryIntentActivities(Intent i,int flags);
 public abstract ApplicationInfo getApplicationInfo(String p,int flags) throws NameNotFoundException;
 public abstract CharSequence getApplicationLabel(ApplicationInfo i);
 public abstract Drawable getApplicationIcon(ApplicationInfo i);
}""",
    "com/focuspen/enhance/v2/SettingsAppCatalogTest.java": """package com.focuspen.enhance.v2;
import android.content.*; import android.content.pm.*; import android.content.res.*;
import android.graphics.drawable.Drawable; import android.os.*; import java.util.*;
public class SettingsAppCatalogTest {
 static class PM extends PackageManager {
  final Map<String,String> labels=new HashMap<>(); final Set<String> launchable=new HashSet<>();
  int queries,icons; boolean fail;
  public List<ResolveInfo> queryIntentActivities(Intent i,int flags){queries++;if(fail)throw new IllegalStateException("scan failed");
   return launchable.stream().map(ResolveInfo::new).toList();}
  public ApplicationInfo getApplicationInfo(String p,int f) throws NameNotFoundException {
   if(!labels.containsKey(p))throw new NameNotFoundException();return new ApplicationInfo(p);}
  public CharSequence getApplicationLabel(ApplicationInfo i){return labels.get(i.packageName);}
  public Drawable getApplicationIcon(ApplicationInfo i){icons++;return new Drawable(i.packageName.hashCode());}
 }
 static class App extends Context {
  final PM pm=new PM(); final Resources resources=new Resources(); BroadcastReceiver receiver; ComponentCallbacks callbacks;
  public Context getApplicationContext(){return this;} public PackageManager getPackageManager(){return pm;}
  public Resources getResources(){return resources;}
  public void registerReceiver(BroadcastReceiver r,IntentFilter f,int flags){receiver=r;}
  public void registerComponentCallbacks(ComponentCallbacks c){callbacks=c;}
 }
 static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
 static void background(){Handler.drain(HandlerThread.worker);} static void main(){Handler.drain(Looper.MAIN);}
 public static void main(String[] args){
  App app=new App();app.pm.launchable.add("z.app");app.pm.labels.put("z.app","Zulu");
  app.pm.labels.put("a.app","Alpha");app.pm.launchable.add("a.app");
  app.pm.labels.put("com.miui.notes","Notes");
  app.pm.fail=true;
  SettingsAppCatalog.initialize(app);
  check(!SettingsAppCatalog.snapshot().ready()&&app.pm.queries==0,"initialization queried PM on UI");
  final int[] called={0};Runnable listener=()->called[0]++;
  SettingsAppCatalog.addListener(listener);main();check(called[0]==1,"initial callback missing");
  background();main();
  check(!SettingsAppCatalog.snapshot().ready()&&app.pm.queries==1,"first scan failure completed loading");
  check(Handler.pendingDelayed(HandlerThread.worker)==1&&Handler.nextDelay(HandlerThread.worker)==250,
   "first retry was not delayed");
  app.pm.fail=false;Handler.advance(HandlerThread.worker,249);background();
  check(app.pm.queries==1,"retry ran before delay");
  Handler.advance(HandlerThread.worker,1);background();main();
  var first=SettingsAppCatalog.snapshot();
  check(first.ready()&&first.packages().equals(List.of("a.app","z.app")),"sorted launchable snapshot");
  check(app.pm.queries==2&&Handler.pendingDelayed(HandlerThread.worker)==0,"transient failure did not recover");
  check(first.contains("a.app")&&!first.contains("com.miui.notes"),"official package in launchables");
  check(first.label("com.miui.notes").equals("Notes")&&first.label("missing").equals("missing"),"label cache/fallback");
  int icons=app.pm.icons;Drawable one=first.icon(app,"a.app"),two=first.icon(app,"a.app");
  check(one!=two&&one.id==two.id&&app.pm.icons==icons,"icon not independently cloned from cache");
  check(first.icon(app,"missing").id==android.R.drawable.sym_def_app_icon&&app.pm.icons==icons,"fallback queried PM");
  int queries=app.pm.queries;SettingsAppCatalog.initialize(app);background();
  check(app.pm.queries==queries,"same configuration rescanned");
  app.pm.launchable.remove("z.app");app.receiver.onReceive(app,new Intent(Intent.ACTION_PACKAGE_REMOVED));
  SettingsAppCatalog.invalidate();background();main();
  check(app.pm.queries==queries+1&&!SettingsAppCatalog.snapshot().contains("z.app"),"stale generation published");
  var prior=SettingsAppCatalog.snapshot();app.pm.fail=true;SettingsAppCatalog.invalidate();background();main();
  check(SettingsAppCatalog.snapshot()==prior,"failed scan cleared old snapshot");
  int failedQueries=app.pm.queries;
  for(long delay:new long[]{250,500,1000}){
   check(Handler.pendingDelayed(HandlerThread.worker)==1&&Handler.nextDelay(HandlerThread.worker)==delay,
    "retry backoff incorrect");
   Handler.advance(HandlerThread.worker,delay);background();main();
   check(SettingsAppCatalog.snapshot()==prior,"retry cleared old snapshot");
  }
  check(app.pm.queries==failedQueries+3&&Handler.pendingDelayed(HandlerThread.worker)==0,
   "failed scan retried without bound");
  app.pm.fail=false;app.resources.config.value=2;SettingsAppCatalog.initialize(app);background();main();
  check(SettingsAppCatalog.snapshot()!=prior,"configuration change did not refresh");
  prior=SettingsAppCatalog.snapshot();app.pm.fail=true;SettingsAppCatalog.invalidate();background();
  check(Handler.pendingDelayed(HandlerThread.worker)==1,"pending retry missing before package update");
  app.pm.fail=false;app.pm.labels.put("new.app","New");app.pm.launchable.add("new.app");
  app.receiver.onReceive(app,new Intent(Intent.ACTION_PACKAGE_ADDED));
  check(Handler.pendingDelayed(HandlerThread.worker)==0,"package update did not cancel pending retry");
  background();main();
  check(SettingsAppCatalog.snapshot()!=prior&&SettingsAppCatalog.snapshot().contains("new.app"),
   "fresh package update did not replace old snapshot");
  int afterUpdate=app.pm.queries;Handler.advance(HandlerThread.worker,2000);background();
  check(app.pm.queries==afterUpdate,"superseded retry scanned after package update");
  int before=called[0];
  app.callbacks.onConfigurationChanged(new Configuration(app.resources.config));background();
  SettingsAppCatalog.removeListener(listener);main();
  check(called[0]==before,"removed listener fired");
  System.out.println("Settings app catalog: PASS");
 }
}""",
}


def main():
    jdk = Path(os.environ["JAVA_HOME"]) / "bin" if os.environ.get("JAVA_HOME") else None
    javac = str(jdk / "javac") if jdk else shutil.which("javac")
    java = str(jdk / "java") if jdk else shutil.which("java")
    if not javac or not java:
        raise SystemExit("Java compiler/runtime unavailable")
    with tempfile.TemporaryDirectory(prefix="focuspen-app-catalog-") as directory:
        target = Path(directory)
        sources = []
        for name, source in SOURCES.items():
            path = target / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(source, encoding="utf-8")
            sources.append(str(path))
        sources.append(str(ROOT / "module/app/src/main/java/com/focuspen/enhance/v2/SettingsAppCatalog.java"))
        subprocess.run([javac, "--release", "17", "-d", str(target), *sources], check=True)
        subprocess.run([java, "-cp", str(target), "com.focuspen.enhance.v2.SettingsAppCatalogTest"], check=True)


if __name__ == "__main__":
    main()
