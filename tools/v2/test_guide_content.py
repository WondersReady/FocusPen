#!/usr/bin/env python3
"""Check the production handwriting lessons with partial host resources."""
from pathlib import Path
import os
import subprocess
import tempfile

root = Path(__file__).resolve().parents[2]
sources = {
    "android/content/pm/PackageManager.java": "package android.content.pm; public class PackageManager { public static class NameNotFoundException extends Exception {} }",
    "android/content/res/Resources.java": """package android.content.res;
import java.util.*;
public class Resources {
 public final Map<String,Integer> ids=new HashMap<>();
 public int getIdentifier(String name,String type,String pkg){return ids.getOrDefault(name,0);}
}""",
    "android/content/Context.java": """package android.content;
public class Context {
 public final android.content.res.Resources resources=new android.content.res.Resources();
 public final java.util.Map<Integer,String> strings=new java.util.HashMap<>();
 public int broken=-1; public Context host=this;
 public String getPackageName(){return "com.miui.securitycore";}
 public Context createPackageContext(String name,int flags){return host;}
 public android.content.res.Resources getResources(){return resources;}
 public String getString(int id){if(id==broken)throw new IllegalStateException("missing");return strings.get(id);}
}""",
    "android/graphics/drawable/Drawable.java": "package android.graphics.drawable; public class Drawable {}",
    "com/focuspen/enhance/v2/ConfigSnapshot.java": "package com.focuspen.enhance.v2; class ConfigSnapshot {}",
    "com/focuspen/enhance/v2/GuideScene.java": """package com.focuspen.enhance.v2;
class GuideScene extends android.graphics.drawable.Drawable {
 GuideScene(android.content.Context c,int topic,int step,boolean pro){}
}""",
    "com/focuspen/enhance/v2/GuideContentTest.java": """package com.focuspen.enhance.v2;
import android.content.Context;
public class GuideContentTest {
 static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
 public static void main(String[] args){
  Context host=new Context();String[] keys={"hand_write","delete_word","select_word","insert_blank","new_line","insert_word"};
  for(int i=0;i<keys.length;i++){
   host.resources.ids.put("guide_"+keys[i]+"_title",i*2+1);
   host.resources.ids.put("guide_"+keys[i]+"_tip",i*2+2);
   host.strings.put(i*2+1,"official title "+i);
   host.strings.put(i*2+2,"official tip "+i);
  }
  GuideContent.Step[] full=GuideContent.steps(host,4,false,new ConfigSnapshot());
  check(full.length==6,"all six handwriting pages must exist");
  for(int i=0;i<6;i++)check(full[i].title().equals("official title "+i),"official page "+i);
  host.resources.ids.remove("guide_select_word_tip");
  GuideContent.Step[] partial=GuideContent.steps(host,4,false,new ConfigSnapshot());
  check(partial.length==6 && partial[2].title().equals("选择文字"),"third page fallback and count");
  check(partial[3].title().equals("official title 3") && partial[5].title().equals("official title 5"),"later official pages survive missing third resource");
  host.broken=8;
  GuideContent.Step[] failed=GuideContent.steps(host,4,true,new ConfigSnapshot());
  check(failed.length==6 && failed[3].title().equals("插入空格"),"string read failure falls back only on its page");
  check(failed[4].title().equals("official title 4"),"string read failure does not lose later pages");
  host.resources.ids.clear();
  GuideContent.Step[] fallback=GuideContent.steps(host,4,false,new ConfigSnapshot());
  check(fallback.length==6 && fallback[5].title().equals("插入文字"),"complete fallback has all six pages");
  System.out.println("Handwriting guide: six stable pages with partial host resources PASS");
 }
}""",
}

with tempfile.TemporaryDirectory(prefix="focuspen-guide-content-") as temp:
    out = Path(temp)
    files = []
    for name, source in sources.items():
        path = out / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(source, encoding="utf-8")
        files.append(str(path))
    files.append(str(root / "module/app/src/main/java/com/focuspen/enhance/v2/GuideContent.java"))
    jdk = Path(os.environ.get("JAVA_HOME", "/usr/lib/jvm/java-21-openjdk-amd64")) / "bin"
    subprocess.run([str(jdk / "javac"), "-d", str(out), *files], check=True)
    subprocess.run([str(jdk / "java"), "-cp", str(out), "com.focuspen.enhance.v2.GuideContentTest"], check=True)
