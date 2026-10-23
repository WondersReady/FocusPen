#!/usr/bin/env python3
"""Check direct-boot provider routing without opening credential storage."""

from pathlib import Path
import os
import re
import subprocess
import tempfile
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[2]
SOURCES = {
    "android/os/Bundle.java": """package android.os;
public class Bundle extends java.util.HashMap<String,Object> {
 public void putString(String k,String v){put(k,v);}public String getString(String k,String d){return (String)getOrDefault(k,d);}
 public void putBoolean(String k,boolean v){put(k,v);}public boolean getBoolean(String k){return (Boolean)getOrDefault(k,false);}
 public void putParcelable(String k,Object v){put(k,v);}
}""",
    "android/os/Binder.java": """package android.os;
public class Binder {
 public static int uid=123,restores;
 public static int getCallingUid(){return uid;}
 public static long clearCallingIdentity(){return 9;}
 public static void restoreCallingIdentity(long token){restores++;}
}""",
    "android/os/Process.java": "package android.os;public class Process {public static int myUid(){return 123;}}",
    "android/os/SystemClock.java": "package android.os;public class SystemClock {public static long elapsedRealtime(){return 100;}}",
    "android/net/Uri.java": """package android.net;
public class Uri {private final String value;private Uri(String text){value=text;}
 public static Uri parse(String text){return new Uri(text);}
 public String toString(){return value;}}""",
    "android/database/Cursor.java": "package android.database;public interface Cursor {}",
    "android/content/ContentValues.java": "package android.content;public class ContentValues {}",
    "android/content/SharedPreferences.java": """package android.content;
public interface SharedPreferences {
 String getString(String key,String fallback);Editor edit();
 interface Editor {Editor putString(String key,String value);boolean commit();}
}""",
    "android/content/Context.java": """package android.content;
import android.content.pm.PackageManager;
public class Context {
 public int prefReads,prefEdits;public final PackageManager packages=new PackageManager();
 public final ContentResolver resolver=new ContentResolver();public String model="auto";
 public SharedPreferences getSharedPreferences(String name,int mode){prefReads++;return new SharedPreferences(){
  public String getString(String key,String fallback){return model;}
  public Editor edit(){prefEdits++;return new Editor(){
   String next;public Editor putString(String k,String v){next=v;return this;}
   public boolean commit(){model=next;return true;}
  };}
 };}
 public PackageManager getPackageManager(){return packages;}
 public ContentResolver getContentResolver(){return resolver;}
}""",
    "android/content/ContentResolver.java": """package android.content;
public class ContentResolver {public int notifications;public void notifyChange(Object uri,Object observer){notifications++;}}""",
    "android/content/Intent.java": """package android.content;
import android.net.Uri;import android.os.Bundle;
public class Intent {
 public String action;
 public Intent(Context c,Class<?> type){}
 public Intent setAction(String value){action=value;return this;}public Intent setData(Uri value){return this;}
 public Intent putExtras(Bundle value){return this;}public Intent putExtra(String key,String value){return this;}
 public Intent putExtra(String key,long value){return this;}
}""",
    "android/content/ContentProvider.java": """package android.content;
import android.database.Cursor;import android.net.Uri;import android.os.Bundle;
public class ContentProvider {
 public Context context;public String caller="io.github.wondersready.focuspen";
 public Context getContext(){return context;}public String getCallingPackage(){return caller;}
 public boolean onCreate(){return true;}public Bundle call(String m,String a,Bundle b){return null;}
 public Cursor query(Uri u,String[] p,String s,String[] a,String sort){return null;}
 public String getType(Uri u){return null;}public Uri insert(Uri u,ContentValues v){return null;}
 public int delete(Uri u,String s,String[] a){return 0;}public int update(Uri u,ContentValues v,String s,String[] a){return 0;}
}""",
    "android/content/pm/ApplicationInfo.java": """package android.content.pm;
public class ApplicationInfo {public static final int FLAG_SYSTEM=1;public int uid,flags;}""",
    "android/content/pm/PackageManager.java": """package android.content.pm;
public class PackageManager {
 public ApplicationInfo getApplicationInfo(String pkg,int flags){ApplicationInfo out=new ApplicationInfo();out.uid=200;out.flags=1;return out;}
}""",
    "android/app/PendingIntent.java": """package android.app;
import android.content.*;
public class PendingIntent {
 public static final int FLAG_CANCEL_CURRENT=1,FLAG_ONE_SHOT=2,FLAG_IMMUTABLE=4;
 public static int calls;public static String lastAction;
 public static PendingIntent getBroadcast(Context c,int request,Intent i,int flags){calls++;lastAction=i.action;return new PendingIntent();}
}""",
    "com/focuspen/enhance/v2/BootInputConfig.java": """package com.focuspen.enhance.v2;
import android.content.Context;import android.os.Bundle;
class BootInputConfig {
 static boolean unlocked;static int bootReads;
 static boolean unlocked(Context c){return unlocked;}
 static Bundle read(Context c){bootReads++;return new Bundle();}
}""",
    "com/focuspen/enhance/v2/ConfigStore.java": """package com.focuspen.enhance.v2;
import android.content.Context;import android.os.Bundle;
class ConfigStore {
 static int reads;static Bundle read(Context c){reads++;return new Bundle();}
 static void put(Bundle b,String key,Object value){b.put(key,value);}
}""",
    "com/focuspen/enhance/v2/ConfigSpec.java": """package com.focuspen.enhance.v2;
class ConfigSpec {static final String ENABLED="enabled";static void validate(String key,Object value){}}""",
    "com/focuspen/enhance/v2/BuildConfig.java": """package com.focuspen.enhance.v2;
class BuildConfig {static final String APPLICATION_ID="io.github.wondersready.focuspen";}""",
    "com/focuspen/enhance/v2/RuntimeBus.java": """package com.focuspen.enhance.v2;
import android.os.Bundle;
class RuntimeBus {
 static final String REGISTER="register";static int registrations,previews,statusReads;
 static Bundle register(String caller,Bundle extras){registrations++;return new Bundle();}
 static Bundle status(){statusReads++;return new Bundle();}
 static boolean preview(){previews++;return true;}
}""",
    "com/focuspen/enhance/v2/ConfigCommandReceiver.java": "package com.focuspen.enhance.v2;class ConfigCommandReceiver {}",
    "com/focuspen/enhance/v2/ConfigProviderTest.java": """package com.focuspen.enhance.v2;
import android.app.*;import android.content.*;import android.os.*;
public class ConfigProviderTest {
 static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
 static void denied(ConfigProvider p,String method,String arg,Bundle extras){
  try{p.call(method,arg,extras);throw new AssertionError("accepted "+method);}catch(SecurityException expected){}
 }
 public static void main(String[] args){
  check(ConfigProvider.AUTHORITY.equals(BuildConfig.APPLICATION_ID+".config")
    &&ConfigProvider.URI.toString().equals("content://io.github.wondersready.focuspen.config/snapshot")
    &&ConfigProvider.ACTION_COMMIT.equals(BuildConfig.APPLICATION_ID+".COMMIT"),"provider identity mismatch");
  ConfigProvider p=new ConfigProvider();Context context=new Context();p.context=context;
  BootInputConfig.unlocked=false;
  p.call(ConfigProvider.METHOD_GET_CONFIG,null,null);
  p.call("runtimeStatus",null,null);p.call(RuntimeBus.REGISTER,null,null);
  check(BootInputConfig.bootReads==1&&ConfigStore.reads==0
    &&RuntimeBus.statusReads==1&&RuntimeBus.registrations==1,"boot-safe methods routed incorrectly");
  Bundle edit=new Bundle();edit.putString("value","note");
  denied(p,"uiModel",null,null);denied(p,"uiModel","pro",null);
  denied(p,ConfigProvider.METHOD_PREPARE_EDIT,"button1Short",edit);
  denied(p,"previewRing",null,null);denied(p,"other",null,null);
  check(context.prefReads==0&&context.prefEdits==0&&ConfigStore.reads==0
    &&PendingIntent.calls==0&&RuntimeBus.previews==0,"locked call touched CE or UI state");
  Binder.uid=200;p.caller="com.android.systemui";
  p.call(ConfigProvider.METHOD_GET_CONFIG,null,null);
  check(BootInputConfig.bootReads==2,"trusted system host could not read boot snapshot");
  denied(p,"runtimeStatus",null,null);
  Binder.uid=201;p.caller="com.example.untrusted";
  denied(p,ConfigProvider.METHOD_GET_CONFIG,null,null);
  check(BootInputConfig.bootReads==2,"untrusted host read boot snapshot");
  Binder.uid=123;p.caller=BuildConfig.APPLICATION_ID;BootInputConfig.unlocked=true;
  p.call(ConfigProvider.METHOD_GET_CONFIG,null,null);
  check(ConfigStore.reads==1,"unlocked call did not read CE config");
  p.call("uiModel","pro",null);
  p.call(ConfigProvider.METHOD_PREPARE_EDIT,"button1Short",edit);
  check(context.prefReads==1&&context.prefEdits==1&&PendingIntent.calls==1
    &&ConfigProvider.ACTION_COMMIT.equals(PendingIntent.lastAction),
    "unlocked UI/edit paths did not run");
  System.out.println("ConfigProvider direct-boot CE isolation and host authorization: PASS");
 }
}""",
}


