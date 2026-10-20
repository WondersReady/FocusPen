#!/usr/bin/env python3
"""Exercise the production RuntimeBus directory lifecycle with deterministic Android doubles."""
from pathlib import Path
import os
import subprocess
root = Path(__file__).resolve().parents[2]
out = root / "work/v2/runtime-bus-test"
sources = {
"android/os/IBinder.java": """package android.os; public interface IBinder {
 int FIRST_CALL_TRANSACTION=1, FLAG_ONEWAY=1;
 boolean isBinderAlive(); boolean transact(int code, Parcel data, Parcel reply, int flags);
}""",
"android/os/Binder.java": """package android.os; public class Binder implements IBinder {
 public static int getCallingUid(){return 1000;} public boolean isBinderAlive(){return true;}
 public boolean transact(int code,Parcel data,Parcel reply,int flags){return onTransact(code,data,reply,flags);}
 protected boolean onTransact(int code,Parcel data,Parcel reply,int flags){return true;}
}""",
"android/os/Parcel.java": """package android.os; public class Parcel {
 private String event; private Bundle payload;
 public static Parcel obtain(){return new Parcel();} public void enforceInterface(String s){}
 public String readString(){return event;} public Bundle readBundle(ClassLoader c){return payload;}
 public int dataAvail(){return 0;} public void writeInterfaceToken(String s){} public void writeString(String s){event=s;}
 public void writeBundle(Bundle b){payload=b;} public void recycle(){}
}""",
"android/os/Bundle.java": """package android.os; public class Bundle {
 private final java.util.Map<String,Object> data=new java.util.HashMap<>();
 public void putBinder(String key,IBinder value){data.put(key,value);}
 public IBinder getBinder(String key){return (IBinder)data.get(key);}
 public void putString(String key,String value){data.put(key,value);} public String getString(String key,String fallback){return (String)data.getOrDefault(key,fallback);}
 public void putBoolean(String key,boolean value){data.put(key,value);} public boolean getBoolean(String key){return (Boolean)data.getOrDefault(key,false);}
}""",
"android/os/Process.java": "package android.os; public class Process { public static final int SYSTEM_UID=1000; }",
"android/os/Looper.java": "package android.os; public class Looper {}",
"android/os/HandlerThread.java": """package android.os; public class HandlerThread {
 public HandlerThread(String name){} public void start(){} public Looper getLooper(){return new Looper();}
}""",
"android/os/Handler.java": """package android.os; public class Handler {
 public static Runnable tick; public Handler(Looper looper){}
 public boolean post(Runnable work){tick=work;return true;}
 public boolean postDelayed(Runnable work,long delay){tick=work;return true;}
 public static void run(){Runnable work=tick;tick=null;work.run();}
}""",
"android/util/Log.java": "package android.util; public class Log { public static int w(String t,String s,Throwable e){return 0;} }",
"android/content/pm/PackageManager.java": """package android.content.pm; public class PackageManager {
 public static class Info {public int uid=1000;} public Info getApplicationInfo(String name,int flags){return new Info();}
}""",
"android/content/ContentResolver.java": """package android.content; public class ContentResolver {
 public android.os.Bundle response; public boolean fail; public Runnable during;
 public android.os.Bundle call(Object uri,String method,String arg,android.os.Bundle request){
  if(during!=null) during.run(); if(fail)throw new IllegalStateException("provider temporarily unavailable");return response;
 }
}""",
"android/content/Context.java": """package android.content; public class Context {
 public final ContentResolver resolver=new ContentResolver();
 public String packageName="test";
 public android.content.pm.PackageManager getPackageManager(){return new android.content.pm.PackageManager();}
 public ContentResolver getContentResolver(){return resolver;}
 public String getPackageName(){return packageName;}
}""",
"com/focuspen/enhance/v2/ConfigProvider.java": "package com.focuspen.enhance.v2; class ConfigProvider {static final Object URI=new Object();}",
"com/focuspen/enhance/v2/BuildConfig.java": "package com.focuspen.enhance.v2; class BuildConfig {static final String APPLICATION_ID=\"module\", VERSION_NAME=\"test\";}",
"com/focuspen/enhance/v2/RuntimeBusTest.java": """package com.focuspen.enhance.v2;
import android.os.*; import android.content.Context;
public class RuntimeBusTest {
 static class Peer extends Binder {
  boolean alive=true, accepts=true; int calls;
  public boolean isBinderAlive(){return alive;}
  public boolean transact(int c,Parcel d,Parcel r,int f){calls++;return accepts;}
 }
 static Bundle directory(Peer peer){Bundle b=new Bundle();b.putBinder(RuntimeBus.UI,peer);return b;}
 static void check(boolean value,String label){if(!value)throw new AssertionError(label);}
 public static void main(String[] args)throws Exception{
  Peer registered=new Peer();Bundle registration=new Bundle();registration.putBinder("endpoint",registered);
  RuntimeBus.register(RuntimeBus.INPUT,registration);
  check(RuntimeBus.status().getBoolean("input")&&RuntimeBus.status().getString("inputVersion","missing").isEmpty(),"legacy live endpoint has unknown version");
  registration.putString("version","test");RuntimeBus.register(RuntimeBus.INPUT,registration);
  check(RuntimeBus.status().getString("inputVersion","").equals("test"),"version comes from loaded host");
  registered.alive=false;check(!RuntimeBus.status().getBoolean("input")&&RuntimeBus.status().getString("inputVersion","missing").isEmpty(),"dead endpoint clears status version");
  try{RuntimeBus.register("malicious",registration);throw new AssertionError("unknown host accepted");}catch(SecurityException expected){}
  Context c=new Context(); Peer first=new Peer(); c.resolver.response=directory(first);
  RuntimeBus bus=new RuntimeBus(c,(event,payload)->{},()->{});
  check(!bus.available(RuntimeBus.UI),"no endpoint invented before registration"); Handler.run();
  check(bus.send(RuntimeBus.UI,"action",new Bundle()),"registered peer accepts");
  c.resolver.fail=true; Handler.run();
  check(bus.send(RuntimeBus.UI,"action",new Bundle()),"provider outage does not discard live authenticated peer");
  first.alive=false; check(!bus.send(RuntimeBus.UI,"action",new Bundle()),"dead peer is never used");
  first.alive=true; Peer replacement=new Peer(); c.resolver.fail=false; c.resolver.response=directory(replacement);
  c.resolver.during=()->check(bus.send(RuntimeBus.UI,"action",new Bundle()),"refresh keeps previous directory usable");
  Handler.run(); c.resolver.during=null;
  check(bus.send(RuntimeBus.UI,"action",new Bundle())&&replacement.calls==1,"replacement published");
  replacement.accepts=false; check(!bus.send(RuntimeBus.UI,"action",new Bundle()),"failed transaction reported");
  check(!bus.available(RuntimeBus.UI),"failed peer removed");
  replacement.accepts=true; Handler.run(); check(bus.available(RuntimeBus.UI),"heartbeat reconnects");
  c.resolver.response=new Bundle(); Handler.run();check(!bus.available(RuntimeBus.UI),"successful directory removal honored");
  Context ui=new Context(); ui.packageName=RuntimeBus.UI;
  int[] received={0}; RuntimeBus uiBus=new RuntimeBus(ui,(event,payload)->received[0]++,()->{});
  var endpointField=RuntimeBus.class.getDeclaredField("endpoint");endpointField.setAccessible(true);
  IBinder endpoint=(IBinder)endpointField.get(uiBus);
  Parcel motion=Parcel.obtain();motion.writeString("motion");motion.writeBundle(new Bundle());
  check(endpoint.transact(IBinder.FIRST_CALL_TRANSACTION,motion,null,IBinder.FLAG_ONEWAY),"motion delivered");
  check(received[0]==1,"UI motion bypasses runtime worker queue");
  Parcel action=Parcel.obtain();action.writeString("action");action.writeBundle(new Bundle());
  check(endpoint.transact(IBinder.FIRST_CALL_TRANSACTION,action,null,IBinder.FLAG_ONEWAY),"action delivered");
  check(received[0]==1,"other events retain queued delivery");
  Handler.run();check(received[0]==2,"queued action eventually delivered");
  System.out.println("RuntimeBus: directory lifecycle and low-latency UI motion PASS");
 }
}""",
}
files=[]
for name,code in sources.items():
    path=out/name
    path.parent.mkdir(parents=True,exist_ok=True)
    path.write_text(code,encoding="utf-8")
    files.append(str(path))
files.append(str(root/"module/app/src/main/java/com/focuspen/enhance/v2/RuntimeBus.java"))
jdk=Path(os.environ.get("JAVA_HOME","/usr/lib/jvm/java-21-openjdk-amd64"))/"bin"
subprocess.run([str(jdk/"javac"),"-d",str(out),*files],check=True)
subprocess.run([str(jdk/"java"),"-cp",str(out),"com.focuspen.enhance.v2.RuntimeBusTest"],check=True)
