package com.focuspen.enhance.v2;

import android.content.Context;
import android.graphics.drawable.Drawable;

/** One thematic guide shared by the system settings and module manager. */
final class GuideContent {
    static final String[] TITLES={"认识触控笔","弧形菜单","笔尖预览","应用配置","手写输入","演示、圈注与鼠标","灵感速记与截图","书写声音"};
    static final String[] SUMMARIES={"找到顺手的按键与手势","常用功能，轻点即达","悬停定位，落笔书写","让不同应用各有一套操作","直接在输入框中书写","指示重点，圈画或控制光标","随时记下灵感","听见铅笔划过纸面"};
    static final int[] ORDER={0,6,1,2,4,3,5,7};
    record Step(String title,String text,String detail) { }
    static Context host(Context c)throws android.content.pm.PackageManager.NameNotFoundException{return c.getPackageName().equals("com.miui.securitycore")?c:c.createPackageContext("com.miui.securitycore",0);}
    static Step[] steps(Context c,int topic,boolean pro,ConfigSnapshot s){
        if(topic==4){
            Step[] lessons=steps(topic,pro,s);
            try{
                Context host=host(c);String[] keys={"hand_write","delete_word","select_word","insert_blank","new_line","insert_word"};
                for(int i=0;i<keys.length;i++){
                    try{
                        int title=host.getResources().getIdentifier("guide_"+keys[i]+"_title","string","com.miui.securitycore");
                        int tip=host.getResources().getIdentifier("guide_"+keys[i]+"_tip","string","com.miui.securitycore");
                        if(title!=0&&tip!=0)lessons[i]=new Step(host.getString(title),host.getString(tip),lessons[i].detail());
                    }catch(Exception ignored){}
                }
            }catch(Exception ignored){}
            return lessons;
        }
        return steps(topic,pro,s);
    }
    static String animation(int topic,int step,boolean pro){
        if(topic==4)return "stylus_guide_"+new String[]{"hand_write","delete_word","select_word","insert_blank","new_line","insert_word"}[Math.min(step,5)]+"_cn";
        if(topic==0&&pro)return new String[]{"touch_film_pinch_guide","touch_film_slide_guide","touch_film_double_tap_guide"}[Math.min(step,2)];
        if(topic==5)return pro?(step==0?"touch_film_laser_guide_step_1":null):switch(step){case 0->"laser";case 1->"laser_mark_first";case 3->"laser_page";default->null;};
        return null;
    }
    static Drawable image(Context c,int topic,boolean pro) {
        try {
            Context host=c.getPackageName().equals("com.miui.securitycore")?c:c.createPackageContext("com.miui.securitycore",0);
            return new GuideScene(host,topic,topic==5?2:0,pro);
        }catch(Exception unavailable){return null;}
    }
    static Step[] steps(int topic,boolean pro,ConfigSnapshot s) {
        return switch(topic) {
            case 0 -> pro?new Step[]{
                new Step("轻捏，唤出常用功能","在笔身感应区域轻捏，使用已设置的快捷动作。","轻捏开关及力度仍由系统手势设置控制。"),
                new Step("沿笔身滑动","上滑或下滑可执行对应设置；进入激光或圈注后，用它切换颜色。","演示中的换色不会同时执行普通滑动动作。"),
                new Step("双击笔身","在感应区域快速敲击两次，可退出激光或鼠标模式。","连续轻捏两次是另一种手势，可在手势设置中更改其功能。")
            }:new Step[]{
                new Step("三个按键，各有用途","速记键和截图键支持短按、长按与快速双击；焦点键用于激光、圈注和鼠标。","未启用激光或圈注时，快速双击焦点键切换鼠标模式。速记键为左键，截图键为右键，按住可拖动。"),
                new Step("短暂按住焦点键","未进入圈注时，短暂按住焦点键显示激光。","激光设为“按住”时松手关闭；设为“按住切换”时再次长按关闭。圈注中长按焦点键则用于绘画。"),
                new Step("快速连按焦点键","未开启激光或圈注时，快速连按两次焦点键进入鼠标模式。","再次连按两次退出；圈注中快速连按会清除笔迹，不会进入鼠标。")
            };
            case 1 -> new Step[]{
                new Step("悬停笔尖，唤出菜单","使用设为弧形菜单的按键或手势，在笔尖附近展开常用功能。","弹出时按笔尖朝向展开，靠近屏幕边缘时自动避让。"),
                new Step("轻点，即刻打开","轻点图标打开功能，轻点菜单外关闭。","菜单弹出后位置保持固定；可在“菜单内容”中添加项目、拖动排序。")};
            case 2 -> new Step[]{
                new Step("悬停，确认落点","笔尖悬停时，淡蓝色圆环随笔尖移动。中心透明，柔光向外扩散。","可在应用配置中单独开启或关闭笔尖预览。"),
                new Step("落笔，安心书写","笔尖接触屏幕时预览收起，抬笔回到感应范围后重新显示。","离开感应范围或息屏后，预览自动消失。")};
            case 3 -> new Step[]{
                new Step("给应用一套专属操作","添加应用，选择全局默认、自定义或已有模板。","未覆盖的项目继承全局设置。模板可在多个应用间复用。"),
                new Step("跟随当前窗口","分屏或小窗中，配置随你正在操作的窗口自动切换。","切换窗口后，重新按键即可使用对应操作。")};
            case 4 -> new Step[]{
                new Step("直接在输入框书写","开启系统随手写后，用笔在支持的输入框中书写，系统会将手写转换为文字。","随手写与息屏灵感速记是不同功能。"),
                new Step("删除文字","在要删除的文字上来回涂抹，即可删除对应内容。","使用系统随手写支持的输入框。"),
                new Step("选择文字","在文字上画横线或圈选，选中后继续编辑。","手势识别由系统手写输入提供。"),
                new Step("插入空格","在文字之间向上或向下划线插入空格，再次操作可删除空格。","具体支持范围以当前系统输入法为准。"),
                new Step("换行","在文字上划折线，将一行文字分为两行。","若应用不支持，仍可使用输入法的常规编辑功能。"),
                new Step("插入文字","在文字之间划折线，留出位置后继续书写。","具体支持范围以当前系统输入法为准。")};
            case 5 -> pro?new Step[]{
                new Step("用激光指示重点","将轻捏组合设为“开启虚拟激光”，使用该手势进入激光模式。","双击笔身可退出演示。"),
                new Step("圈画重点","进入激光模式后，轻捏并按住笔身描画，松开后回到激光。","已画出的笔迹保留到清除或退出；松手不会继续绘画。"),
                new Step("换一种颜色","上滑切换上一色，下滑切换下一色。","新产生的线条使用新颜色，已画出的笔迹保留原色。"),
                new Step("应用内移动页面","可在应用配置中给上、下滑动设置滚动动作；页面响应取决于当前应用。","演示画面为操作示例；实际效果取决于应用配置和当前应用。")
            }:new Step[]{
                new Step("用激光指示重点","长按焦点键显示激光；选择“按住”时松手关闭，选择“按住切换”时再次长按关闭。","激光模式中短按焦点键不会切换圈注；先关闭激光，再短按进入圈注。"),
                new Step("圈画重点","未进入激光时，短按焦点键进入圈注；长按焦点键绘画，松开停止，双击清除笔迹。","已画出的笔迹保留到清除或退出；松手不会继续绘画。"),
                new Step("换一种颜色","短按速记键切换上一色，短按截图键切换下一色。","新产生的线条使用新颜色，已画出的笔迹保留原色。"),
                new Step("翻页","将应用上／下动作绑定到按键，用于支持的演示应用。","演示画面为操作示例；实际效果取决于应用配置和当前应用。"),
                new Step("进入鼠标模式","先退出激光和圈注，再快速连按两次焦点键。","如果仍在圈注中，快速连按只会清除笔迹；先短按焦点键退出圈注。"),
                new Step("移动与点击","移动笔来控制光标；短按速记键为左键，短按截图键为右键。","鼠标模式下两侧键不执行平时设置的快捷动作。"),
                new Step("按住并拖动","按住速记键，移动笔拖动物体，松开结束拖动。","按住截图键可进行右键拖动；离开模式时按下的鼠标键会释放。"),
                new Step("退出鼠标模式","再次快速连按两次焦点键，结束鼠标会话。","笔归位或屏幕关闭也会退出；取下后需要重新连按进入。")};
            case 6 -> new Step[]{
                new Step("两种灵感记录方式","在弧形菜单中选择笔记或小米创作，打开对应的速记界面。","也可将常用的记录方式设为按键或手势动作。"),
                new Step("息屏点按，开始记录","开启息屏灵感速记并选择笔记或小米创作；息屏后用笔在屏幕空白处点一下，屏幕亮起并进入记录流程。","后续是否需要解锁，由系统与所选应用决定。")};
            default -> new Step[]{
                new Step("听见铅笔的声音","开启书写声音反馈后，落笔移动时通过内置扬声器播放铅笔声。","悬停和抬笔不发声。实际听感随书写速度与压力变化。"),
                new Step("调到舒适的音量","用“书写声音音量”调整轻重，整体音量随系统通知音量变化。","静音模式下不播放书写声音。")};
        };
    }
    private GuideContent() { }
}
