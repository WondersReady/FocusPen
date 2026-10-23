#!/usr/bin/env python3
"""Exercise the production hall-state observer with a queued Android substitute."""
import os
import subprocess
from pathlib import Path

root = Path(__file__).resolve().parents[2]
out = root / "work/v2/dock-monitor-test"
sources = {
    "android/net/Uri.java": "package android.net; public class Uri {public final String key;public Uri(String key){this.key=key;}}",
    "android/os/Handler.java": """package android.os;
import java.util.*;
public class Handler {
 public final List<Runnable> queued=new ArrayList<>();public final List<Runnable> delayed=new ArrayList<>();
 public boolean post(Runnable r){queued.add(r);return true;}
 public boolean postDelayed(Runnable r,long ms){if(ms!=1000)throw new AssertionError("retry interval");delayed.add(r);return true;}
 public void removeCallbacks(Runnable r){queued.removeIf(item->item==r);delayed.removeIf(item->item==r);}
 public void runOne(){Runnable r=queued.remove(0);r.run();}
 public void runRetry(){Runnable r=delayed.remove(0);r.run();}
}""",
    "android/database/ContentObserver.java": """package android.database;
public abstract class ContentObserver {
 public final android.os.Handler handler;
 public ContentObserver(android.os.Handler h){handler=h;}
 public void onChange(boolean selfChange){}
}""",
    "android/content/ContentResolver.java": """package android.content;
public class ContentResolver {
 public android.database.ContentObserver observer;public android.net.Uri uri;
 public int registrations,unregistrations;
 public int value=-1;public boolean fail;
 public boolean pauseRead;
 public final java.util.concurrent.CountDownLatch readEntered=new java.util.concurrent.CountDownLatch(1);
 public final java.util.concurrent.CountDownLatch allowRead=new java.util.concurrent.CountDownLatch(1);
 public void registerContentObserver(android.net.Uri u,boolean descendants,android.database.ContentObserver o){
  if(descendants)throw new AssertionError("descendants");uri=u;observer=o;registrations++;
 }
 public void unregisterContentObserver(android.database.ContentObserver o){
  if(o!=observer)throw new AssertionError("observer mismatch");unregistrations++;observer=null;
 }
 public void notifyObserver(){if(observer!=null)observer.onChange(false);}
}""",
    "android/content/Context.java": """package android.content;
public class Context {
 public final ContentResolver resolver=new ContentResolver();
 public ContentResolver getContentResolver(){return resolver;}
}""",
    "android/provider/Settings.java": """package android.provider;
public class Settings {
 public static class System {
  public static android.net.Uri getUriFor(String key){return new android.net.Uri(key);}
  public static int getInt(android.content.ContentResolver r,String key,int fallback){
   if(!key.equals("stylus_hall_status"))throw new AssertionError("setting key");
   if(r.pauseRead){
    r.readEntered.countDown();
    try {if(!r.allowRead.await(5,java.util.concurrent.TimeUnit.SECONDS))throw new AssertionError("read was not released");}
    catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}
   }
   if(r.fail)throw new IllegalStateException("setting unavailable");return r.value;
  }
 }
}""",
    "android/util/Log.java": """package android.util;
public class Log {
 public static int i(String tag,String message){return 0;}
 public static int w(String tag,String message,Throwable error){return 0;}
}""",
    "com/focuspen/enhance/v2/PenDockMonitorTest.java": """package com.focuspen.enhance.v2;
import android.content.Context;import android.os.Handler;import java.util.*;
import java.util.concurrent.*;import java.util.concurrent.atomic.AtomicReference;
public class PenDockMonitorTest {
 static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
 public static void main(String[] args) throws Exception {
  Context c=new Context();Handler h=new Handler();List<Integer> calls=new ArrayList<>();
  c.resolver.value=PenDockMonitor.DOCKED;
  PenDockMonitor monitor=new PenDockMonitor(c,h,calls::add);
  check(c.resolver.registrations==1&&c.resolver.uri.key.equals("stylus_hall_status"),"observer registration");
  check(monitor.state()==PenDockMonitor.UNKNOWN&&calls.isEmpty()&&h.queued.size()==1,"initial read must be queued");
  h.runOne();check(monitor.state()==PenDockMonitor.DOCKED&&calls.equals(List.of(0)),"initial docked setting");
  c.resolver.notifyObserver();check(calls.equals(List.of(0)),"duplicate docked callback");
  c.resolver.value=1;c.resolver.notifyObserver();
  check(monitor.state()==1&&calls.equals(List.of(0,1)),"detach change");
  c.resolver.value=2;c.resolver.notifyObserver();
  check(monitor.state()==-1&&calls.equals(List.of(0,1,-1))&&h.delayed.size()==1,"invalid value retry");
  c.resolver.notifyObserver();check(calls.equals(List.of(0,1,-1))&&h.delayed.size()==1,"duplicate unknown retry");
  c.resolver.fail=true;h.runRetry();
  check(calls.equals(List.of(0,1,-1))&&h.delayed.size()==1,"exception retry");
  c.resolver.fail=false;c.resolver.value=0;h.runRetry();
  check(calls.equals(List.of(0,1,-1,0))&&h.delayed.isEmpty(),"retry recovery");
  c.resolver.value=1;c.resolver.notifyObserver();check(calls.equals(List.of(0,1,-1,0,1)),"second detach");
  c.resolver.value=9;c.resolver.notifyObserver();check(h.delayed.size()==1,"unknown before close");
  int before=calls.size();monitor.close();
  check(c.resolver.unregistrations==1&&h.delayed.isEmpty(),"close cleanup");
  c.resolver.value=0;c.resolver.notifyObserver();
  check(calls.size()==before,"callback after close");
  inFlightClose(0);
  inFlightClose(9);
  System.out.println("PenDockMonitor observer, initial dock, invalid states, retry and close: PASS");
 }
 static void inFlightClose(int settingValue) throws Exception {
  Context c=new Context();Handler h=new Handler();List<Integer> calls=new ArrayList<>();
  c.resolver.value=settingValue;c.resolver.pauseRead=true;
  PenDockMonitor monitor=new PenDockMonitor(c,h,calls::add);
  android.database.ContentObserver stale=c.resolver.observer;
  AtomicReference<Throwable> failure=new AtomicReference<>();
  Thread reader=new Thread(()->{try{h.runOne();}catch(Throwable e){failure.set(e);}},"dock-read");
  reader.start();
  check(c.resolver.readEntered.await(5,TimeUnit.SECONDS),"read did not reach settings");
  CountDownLatch closeReturned=new CountDownLatch(1);
  Thread closer=new Thread(()->{try{monitor.close();}catch(Throwable e){failure.set(e);}finally{closeReturned.countDown();}},"dock-close");
  closer.start();
  long deadline=java.lang.System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
  while(closer.getState()!=Thread.State.BLOCKED && closeReturned.getCount()!=0
    && java.lang.System.nanoTime()<deadline)Thread.yield();
  check(closer.getState()==Thread.State.BLOCKED,"close did not wait for in-flight read");
  check(closeReturned.getCount()==1,"close returned during in-flight read");
  c.resolver.allowRead.countDown();
  reader.join(5000);closer.join(5000);
  check(!reader.isAlive()&&!closer.isAlive()&&failure.get()==null,"read/close threads failed");
  check(c.resolver.unregistrations==1&&h.delayed.isEmpty(),"retry remained after close");
  int afterClose=calls.size();
  stale.onChange(false);
  check(calls.size()==afterClose&&h.delayed.isEmpty(),"callback or retry after close returned");
 }
}""",
}

files = []
for name, source in sources.items():
    path = out / name
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(source, encoding="utf-8")
    files.append(str(path))
files.append(str(root / "module/app/src/main/java/com/focuspen/enhance/v2/PenDockMonitor.java"))
jdk = Path(os.environ.get("JAVA_HOME", "/usr/lib/jvm/java-21-openjdk-amd64")) / "bin"
subprocess.run([str(jdk / "javac"), "-d", str(out), *files], check=True)
subprocess.run([str(jdk / "java"), "-cp", str(out), "com.focuspen.enhance.v2.PenDockMonitorTest"], check=True)
