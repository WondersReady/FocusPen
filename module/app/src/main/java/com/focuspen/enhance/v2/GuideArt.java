package com.focuspen.enhance.v2;

import android.graphics.*;
import android.graphics.drawable.Drawable;

/** Resolution-independent tutorial artwork, shared by native guide cards and lessons. */
final class GuideArt extends Drawable {
    final int topic;
    int step;
    float progress = 1;
    boolean finished;
    boolean showLabels;
    private final boolean dark;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final int BLUE = 0xff3482ff;

    GuideArt(int topic, boolean dark) { this.topic = topic; this.dark = dark; }

    @Override public void draw(Canvas c) {
        Rect b = getBounds();
        float s = Math.min(b.width()/600f, b.height()/320f);
        c.save(); c.translate(b.centerX()-300*s, b.centerY()-160*s); c.scale(s,s);
        if (topic == 0) keys(c);
        else if (topic == 1) ring(c);
        else if (topic == 2) hover(c);
        else windows(c);
        c.restore();
    }
    private int line() { return dark ? 0xff51555c : 0xffdfe3e9; }
    private int paper() { return dark ? 0xff292c32 : 0xffffffff; }
    private void box(Canvas c,float l,float t,float r,float b,float radius,int color) {
        p.setColor(color); c.drawRoundRect(l,t,r,b,radius,radius,p);
    }
    private void stroke(Canvas c,float x,float y,float x2,float y2,int color,float width) {
        p.setColor(color); p.setStrokeWidth(width); p.setStrokeCap(Paint.Cap.ROUND); c.drawLine(x,y,x2,y2,p);
    }
    private void circle(Canvas c,float x,float y,float r,int color) { p.setColor(color); c.drawCircle(x,y,r,p); }
    private void outline(Canvas c,float x,float y,float r,int color,float width) {
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(width); circle(c,x,y,r,color); p.setStyle(Paint.Style.FILL);
    }
    private void pen(Canvas c) {
        p.setShader(new LinearGradient(0,136,0,163,new int[]{0xffbfc4cb,0xfff8fafc,0xffd4d9e0,0xff9ea6b0},null,Shader.TileMode.CLAMP));
        c.drawRoundRect(112,136,520,164,14,14,p); p.setShader(null);
        Path tip = new Path(); tip.moveTo(112,136);tip.lineTo(75,150);tip.lineTo(112,164);tip.close();
        p.setColor(0xffcbd1d8);c.drawPath(tip,p);stroke(c,75,150,86,150,0xff6b747f,4);
        box(c,162,140,232,160,8,0xffa1aab5);
        stroke(c,197,143,197,157,0xffdce1e8,1.5f);
        box(c,277,140,305,160,9,0xffa1aab5);
    }
    private void keys(Canvas c) {
        c.save();c.rotate(-12,300,150);
        box(c,123,171,505,178,4,dark?0xff1e2025:0xffe8ebf0); pen(c);
        float pulse = (float)Math.max(0, Math.sin(progress*Math.PI*(step==2?4:1)));
        for(int i=0;i<2;i++) {
            float x=180+34*i;
            outline(c,x,150,22+5*pulse,0x443482ff,2);
            circle(c,x,150,4,BLUE);
        }
        stroke(c,180,128,180,82,line(),2);circle(c,180,78,4,BLUE);
        stroke(c,214,172,214,215,line(),2);circle(c,214,220,4,BLUE);
        stroke(c,291,128,291,82,line(),2);circle(c,291,78,4,0xff929aa6);
        c.restore();
        if(showLabels){
            label(c,"速记键",151,88);label(c,"截图键",231,267);label(c,"焦点键",300,62);
        }
    }
    private void label(Canvas c,String text,float x,float y){
        p.setColor(dark?0xffbbc2cc:0xff7a8594);p.setTextSize(17);p.setTypeface(Typeface.create("sans-serif",Typeface.NORMAL));p.setTextAlign(Paint.Align.CENTER);c.drawText(text,x,y,p);
    }
    private void tablet(Canvas c) {
        box(c,102,31,498,289,24,dark?0xff191b20:0xffdce1e8);
        box(c,108,37,492,283,20,paper());
        circle(c,300,34,1.4f,0xff8c96a4);
        for(int i=0;i<4;i++) box(c,136,88+i*27,222+(i%2)*26,94+i*27,3,line());
    }
    private void ring(Canvas c) {
        tablet(c);
        if (finished) { outline(c,300,160,23,BLUE,3);stroke(c,289,160,297,168,BLUE,3);stroke(c,297,168,313,151,BLUE,3);return; }
        float reveal = step==0 ? .7f+.3f*progress : 1;
        c.save(); c.scale(reveal,reveal,285,165);
        p.setStyle(Paint.Style.STROKE);p.setStrokeCap(Paint.Cap.ROUND);p.setStrokeWidth(68);p.setColor(dark?0xff424b59:0xffe6effc);
        c.drawArc(new RectF(207,87,363,243),-65,130,false,p);p.setStyle(Paint.Style.FILL);
        for(int i=0;i<3;i++) {
            double a=Math.toRadians(-65+i*65); float x=285+78*(float)Math.cos(a),y=165+78*(float)Math.sin(a);
            circle(c,x,y,25,paper());
            icon(c,x,y,i);
        }
        outline(c,285,165,6,0xff82c5ff,2);c.restore();
    }
    private void icon(Canvas c,float x,float y,int kind) {
        int color=kind==0?0xffffb329:kind==1?BLUE:0xff7d85e9;
        if(kind==0){box(c,x-11,y-13,x+11,y+13,4,color);for(int i=0;i<3;i++)stroke(c,x-5,y-5+i*5,x+5,y-5+i*5,Color.WHITE,2);}
        else if(kind==1){stroke(c,x-8,y+8,x+8,y-8,color,6);stroke(c,x-10,y+10,x-7,y+7,color,3);}
        else {for(int sign:new int[]{-1,1}) {stroke(c,x+sign*11,y-sign*3,x+sign*11,y+sign*11,color,2.5f);stroke(c,x-sign*3,y+sign*11,x+sign*11,y+sign*11,color,2.5f);}}
    }
    private void hover(Canvas c) {
        box(c,100,192,500,248,18,dark?0xff252a33:0xffe7edf7);
        stroke(c,122,207,475,207,line(),1);
        float x=250+65*progress, y=199;
        boolean contact=step==1&&progress>.7f;
        if(!contact){for(int r=26;r>15;r--)outline(c,x,y,r,0x1285caff,1);outline(c,x,y,15,0xff83caff,2.5f);}
        else stroke(c,265,199,x,199,0xff8796aa,2);
        c.save(); c.translate(x-75, y-150-(contact?0:28));c.rotate(-52,75,150);pen(c);c.restore();
    }
    private void windows(Canvas c) {
        box(c,74,46,526,278,20,dark?0xff191b20:0xffdce1e8);
        for(int i=0;i<2;i++) {
            float x=82+i*224;
            box(c,x,54,x+212,270,14,paper());
            box(c,x+16,72,x+196,100,8,i==step?(dark?0xff284a7e:0xffe6efff):line());
            for(int n=0;n<4;n++)box(c,x+22,127+n*25,x+132+(n%2)*37,133+n*25,3,line());
            if(i==step){box(c,x+51,231,x+161,252,10,BLUE);circle(c,x+106,241,3,Color.WHITE);}
        }
    }
    int hit(float x,float y) {
        for(int i=0;i<3;i++){double a=Math.toRadians(-65+i*65);if(Math.hypot(x-285-78*Math.cos(a),y-165-78*Math.sin(a))<=27)return i;}
        return -1;
    }
    @Override public void setAlpha(int alpha) { p.setAlpha(alpha); }
    @Override public void setColorFilter(ColorFilter filter) { p.setColorFilter(filter); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    @Override public int getIntrinsicWidth() { return 600; }
    @Override public int getIntrinsicHeight() { return 320; }
}
