package com.focuspen.enhance.v2;

import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.WeakHashMap;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/** Native rows stay in the list while being dragged; nearby rows make room in place. */
final class MenuPickerViews {
    private static final class Row {
        final String id;
        int position;
        Row(String id,int position){this.id=id;this.position=position;}
    }
    private static final WeakHashMap<View,Row> rows=new WeakHashMap<>();
    private static long dragGeneration;
    private static Runnable cancelDrag;
    private static DropHandoff pendingDrop;
    private static ViewGroup animatedList;

    // MIUIX derives the card bounds from the first/last row's translated Y.
    // Draw that shared background using layout coordinates; restore row transforms
    // before RecyclerView draws its children, so only the rows move.
    static java.util.Map<View,Float> beginGroupDraw(ViewGroup list){
        if(list!=animatedList)return null;
        java.util.Map<View,Float> offsets=new java.util.IdentityHashMap<>();
        for(int i=0;i<list.getChildCount();i++){
            View child=list.getChildAt(i);
            if(rows.containsKey(child)&&child.getTranslationY()!=0){
                offsets.put(child,child.getTranslationY());child.setTranslationY(0);
            }
        }
        return offsets;
    }
    static void endGroupDraw(java.util.Map<View,Float> offsets){
        if(offsets!=null)for(var entry:offsets.entrySet())entry.getKey().setTranslationY(entry.getValue());
    }

