package com.focuspen.enhance.v2;

import android.content.Context;
import android.graphics.drawable.Drawable;

/** One thematic guide shared by the system settings and module manager. */
final class GuideContent {
    static final String[] TITLES={"认识触控笔","弧形菜单","笔尖预览","应用配置","手写输入","演示与圈注","灵感速记与截图","书写声音"};
    static final String[] SUMMARIES={"找到顺手的按键与手势","常用功能，轻点即达","悬停定位，落笔书写","让不同应用各有一套操作","直接在输入框中书写","指示重点，用颜色记录","随时记下灵感","听见铅笔划过纸面"};
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
        if(topic==5)return pro?(step<2?"touch_film_laser_guide_step_"+(step+1):null):switch(step){case 0->"laser";case 1->"laser_mark_first";case 3->"laser_page";case 4->"laser_remote";default->null;};
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
                new Step("短暂按住","短暂按住速记键或截图键，","打开为这个按键设置的功能。"),
                new Step("快速连按","快速连按两次速记键或截图键，","打开为快速双击设置的功能。")
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
                new Step("插入空格","在文字之间使用插入空格手势。","具体支持范围以当前系统输入法为准。"),
                new Step("换行","在需要换行的位置使用换行手势。","若应用不支持，仍可使用输入法的常规编辑功能。"),
                new Step("插入文字","在需要插入的位置使用插入手势，再开始书写。","具体支持范围以当前系统输入法为准。")};
            case 5 -> new Step[]{
                new Step("用激光指示重点",pro?"将轻捏组合设为“开启虚拟激光”，使用该手势进入演示。":"按住焦点键显示激光；若选择“按住切换”，长按一次开启，再长按一次关闭。",pro?"双击笔身退出演示。":"短按焦点键可切换到画笔圈注。"),
                new Step("圈画重点",pro?"使用官方演示手势进行圈注。":"圈注模式中，按住焦点键绘画，松开停止；双击清除笔迹。","激光的保持开启设置不会让松开后的画笔继续绘画。"),
                new Step("换一种颜色",pro?"上滑切换上一色，下滑切换下一色。":"短按速记键切换上一色，短按截图键切换下一色。","新产生的线条使用新颜色，已画出的笔迹保留原色。"),
                new Step("翻页","普通模式可将应用上／下动作绑定到按键，用于支持的演示应用。","演示画面为操作示例；实际翻页需要相应的应用配置。"),
                new Step("遥控拍照",pro?"相机中继续使用系统支持的遥控手势。":"打开相机后，使用焦点键进行官方遥控拍照或录像。","相机中的官方输入优先，不进入模块鼠标模式。")};
            case 6 -> new Step[]{
                new Step("两种灵感记录方式","在弧形菜单中选择笔记或小米创作，打开对应的速记界面。","也可将常用的记录方式设为按键或手势动作。"),
                new Step("息屏，也能开始记录","开启息屏灵感速记，选择打开笔记或小米创作，再在息屏时用笔点击屏幕。","独立开关，无需先绑定按键。"),
                new Step("锁屏快捷入口",pro?"锁屏圆环提供笔记、小米创作和区域截屏。":"短暂按住速记键打开锁屏圆环，短暂按住截图键进行区域截屏。",pro?"后续页面是否要求解锁，由系统与目标应用决定。":"圆环提供笔记、小米创作和区域截屏；后续页面是否要求解锁，由系统与目标应用决定。")};
            default -> new Step[]{
                new Step("听见铅笔的声音","开启书写声音反馈后，落笔移动时通过内置扬声器播放铅笔声。","悬停和抬笔不发声。实际听感随书写速度与压力变化。"),
                new Step("调到舒适的音量","用“书写声音音量”调整轻重，整体音量随系统通知音量变化。","静音模式下不播放书写声音。")};
        };
    }
    private GuideContent() { }
}
