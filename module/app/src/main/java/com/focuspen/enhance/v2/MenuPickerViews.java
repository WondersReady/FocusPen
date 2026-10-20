package com.focuspen.enhance.v2;

import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.WeakHashMap;
import java.util.function.BiConsumer;

/** Native rows stay in the list while being dragged; nearby rows make room in place. */
final class MenuPickerViews {
    private record Row(String id,int position){}
    private static final WeakHashMap<View,Row> rows=new WeakHashMap<>();
    private static long dragGeneration;
    private static Runnable cancelDrag;
    static void bind(View root,String id,RingContent content,BiConsumer<String,Integer> move){
        float density=root.getResources().getDisplayMetrics().density;
        root.animate().cancel();root.setTranslationY(0);root.setTranslationZ(0);
        ImageView icon=root.findViewById(android.R.id.icon);
        if(icon!=null){ViewGroup.LayoutParams p=icon.getLayoutParams();p.width=p.height=Math.round(36*density);icon.setLayoutParams(p);icon.setScaleType(ImageView.ScaleType.FIT_CENTER);}
        if(!(root.findViewById(android.R.id.widget_frame) instanceof LinearLayout widget))return;
        View previous=widget.findViewWithTag("focuspen.reorder");if(previous!=null)widget.removeView(previous);
        root.setOnDragListener(null);root.setPressed(false);rows.remove(root);
        RingContent.Item item=content.find(id);if(item==null)return;
        int position=content.items.indexOf(item);Row binding=new Row(id,position);rows.put(root,binding);
        TextView handle=new TextView(root.getContext());handle.setTag("focuspen.reorder");handle.setText("≡");handle.setTextSize(26);handle.setGravity(Gravity.CENTER);
        boolean dark=(root.getResources().getConfiguration().uiMode&48)==32;
        handle.setTextColor(dark?0xff959ba5:0xff858b96);handle.setContentDescription("拖动"+MenuEntry.label(root.getContext(),id)+"调整顺序");handle.setFocusable(true);
        widget.setOrientation(LinearLayout.HORIZONTAL);widget.setGravity(Gravity.CENTER_VERTICAL);
        widget.addView(handle,new LinearLayout.LayoutParams(Math.round(48*density),Math.round(52*density)));
        class DragGesture implements View.OnTouchListener,View.OnAttachStateChangeListener {
            float startY;boolean moving,tracking,settling;int destination;Drawable background;long generation;
            final ArrayList<View> siblings=new ArrayList<>();
            final Runnable cancellation=this::cancel;
            public boolean onTouch(View v,MotionEvent e){
                if(e.getActionMasked()==MotionEvent.ACTION_DOWN){
                    if(settling||root.getParent()==null||rows.get(root)!=binding)return true;
                    if(cancelDrag!=null)cancelDrag.run();cancelDrag=cancellation;
                    tracking=true;generation=++dragGeneration;startY=e.getRawY();moving=false;destination=position;root.getParent().requestDisallowInterceptTouchEvent(true);
                    siblings.clear();if(root.getParent() instanceof ViewGroup list)for(int i=0;i<list.getChildCount();i++){View child=list.getChildAt(i);if(rows.containsKey(child))siblings.add(child);}return true;
                }
                if(!tracking||generation!=dragGeneration||rows.get(root)!=binding)return true;
                if(e.getActionMasked()==MotionEvent.ACTION_POINTER_DOWN){cancel();return true;}
                if(e.getActionMasked()==MotionEvent.ACTION_MOVE){
                    float delta=e.getRawY()-startY;
                    if(!moving&&Math.abs(delta)>android.view.ViewConfiguration.get(v.getContext()).getScaledTouchSlop()){
                        moving=true;background=root.getBackground();GradientDrawable lifted=new GradientDrawable();lifted.setColor(dark?0xff252527:0xffffffff);lifted.setCornerRadius(16*density);root.setBackground(lifted);root.setTranslationZ(8*density);
                    }
                    if(moving){
                        float low=0,high=0;for(View child:siblings){low=Math.min(low,child.getTop()-root.getTop());high=Math.max(high,child.getBottom()-root.getBottom());}
                        delta=Math.max(low,Math.min(high,delta));root.setTranslationY(delta);
                        float center=(root.getTop()+root.getBottom())/2f+delta;int next=position;
                        for(View child:siblings){Row info=rows.get(child);if(info==null||child==root)continue;float other=(child.getTop()+child.getBottom())/2f;
                            if(info.position>position&&center>=other-root.getHeight()/2f)next=Math.max(next,info.position);
                            if(info.position<position&&center<=other+root.getHeight()/2f)next=Math.min(next,info.position);
                        }
                        if(next!=destination){destination=next;for(View child:siblings){Row info=rows.get(child);if(child==root||info==null)continue;
                            float shift=info.position>position&&info.position<=destination?-root.getHeight():info.position<position&&info.position>=destination?root.getHeight():0;
                            child.animate().translationY(shift).setDuration(140).start();}}
                    }return true;
                }
                if(e.getActionMasked()==MotionEvent.ACTION_UP||e.getActionMasked()==MotionEvent.ACTION_CANCEL){
                    tracking=false;if(root.getParent()!=null)root.getParent().requestDisallowInterceptTouchEvent(false);
                    boolean save=moving&&e.getActionMasked()==MotionEvent.ACTION_UP&&destination!=position;
                    if(moving){root.setBackground(background);root.setTranslationZ(0);}
                    if(save){
                        settling=true;
                        // Retain the settled visual positions until the native adapter lays out
                        // the same row objects in their new order. No activity/fragment replacement.
                        float offset=0;for(View child:siblings){Row info=rows.get(child);if(info!=null&&info.position==destination)offset=child.getTop()-root.getTop();}
                        root.animate().translationY(offset).setDuration(100).withEndAction(()->{
                            if(generation!=dragGeneration||rows.get(root)!=binding||!root.isAttachedToWindow())return;
                            move.accept(id,destination);
                            root.postDelayed(()->{if(generation==dragGeneration){reset(siblings);settling=false;}},180);
                        }).start();
                    }else for(View child:siblings)child.animate().translationY(0).setDuration(140).start();
                    moving=false;return true;
                }return true;
            }
            private void cancel(){
                tracking=false;settling=false;
                if(cancelDrag==cancellation)cancelDrag=null;
                if(generation!=dragGeneration)return;
                ++dragGeneration;
                if(moving)root.setBackground(background);
                moving=false;reset(siblings);
                if(root.getParent()!=null)root.getParent().requestDisallowInterceptTouchEvent(false);
            }
            public void onViewAttachedToWindow(View v){}
            public void onViewDetachedFromWindow(View v){cancel();}
        }
        DragGesture gesture=new DragGesture();handle.setOnTouchListener(gesture);handle.addOnAttachStateChangeListener(gesture);
        handle.setAccessibilityDelegate(new View.AccessibilityDelegate(){
            public void onInitializeAccessibilityNodeInfo(View v,android.view.accessibility.AccessibilityNodeInfo info){super.onInitializeAccessibilityNodeInfo(v,info);
                if(position>0)info.addAction(new android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD,"向前移动"));
                if(position<content.items.size()-1)info.addAction(new android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD,"向后移动"));}
            public boolean performAccessibilityAction(View v,int action,android.os.Bundle args){
                if(action==android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD&&position>0){move.accept(id,position-1);return true;}
                if(action==android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD&&position<content.items.size()-1){move.accept(id,position+1);return true;}return super.performAccessibilityAction(v,action,args);
            }
        });
    }
    private static void reset(ArrayList<View> views){for(View v:views){v.animate().cancel();v.setTranslationY(0);v.setTranslationZ(0);}}
}
