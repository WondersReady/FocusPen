package com.focuspen.enhance.v2;

import android.app.Dialog;
import android.content.Context;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import de.robv.android.xposed.XposedHelpers;

/** The host's guide card, pager, page dots and buttons, with unified lessons. */
final class GuideDemo extends Dialog implements ConfigClient.Listener {
    static final String[] TITLES=GuideContent.TITLES, SUMMARIES=GuideContent.SUMMARIES;
    private final ConfigClient client;
    private final int topic;
    private final boolean pro;
    private final ArrayList<GuideMedia> pages=new ArrayList<>();
    private int step,scrollState;
    private TextView title,body,next;
    private View pager,indicator,card,root;
    private boolean closing;
    static GuideDemo show(Context c,ConfigClient client,int topic,boolean pro){
        GuideDemo d=new GuideDemo(c,client,topic,pro);d.show();return d;
    }
    private GuideDemo(Context c,ConfigClient client,int topic,boolean pro){
        super(c);this.client=client;this.topic=topic;this.pro=pro;
    }
    private int id(String name){return GuideNative.resource(getContext(),"id",name);}
    @Override protected void onCreate(Bundle saved){
        super.onCreate(saved);requestWindowFeature(Window.FEATURE_NO_TITLE);
        Context c=getContext();
        root=LayoutInflater.from(c).inflate(GuideNative.resource(c,"layout","virtual_laser_guide_layout"),null);
        title=root.findViewById(id("title_tv"));body=root.findViewById(id("tip_tv"));
        next=root.findViewById(id("continue_bt"));pager=root.findViewById(id("guide_animation"));
        indicator=root.findViewById(id("instruction_indicator"));card=root.findViewById(id("guide_container"));
        GuideContent.Step[] lessons=GuideContent.steps(c,topic,pro,client.get());
        ArrayList<View> views=new ArrayList<>();
        for(int i=0;i<lessons.length;i++){GuideMedia media=new GuideMedia(c);media.prepare(topic,i,pro);pages.add(media);views.add(media);}
        try {
            Object adapter=c.getClassLoader().loadClass("t2.N").getConstructor(ArrayList.class,ArrayList.class,int.class)
                    .newInstance(views,new ArrayList<>(),2);
            XposedHelpers.callMethod(pager,"setAdapter",adapter);
            java.lang.reflect.Method listener=java.util.Arrays.stream(pager.getClass().getMethods())
                    .filter(m->m.getName().equals("setOnPageChangeListener")).findFirst().orElseThrow();
            Class<?> callback=listener.getParameterTypes()[0];
            listener.invoke(pager,Proxy.newProxyInstance(c.getClassLoader(),new Class[]{callback},(o,m,args)->{
                if(m.getName().equals("onPageSelected")){step=(Integer)args[0];render(client.get());}
                if(m.getName().equals("onPageScrollStateChanged")){scrollState=(Integer)args[0];next.setEnabled(scrollState==0);syncMedia();}
                if(m.getName().equals("hashCode"))return System.identityHashCode(o);
                if(m.getName().equals("equals"))return o==args[0];
                if(m.getName().equals("toString"))return "FocusPenGuidePages";
                return null;
            }));
            XposedHelpers.callMethod(indicator,"a",lessons.length);
        }catch(Exception e){throw new IllegalStateException("Official guide pager unavailable",e);}
        next.setOnClickListener(v->{if(step==pages.size()-1)closeAnimated();else XposedHelpers.callMethod(pager,"setCurrentItem",step+1);});
        root.findViewById(id("skip_bt")).setOnClickListener(v->closeAnimated());
        root.setOnClickListener(v->closeAnimated());card.setOnClickListener(v->{});
        setContentView(root);setCanceledOnTouchOutside(true);
        root.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->adapt());
    }
    private void adapt(){
        if(root.getWidth()==0)return;
        int margin=px(20),width=Math.min(dim("laser_guide_card_width"),root.getWidth()-2*margin);
        ViewGroup.LayoutParams cp=card.getLayoutParams();if(cp.width!=width){cp.width=width;card.setLayoutParams(cp);}
        LinearLayout buttons=(LinearLayout)next.getParent();
        View spacer=root.findViewById(id("button_space"));
        LinearLayout.LayoutParams space=(LinearLayout.LayoutParams)spacer.getLayoutParams();
        if(space.weight!=1){space.width=px(16);space.weight=1;spacer.setLayoutParams(space);}
        for(int i=0;i<buttons.getChildCount();i++)if(buttons.getChildAt(i) instanceof Button button){
            LinearLayout.LayoutParams bp=(LinearLayout.LayoutParams)button.getLayoutParams();
            int bw=Math.min(dim("laser_guide_button_min_width"),(width-buttons.getPaddingLeft()-buttons.getPaddingRight()-px(16))/2);
            if(bp.width!=bw){bp.width=bw;button.setMinWidth(0);button.setLayoutParams(bp);}
        }
        int chrome=card.getHeight()-pager.getHeight();
        int height=Math.min(dim("laser_guide_animation_height"),Math.max(px(80),root.getHeight()-2*margin-chrome));
        ViewGroup.LayoutParams pp=pager.getLayoutParams();if(pp.height!=height){pp.height=height;pager.setLayoutParams(pp);}
    }
    @Override protected void onStart(){
        super.onStart();closing=false;root.setAlpha(1);card.setScaleX(1);card.setScaleY(1);card.setTranslationY(0);
        Window w=getWindow();if(w!=null){w.setBackgroundDrawableResource(android.R.color.transparent);
            w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);w.setLayout(-1,-1);}
        if(android.animation.ValueAnimator.areAnimatorsEnabled()){
            root.setAlpha(0);root.animate().alpha(1).setDuration(220).start();
            card.setScaleX(.96f);card.setScaleY(.96f);card.setTranslationY(px(12));
            card.animate().scaleX(1).scaleY(1).translationY(0).setDuration(300)
                    .setInterpolator(new android.view.animation.PathInterpolator(.22f,.8f,.2f,1)).start();
        }
        client.add(this);
    }
    @Override protected void onStop(){client.remove(this);for(GuideMedia media:pages)media.stop();super.onStop();}
    @Override public void dismiss(){
        // Lifecycle teardown must finish synchronously: the Activity can remove
        // its windows before a delayed animation callback gets a chance to run.
        if(root!=null){root.animate().withEndAction(null);root.animate().cancel();}
        if(card!=null)card.animate().cancel();
        if(title!=null)title.animate().cancel();if(body!=null)body.animate().cancel();
        super.dismiss();
    }
    private void closeAnimated(){
        if(root==null||!isShowing()){dismiss();return;}
        if(closing)return;closing=true;
        for(GuideMedia media:pages)media.stop();
        if(!android.animation.ValueAnimator.areAnimatorsEnabled()){dismiss();return;}
        root.animate().cancel();
        card.animate().cancel();card.animate().scaleX(.98f).scaleY(.98f).translationY(px(8)).setDuration(160).start();
        root.animate().alpha(0).setDuration(170)
                .setInterpolator(new android.view.animation.PathInterpolator(.5f,0,.6f,1))
                .withEndAction(this::dismiss).start();
    }
    @Override public void changed(ConfigSnapshot s){if(title!=null)render(s);}
    private void render(ConfigSnapshot s){
        GuideContent.Step lesson=GuideContent.steps(getContext(),topic,pro,s)[step];
        updateText(title,lesson.title());updateText(body,lesson.text()+"\n"+lesson.detail());
        XposedHelpers.callMethod(indicator,"setCheckItem",step);
        next.setText(step==pages.size()-1?"完成":"继续");
        syncMedia();
        pager.setContentDescription(TITLES[topic]+"，第 "+(step+1)+" 页，共 "+pages.size()+" 页。左右滑动翻页");
        root.post(this::adapt);
    }
    private void syncMedia(){
        for(int i=0;i<pages.size();i++){
            if(i==step&&scrollState==0&&!closing)pages.get(i).show(topic,step,pro);else pages.get(i).stop();
        }
    }
    private void updateText(TextView view,String text){
        if(text.contentEquals(view.getText()))return;
        boolean animate=view.length()>0&&android.animation.ValueAnimator.areAnimatorsEnabled();
        view.animate().cancel();view.setText(text);
        if(animate){view.setAlpha(0);view.setTranslationY(px(4));view.animate().alpha(1).translationY(0).setDuration(180)
                .setInterpolator(new android.view.animation.PathInterpolator(.22f,.8f,.2f,1)).start();}
        else{view.setAlpha(1);view.setTranslationY(0);}
    }
    private int dim(String name){return getContext().getResources().getDimensionPixelSize(GuideNative.resource(getContext(),"dimen",name));}
    private int px(int n){return Math.round(n*getContext().getResources().getDisplayMetrics().density);}
}
