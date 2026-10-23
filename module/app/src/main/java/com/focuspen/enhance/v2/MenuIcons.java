package com.focuspen.enhance.v2;

import android.content.Context;
import android.graphics.drawable.Drawable;

/** Settings-only icons, separate from action routing. */
final class MenuIcons {
    static Drawable icon(Context c,String id){
        String pkg=packageFor(id);
        if(RingContent.SCREENSHOT.equals(id))return new android.graphics.drawable.Drawable(){
            final android.graphics.Paint p=new android.graphics.Paint(3);
            public void draw(android.graphics.Canvas canvas){android.graphics.Rect b=getBounds();canvas.save();canvas.translate(b.left,b.top);canvas.scale(b.width()/40f,b.height()/40f);
                p.setStyle(android.graphics.Paint.Style.FILL);p.setColor(0xffeaf1ff);canvas.drawRoundRect(0,0,40,40,11,11,p);
                p.setColor(0xff3482ff);p.setStyle(android.graphics.Paint.Style.STROKE);p.setStrokeWidth(2);p.setStrokeCap(android.graphics.Paint.Cap.ROUND);
                canvas.drawLine(12,7,12,28,p);canvas.drawLine(12,28,33,28,p);canvas.drawLine(7,12,28,12,p);canvas.drawLine(28,12,28,33,p);canvas.restore();}
            public void setAlpha(int a){p.setAlpha(a);}public void setColorFilter(android.graphics.ColorFilter f){p.setColorFilter(f);}public int getOpacity(){return android.graphics.PixelFormat.TRANSLUCENT;}
            public int getIntrinsicWidth(){return Math.round(40*c.getResources().getDisplayMetrics().density);}public int getIntrinsicHeight(){return getIntrinsicWidth();}
        };
        return SettingsAppCatalog.snapshot().icon(c,pkg);
    }
    private static String packageFor(String id){return RingContent.NOTES.equals(id)?"com.miui.notes":RingContent.CREATION.equals(id)?"com.miui.creation":RingContent.RECOGNITION.equals(id)?"com.miui.voiceassist":id;}
}