def main() -> None:
    gradle = (ROOT / "module/app/build.gradle.kts").read_text(encoding="utf-8")
    app_id = re.search(r'applicationId\s*=\s*"([^"]+)"', gradle).group(1)
    namespace = re.search(r'namespace\s*=\s*"([^"]+)"', gradle).group(1)
    assert app_id == "io.github.wondersready.focuspen"
    assert namespace == "com.focuspen.enhance.v2"
    manifest = ET.parse(ROOT / "module/app/src/main/AndroidManifest.xml").getroot()
    android = "{http://schemas.android.com/apk/res/android}"
    application = manifest.find("application")
    assert application.find("provider").get(android + "authorities") == "${applicationId}.config"
    for tag in ("activity", "service", "receiver", "provider"):
        for component in application.findall(tag):
            assert component.get(android + "name").startswith(namespace + ".")
    job = next(component for component in application.findall("service")
               if component.get(android + "name") == namespace + ".UpdateCheckJob")
    assert job.get(android + "exported") == "false"
    assert job.get(android + "permission") == "android.permission.BIND_JOB_SERVICE"
    jdk = Path(os.environ.get("JAVA_HOME", "/usr/lib/jvm/java-21-openjdk-amd64")) / "bin"
    with tempfile.TemporaryDirectory(prefix="focuspen-provider-") as folder:
        out = Path(folder)
        files = []
        for name, source in SOURCES.items():
            path = out / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(source, encoding="utf-8")
            files.append(str(path))
        files.append(str(ROOT / "module/app/src/main/java/com/focuspen/enhance/v2/ConfigProvider.java"))
        subprocess.run([str(jdk / "javac"), "-d", str(out), *files], check=True)
        subprocess.run([str(jdk / "java"), "-cp", str(out), "com.focuspen.enhance.v2.ConfigProviderTest"], check=True)


if __name__ == "__main__":
    main()
