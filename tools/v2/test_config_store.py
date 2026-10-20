#!/usr/bin/env python3
"""Production configuration migration with in-memory Android storage; no device proof."""
import os, subprocess
from pathlib import Path
root=Path(__file__).resolve().parents[2]
out=root/'work/v2/config-store-test';out.mkdir(parents=True,exist_ok=True)
sources={
'android/os/Bundle.java':'''package android.os; public class Bundle {
 private final java.util.Map<String,Object> m=new java.util.HashMap<>();
 public Bundle(){} public Bundle(Bundle b){m.putAll(b.m);} public Object get(String k){return m.get(k);}
 public void putString(String k,String v){m.put(k,v);} public String getString(String k,String d){return (String)m.getOrDefault(k,d);}
 public void putInt(String k,int v){m.put(k,v);} public int getInt(String k,int d){return (Integer)m.getOrDefault(k,d);}
 public void putLong(String k,long v){m.put(k,v);} public long getLong(String k){return getLong(k,0);} public long getLong(String k,long d){return (Long)m.getOrDefault(k,d);}
 public void putBoolean(String k,boolean v){m.put(k,v);} public boolean getBoolean(String k,boolean d){return (Boolean)m.getOrDefault(k,d);}
}''',
'android/os/Process.java':'package android.os; public class Process {public static int myUid(){return 123;}}',
'android/util/Log.java':'package android.util; public class Log {public static int i(String t,String v){return 0;}}',
'android/content/SharedPreferences.java':'''package android.content; public interface SharedPreferences {
 int getInt(String k,int d); long getLong(String k,long d); String getString(String k,String d); boolean contains(String k); java.util.Map<String,?> getAll(); Editor edit();
 interface Editor {Editor putString(String k,String v); Editor putInt(String k,int v); Editor putLong(String k,long v); Editor putBoolean(String k,boolean v); boolean commit();}
}''',
'android/content/ContentResolver.java':'package android.content; public class ContentResolver {public void notifyChange(Object uri,Object observer){}}',
'android/content/Context.java':'''package android.content; public class Context {
 public static final int MODE_PRIVATE=0; public final Memory prefs=new Memory();
 public String getPackageName(){return "com.focuspen.enhance.v2";} public SharedPreferences getSharedPreferences(String n,int m){return prefs;}
 public ContentResolver getContentResolver(){return new ContentResolver();}
 public static class Memory implements SharedPreferences {
  public final java.util.Map<String,Object> values=new java.util.HashMap<>(); public int commits;
  public int getInt(String k,int d){return (Integer)values.getOrDefault(k,d);} public long getLong(String k,long d){return (Long)values.getOrDefault(k,d);}
  public String getString(String k,String d){return (String)values.getOrDefault(k,d);} public boolean contains(String k){return values.containsKey(k);}
  public java.util.Map<String,?> getAll(){return new java.util.HashMap<>(values);}
  public Editor edit(){return new Editor(){java.util.Map<String,Object> pending=new java.util.HashMap<>();
   public Editor putString(String k,String v){pending.put(k,v);return this;} public Editor putInt(String k,int v){pending.put(k,v);return this;}
   public Editor putLong(String k,long v){pending.put(k,v);return this;} public Editor putBoolean(String k,boolean v){pending.put(k,v);return this;}
   public boolean commit(){values.putAll(pending);commits++;return true;}};}
 }
}''',
'com/focuspen/enhance/v2/BuildConfig.java':'package com.focuspen.enhance.v2; class BuildConfig {static final String APPLICATION_ID="com.focuspen.enhance.v2",VERSION_NAME="test";}',
'com/focuspen/enhance/v2/ConfigProvider.java':'package com.focuspen.enhance.v2; class ConfigProvider {static final String PREFS="test";static final Object URI=new Object();}',
'com/focuspen/enhance/v2/ConfigStoreTest.java':'''package com.focuspen.enhance.v2;
import android.content.Context; import java.util.*;
public class ConfigStoreTest {
 static void check(boolean b,String m){if(!b)throw new AssertionError(m);}
 static Context baseline(int schema,String content){Context c=new Context();c.prefs.values.putAll(ConfigSpec.DEFAULTS);c.prefs.values.put("schemaVersion",schema);c.prefs.values.put("revision",93L);c.prefs.values.put(ConfigSpec.CONTENT,content);return c;}
 public static void main(String[] args){
  for(String content:List.of(RingContent.DEFAULT,"v1\\n+com.miui.notes\\n+com.miui.creation","v1\\n+com.miui.notes\\n+com.miui.creation\\n+"+RingContent.NOTES+"\\n+"+RingContent.CREATION+"\\n+"+RingContent.SCREENSHOT,"v1\\n-"+RingContent.NOTES+"\\n!com.example.removed")){
   Context c=baseline(10,content);c.prefs.values.put(ConfigSpec.PROFILES,AppProfiles.parse(AppProfiles.EMPTY).set("com.example.app",4,"swipe").encode());
   Map<String,Object> expected=new HashMap<>(c.prefs.values);expected.put("schemaVersion",ConfigSpec.SCHEMA);
   ConfigSnapshot.parse(ConfigStore.read(c));check(c.prefs.values.equals(expected),"Schema 10 migration modified existing user data");
   int commits=c.prefs.commits;ConfigStore.read(c);check(c.prefs.commits==commits,"Migration repeated");
   String reversed=AppProfiles.parse((String)c.prefs.values.get(ConfigSpec.PROFILES)).set("com.example.app",4,"swipe_reverse").encode();
   ConfigStore.write(c,ConfigSpec.PROFILES,reversed);check(c.prefs.getLong("revision",0)==94,"Edit did not increment revision");
   ConfigSnapshot snapshot=ConfigSnapshot.parse(ConfigStore.read(c));check(snapshot.direction("com.example.app",1).equals("swipe_reverse"),"Reversed direction lost across storage/snapshot");
  }
  Context legacy=baseline(9,"v1\\n+com.miui.notes\\n+com.miui.creation");ConfigStore.read(legacy);
  check(legacy.prefs.getString(ConfigSpec.CONTENT,"").equals(RingContent.DEFAULT),"Legacy seeded migration removed");
  Context prior=baseline(11,RingContent.DEFAULT);prior.prefs.values.put("button1Short","up");prior.prefs.values.put("button2Short","down");
  prior.prefs.values.put(ConfigSpec.PROFILES,"v2\\nA|com.example.app|1|custom||note|none|none|none|swipe|page|true");
  for(String key:ConfigSpec.PRO_GESTURES)prior.prefs.values.remove(key);
  ConfigSnapshot upgraded=ConfigSnapshot.parse(ConfigStore.read(prior));
  check(upgraded.shortAction("com.example.app",1)==PenAction.NONE&&upgraded.shortAction("com.example.app",2)==PenAction.NONE,"Old fixed short actions were not reset");
  check(upgraded.action("com.example.app",1,false)==PenAction.NOTE&&upgraded.preview("com.example.app"),"Migration lost old profile overrides");
  check(upgraded.proAction("com.example.app",0)==PenAction.RING,"Pro defaults missing");
  ConfigStore.write(prior,"button1Short","note");
  check(ConfigSnapshot.parse(ConfigStore.read(prior)).shortAction("other.app",1)==PenAction.NOTE,"Migration repeated over new custom short");
  AppProfiles expanded=AppProfiles.parse(upgraded.text(ConfigSpec.PROFILES)).saveTemplate("new","New",AppProfiles.defaults())
    .setTemplate("new",7,"screenshot").setTemplate("new",9,"creation").select("com.example.app","template","new");
  ConfigStore.write(prior,ConfigSpec.PROFILES,expanded.encode());
  ConfigSnapshot configured=ConfigSnapshot.parse(ConfigStore.read(prior));
  check(configured.shortAction("com.example.app",1)==PenAction.SCREENSHOT&&configured.proAction("com.example.app",0)==PenAction.CREATION,"Short/Pro template overrides not resolved");
  check(configured.shortAction("other.app",1)==PenAction.NOTE,"Global short lost");
  ConfigStore.write(prior,ConfigSpec.RULES,false);configured=ConfigSnapshot.parse(ConfigStore.read(prior));
  check(configured.shortAction("com.example.app",1)==PenAction.NOTE&&configured.proAction("com.example.app",0)==PenAction.RING,"Disabled profiles still override");
  Context future=baseline(ConfigSpec.SCHEMA+1,RingContent.DEFAULT);
  try{ConfigStore.read(future);throw new AssertionError("Future schema accepted");}catch(IllegalStateException expected){}
  check(future.prefs.commits==0,"Rejected schema was written");
  for(String slot:List.of("button1Short","button2Short","button1Long","button1Double","button2Long","button2Double","proPinch","proDouble","proSlideUp","proSlideDown")) for(String action:List.of("note","creation")){
   Context c=baseline(ConfigSpec.SCHEMA,RingContent.DEFAULT);
   ConfigStore.write(c,ConfigSpec.PROFILES,AppProfiles.parse(AppProfiles.EMPTY).set("com.example.app",0,action).encode());
   ConfigStore.write(c,ConfigSpec.SCREEN_OFF,true);
   check(ConfigSnapshot.parse(ConfigStore.read(c)).flag(ConfigSpec.SCREEN_OFF),"Independent screen-off switch rejected");
   ConfigStore.write(c,ConfigSpec.SCREEN_OFF_TARGET,"creation");
   ConfigStore.write(c,ConfigSpec.SCREEN_OFF,false);
   ConfigStore.write(c,slot,action);
   check(!ConfigSnapshot.parse(ConfigStore.read(c)).flag(ConfigSpec.SCREEN_OFF),"Binding automatically enabled screen-off note");
   ConfigStore.write(c,ConfigSpec.SCREEN_OFF,true);
   check(ConfigSnapshot.parse(ConfigStore.read(c)).flag(ConfigSpec.SCREEN_OFF),"Global binding could not enable screen-off note");
   String second=slot.equals("button1Long")?"button2Double":"button1Long";
   ConfigStore.write(c,second,action);
   ConfigStore.write(c,slot,"none");
   check(ConfigSnapshot.parse(ConfigStore.read(c)).flag(ConfigSpec.SCREEN_OFF),"Removing non-last binding disabled screen-off note");
   long rev=c.prefs.getLong("revision",0);
   ConfigStore.write(c,second,"none");
   check(ConfigSnapshot.parse(ConfigStore.read(c)).flag(ConfigSpec.SCREEN_OFF),"Last binding removal changed independent switch");
   check(ConfigSnapshot.parse(ConfigStore.read(c)).text(ConfigSpec.SCREEN_OFF_TARGET).equals("creation"),"Binding edit changed independent target");
   check(c.prefs.getLong("revision",0)==rev+1,"Dependent screen-off edit was not atomic with binding removal");
   ConfigStore.write(c,ConfigSpec.SCREEN_OFF,false);ConfigStore.write(c,slot,action);
   check(!ConfigSnapshot.parse(ConfigStore.read(c)).flag(ConfigSpec.SCREEN_OFF),"Rebinding reenabled stale preference");
  }
  System.out.println("Production ConfigStore: schema 10 preservation, legacy migration, reverse persistence and future-schema rejection PASS");
  Context v12=baseline(12,RingContent.DEFAULT);v12.prefs.values.remove(ConfigSpec.SCREEN_OFF_TARGET);v12.prefs.values.remove(ConfigSpec.LASER_ACTIVATION);
  v12.prefs.values.put(ConfigSpec.SCREEN_OFF,true);v12.prefs.values.put("button1Short","screenshot");
  ConfigSnapshot v13=ConfigSnapshot.parse(ConfigStore.read(v12));
  check(v13.flag(ConfigSpec.SCREEN_OFF)&&v13.shortAction("",1)==PenAction.SCREENSHOT,"Schema 13 must preserve switch and custom short");
  check(v13.text(ConfigSpec.SCREEN_OFF_TARGET).equals("note"),"Missing target receives explicit default");
  System.out.println("Schema 11/12 upgrades, short/Pro templates and independent screen-off targets PASS");
  Context restore=baseline(ConfigSpec.SCHEMA,RingContent.DEFAULT);
  Map<String,Object> incoming=new LinkedHashMap<>(ConfigSpec.DEFAULTS);incoming.put(ConfigSpec.LONG_PRESS,420);incoming.put("button1Short","note");
  check(ConfigStore.restore(restore,incoming),"restore failed");
  check(restore.prefs.getLong("revision",0)==94&&restore.prefs.commits==1,"restore must commit exactly one revision");
  check(ConfigSnapshot.parse(ConfigStore.read(restore)).shortAction("",1)==PenAction.NOTE,"restored values not visible");
  Map<String,Object> before=new HashMap<>(restore.prefs.values);
  incoming.put(ConfigSpec.LONG_PRESS,9999);
  try{ConfigStore.restore(restore,incoming);throw new AssertionError("invalid imported threshold accepted");}catch(IllegalArgumentException expected){}
  check(before.equals(restore.prefs.values)&&restore.prefs.commits==1,"invalid import partially modified settings");
  incoming.remove(ConfigSpec.LONG_PRESS);
  try{ConfigStore.restore(restore,incoming);throw new AssertionError("incomplete backup accepted");}catch(IllegalArgumentException expected){}
  check(before.equals(restore.prefs.values),"incomplete import mutated settings");
  System.out.println("Atomic full-config restore, one revision, invalid/incomplete preservation PASS");
 }
}'''
}
files=[]
for name,source in sources.items():
 path=out/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_text(source,encoding='utf-8');files.append(str(path))
for name in ['ProBindings','ConfigStore','ConfigSnapshot','ConfigSpec','AppProfiles','RingContent','PenAction','DirectionAdapter']:
 files.append(str(root/f'module/app/src/main/java/com/focuspen/enhance/v2/{name}.java'))
jdk=Path(os.environ.get('JAVA_HOME','/usr/lib/jvm/java-21-openjdk-amd64'))/'bin'
subprocess.run([str(jdk/'javac'),'-d',str(out),*files],check=True)
subprocess.run([str(jdk/'java'),'-cp',str(out),'com.focuspen.enhance.v2.ConfigStoreTest'],check=True)
