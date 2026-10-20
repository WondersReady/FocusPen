package com.focuspen.enhance.v2;

import android.content.Context;
import android.content.res.Configuration;
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
        value.setText(current+(key.endsWith(ConfigSpec.AUDIO_VOLUME)?"%":" 毫秒"));
        value.setEnabled((Boolean)XposedHelpers.callMethod(pref,"isEnabled"));
        SeekBar seek=root.findViewById(resource(c,"id","seekbar"));
        seek.setKeyProgressIncrement(key.endsWith(ConfigSpec.AUDIO_VOLUME)?1:10);
        seek.setStateDescription(value.getText());
    }
    private GuideNative(){}
}
