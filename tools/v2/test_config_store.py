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
'android/os/UserManager.java':'package android.os; public class UserManager {public boolean unlocked=true;public boolean isUserUnlocked(){return unlocked;}}',
'android/util/Log.java':'package android.util; public class Log {public static int i(String t,String v){return 0;}public static int w(String t,String v){return 0;}}',
'android/content/SharedPreferences.java':'''package android.content; public interface SharedPreferences {
 int getInt(String k,int d); long getLong(String k,long d); String getString(String k,String d); boolean getBoolean(String k,boolean d); boolean contains(String k); java.util.Map<String,?> getAll(); Editor edit();
 interface Editor {Editor putString(String k,String v); Editor putInt(String k,int v); Editor putLong(String k,long v); Editor putBoolean(String k,boolean v); Editor remove(String k); boolean commit();}
}''',
'android/content/ContentResolver.java':'package android.content; public class ContentResolver {public void notifyChange(Object uri,Object observer){}}',
'android/content/Context.java':'''package android.content; public class Context {
 public static final int MODE_PRIVATE=0; public final Memory prefs,bootPrefs;
 public final android.os.UserManager users;public String packageName="io.github.wondersready.focuspen";
 private final boolean device;
 public Context(){this(new Memory(),new Memory(),new android.os.UserManager(),false);}
 private Context(Memory ce,Memory de,android.os.UserManager user,boolean device){prefs=ce;bootPrefs=de;users=user;this.device=device;}
 public String getPackageName(){return packageName;}
 public SharedPreferences getSharedPreferences(String n,int m){return device?bootPrefs:prefs;}
 public Context createDeviceProtectedStorageContext(){Context c=new Context(prefs,bootPrefs,users,true);c.packageName=packageName;return c;}
 public <T>T getSystemService(Class<T> type){return type.cast(users);}
 public ContentResolver getContentResolver(){return new ContentResolver();}
 public static class Memory implements SharedPreferences {
  public final java.util.Map<String,Object> values=new java.util.HashMap<>(); public int commits;
  public int getInt(String k,int d){return (Integer)values.getOrDefault(k,d);} public long getLong(String k,long d){return (Long)values.getOrDefault(k,d);}
  public String getString(String k,String d){return (String)values.getOrDefault(k,d);} public boolean getBoolean(String k,boolean d){return (Boolean)values.getOrDefault(k,d);} public boolean contains(String k){return values.containsKey(k);}
  public java.util.Map<String,?> getAll(){return new java.util.HashMap<>(values);}
  public Editor edit(){return new Editor(){java.util.Map<String,Object> pending=new java.util.HashMap<>();java.util.Set<String> removed=new java.util.HashSet<>();
   public Editor putString(String k,String v){pending.put(k,v);return this;} public Editor putInt(String k,int v){pending.put(k,v);return this;}
   public Editor putLong(String k,long v){pending.put(k,v);return this;} public Editor putBoolean(String k,boolean v){pending.put(k,v);return this;}
   public Editor remove(String k){removed.add(k);return this;}
   public boolean commit(){for(String k:removed)values.remove(k);values.putAll(pending);commits++;return true;}};}
 }
}''',
'com/focuspen/enhance/v2/BuildConfig.java':'package com.focuspen.enhance.v2; class BuildConfig {static final String APPLICATION_ID="io.github.wondersready.focuspen",VERSION_NAME="test";}',
'com/focuspen/enhance/v2/ConfigProvider.java':'package com.focuspen.enhance.v2; class ConfigProvider {static final String PREFS="test";static final Object URI=new Object();}',
'com/focuspen/enhance/v2/ConfigStoreTest.java':'''package com.focuspen.enhance.v2;
import android.content.Context; import java.util.*;
public class ConfigStoreTest {
 static void check(boolean b,String m){if(!b)throw new AssertionError(m);}
 static Context baseline(int schema,String content){Context c=new Context();c.prefs.values.putAll(ConfigSpec.DEFAULTS);c.prefs.values.put("schemaVersion",schema);c.prefs.values.put("revision",93L);c.prefs.values.put(ConfigSpec.CONTENT,content);return c;}
 public static void main(String[] args){
  Context audioLegacy=new Context();
  ConfigStore.read(audioLegacy);
  check(ConfigStore.write(audioLegacy,ConfigSpec.AUDIO,true),"Legacy audio preference remains importable");
  ConfigSnapshot audioSnapshot=ConfigSnapshot.parse(ConfigStore.read(audioLegacy));
  check(audioSnapshot.flag(ConfigSpec.AUDIO)&&!audioSnapshot.audioEnabled(),"Stored audio opt-in must not enable suspended feature");
  Map<String,Object> audioBackup=new LinkedHashMap<>(ConfigSpec.DEFAULTS);audioBackup.put(ConfigSpec.AUDIO,true);
  check(ConfigStore.restore(audioLegacy,audioBackup),"Legacy audio backup restores");
  check(!ConfigSnapshot.parse(ConfigStore.read(audioLegacy)).audioEnabled(),"Backup must not reactivate suspended audio");
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
  for(int[] item:new int[][]{{150,150},{199,150},{200,250},{250,250},{300,250},{301,350},{800,350}}){
   Context old=baseline(14,RingContent.DEFAULT);old.prefs.values.remove(ConfigSpec.TIMING);
   old.prefs.values.put(ConfigSpec.LONG_PRESS,item[0]);old.prefs.values.put(ConfigSpec.DOUBLE_PRESS,280);
   old.prefs.values.put("button1Short","note");
   ConfigSnapshot migrated=ConfigSnapshot.parse(ConfigStore.read(old));
   check(migrated.longPressMs()==item[1]&&migrated.doublePressMs()==item[1],"Legacy timing migration lost common threshold");
   check(migrated.shortAction("",1)==PenAction.NOTE&&old.prefs.getLong("revision",0)==93,
     "Legacy timing migration changed unrelated settings or revision");
   check(!old.prefs.values.containsKey(ConfigSpec.LONG_PRESS)&&!old.prefs.values.containsKey(ConfigSpec.DOUBLE_PRESS),
     "Legacy timing fields remain in current storage");
   int commits=old.prefs.commits;ConfigStore.read(old);
   check(old.prefs.commits==commits,"Timing migration repeated");
  }
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
  Map<String,Object> incoming=new LinkedHashMap<>(ConfigSpec.DEFAULTS);incoming.put(ConfigSpec.TIMING,350);incoming.put("button1Short","note");
  check(ConfigStore.restore(restore,incoming),"restore failed");
  check(restore.prefs.getLong("revision",0)==94&&restore.prefs.commits==1,"restore must commit exactly one revision");
  check(ConfigSnapshot.parse(ConfigStore.read(restore)).shortAction("",1)==PenAction.NOTE,"restored values not visible");
  Map<String,Object> before=new HashMap<>(restore.prefs.values);
  incoming.put(ConfigSpec.TIMING,9999);
  try{ConfigStore.restore(restore,incoming);throw new AssertionError("invalid imported threshold accepted");}catch(IllegalArgumentException expected){}
  check(before.equals(restore.prefs.values)&&restore.prefs.commits==1,"invalid import partially modified settings");
  incoming.remove(ConfigSpec.TIMING);
  try{ConfigStore.restore(restore,incoming);throw new AssertionError("incomplete backup accepted");}catch(IllegalArgumentException expected){}
  check(before.equals(restore.prefs.values),"incomplete import mutated settings");
  System.out.println("Atomic full-config restore, one revision, invalid/incomplete preservation PASS");
  bootInput();
 }
 static void bootInput(){
  Context legacyMirror=baseline(ConfigSpec.SCHEMA,RingContent.DEFAULT);
  for(String key:List.of(ConfigSpec.KEYS,ConfigSpec.SCREEN_OFF,ConfigSpec.SCREEN_OFF_TARGET,
    ConfigSpec.RING,ConfigSpec.LASER_ACTIVATION))
   legacyMirror.bootPrefs.values.put(key,legacyMirror.prefs.values.get(key));
  legacyMirror.bootPrefs.values.put(ConfigSpec.LONG_PRESS,200);
  legacyMirror.bootPrefs.values.put(ConfigSpec.DOUBLE_PRESS,280);
  legacyMirror.bootPrefs.values.put("sourceRevision",93L);
  ConfigSnapshot oldBoot=ConfigSnapshot.parse(BootInputConfig.read(legacyMirror));
  check(oldBoot.inputActionsReady()&&oldBoot.longPressMs()==250&&oldBoot.doublePressMs()==250,
    "old direct-boot mirror did not preserve standard timing before unlock");
  legacyMirror.bootPrefs.values.put(ConfigSpec.DOUBLE_PRESS,999);
  check(!ConfigSnapshot.parse(BootInputConfig.read(legacyMirror)).inputActionsReady(),
    "invalid old direct-boot mirror enabled input actions");
  legacyMirror.bootPrefs.values.put(ConfigSpec.DOUBLE_PRESS,280);
  ConfigStore.read(legacyMirror);
  check(legacyMirror.bootPrefs.values.containsKey(ConfigSpec.TIMING)
    &&!legacyMirror.bootPrefs.values.containsKey(ConfigSpec.LONG_PRESS)
    &&!legacyMirror.bootPrefs.values.containsKey(ConfigSpec.DOUBLE_PRESS),
    "old direct-boot mirror was not replaced after unlock");
  Context c=baseline(ConfigSpec.SCHEMA,RingContent.DEFAULT);
  c.users.unlocked=false;
  check(!BootInputConfig.unlocked(c),"locked user reported unlocked");
  ConfigSnapshot first=ConfigSnapshot.parse(BootInputConfig.read(c));
  check(first.enabled()&&first.flag(ConfigSpec.KEYS)&&!first.flag(ConfigSpec.SCREEN_OFF)
    &&!first.flag(ConfigSpec.PREVIEW),"first direct boot did not retain safe key ownership");
  check(BootInputConfig.read(c).getBoolean("bootOnly",false)
    &&!BootInputConfig.read(c).getBoolean("mirrorPresent",true),"fresh boot flags incorrect");
  check(c.bootPrefs.values.isEmpty(),"boot read wrote credential configuration");
  c.users.unlocked=true;
  check(BootInputConfig.unlocked(c),"unlocked user reported locked");
  android.os.Bundle regular=ConfigStore.read(c);
  ConfigSnapshot.parse(regular);
  check(!regular.getBoolean("bootOnly",true)&&regular.getBoolean("mirrorPresent",false),
    "unlocked configuration flags incorrect");
  check(c.bootPrefs.commits==1&&c.prefs.commits==0,"read did not create separate boot mirror");
  check(!c.bootPrefs.values.containsKey(ConfigSpec.PROFILES)
    &&!c.bootPrefs.values.containsKey(ConfigSpec.CONTENT),"private application lists reached boot storage");
  check(c.bootPrefs.values.keySet().equals(Set.of("sourceRevision",ConfigSpec.KEYS,
    ConfigSpec.SCREEN_OFF,ConfigSpec.SCREEN_OFF_TARGET,ConfigSpec.RING,
    ConfigSpec.TIMING,ConfigSpec.LASER_ACTIVATION)),
    "boot mirror contains fields beyond the lock-screen inputs");
  int writes=c.bootPrefs.commits;
  ConfigStore.read(c);ConfigStore.write(c,ConfigSpec.KEYS,true);
  check(c.bootPrefs.commits==writes,"unchanged values repeated boot write");
  ConfigStore.write(c,ConfigSpec.PROFILES,AppProfiles.parse(AppProfiles.EMPTY).set("com.example.secret",0,"note").encode());
  check(c.bootPrefs.commits==writes,"unrelated profile change wrote boot mirror");
  check(!c.bootPrefs.values.toString().contains("com.example.secret"),"profile name leaked into boot mirror");
  ConfigStore.write(c,"button1Short","note");
  ConfigStore.write(c,"proFourPinch","app:com.example.secret");
  check(c.bootPrefs.commits==writes,"unlocked-only bindings wrote boot mirror");
  check(!c.bootPrefs.values.toString().contains("com.example.secret"),"application binding leaked into boot mirror");
  ConfigStore.write(c,ConfigSpec.SCREEN_OFF,true);
  ConfigStore.write(c,ConfigSpec.SCREEN_OFF_TARGET,"creation");
  ConfigStore.write(c,ConfigSpec.TIMING,350);
  ConfigStore.write(c,ConfigSpec.LASER_ACTIVATION,"toggle");
  check(c.bootPrefs.commits==writes+4,"boot input edits did not each refresh mirror");
  c.users.unlocked=false;
  ConfigSnapshot locked=ConfigSnapshot.parse(BootInputConfig.read(c));
  check(BootInputConfig.read(c).getBoolean("bootOnly",false)
    &&BootInputConfig.read(c).getBoolean("mirrorPresent",false),"mirrored boot flags incorrect");
  check(locked.flag(ConfigSpec.KEYS)&&locked.flag(ConfigSpec.SCREEN_OFF)
    &&locked.text(ConfigSpec.SCREEN_OFF_TARGET).equals("creation")
    &&locked.longPressMs()==350&&locked.doublePressMs()==350&&locked.shortAction("",1)==PenAction.NONE
    &&locked.text(ConfigSpec.LASER_ACTIVATION).equals("toggle"),
    "locked snapshot lost mirrored input controls");
  check(locked.revision!=c.prefs.getLong("revision",0),"lock transition reused unlocked revision");
  c.users.unlocked=true;
  check(!c.bootPrefs.values.containsKey("proFourPinch")
    &&!c.bootPrefs.values.containsKey("button1Short"),"non-boot bindings reached device storage");
  Object savedTarget=c.bootPrefs.values.remove(ConfigSpec.SCREEN_OFF_TARGET);
  android.os.Bundle incomplete=BootInputConfig.read(c);
  check(!incomplete.getBoolean("mirrorPresent",true)
    &&!ConfigSnapshot.parse(incomplete).flag(ConfigSpec.SCREEN_OFF),
    "incomplete mirror enabled screen-off action");
  c.bootPrefs.values.put(ConfigSpec.SCREEN_OFF_TARGET,savedTarget);
  Map<String,Object> replacement=new LinkedHashMap<>(ConfigSpec.DEFAULTS);
  replacement.put(ConfigSpec.KEYS,false);replacement.put(ConfigSpec.SCREEN_OFF,true);
  replacement.put(ConfigSpec.SCREEN_OFF_TARGET,"note");
  writes=c.bootPrefs.commits;
  check(ConfigStore.restore(c,replacement)&&c.bootPrefs.commits==writes+1,"restore did not mirror once");
  locked=ConfigSnapshot.parse(BootInputConfig.read(c));
  check(!locked.flag(ConfigSpec.KEYS)&&locked.flag(ConfigSpec.SCREEN_OFF)
    &&locked.text(ConfigSpec.SCREEN_OFF_TARGET).equals("note"),"restore did not replace boot controls");
  c.packageName="android";
  try{BootInputConfig.read(c);throw new AssertionError("host opened boot preferences");}
  catch(SecurityException expected){}
  System.out.println("Direct-boot input mirror, bounded fields, no-op writes and restore PASS");
 }
}'''
}
files=[]
for name,source in sources.items():
 path=out/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_text(source,encoding='utf-8');files.append(str(path))
for name in ['ProBindings','BootInputConfig','ConfigStore','ConfigSnapshot','ConfigSpec','AppProfiles','RingContent','PenAction','DirectionAdapter']:
 files.append(str(root/f'module/app/src/main/java/com/focuspen/enhance/v2/{name}.java'))
jdk=Path(os.environ.get('JAVA_HOME','/usr/lib/jvm/java-21-openjdk-amd64'))/'bin'
subprocess.run([str(jdk/'javac'),'-d',str(out),*files],check=True)
subprocess.run([str(jdk/'java'),'-cp',str(out),'com.focuspen.enhance.v2.ConfigStoreTest'],check=True)
