#!/usr/bin/env python3
"""Check ConfigClient resource rollback with small Android doubles."""
from pathlib import Path
import os
import subprocess

root = Path(__file__).resolve().parents[2]
out = root / "work/v2/config-client-lifecycle-test"
sources = {
    "android/os/Looper.java": "package android.os; public class Looper {public static Looper getMainLooper(){return new Looper();}}",
    "android/os/HandlerThread.java": """package android.os; public class HandlerThread {
 public static int stopped; public HandlerThread(String name){} public void start(){}
 public Looper getLooper(){return new Looper();} public boolean quitSafely(){stopped++;return true;}
}""",
    "android/os/Handler.java": """package android.os; public class Handler {
 public static int posted; public Handler(Looper looper){} public boolean post(Runnable r){posted++;return true;}
 public boolean postDelayed(Runnable r,long delay){posted++;return true;}
 public void removeCallbacks(Runnable r){}
}""",
    "android/os/Bundle.java": "package android.os; public class Bundle {}",
    "android/database/ContentObserver.java": """package android.database; public abstract class ContentObserver {
 public ContentObserver(android.os.Handler h){} public void onChange(boolean selfChange){}
}""",
    "android/content/Intent.java": "package android.content; public class Intent {public static final String ACTION_USER_UNLOCKED=\"unlock\";}",
    "android/content/IntentFilter.java": "package android.content; public class IntentFilter {public IntentFilter(String action){}}",
    "android/content/BroadcastReceiver.java": """package android.content; public abstract class BroadcastReceiver {
 public abstract void onReceive(Context context,Intent intent);
}""",
    "android/content/ContentResolver.java": """package android.content; public class ContentResolver {
 public int registered,unregistered;
 public void registerContentObserver(Object uri,boolean descendants,android.database.ContentObserver observer){registered++;}
 public void unregisterContentObserver(android.database.ContentObserver observer){unregistered++;}
 public android.os.Bundle call(Object uri,String method,String arg,android.os.Bundle extras){return new android.os.Bundle();}
}""",
    "android/content/Context.java": """package android.content; public class Context {
 public final ContentResolver resolver=new ContentResolver(); public boolean failReceiver;
 public int receivers,unregistered;
 public ContentResolver getContentResolver(){return resolver;}
 public Intent registerReceiver(BroadcastReceiver receiver,IntentFilter filter){
  if(failReceiver)throw new IllegalStateException("receiver unavailable");receivers++;return null;
 }
 public void unregisterReceiver(BroadcastReceiver receiver){unregistered++;}
 public String getPackageName(){return "android";}
}""",
    "android/util/Log.java": """package android.util; public class Log {
 public static int w(String tag,String message,Throwable error){return 0;}
 public static int i(String tag,String message){return 0;}
}""",
    "com/focuspen/enhance/v2/ConfigProvider.java": """package com.focuspen.enhance.v2; class ConfigProvider {
 static final Object URI=new Object(); static final String METHOD_GET_CONFIG="get";
}""",
    "com/focuspen/enhance/v2/ConfigSpec.java": "package com.focuspen.enhance.v2; class ConfigSpec {static final int SCHEMA=1;}",
    "com/focuspen/enhance/v2/ConfigSnapshot.java": """package com.focuspen.enhance.v2; class ConfigSnapshot {
 final long revision=0; final boolean available=false;
 static ConfigSnapshot unavailable(){return new ConfigSnapshot();}
 static ConfigSnapshot parse(android.os.Bundle value){return new ConfigSnapshot();}
 boolean enabled(){return false;}
}""",
    "com/focuspen/enhance/v2/ConfigClientLifecycleTest.java": """package com.focuspen.enhance.v2;
import android.content.Context; import android.os.Handler; import android.os.HandlerThread;
public class ConfigClientLifecycleTest {
 static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
 public static void main(String[] args){
  Context failed=new Context();failed.failReceiver=true;
  try{new ConfigClient(failed);throw new AssertionError("receiver failure accepted");}
  catch(IllegalStateException expected){}
  check(failed.resolver.registered==1&&failed.resolver.unregistered==1,"observer leaked on constructor failure");
  check(HandlerThread.stopped==1,"configuration thread leaked on constructor failure");
  Context healthy=new Context();ConfigClient client=new ConfigClient(healthy);
  check(healthy.resolver.registered==1&&healthy.receivers==1,"listeners missing");
  client.close();client.close();
  check(healthy.resolver.unregistered==1&&healthy.unregistered==1,"listeners not removed exactly once");
  check(HandlerThread.stopped==2,"configuration thread not stopped exactly once");
  int before=Handler.posted;client.refresh();check(Handler.posted==before,"closed client scheduled refresh");
  System.out.println("ConfigClient: constructor rollback and close PASS");
 }
}""",
}
files = []
for name, source in sources.items():
    path = out / name
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(source, encoding="utf-8")
    files.append(str(path))
files.append(str(root / "module/app/src/main/java/com/focuspen/enhance/v2/ConfigClient.java"))
jdk = Path(os.environ.get("JAVA_HOME", "/usr/lib/jvm/java-21-openjdk-amd64")) / "bin"
subprocess.run([str(jdk / "javac"), "-d", str(out), *files], check=True)
subprocess.run([str(jdk / "java"), "-cp", str(out), "com.focuspen.enhance.v2.ConfigClientLifecycleTest"], check=True)