    /** Only a committed drag has already animated to its destination. */
    static void beforeContentUpdate(View list){
        if(pendingDrop!=null&&pendingDrop.list==list)pendingDrop.prepare();
    }
    static void afterContentUpdate(View list){
        if(pendingDrop!=null&&pendingDrop.list==list)pendingDrop.layout();
    }
    private static final class DropHandoff implements ViewTreeObserver.OnPreDrawListener,View.OnAttachStateChangeListener {
        final ViewGroup list;final ArrayList<View> views;final Runnable finished;
        final Runnable timeout=this::finish;
        final ViewTreeObserver observer;
        Object animator;java.lang.reflect.Method setter;
        boolean prepared,ready,done;
        DropHandoff(ViewGroup list,ArrayList<View> views,Runnable finished){
            this.list=list;this.views=new ArrayList<>(views);this.finished=finished;
            observer=list.getViewTreeObserver();list.addOnAttachStateChangeListener(this);
            list.postDelayed(timeout,1500);
        }
        void prepare(){
            if(prepared||done)return;prepared=true;
            try{
                animator=list.getClass().getMethod("getItemAnimator").invoke(list);
                for(java.lang.reflect.Method method:list.getClass().getMethods())
                    if(method.getName().equals("setItemAnimator")&&method.getParameterCount()==1){setter=method;break;}
                if(setter!=null)setter.invoke(list,new Object[]{null});
            }catch(ReflectiveOperationException failure){android.util.Log.w("FocusPenSettings","Cannot pause menu reorder animator",failure);}
            // Adapter positions will now represent the drag's settled visual positions.
            reset(views);observer.addOnPreDrawListener(this);
        }
        void layout(){
            if(!prepared||done)return;
            // PreferenceGroup posts its adapter sort. Queue behind it before allowing
            // a draw, then restore the normal animator after that layout has finished.
            list.post(()->{if(!done){ready=true;list.requestLayout();list.invalidate();}});
        }
        public boolean onPreDraw(){if(!ready)return false;finish();return true;}
        void finish(){
            if(done)return;done=true;list.removeCallbacks(timeout);
            if(observer.isAlive())observer.removeOnPreDrawListener(this);
            list.removeOnAttachStateChangeListener(this);reset(views);
            if(setter!=null)try{setter.invoke(list,animator);}catch(ReflectiveOperationException failure){android.util.Log.w("FocusPenSettings","Cannot restore menu animator",failure);}
            if(pendingDrop==this)pendingDrop=null;finished.run();
            if(animatedList==list)animatedList=null;
        }
        public void onViewAttachedToWindow(View v){}
        public void onViewDetachedFromWindow(View v){finish();}
    }
    static void bind(View root,String id,Supplier<RingContent> content,BiConsumer<String,Integer> move){
        float density=root.getResources().getDisplayMetrics().density;
        root.animate().cancel();root.setTranslationY(0);root.setTranslationZ(0);
        if(!(root.findViewById(android.R.id.widget_frame) instanceof LinearLayout widget))return;
        View previous=widget.findViewWithTag("focuspen.reorder");if(previous!=null)widget.removeView(previous);
        root.setOnDragListener(null);root.setPressed(false);rows.remove(root);
        RingContent current=content.get();RingContent.Item item=current.find(id);if(item==null)return;
        Row binding=new Row(id,current.items.indexOf(item));rows.put(root,binding);
        TextView handle=new TextView(root.getContext());handle.setTag("focuspen.reorder");handle.setText("≡");handle.setTextSize(26);handle.setGravity(Gravity.CENTER);
        boolean dark=(root.getResources().getConfiguration().uiMode&48)==32;
        handle.setTextColor(dark?0xff959ba5:0xff858b96);handle.setContentDescription("拖动"+MenuEntry.label(root.getContext(),id)+"调整顺序");handle.setFocusable(true);
        widget.setOrientation(LinearLayout.HORIZONTAL);widget.setGravity(Gravity.CENTER_VERTICAL);
        widget.addView(handle,new LinearLayout.LayoutParams(Math.round(48*density),Math.round(52*density)));
        class DragGesture implements View.OnTouchListener,View.OnAttachStateChangeListener {
            float startY,pointerY,grabY;boolean moving,tracking,settling;int position,destination,itemCount;Drawable background;long generation;
            ViewGroup list;long lastFrame;float scrollRemainder;
            final int[] location=new int[2];
            final ArrayList<View> siblings=new ArrayList<>();
            final java.util.Map<View,Float> shifts=new java.util.IdentityHashMap<>();
            final Runnable cancellation=this::cancel;
            final Runnable scrollFrame=this::scroll;
            public boolean onTouch(View v,MotionEvent e){
                if(e.getActionMasked()==MotionEvent.ACTION_DOWN){
                    if(pendingDrop!=null||settling||root.getParent()==null||rows.get(root)!=binding)return true;
                    RingContent latest=content.get();syncPositions(latest);
                    if(binding.position<0)return true;
                    if(cancelDrag!=null)cancelDrag.run();cancelDrag=cancellation;
                    if(!(root.getParent() instanceof ViewGroup parent))return true;
                    position=binding.position;itemCount=latest.items.size();
                    list=parent;tracking=true;generation=++dragGeneration;startY=pointerY=e.getRawY();moving=false;destination=position;
                    root.getLocationOnScreen(location);grabY=pointerY-location[1];list.requestDisallowInterceptTouchEvent(true);
                    siblings.clear();shifts.clear();collect();return true;
                }
                if(!tracking||generation!=dragGeneration||rows.get(root)!=binding)return true;
                if(e.getActionMasked()==MotionEvent.ACTION_POINTER_DOWN){cancel();return true;}
                if(e.getActionMasked()==MotionEvent.ACTION_MOVE){
                    pointerY=e.getRawY();float delta=pointerY-startY;
                    if(!moving&&Math.abs(delta)>android.view.ViewConfiguration.get(v.getContext()).getScaledTouchSlop()){
                        moving=true;animatedList=list;background=root.getBackground();GradientDrawable lifted=new GradientDrawable();lifted.setColor(dark?0xff252527:0xffffffff);lifted.setCornerRadius(16*density);root.setBackground(lifted);root.setTranslationZ(8*density);
                        lastFrame=android.os.SystemClock.uptimeMillis();scrollRemainder=0;root.postOnAnimation(scrollFrame);
                    }
                    if(moving){
                        updatePosition();
                    }return true;
                }
                if(e.getActionMasked()==MotionEvent.ACTION_UP||e.getActionMasked()==MotionEvent.ACTION_CANCEL){
                    // The release can carry a newer position than the last MOVE frame.
                    if(moving&&e.getActionMasked()==MotionEvent.ACTION_UP){pointerY=e.getRawY();updatePosition();}
                    tracking=false;root.removeCallbacks(scrollFrame);if(root.getParent()!=null)root.getParent().requestDisallowInterceptTouchEvent(false);
                    boolean save=moving&&e.getActionMasked()==MotionEvent.ACTION_UP&&destination!=position;
                    if(moving){root.setBackground(background);root.setTranslationZ(0);}
                    if(save){
                        settling=true;
                        // Retain the settled visual positions until the native adapter lays out
                        // the same row objects in their new order. No activity/fragment replacement.
                        float offset=0;for(View child:siblings){Row info=rows.get(child);if(info!=null&&info.position==destination)
                            offset=(destination>position?child.getBottom()-root.getHeight():child.getTop())-root.getTop();}
                        root.animate().translationY(offset).setDuration(100).withEndAction(()->{
                            if(generation!=dragGeneration||rows.get(root)!=binding||!root.isAttachedToWindow())return;
                            pendingDrop=new DropHandoff(list,siblings,()->{settling=false;if(cancelDrag==cancellation)cancelDrag=null;});
                            move.accept(id,destination);
                        }).start();
                    }else {
                        for(View child:siblings){
                            child.animate().translationY(0).setDuration(140);
                            if(child==root)child.animate().withEndAction(()->{if(generation==dragGeneration&&animatedList==list)animatedList=null;});
                            child.animate().start();
                        }
                        if(cancelDrag==cancellation)cancelDrag=null;
                    }
                    moving=false;return true;
                }return true;
            }
            private void collect(){
                // Scroll can attach new native rows. Never keep recycled rows in the drag set.
                siblings.removeIf(child->{Row info=rows.get(child);return child.getParent()!=list||info==null||info.position<0||info.position>=itemCount;});
                shifts.keySet().retainAll(siblings);
                for(int i=0;i<list.getChildCount();i++){View child=list.getChildAt(i);Row info=rows.get(child);
                    if(info!=null&&info.position>=0&&info.position<itemCount&&!siblings.contains(child))siblings.add(child);}
            }
            private void updatePosition(){
                collect();list.getLocationOnScreen(location);
                float top=pointerY-location[1]-grabY;
                float low=root.getTop(),high=root.getBottom()-root.getHeight();
                for(View child:siblings){low=Math.min(low,child.getTop());high=Math.max(high,child.getBottom()-root.getHeight());}
                // Keep the actual row visible while its layout position scrolls away underneath it.
                low=Math.max(low,list.getPaddingTop());high=Math.min(high,list.getHeight()-list.getPaddingBottom()-root.getHeight());
                top=Math.max(low,Math.min(Math.max(low,high),top));root.setTranslationY(top-root.getTop());
                float center=top+root.getHeight()/2f;int next=position;
                for(View child:siblings){Row info=rows.get(child);if(info==null||child==root)continue;float other=(child.getTop()+child.getBottom())/2f;
                    if(info.position>position&&center>=other-root.getHeight()/2f)next=Math.max(next,info.position);
                    if(info.position<position&&center<=other+root.getHeight()/2f)next=Math.min(next,info.position);
                }
                destination=next;
                for(View child:siblings){Row info=rows.get(child);if(child==root||info==null)continue;
                    float shift=info.position>position&&info.position<=destination?-root.getHeight():info.position<position&&info.position>=destination?root.getHeight():0;
                    Float previous=shifts.put(child,shift);
                    if(previous==null||previous!=shift)child.animate().translationY(shift).setDuration(140).start();
                }
            }
            private void scroll(){
                if(!tracking||!moving||generation!=dragGeneration||rows.get(root)!=binding||root.getParent()!=list)return;
                long now=android.os.SystemClock.uptimeMillis();float seconds=Math.min(32,Math.max(0,now-lastFrame))/1000f;lastFrame=now;
                list.getLocationOnScreen(location);float y=pointerY-location[1];
                float edge=Math.min(64*density,(list.getHeight()-list.getPaddingTop()-list.getPaddingBottom())/3f);
                float speed=0;
                if(edge>0&&y<list.getPaddingTop()+edge)speed=-Math.min(1,(list.getPaddingTop()+edge-y)/edge);
                else if(edge>0&&y>list.getHeight()-list.getPaddingBottom()-edge)speed=Math.min(1,(y-list.getHeight()+list.getPaddingBottom()+edge)/edge);
                // A stationary finger keeps scrolling; frame time determines speed, not refresh rate.
                float distance=speed*Math.abs(speed)*600*density*seconds;
                if(speed==0||!list.canScrollVertically(speed<0?-1:1))scrollRemainder=0;
                else {
                    collect();float limit=Float.MAX_VALUE;
                    for(View child:siblings){Row info=rows.get(child);if(info==null)continue;
                        if(speed<0&&info.position==0)limit=Math.max(0,list.getPaddingTop()-child.getTop());
                        if(speed>0&&info.position==itemCount-1)limit=Math.max(0,child.getBottom()-list.getHeight()+list.getPaddingBottom());
                    }
                    distance=Math.copySign(Math.min(Math.abs(distance+scrollRemainder),limit),speed);
                    int pixels=(int)distance;scrollRemainder=distance-pixels;
                    if(pixels!=0)list.scrollBy(0,pixels);
                }
                if(!tracking||generation!=dragGeneration||rows.get(root)!=binding||root.getParent()!=list)return;
                updatePosition();root.postOnAnimation(scrollFrame);
            }
            private void cancel(){
                tracking=false;settling=false;
                root.removeCallbacks(scrollFrame);
                if(cancelDrag==cancellation)cancelDrag=null;
                if(generation!=dragGeneration)return;
                ++dragGeneration;
                if(moving)root.setBackground(background);
                moving=false;reset(siblings);
                if(animatedList==list)animatedList=null;
                if(root.getParent()!=null)root.getParent().requestDisallowInterceptTouchEvent(false);
            }
            public void onViewAttachedToWindow(View v){}
            public void onViewDetachedFromWindow(View v){if(!settling)cancel();}
        }
        DragGesture gesture=new DragGesture();handle.setOnTouchListener(gesture);handle.addOnAttachStateChangeListener(gesture);
        handle.setAccessibilityDelegate(new View.AccessibilityDelegate(){
            public void onInitializeAccessibilityNodeInfo(View v,android.view.accessibility.AccessibilityNodeInfo info){super.onInitializeAccessibilityNodeInfo(v,info);
                RingContent latest=content.get();syncPositions(latest);int position=binding.position;
                if(position>0)info.addAction(new android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD,"向前移动"));
                if(position>=0&&position<latest.items.size()-1)info.addAction(new android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD,"向后移动"));}
            public boolean performAccessibilityAction(View v,int action,android.os.Bundle args){
                RingContent latest=content.get();syncPositions(latest);int position=binding.position;
                if(action==android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD&&position>0){move.accept(id,position-1);return true;}
                if(action==android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD&&position>=0&&position<latest.items.size()-1){move.accept(id,position+1);return true;}return super.performAccessibilityAction(v,action,args);
            }
        });
    }
    private static void syncPositions(RingContent content){
        for(Row row:rows.values()){
            RingContent.Item item=content.find(row.id);
            row.position=item==null?-1:content.items.indexOf(item);
        }
    }
    private static void reset(ArrayList<View> views){for(View v:views){v.animate().cancel();v.setTranslationY(0);v.setTranslationZ(0);}}
}
