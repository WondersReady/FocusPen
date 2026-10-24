#!/usr/bin/env python3
"""Decode real JSON backup text through the production ConfigBackup."""
import os
import subprocess
from pathlib import Path

root = Path(__file__).resolve().parents[2]
out = root / "work/v2/config-backup-test"
gson = Path("/home/wonders/tools/gradle-8.9/lib/gson-2.10.jar")
sources = {
    "android/os/Bundle.java": """package android.os;
public class Bundle {
 private final java.util.Map<String,Object> values=new java.util.HashMap<>();
 public Object get(String key){return values.get(key);}
 public void put(String key,Object value){values.put(key,value);}
}""",
    "com/focuspen/enhance/v2/BuildConfig.java": """package com.focuspen.enhance.v2;
final class BuildConfig {static final String VERSION_NAME="test";}
""",
    "org/json/JSONObject.java": """package org.json;
import com.google.gson.*;
public class JSONObject {
 private final JsonObject values;
 public JSONObject(){values=new JsonObject();}
 public JSONObject(String source){values=JsonParser.parseString(source).getAsJsonObject();}
 private JSONObject(JsonObject values){this.values=values;}
 public JSONObject put(String key,Object value){
  if(value instanceof JSONObject)values.add(key,((JSONObject)value).values);
  else if(value instanceof Boolean)values.addProperty(key,(Boolean)value);
  else if(value instanceof Number)values.addProperty(key,(Number)value);
  else values.addProperty(key,(String)value);
  return this;
 }
 public Object opt(String key){
  JsonElement value=values.get(key);
  if(value==null)return null;
  if(value.isJsonNull())return JsonNull.INSTANCE;
  if(value.isJsonObject())return new JSONObject(value.getAsJsonObject());
  if(value.isJsonArray())return value.getAsJsonArray();
  JsonPrimitive primitive=value.getAsJsonPrimitive();
  if(primitive.isBoolean())return primitive.getAsBoolean();
  if(primitive.isString())return primitive.getAsString();
  String number=primitive.getAsString();
  if(number.matches("-?(0|[1-9][0-9]*)")){
   try{return Integer.valueOf(number);}catch(NumberFormatException ignored){
    try{return Long.valueOf(number);}catch(NumberFormatException ignoredAgain){}
   }
  }
  return Double.valueOf(number);
 }
 public String optString(String key){Object value=opt(key);return value==null?"":String.valueOf(value);}
 public Object get(String key){if(!values.has(key))throw new IllegalArgumentException("Missing "+key);return opt(key);}
 public JSONObject getJSONObject(String key){Object value=get(key);if(!(value instanceof JSONObject))throw new IllegalArgumentException("Not object "+key);return (JSONObject)value;}
 public boolean has(String key){return values.has(key);}
 public JSONObject remove(String key){values.remove(key);return this;}
 public int length(){return values.size();}
 public String toString(int indent){return values.toString();}
 @Override public String toString(){return values.toString();}
}""",
    "com/focuspen/enhance/v2/ConfigBackupTest.java": """package com.focuspen.enhance.v2;
import android.os.Bundle;import java.util.*;import org.json.JSONObject;
public class ConfigBackupTest {
 static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
 static void reject(String source){try{ConfigBackup.decode(source);throw new AssertionError("Accepted invalid backup");}
  catch(AssertionError e){throw e;}catch(Exception expected){}}
 static JSONObject settings(boolean old){
  JSONObject result=new JSONObject();
  for(var entry:ConfigSpec.DEFAULTS.entrySet())
   if(!old||!entry.getKey().equals(ConfigSpec.TIMING))result.put(entry.getKey(),entry.getValue());
  if(old)result.put(ConfigSpec.LONG_PRESS,200).put(ConfigSpec.DOUBLE_PRESS,280);
  return result;
 }
 static String backup(int schema,JSONObject settings){
  return new JSONObject().put("format","focuspen-settings").put("formatVersion",1)
   .put("schemaVersion",schema).put("settings",settings).toString();
 }
 public static void main(String[] args) throws Exception {
  JSONObject current=settings(false);
  Map<String,Object> currentValues=ConfigBackup.decode(backup(15,current));
  check(currentValues.equals(ConfigSpec.DEFAULTS),"schema 15 roundtrip changed settings");
  JSONObject legacy=settings(true);legacy.put("button1Short","note");
  Map<String,Object> migrated=ConfigBackup.decode(backup(14,legacy));
  check(migrated.keySet().equals(ConfigSpec.DEFAULTS.keySet())
    &&migrated.get(ConfigSpec.TIMING).equals(250)
    &&migrated.get("button1Short").equals("note")
    &&!migrated.containsKey(ConfigSpec.LONG_PRESS)
    &&!migrated.containsKey(ConfigSpec.DOUBLE_PRESS),"schema 14 data lost during migration");
  for(int[] sample:new int[][]{{150,150},{199,150},{200,250},{300,250},{301,350},{800,350}}){
   legacy.put(ConfigSpec.LONG_PRESS,sample[0]);
   check(ConfigBackup.decode(backup(14,legacy)).get(ConfigSpec.TIMING).equals(sample[1]),
     "schema 14 timing boundary");
  }
  legacy.put(ConfigSpec.LONG_PRESS,149);reject(backup(14,legacy));
  legacy.put(ConfigSpec.LONG_PRESS,801);reject(backup(14,legacy));
  legacy.put(ConfigSpec.LONG_PRESS,200);
  legacy.put(ConfigSpec.DOUBLE_PRESS,179);reject(backup(14,legacy));
  legacy.put(ConfigSpec.DOUBLE_PRESS,451);reject(backup(14,legacy));
  legacy.put(ConfigSpec.DOUBLE_PRESS,280);
  legacy.put(ConfigSpec.TIMING,250);reject(backup(14,legacy));
  legacy=settings(true);legacy.put("extra",true);reject(backup(14,legacy));
  legacy=settings(true);legacy.remove(ConfigSpec.LONG_PRESS);reject(backup(14,legacy));
  legacy=settings(true);legacy.put(ConfigSpec.LONG_PRESS,"200");reject(backup(14,legacy));
  legacy=settings(true);legacy.put("button1Short","invalid");reject(backup(14,legacy));
  current=settings(false);current.put(ConfigSpec.TIMING,237);
  check(ConfigBackup.decode(backup(15,current)).get(ConfigSpec.TIMING).equals(237),"continuous timing backup");
  current.put(ConfigSpec.TIMING,401);reject(backup(15,current));
  reject(backup(13,settings(true)));reject(backup(16,settings(false)));
  System.out.println("ConfigBackup schema 14 migration and strict schema 15 decode: PASS");
 }
}""",
}

files = []
for name, source in sources.items():
    path = out / name
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(source, encoding="utf-8")
    files.append(str(path))
for name in ("ConfigBackup", "ConfigSpec", "ProBindings", "AppProfiles", "RingContent", "PenAction", "DirectionAdapter"):
    files.append(str(root / f"module/app/src/main/java/com/focuspen/enhance/v2/{name}.java"))
jdk = Path(os.environ.get("JAVA_HOME", "/usr/lib/jvm/java-21-openjdk-amd64")) / "bin"
subprocess.run([str(jdk / "javac"), "-cp", str(gson), "-d", str(out), *files], check=True)
subprocess.run([str(jdk / "java"), "-cp", f"{out}:{gson}", "com.focuspen.enhance.v2.ConfigBackupTest"], check=True)
