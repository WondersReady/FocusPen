package com.focuspen.enhance.v2;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.MotionEvent;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import java.util.function.Consumer;
import java.util.function.BiConsumer;

/** Fixed clockwise editing positions; each target is a real accessible button. */
final class RingEditorView extends FrameLayout {
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final boolean dark;
    private final int count;
    private final TextView center;
    private final String hint;
    private int dropIndex=-1;
    private View dragged;
    private int dragGeneration;
    final String contentValue;
    RingEditorView(Context c,RingContent content,Consumer<String> open,BiConsumer<String,Integer> move){
        super(c);setWillNotDraw(false);setClipChildren(false);contentValue=content.encode();
        dark=(c.getResources().getConfiguration().uiMode&48)==32;count=content.items.size();
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        center=new TextView(c);center.setGravity(Gravity.CENTER);center.setTextSize(15);
        center.setTextColor(dark?0xffb6bdc8:0xff737a87);
        hint=count==0?"点击 ＋\n添加第一个项目":"已添加 "+count+" 项\n拖动调整顺序";center.setText(hint);
        addView(center,new LayoutParams(dp(150),dp(72),Gravity.CENTER));
        int total=count+(count<RingContent.MAX_ITEMS?1:0);
        for(int i=0;i<total;i++){
            boolean add=i==count;RingContent.Item item=add?null:content.items.get(i);
            boolean available=add||MenuEntry.available(c,item.id());
            FrameLayout target=new FrameLayout(c);target.setFocusable(true);target.setClickable(true);
            String name=add?"添加第 "+(i+1)+" 个项目":MenuEntry.label(c,item.id());
            target.setContentDescription(add?name:"第 "+(i+1)+" 项，"+name+(!available?"，不可用":!item.enabled()?"，已停用":"")+"，点击编辑，拖动排序");
            target.setAccessibilityDelegate(new View.AccessibilityDelegate(){
                @Override public void onInitializeAccessibilityNodeInfo(View v,android.view.accessibility.AccessibilityNodeInfo info){super.onInitializeAccessibilityNodeInfo(v,info);info.setClassName("android.widget.Button");}
            });
            GradientDrawable base=new GradientDrawable();base.setShape(GradientDrawable.OVAL);base.setColor(dark?0xff30343c:0xfff2f5fa);
            target.setBackground(new RippleDrawable(ColorStateList.valueOf(0x293482ff),base,null));
            android.graphics.drawable.Drawable icon=null;
            if(!add)icon=MenuIcons.icon(c,item.id());
            if(icon!=null){ImageView picture=new ImageView(c);picture.setImageDrawable(icon);picture.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
                picture.setAlpha(available&&item.enabled()?1f:.35f);target.addView(picture,new LayoutParams(dp(34),dp(34),Gravity.CENTER));}
            else{TextView glyph=new TextView(c);glyph.setGravity(Gravity.CENTER);glyph.setText(add?"+":RingContent.SCREENSHOT.equals(item.id())?"⌗":"•");glyph.setTextSize(add?30:27);glyph.setTextColor(0xff3482ff);glyph.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);target.addView(glyph,new LayoutParams(-1,-1));}
            if(!add){TextView number=new TextView(c);number.setText(!available||!item.enabled()?"−":String.valueOf(i+1));number.setGravity(Gravity.CENTER);number.setTextSize(10);number.setTextColor(dark?0xffd4d9e2:0xff626b7c);
                GradientDrawable badge=new GradientDrawable();badge.setShape(GradientDrawable.OVAL);badge.setColor(dark?0xff22252b:0xffffffff);number.setBackground(badge);
                number.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);target.addView(number,new LayoutParams(dp(17),dp(17),Gravity.BOTTOM|Gravity.END));}
            String page=add?"addContent":"item:"+item.id();target.setOnClickListener(v->open.accept(page));
            if(!add){
                final int source=i;
                target.setOnTouchListener(new OnTouchListener(){float downX,downY;boolean moving,tracking;int generation;
                    public boolean onTouch(View v,MotionEvent e){
                        if(e.getActionMasked()==MotionEvent.ACTION_DOWN){
                            tracking=dragged==null;if(!tracking)return true;
                            generation=++dragGeneration;downX=e.getRawX();downY=e.getRawY();moving=false;v.setPressed(true);
                            getParent().requestDisallowInterceptTouchEvent(true);return true;}
                        if(!tracking||generation!=dragGeneration)return true;
                        if(e.getActionMasked()==MotionEvent.ACTION_POINTER_DOWN){
                            tracking=false;moving=false;v.setPressed(false);dragGeneration++;dragged=null;dropIndex=-1;
                            resetPositions();center.setText(hint);if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(false);invalidate();return true;
                        }
                        if(e.getActionMasked()==MotionEvent.ACTION_MOVE){
                            float dx=e.getRawX()-downX,dy=e.getRawY()-downY;
                            if(!moving&&Math.hypot(dx,dy)>android.view.ViewConfiguration.get(c).getScaledTouchSlop()){
                                moving=true;dragged=v;v.setPressed(false);v.animate().cancel();v.setTranslationZ(dp(8));
                                center.setText("拖到目标位置\n松手完成排列");
                            }
                            if(moving){v.setTranslationX(dx);v.setTranslationY(dy);
                                int destination=nearest(v.getLeft()+v.getWidth()/2f+dx,v.getTop()+v.getHeight()/2f+dy);
                                if(destination!=dropIndex){dropIndex=destination;makeRoom(source,destination);invalidate();}}
                            return true;
                        }
                        if(e.getActionMasked()==MotionEvent.ACTION_UP||e.getActionMasked()==MotionEvent.ACTION_CANCEL){
                            tracking=false;v.setPressed(false);getParent().requestDisallowInterceptTouchEvent(false);
                            if(!moving){if(e.getActionMasked()==MotionEvent.ACTION_UP)v.performClick();return true;}
                            int destination=e.getActionMasked()==MotionEvent.ACTION_UP?dropIndex:-1;
                            boolean changed=destination>=0&&destination!=source;
                            View slot=getChildAt((changed?destination:source)+1);
                            if(!changed)makeRoom(source,-1);
                            Runnable settled=()->{if(generation!=dragGeneration||!isAttachedToWindow())return;
                                if(!changed)dragged=null;v.setTranslationZ(0);dropIndex=-1;center.setText(hint);invalidate();
                                if(changed){move.accept(item.id(),destination);
                                    // If saving fails, return the same views to their saved positions.
                                    postDelayed(()->{if(generation==dragGeneration&&isAttachedToWindow()){dragged=null;resetPositions();}},500);}
                            };
                            if(android.animation.ValueAnimator.areAnimatorsEnabled())v.animate().translationX(slot.getLeft()-v.getLeft()).translationY(slot.getTop()-v.getTop()).setDuration(180)
                                    .setInterpolator(new android.view.animation.PathInterpolator(.22f,.8f,.2f,1)).withEndAction(settled).start();
                            else{v.setTranslationX(slot.getLeft()-v.getLeft());v.setTranslationY(slot.getTop()-v.getTop());settled.run();}
                            moving=false;return true;
                        }
                        return true;
                    }
                });
            }
            addView(target,new LayoutParams(dp(48),dp(48)));
        }
    }
    private void makeRoom(int source,int destination){
        for(int i=0;i<count;i++){if(i==source)continue;int next=i;
            if(destination>=0){if(i>source&&i<=destination)next=i-1;else if(i<source&&i>=destination)next=i+1;}
            View child=getChildAt(i+1),slot=getChildAt(next+1);
            child.animate().cancel();
            if(android.animation.ValueAnimator.areAnimatorsEnabled())child.animate().translationX(slot.getLeft()-child.getLeft()).translationY(slot.getTop()-child.getTop())
                    .setDuration(180).setInterpolator(new android.view.animation.PathInterpolator(.22f,.8f,.2f,1)).start();
            else{child.setTranslationX(slot.getLeft()-child.getLeft());child.setTranslationY(slot.getTop()-child.getTop());}
        }
    }
    private void resetPositions(){for(int i=1;i<getChildCount();i++){View child=getChildAt(i);child.animate().cancel();child.setTranslationX(0);child.setTranslationY(0);child.setTranslationZ(0);}}
    @Override protected void onDetachedFromWindow(){
        dragGeneration++;dragged=null;dropIndex=-1;center.setText(hint);resetPositions();
        if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(false);
        super.onDetachedFromWindow();
    }
    private int nearest(float x,float y){
        int closest=-1;double distance=dp(48);
        for(int i=0;i<count;i++){View child=getChildAt(i+1);double d=Math.hypot(x-(child.getLeft()+child.getRight())/2f,y-(child.getTop()+child.getBottom())/2f);if(d<distance){distance=d;closest=i;}}
        return closest;
    }
    private int dp(float n){return Math.round(n*getResources().getDisplayMetrics().density);}
    @Override protected void onMeasure(int w,int h){int width=MeasureSpec.getSize(w);int height=Math.min(width,dp(364));super.onMeasure(w,MeasureSpec.makeMeasureSpec(height,MeasureSpec.EXACTLY));}
    @Override protected void onLayout(boolean changed,int l,int t,int r,int b){
        float cx=getWidth()/2f,cy=getHeight()/2f,radius=Math.min(getWidth(),getHeight())/2f-dp(32);
        center.layout((int)cx-center.getMeasuredWidth()/2,(int)cy-center.getMeasuredHeight()/2,(int)cx+center.getMeasuredWidth()/2,(int)cy+center.getMeasuredHeight()/2);
        for(int i=1;i<getChildCount();i++){View child=getChildAt(i);double a=Math.toRadians(-90+(i-1)*30);int x=Math.round(cx+radius*(float)Math.cos(a)),y=Math.round(cy+radius*(float)Math.sin(a));int size=child.getMeasuredWidth();child.layout(x-size/2,y-size/2,x+size/2,y+size/2);}
    }
    @Override protected void onDraw(Canvas c){super.onDraw(c);paint.setColor(dark?0xff353b46:0xffe5eaf2);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(2));c.drawCircle(getWidth()/2f,getHeight()/2f,Math.min(getWidth(),getHeight())/2f-dp(32),paint);
        if(dropIndex>=0){View item=getChildAt(dropIndex+1);paint.setColor(0xff3482ff);c.drawCircle((item.getLeft()+item.getRight())/2f,(item.getTop()+item.getBottom())/2f,dp(28),paint);}
    }
}
