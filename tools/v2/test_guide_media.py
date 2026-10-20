#!/usr/bin/env python3
"""Exercise production GuideMedia lifecycle with deterministic host/animation doubles."""
from pathlib import Path
import os
import subprocess

root = Path(__file__).resolve().parents[2]
out = root / "work/v2/guide-media-test"
sources = {
"android/content/Context.java": '''package android.content; public class Context {
 public String getPackageName(){return "com.miui.securitycore";} public ClassLoader getClassLoader(){return getClass().getClassLoader();}
 public android.content.res.Resources getResources(){return new android.content.res.Resources();}
}''',
"android/content/res/Resources.java": '''package android.content.res; public class Resources {
 public int getIdentifier(String a,String b,String c){return 1;}
 public java.io.InputStream openRawResource(int id){return new java.io.ByteArrayInputStream(new byte[0]);}
}''',
"android/view/View.java": '''package android.view; public class View {
 public static final int VISIBLE=0,INVISIBLE=4,GONE=8,IMPORTANT_FOR_ACCESSIBILITY_NO=2;
 public int visibility; public final android.content.Context c;
 public View(android.content.Context c){this.c=c;} public android.content.Context getContext(){return c;}
 public void setVisibility(int v){visibility=v;} public void setImportantForAccessibility(int v){}
 public boolean post(Runnable r){r.run();return true;} protected void onDetachedFromWindow(){}
}''',
"android/widget/FrameLayout.java": '''package android.widget; public class FrameLayout extends android.view.View {
 public static class LayoutParams {public LayoutParams(int a,int b){}}
 public final java.util.ArrayList<android.view.View> children=new java.util.ArrayList<>();
 public FrameLayout(android.content.Context c){super(c);} public void addView(android.view.View v,LayoutParams p){children.add(v);}
 public void removeView(android.view.View v){children.remove(v);}
}''',
"android/widget/ImageView.java": '''package android.widget; public class ImageView extends android.view.View {
 public enum ScaleType {FIT_CENTER} public ImageView(android.content.Context c){super(c);}
 public void setScaleType(ScaleType s){} public void setImageDrawable(Object d){}
}''',
"android/animation/ValueAnimator.java": '''package android.animation; public class ValueAnimator {
 public static final int RESTART=1; public static boolean enabled=true; public static int starts;
 public boolean started; public long time;
 public interface Listener {void update(ValueAnimator a);} public static ValueAnimator ofFloat(float a,float b){return new ValueAnimator();}
 public void setDuration(long t){} public void setInterpolator(Object i){} public void setRepeatCount(int i){} public void setRepeatMode(int i){}
 public void addUpdateListener(Listener l){} public Object getAnimatedValue(){return .5f;}
 public static boolean areAnimatorsEnabled(){return enabled;} public void start(){started=true;starts++;}
 public void cancel(){started=false;} public boolean isStarted(){return started;}
 public void setCurrentPlayTime(long t){time=t;} public long getCurrentPlayTime(){return time;}
}''',
"android/view/animation/LinearInterpolator.java": 'package android.view.animation; public class LinearInterpolator {}',
"android/graphics/BitmapFactory.java": '''package android.graphics; public class BitmapFactory {
 public static boolean valid=true; public static class Options {public boolean inJustDecodeBounds; public int outWidth,outHeight;}
 public static Object decodeResource(Object r,int id,Options o){o.outWidth=o.outHeight=valid?10:-1;return null;}
}''',
"org/json/JSONObject.java": '''package org.json; public class JSONObject {
 public JSONObject(String text){} public JSONArray optJSONArray(String key){return new JSONArray();}
 public String optString(String key){return "img_2.png";}
}''',
"org/json/JSONArray.java": '''package org.json; public class JSONArray {
 public int length(){return 1;} public JSONObject getJSONObject(int i){return new JSONObject("");}
}''',
"de/robv/android/xposed/XposedHelpers.java": '''package de.robv.android.xposed; public class XposedHelpers {
 public static Object callMethod(Object o,String s,Object a){return null;}
}''',
"t3/d.java": 'package t3; public class d {public d(android.content.Context c){}}',
"com/airbnb/lottie/LottieAnimationView.java": '''package com.airbnb.lottie; public class LottieAnimationView extends android.view.View {
 public interface Failure {void failed(Throwable t);} public Failure listener;
 public static boolean failStart,failProgress; public int starts,pauses,cancels,resource; public float progress=.4f; public boolean running;public String imageFolder;
 public LottieAnimationView(android.content.Context c){super(c);} public void setFailureListener(Failure l){listener=l;}
 public void setImageAssetsFolder(String folder){imageFolder=folder;}
 public void setAnimation(int id){resource=id;} public void setRepeatCount(int n){}
 public void drawImageLayer(){if(imageFolder==null||imageFolder.isEmpty())throw new IllegalStateException("You must set an images folder before loading an image.");}
 public void i(){if(failStart)throw new IllegalStateException();starts++;running=true;}
 public void d(){pauses++;running=false;} public void cancelAnimation(){cancels++;running=false;}
 public float getProgress(){return progress;} public void setProgress(float f){if(failProgress)throw new IllegalStateException();progress=f;}
}''',
"com/focuspen/enhance/v2/GuideScene.java": '''package com.focuspen.enhance.v2; class GuideScene {
 float progress; GuideScene(android.content.Context c,int t,int s,boolean p){} void invalidateSelf(){}
}''',
"com/focuspen/enhance/v2/GuideContent.java": '''package com.focuspen.enhance.v2; class GuideContent {
 static String animation(int t,int s,boolean p){return t==9?null:"test";}
}''',
"com/focuspen/enhance/v2/GuideMediaTest.java": '''package com.focuspen.enhance.v2;
import com.airbnb.lottie.LottieAnimationView;
import android.animation.ValueAnimator;
public class GuideMediaTest {
 static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
 static LottieAnimationView nativeView(GuideMedia m){return (LottieAnimationView)m.children.get(1);}
 public static void main(String[] args){
  android.content.Context c=new android.content.Context();
  GuideMedia m=new GuideMedia(c);m.prepare(0,0,false);LottieAnimationView v=nativeView(m);
  m.show(0,0,false);m.show(0,0,false);check(v.starts==1,"configuration refresh must not restart lesson");
  v.progress=.6f;m.stop();check(!v.running,"pause native animation");m.show(0,0,false);check(v.progress==.6f,"resume at previous progress");
  LottieAnimationView.failProgress=true;m.stop();m.show(0,0,false);m.stop();check(!v.running,"progress failure must not prevent stop");
  LottieAnimationView.failProgress=false;m.show(0,0,false);int before=ValueAnimator.starts;
  v.listener.failed(new IllegalStateException());check(!v.running&&m.children.size()==1&&ValueAnimator.starts==before+1,"async failure stops native and starts fallback");
  m.onDetachedFromWindow();before=ValueAnimator.starts;v.listener.failed(new IllegalStateException());check(ValueAnimator.starts==before,"stale failure cannot restart detached lesson");
  LottieAnimationView.failStart=true;GuideMedia bad=new GuideMedia(c);bad.prepare(0,0,false);v=nativeView(bad);bad.show(0,0,false);
  check(v.cancels==1&&bad.children.size()==1,"play failure removes native view");bad.stop();LottieAnimationView.failStart=false;
  android.graphics.BitmapFactory.valid=false;GuideMedia pro=new GuideMedia(c);pro.prepare(0,0,true);check(pro.children.size()==1,"empty Pro bitmap uses fallback");
  ValueAnimator.enabled=false;before=ValueAnimator.starts;pro.show(0,0,true);check(before==ValueAnimator.starts,"disabled animation stays static");
  ValueAnimator.enabled=true;GuideMedia custom=new GuideMedia(c);custom.show(9,0,false);before=ValueAnimator.starts;custom.show(9,0,false);
  check(ValueAnimator.starts==before,"custom lesson does not restart on refresh");custom.onDetachedFromWindow();
  for(boolean penPro:new boolean[]{false,true})for(int page=0;page<6;page++){
   GuideMedia handwriting=new GuideMedia(c);handwriting.show(4,page,penPro);LottieAnimationView lesson=nativeView(handwriting);
   lesson.drawImageLayer();check("img/".equals(lesson.imageFolder),"handwriting images must use official assets directory");
   handwriting.stop();handwriting.show(4,page,penPro);lesson.drawImageLayer();handwriting.onDetachedFromWindow();
   check(!lesson.running,"handwriting animation survived detach");
  }
  System.out.println("GuideMedia pause/resume, playback failure, async failure, missing Pro image and reduced motion PASS");
 }
}''',
}
files = []
for name, text in sources.items():
    path = out / name
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")
    files.append(str(path))
src = root / "module/app/src/main/java/com/focuspen/enhance/v2"
jdk = Path(os.environ.get("JAVA_HOME", "/usr/lib/jvm/java-21-openjdk-amd64"))
subprocess.run([str(jdk / "bin/javac"), "-d", str(out), *files, str(src / "GuideMedia.java"), str(src / "GuideMotion.java")], check=True)
subprocess.run([str(jdk / "bin/java"), "-cp", str(out), "com.focuspen.enhance.v2.GuideMediaTest"], check=True)
