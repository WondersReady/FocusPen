#!/usr/bin/env python3
"""Exercise the real update policy with controlled JSON and Android compile stubs."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile


ROOT = Path(__file__).resolve().parents[2]
ANDROID_JAR = Path(os.environ.get("ANDROID_JAR", "/home/wonders/Android/Sdk/platforms/android-37/android.jar"))
GSON_JAR = Path(os.environ.get("GSON_JAR", "/home/wonders/tools/gradle-8.9/lib/gson-2.10.jar"))
SOURCES = {
    "com/focuspen/enhance/v2/BuildConfig.java": """package com.focuspen.enhance.v2;
final class BuildConfig { static final String APPLICATION_ID="com.focuspen.enhance.v2";
 static final int VERSION_CODE=41; }
""",
    "org/json/JSONException.java": "package org.json; public class JSONException extends Exception { public JSONException(String s){super(s);} }",
    "org/json/JSONArray.java": """package org.json;
import com.google.gson.*;
public class JSONArray {
 private final JsonArray data;
 public JSONArray(String text){data=JsonParser.parseString(text).getAsJsonArray();}
 JSONArray(JsonArray data){this.data=data;}
 public int length(){return data.size();}
 public JSONObject optJSONObject(int index){
  if(index<0||index>=data.size()||!data.get(index).isJsonObject())return null;
  return new JSONObject(data.get(index).getAsJsonObject());
 }
}""",
    "org/json/JSONObject.java": """package org.json;
import com.google.gson.*;
public class JSONObject {
 private final JsonObject data;
 JSONObject(JsonObject data){this.data=data;}
 public boolean optBoolean(String key,boolean fallback){
  JsonElement v=data.get(key);return v==null||!v.isJsonPrimitive()||!v.getAsJsonPrimitive().isBoolean()
   ?fallback:v.getAsBoolean();
 }
 public String optString(String key,String fallback){
  JsonElement v=data.get(key);return v==null||!v.isJsonPrimitive()||!v.getAsJsonPrimitive().isString()
   ?fallback:v.getAsString();
 }
 public JSONArray optJSONArray(String key){JsonElement v=data.get(key);
  return v!=null&&v.isJsonArray()?new JSONArray(v.getAsJsonArray()):null;}
}""",
    "com/focuspen/enhance/v2/UpdateCheckerTest.java": """package com.focuspen.enhance.v2;
public class UpdateCheckerTest {
 static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
 static String release(String tag,boolean draft,boolean beta,String page,String asset,String name){
  return "{\\"tag_name\\":\\""+tag+"\\",\\"draft\\":"+draft+",\\"prerelease\\":"+beta+
   ",\\"html_url\\":\\""+page+"\\",\\"assets\\":[{\\"name\\":\\""+name+
   "\\",\\"browser_download_url\\":\\""+asset+"\\"}]}";
 }
 static String page(String tag){return "https://github.com/WondersReady/FocusPen/releases/tag/"+tag;}
 static String asset(String tag){return "https://github.com/WondersReady/FocusPen/releases/download/"+tag+"/FocusPen.apk";}
 static String valid(String tag,boolean beta){return release(tag,false,beta,page(tag),asset(tag),"FocusPen.apk");}
 static void rejects(String source){
  try{UpdateChecker.parseReleases(source);throw new AssertionError("Accepted invalid release feed");}
  catch(org.json.JSONException expected){}
 }
 public static void main(String[] args) throws Exception {
  String feed="["+valid("40-0.9",false)+","+valid("42-1.0Beta",true)+","
   +release("50-2.0",true,false,page("50-2.0"),asset("50-2.0"),"FocusPen.apk")+","
   +release("43-1.1",false,false,page("43-1.1"),asset("43-1.1"),"source.zip")+"]";
  var best=UpdateChecker.parseReleases(feed);
  check(best.versionCode()==42&&best.versionName().equals("1.0Beta")&&best.prerelease(),
   "Did not select highest usable prerelease");
  check(best.pageUrl().equals(page("42-1.0Beta"))&&best.apkUrl().equals(asset("42-1.0Beta")),
   "Release links changed");
  rejects("["+valid("0-zero",false)+","+valid("42-",false)+","
   +valid("999999999999999999999-overflow",false)+"]");
  rejects("["+release("42-1.0",false,false,"https://evil.example/releases/tag/42-1.0",
   asset("42-1.0"),"FocusPen.apk")+"]");
  rejects("["+release("42-1.0",false,false,page("42-1.0"),
   "https://github.com.evil.example/WondersReady/FocusPen/releases/download/42-1.0/FocusPen.apk",
   "FocusPen.apk")+"]");
  rejects("["+release("42-1.0",false,false,page("42-1.0"),
   "https://github.com/Other/FocusPen/releases/download/42-1.0/FocusPen.apk",
   "FocusPen.apk")+"]");
  rejects("["+release("42-1.0",false,false,page("42-1.0"),
   asset("42-1.0")+"?redirect=evil","FocusPen.apk")+"]");
  check(!UpdateChecker.trustedReleaseUrl("http://github.com/WondersReady/FocusPen/releases/tag/42-1.0",
   "42-1.0"),"Accepted plaintext release link");
  check(!UpdateChecker.trustedReleaseUrl(page("42-1.0")+"/../../evil","42-1.0"),
   "Accepted path traversal");
  long now=1_700_000_000_000L;
  check(UpdateChecker.withinDailyWindow(now-86_399_999L,now),"Daily throttle ended early");
  check(!UpdateChecker.withinDailyWindow(now-86_400_000L,now)
   &&!UpdateChecker.withinDailyWindow(now+1,now),"Daily throttle boundary wrong");
  check(UpdateChecker.retryAt("120",null,now)==now+120_000,"Retry-After seconds ignored");
  check(UpdateChecker.retryAt(null,Long.toString((now+180_000)/1000),now)==now+180_000,
   "Rate reset ignored");
  check(UpdateChecker.retryAt("120",Long.toString((now+180_000)/1000),now)==now+180_000,
   "Earlier limit overrode later limit");
  check(UpdateChecker.retryAt("bad","bad",now)==0,"Invalid rate headers accepted");
  System.out.println("Update checker policy: PASS");
 }
}""",
}


def main():
    if not ANDROID_JAR.is_file() or not GSON_JAR.is_file():
        raise SystemExit("Android API or Gson test dependency unavailable")
    javac = shutil.which("javac")
    java = shutil.which("java")
    if not javac or not java:
        raise SystemExit("Java compiler/runtime unavailable")
    with tempfile.TemporaryDirectory(prefix="focuspen-update-checker-") as directory:
        target = Path(directory)
        files = []
        for name, source in SOURCES.items():
            path = target / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(source, encoding="utf-8")
            files.append(str(path))
        java_dir = ROOT / "module/app/src/main/java/com/focuspen/enhance/v2"
        files += [str(java_dir / "UpdateChecker.java"), str(java_dir / "UpdateCheckJob.java")]
        classpath = os.pathsep.join((str(GSON_JAR), str(ANDROID_JAR)))
        subprocess.run([javac, "--release", "17", "-cp", classpath, "-d", str(target), *files], check=True)
        subprocess.run([java, "-cp", os.pathsep.join((str(target), classpath)),
                        "com.focuspen.enhance.v2.UpdateCheckerTest"], check=True)


if __name__ == "__main__":
    main()
