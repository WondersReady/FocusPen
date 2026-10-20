#!/usr/bin/env python3
"""Run production row dragging against a scrolling/recycling host double, not device UI."""
from pathlib import Path
import os
import subprocess

root = Path(__file__).resolve().parents[2]
out = root / 'work/v2/menu-drag-test'
sources = {
'android/R': 'package android; public class R {public static class id {public static int icon=1,widget_frame=2;}}',
'android/content/Context': 'package android.content; public class Context {}',
'android/content/res/Resources': '''package android.content.res; public class Resources {
 public android.util.DisplayMetrics getDisplayMetrics(){return new android.util.DisplayMetrics();}
 public Configuration getConfiguration(){return new Configuration();}
}''',
'android/content/res/Configuration': 'package android.content.res;public class Configuration {public int uiMode=16;}',
'android/util/DisplayMetrics': 'package android.util;public class DisplayMetrics {public float density=1;}',
'android/os/Bundle': 'package android.os;public class Bundle {}',
'android/os/SystemClock': 'package android.os;public class SystemClock {public static long time;public static long uptimeMillis(){return time;}}',
'android/graphics/drawable/Drawable': 'package android.graphics.drawable;public class Drawable {}',
'android/graphics/drawable/GradientDrawable': '''package android.graphics.drawable;public class GradientDrawable extends Drawable {
 public void setColor(int c){}public void setCornerRadius(float r){}
}''',
'android/view/Gravity': 'package android.view;public class Gravity {public static int CENTER=1,CENTER_VERTICAL=2;}',
'android/view/MotionEvent': '''package android.view;public class MotionEvent {
 public static final int ACTION_DOWN=0,ACTION_UP=1,ACTION_MOVE=2,ACTION_CANCEL=3,ACTION_POINTER_DOWN=5;
 int action;float raw;public MotionEvent(int a,float y){action=a;raw=y;}public int getActionMasked(){return action;}public float getRawY(){return raw;}
}''',
'android/view/ViewConfiguration': '''package android.view;public class ViewConfiguration {
 public static ViewConfiguration get(android.content.Context c){return new ViewConfiguration();}public int getScaledTouchSlop(){return 8;}
}''',
'android/view/accessibility/AccessibilityNodeInfo': '''package android.view.accessibility;public class AccessibilityNodeInfo {
 public static final int ACTION_SCROLL_BACKWARD=1,ACTION_SCROLL_FORWARD=2;
 public static class AccessibilityAction {public AccessibilityAction(int a,String b){}}public void addAction(AccessibilityAction a){}
}''',
'android/view/View': '''package android.view;import java.util.*;public class View {
 public int id,top,height=60;public float ty,tz;public Object tag;public ViewGroup parent;public boolean attached=true;
 public android.graphics.drawable.Drawable background;public android.content.Context context;
 public OnTouchListener touch;public final List<OnAttachStateChangeListener> listeners=new ArrayList<>();
 public final List<Runnable> frames=new ArrayList<>(),delays=new ArrayList<>();public final Animator animator=new Animator(this);
 public View(android.content.Context c){context=c;}public android.content.Context getContext(){return context;}
 public android.content.res.Resources getResources(){return new android.content.res.Resources();}
 @SuppressWarnings("unchecked") public <T extends View>T findViewById(int i){return id==i?(T)this:null;}
 @SuppressWarnings("unchecked") public <T extends View>T findViewWithTag(Object t){return t.equals(tag)?(T)this:null;}
 public ViewGroup getParent(){return parent;}public int getTop(){return top;}public int getBottom(){return top+height;}public int getHeight(){return height;}
 public void setTranslationY(float f){ty=f;}public float getTranslationY(){return ty;}public void setTranslationZ(float f){tz=f;}
 public void getLocationOnScreen(int[] a){a[0]=0;a[1]=Math.round(top+ty);if(parent!=null){int[] p=new int[2];parent.getLocationOnScreen(p);a[1]+=p[1];}}
 public void setBackground(android.graphics.drawable.Drawable d){background=d;}public android.graphics.drawable.Drawable getBackground(){return background;}
 public void setTag(Object t){tag=t;}public void setPressed(boolean b){}public void setFocusable(boolean b){}public void setContentDescription(String s){}
 public void setOnDragListener(Object o){}public void setOnTouchListener(OnTouchListener l){touch=l;}public void addOnAttachStateChangeListener(OnAttachStateChangeListener l){listeners.add(l);}
 public void setAccessibilityDelegate(AccessibilityDelegate d){}public boolean isAttachedToWindow(){return attached;}
 public void postOnAnimation(Runnable r){if(frames.contains(r))throw new AssertionError("duplicate frame");frames.add(r);}
 public boolean postDelayed(Runnable r,long ms){delays.add(r);return true;}public boolean removeCallbacks(Runnable r){frames.remove(r);delays.remove(r);return true;}
 public Animator animate(){return animator;}public ViewGroup.LayoutParams getLayoutParams(){return new ViewGroup.LayoutParams(1,1);}public void setLayoutParams(ViewGroup.LayoutParams p){}
 public interface OnTouchListener {boolean onTouch(View v,MotionEvent e);}public interface OnAttachStateChangeListener {void onViewAttachedToWindow(View v);void onViewDetachedFromWindow(View v);}
 public void detach(){attached=false;for(OnAttachStateChangeListener l:List.copyOf(listeners))l.onViewDetachedFromWindow(this);}
 public static class AccessibilityDelegate {public void onInitializeAccessibilityNodeInfo(View v,android.view.accessibility.AccessibilityNodeInfo i){}public boolean performAccessibilityAction(View v,int a,android.os.Bundle b){return false;}}
 public static class Animator {View v;Runnable end;public int starts;Animator(View v){this.v=v;}public void cancel(){end=null;}public Animator translationY(float f){v.ty=f;return this;}
 public Animator setDuration(long t){return this;}public Animator withEndAction(Runnable r){end=r;return this;}public void start(){starts++;}
 public void finish(){Runnable r=end;end=null;if(r!=null)r.run();}}
}''',
'android/view/ViewGroup': '''package android.view;import java.util.*;public class ViewGroup extends View {
 public final List<View> children=new ArrayList<>();public int paddingTop,paddingBottom,scroll;public boolean intercept;public Runnable afterScroll;
 public ViewGroup(android.content.Context c){super(c);}public static class LayoutParams {public int width,height;public LayoutParams(int w,int h){width=w;height=h;}}
 public void addView(View v,LayoutParams p){children.add(v);v.parent=this;}public void removeView(View v){children.remove(v);v.parent=null;v.detach();}
 public int getChildCount(){return children.size();}public View getChildAt(int i){return children.get(i);}
 @Override public <T extends View>T findViewById(int i){T self=super.findViewById(i);if(self!=null)return self;for(View c:children){T v=c.findViewById(i);if(v!=null)return v;}return null;}
 @Override public <T extends View>T findViewWithTag(Object t){T self=super.findViewWithTag(t);if(self!=null)return self;for(View c:children){T v=c.findViewWithTag(t);if(v!=null)return v;}return null;}
 public void requestDisallowInterceptTouchEvent(boolean b){intercept=b;}public int getPaddingTop(){return paddingTop;}public int getPaddingBottom(){return paddingBottom;}
 public boolean canScrollVertically(int d){return d<0?scroll>0:scroll<1200;}
 public void scrollBy(int x,int y){scroll+=y;for(View c:children)c.top-=y;if(afterScroll!=null)afterScroll.run();}
}''',
'android/widget/LinearLayout': '''package android.widget;public class LinearLayout extends android.view.ViewGroup {
 public static int HORIZONTAL=0;public LinearLayout(android.content.Context c){super(c);}public void setOrientation(int v){}public void setGravity(int v){}
}''',
'android/widget/ImageView': '''package android.widget;public class ImageView extends android.view.View {
 public ImageView(android.content.Context c){super(c);}public enum ScaleType {FIT_CENTER}public void setScaleType(ScaleType s){}
}''',
'android/widget/TextView': '''package android.widget;public class TextView extends android.view.View {
 public TextView(android.content.Context c){super(c);}public void setText(String s){}public void setTextSize(int n){}public void setGravity(int n){}public void setTextColor(int n){}
}''',
'com/focuspen/enhance/v2/MenuEntry': 'package com.focuspen.enhance.v2;class MenuEntry {static String label(android.content.Context c,String id){return id;}}',
'com/focuspen/enhance/v2/RingContent': '''package com.focuspen.enhance.v2;class RingContent {
 record Item(String id){}final java.util.List<Item> items=new java.util.ArrayList<>();
 RingContent(int count){for(int i=0;i<count;i++)items.add(new Item("item"+i));}Item find(String id){return items.stream().filter(i->i.id.equals(id)).findFirst().orElse(null);}
}''',
'com/focuspen/enhance/v2/MenuDragTest': '''package com.focuspen.enhance.v2;import android.view.*;import android.widget.*;import java.util.*;
public class MenuDragTest {
 static final android.content.Context context=new android.content.Context();static ViewGroup list;static RingContent content;
 static List<ViewGroup> roots;static List<String> commits;
 static void check(boolean b,String m){if(!b)throw new AssertionError(m);}
 static void setup(int count){list=new ViewGroup(context);list.height=240;list.top=100;content=new RingContent(count);roots=new ArrayList<>();commits=new ArrayList<>();
  for(int i=0;i<count;i++)attach(i);
 }
 static ViewGroup attach(int i){ViewGroup row=new ViewGroup(context);row.top=i*60-list.scroll;LinearLayout widget=new LinearLayout(context);widget.id=android.R.id.widget_frame;
  row.addView(widget,new ViewGroup.LayoutParams(1,1));list.addView(row,new ViewGroup.LayoutParams(1,1));roots.add(row);
  MenuPickerViews.bind(row,"item"+i,content,(id,p)->commits.add(id+":"+p));return row;}
 static View handle(int i){return roots.get(i).findViewWithTag("focuspen.reorder");}
 static void event(int i,int a,float y){View v=handle(i);v.touch.onTouch(v,new MotionEvent(a,y));}
 static void frames(int count){for(int i=0;i<count;i++){android.os.SystemClock.time+=16;for(View v:List.copyOf(roots)){List<Runnable> todo=List.copyOf(v.frames);v.frames.clear();for(Runnable r:todo)r.run();}}}
 static void finish(int i){View v=roots.get(i);v.animate().finish();List<Runnable> todo=List.copyOf(v.delays);v.delays.clear();todo.forEach(Runnable::run);}
 static void idle(){for(View v:roots)check(v.frames.isEmpty(),"frame survived gesture");check(!list.intercept,"parent remained intercepted");}
 public static void main(String[] args){
  setup(12);event(0,0,130);event(0,2,330);frames(90);
  check(list.scroll==480,"scrolled beyond selected rows: "+list.scroll);check(commits.isEmpty(),"saved before release");
  check(Math.abs(roots.get(0).top+roots.get(0).ty-180)<1,"dragged row lost finger during scroll");
  int starts=roots.get(1).animate().starts;frames(10);check(starts==roots.get(1).animate().starts,"stationary frame restarted neighbor animation");
  event(0,1,330);finish(0);check(commits.equals(List.of("item0:11")),"first-to-last failed: "+commits);idle();
  setup(12);list.scrollBy(0,480);event(11,0,310);event(11,2,110);frames(90);check(list.scroll==0,"top edge overscroll");event(11,1,110);finish(11);
  check(commits.equals(List.of("item11:0")),"last-to-first failed: "+commits);idle();
  setup(3);event(0,0,130);event(0,2,330);frames(30);check(list.scroll==0,"short selected list scrolled into candidates");event(0,3,330);check(commits.isEmpty(),"cancel persisted order");idle();
  setup(12);event(0,0,130);event(0,2,330);frames(2);event(0,5,330);frames(5);check(commits.isEmpty(),"multitouch persisted order");idle();
  setup(12);event(0,0,130);event(0,2,330);frames(2);handle(0).detach();frames(5);check(commits.isEmpty(),"detach persisted order");idle();
  setup(3);event(0,0,130);event(0,1,130);idle();check(commits.isEmpty(),"handle tap moved row");
  setup(3);roots.get(1).height=84;roots.get(2).top=144;event(0,0,130);event(0,2,290);event(0,1,290);
  check(roots.get(0).ty==144,"different row height settled into occupied space");finish(0);check(commits.equals(List.of("item0:2")),"mixed-height destination wrong");idle();
  setup(12);for(int i=11;i>=4;i--)list.children.remove(roots.get(i));
  list.afterScroll=()->{for(int i=4;i<12;i++){View row=roots.get(i);if(!list.children.contains(row)&&i*60-list.scroll<240){row.top=i*60-list.scroll;list.children.add(row);}}};
  event(0,0,130);event(0,2,330);frames(90);event(0,1,330);finish(0);
  check(commits.equals(List.of("item0:11")),"newly attached rows missing from destinations: "+commits);idle();
  System.out.println("Production menu drag: edge scroll / both end moves / stationary frames / attach / cancel / multitouch / detach PASS");
 }
}''',
}
files=[]
for name,source in sources.items():
    path=out/(name+'.java')
    path.parent.mkdir(parents=True,exist_ok=True)
    path.write_text(source,encoding='utf-8')
    files.append(str(path))
files.append(str(root/'module/app/src/main/java/com/focuspen/enhance/v2/MenuPickerViews.java'))
jdk=Path(os.environ.get('JAVA_HOME','/usr/lib/jvm/java-21-openjdk-amd64'))
subprocess.run([str(jdk/'bin/javac'),'-d',str(out),*files],check=True)
subprocess.run([str(jdk/'bin/java'),'-cp',str(out),'com.focuspen.enhance.v2.MenuDragTest'],check=True)
