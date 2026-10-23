#!/usr/bin/env python3
"""Compile the production guide model against partial host resource doubles."""
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
  for(int i=0;i<6;i++){
   check(full[i].title().equals("official title "+i),"official page "+i);
   check(GuideContent.animation(4,i,false).equals("stylus_guide_"+keys[i]+"_cn"),"animation for page "+i);
  }
  host.resources.ids.remove("guide_select_word_tip");
  GuideContent.Step[] partial=GuideContent.steps(host,4,false,new ConfigSnapshot());
  check(partial.length==6&&partial[2].title().equals("选择文字"),"third page fallback");
  check(partial[3].title().equals("official title 3")&&partial[5].title().equals("official title 5"),"later official pages survive");
  host.broken=8;
  GuideContent.Step[] failed=GuideContent.steps(host,4,true,new ConfigSnapshot());
  check(failed[3].title().equals("插入空格")&&failed[4].title().equals("official title 4"),"string failure isolated to one page");
  host.resources.ids.clear();
  GuideContent.Step[] fallback=GuideContent.steps(host,4,false,new ConfigSnapshot());
  check(fallback.length==6&&fallback[4].title().equals("换行")&&fallback[5].title().equals("插入文字"),"complete fallback maps all six lessons");
  check(GuideContent.steps(host,6,false,new ConfigSnapshot()).length==2,"notes and screenshot use two pages");
  check(GuideContent.steps(host,6,true,new ConfigSnapshot()).length==2,"Pro notes and screenshot use two pages");
  GuideContent.Step[] keyLessons=GuideContent.steps(host,0,false,new ConfigSnapshot());
  check(keyLessons[1].title().contains("焦点键")&&keyLessons[1].text().contains("激光"),"hold lesson teaches focus-key laser");
  check(keyLessons[2].title().contains("焦点键")&&keyLessons[2].text().contains("鼠标")&&keyLessons[2].detail().contains("圈注"),"double lesson teaches mouse guard");
  check(GuideContent.TITLES[5].equals("演示、圈注与鼠标"),"presentation title includes mouse");
  GuideContent.Step[] notes=GuideContent.steps(host,6,false,new ConfigSnapshot());
  check(notes[1].text().contains("空白处点一下")&&!notes[1].text().contains("锁屏"),"screen-off lesson has one tap");
  for(boolean pro:new boolean[]{false,true}){
   GuideContent.Step[] presentation=GuideContent.steps(host,5,pro,new ConfigSnapshot());
   check(presentation.length==(pro?4:8),"standard presentation has mouse lessons; Pro stays hardware-specific");
   for(GuideContent.Step page:presentation)check(!page.title().contains("遥控拍照")&&!page.detail().contains("官方输入优先"),"no camera bypass claim");
   check(presentation[0].detail().contains(pro?"双击笔身":"先关闭激光"),"laser exit must match current mode");
   check(presentation[1].text().contains(pro?"轻捏并按住":"长按焦点键"),"drawing uses the owned gesture");
   if(!pro){
    check(presentation[4].text().contains("先退出激光和圈注"),"mouse entry precondition");
    check(presentation[5].text().contains("速记键为左键")&&presentation[5].text().contains("截图键为右键"),"mouse buttons");
    check(presentation[6].text().contains("拖动")&&presentation[7].text().contains("结束鼠标"),"drag and exit lessons");
   }
  }
  System.out.println("Guide content: six mapped handwriting pages, isolated fallback, two notes pages PASS");
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
