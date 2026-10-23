package com.focuspen.enhance.v2;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import de.robv.android.xposed.XposedHelpers;

/** Binds module content into this host version's official tutorial and sound layouts. */
final class GuideNative {
    static int resource(Context c,String type,String name) {
        int id=c.getResources().getIdentifier(name,type,"com.miui.securitycore");
        if(id==0)throw new IllegalStateException("Missing host resource: "+name);
        return id;
    }
    static void card(Object pref,Object holder,int topic) {
        View root=(View)XposedHelpers.getObjectField(holder,"itemView");Context c=root.getContext();
        TextView title=root.findViewById(resource(c,"id","title"));
        TextView summary=root.findViewById(resource(c,"id","summary"));
        ImageView picture=root.findViewById(resource(c,"id","image"));
        title.setText(GuideDemo.TITLES[topic]);summary.setText(GuideDemo.SUMMARIES[topic]);
        picture.setImageDrawable(GuideContent.image(c,topic,SettingsHooks.previewPro(c)));picture.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        root.setContentDescription(GuideDemo.TITLES[topic]+"，"+GuideDemo.SUMMARIES[topic]);
    }
    static String officialTitle(int topic){return new String[]{"手写输入","演示遥控","Pro 基础手势","Pro 演示遥控"}[topic-4];}
    static String officialSummary(int topic){return new String[]{"在输入框中直接书写","激光指示、圈画与翻页","基础手势示范，实际动作以当前设置为准","了解 Pro 的激光与演示操作"}[topic-4];}
    static void slider(Object pref,Object holder) {
        View root=(View)XposedHelpers.getObjectField(holder,"itemView");Context c=root.getContext();
        TextView title=root.findViewById(android.R.id.title);
        if(title==null || !(title.getParent() instanceof LinearLayout row))return;
        TextView value=row.findViewWithTag("focuspen.slider.value");
        if(value==null){
            value=new TextView(c);value.setTag("focuspen.slider.value");value.setTextSize(15);
            value.setTextColor(title.getTextColors());value.setAlpha(.55f);
            title.setLayoutParams(new LinearLayout.LayoutParams(0,-2,1));title.setSingleLine(false);
            row.addView(value,new LinearLayout.LayoutParams(-2,-2));
        }
        String key=(String)XposedHelpers.callMethod(pref,"getKey");
        int current=XposedHelpers.getIntField(pref,"a");
        boolean timing=key.endsWith(ConfigSpec.TIMING);
        value.setText(timing?new String[]{"快速","标准","柔和"}[Math.max(0,Math.min(2,current))]
                :current+(key.endsWith(ConfigSpec.AUDIO_VOLUME)?"%":" 毫秒"));
        value.setEnabled((Boolean)XposedHelpers.callMethod(pref,"isEnabled"));
        SeekBar seek=root.findViewById(resource(c,"id","seekbar"));
        seek.setKeyProgressIncrement(timing||key.endsWith(ConfigSpec.AUDIO_VOLUME)?1:10);
        seek.setStateDescription(value.getText());
        if(timing&&seek.getParent() instanceof LinearLayout container){
            seek.setTickMark(null);
            StopTicks ticks=container.findViewWithTag("focuspen.slider.ticks");
            if(ticks==null){
                ticks=new StopTicks(c);ticks.setTag("focuspen.slider.ticks");
                ticks.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                container.addView(ticks,container.indexOfChild(seek)+1,
                        new LinearLayout.LayoutParams(-1,Math.round(8*c.getResources().getDisplayMetrics().density)));
            }
            ticks.setStops(seek,current,title.getCurrentTextColor());
            ticks.setVisibility(View.VISIBLE);
            LinearLayout labels=container.findViewWithTag("focuspen.slider.stops");
            if(labels==null){
                labels=new LinearLayout(c);labels.setTag("focuspen.slider.stops");
                labels.setOrientation(LinearLayout.HORIZONTAL);
                for(int i=0;i<3;i++){
                    TextView label=new TextView(c);label.setText(new String[]{"快速","标准","柔和"}[i]);
                    label.setTextSize(12);label.setGravity(i==0?android.view.Gravity.START:i==1?android.view.Gravity.CENTER:android.view.Gravity.END);
                    labels.addView(label,new LinearLayout.LayoutParams(0,-2,1));
                }
                container.addView(labels,container.indexOfChild(ticks)+1,new LinearLayout.LayoutParams(-1,-2));
            }
            labels.setPadding(seek.getPaddingLeft(),0,seek.getPaddingRight(),0);
            labels.setVisibility(View.VISIBLE);
            for(int i=0;i<3;i++){
                TextView label=(TextView)labels.getChildAt(i);
                label.setTextColor(i==current?0xff3482ff:title.getCurrentTextColor());
                label.setAlpha(i==current?1f:.55f);
            }
        }else{
            View labels=root.findViewWithTag("focuspen.slider.stops");if(labels!=null)labels.setVisibility(View.GONE);
            View ticks=root.findViewWithTag("focuspen.slider.ticks");if(ticks!=null)ticks.setVisibility(View.GONE);
            seek.setTickMark(null);
        }
    }
    private static final class StopTicks extends View {
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float density;
        private SeekBar seek;
        private int selected,color;
        StopTicks(Context c){super(c);density=c.getResources().getDisplayMetrics().density;}
        void setStops(SeekBar seek,int selected,int color){
            this.seek=seek;this.selected=selected;this.color=color;invalidate();
        }
        @Override protected void onDraw(Canvas canvas){
            super.onDraw(canvas);
            float start=seek.getLeft()-getLeft()+seek.getPaddingLeft();
            float end=seek.getRight()-getLeft()-seek.getPaddingRight();
            float half=density,y=(getHeight()-6*density)/2;
            for(int i=0;i<3;i++){
                float x=start+(end-start)*i/2;
                paint.setColor(i==selected?0xff3482ff:color);
                paint.setAlpha(i==selected?255:140);
                canvas.drawRoundRect(x-half,y,x+half,y+6*density,half,half,paint);
            }
        }
    }
    private GuideNative(){}
}
