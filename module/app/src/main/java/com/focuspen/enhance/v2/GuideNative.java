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
    private GuideNative(){}
}
