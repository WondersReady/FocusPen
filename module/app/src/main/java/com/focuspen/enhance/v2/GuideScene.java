package com.focuspen.enhance.v2;

import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.Drawable;

/** New lesson scenes assembled from the installed ROM's device paths and pen artwork. */
final class GuideScene extends Drawable {
    private final int topic,step;
    private final boolean pro,dark;
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
    private final Path screen,border,button,longButton;
    private final Bitmap standardPen,proPen;
    private final PathMeasure measure=new PathMeasure();
    private final Path segment=new Path();
    private final float[] position=new float[2],tangent=new float[2];
    float progress;
    private static final int BLUE=0xff3682ff;
    GuideScene(Context c,int topic,int step,boolean pro){
        this.topic=topic;this.step=step;this.pro=pro;
        dark=(c.getResources().getConfiguration().uiMode&48)==32;
        GuideAssets.Art art=GuideAssets.get(c);
        screen=art.screen();border=art.border();button=art.button();longButton=art.longButton();
        standardPen=art.standardPen();proPen=art.proPen();
    }
    @Override public void draw(Canvas canvas){
        Rect bounds=getBounds();float scale=Math.min(bounds.width()/1000f,bounds.height()/580f);
        canvas.save();canvas.translate(bounds.centerX()-500*scale,bounds.centerY()-290*scale);canvas.scale(scale,scale);
        if(topic==0)keys(canvas);else {
            tablet(canvas);
            switch(topic){case 1->ring(canvas);case 2->hover(canvas);case 3->profiles(canvas);case 4->handwriting(canvas);case 5->presentation(canvas);case 6->notes(canvas);case 7->audio(canvas);default->notes(canvas);}
        }
        if(pro&&proPen==null)label(canvas,"当前系统未提供此笔型的示意素材",500,topic==0?290:555,22,dark?0xffa7adb8:0xff777e89);
        canvas.restore();
    }
    private int paper(){return dark?0xff27292e:0xffffffff;}
    private int muted(){return dark?0xff606575:0xffdfe3ec;}
    private void fill(int color){paint.setShader(null);paint.setColor(color);paint.setStyle(Paint.Style.FILL);}
    private void line(Canvas c,float a,float b,float x,float y,int color,float width){fill(color);paint.setStrokeWidth(width);paint.setStrokeCap(Paint.Cap.ROUND);c.drawLine(a,b,x,y,paint);}
    private void box(Canvas c,float l,float t,float r,float b,float radius,int color){fill(color);c.drawRoundRect(l,t,r,b,radius,radius,paint);}
    private void dot(Canvas c,float x,float y,float r,int color){fill(color);c.drawCircle(x,y,r,paint);}
    private void label(Canvas c,String text,float x,float y,int size,int color){fill(color);paint.setTypeface(Typeface.create("mipro-medium",0));paint.setTextSize(size);paint.setTextAlign(Paint.Align.CENTER);c.drawText(text,x,y,paint);}
    private void tablet(Canvas c){
        if(screen==null||border==null)return;
        float wake=topic==6&&step==1?GuideMotion.screenWake(progress):1;
        c.save();c.translate(500,285);c.scale(.91f,.91f);
        fill(0xff373737);c.drawPath(border,paint);
        fill(blend(0xff111318,paper(),wake));c.drawPath(screen,paint);
        c.restore();
        if(topic==6&&step==1)return;
        int saved=c.saveLayerAlpha(0,0,1000,580,Math.round(255*wake));
        line(c,180,112,820,112,muted(),2);dot(c,192,89,6,muted());dot(c,214,89,6,muted());dot(c,236,89,6,muted());
        c.restoreToCount(saved);
    }
    private void pen(Canvas c,float x,float y,float rotation,float scale,boolean highlight){
        c.save();c.translate(x,y);c.rotate(rotation);c.scale(scale,scale);
        if(pro){
            if(proPen!=null){
                // The installed Pro art is diagonal. Anchor its actual tip at the
                // scene origin, then normalize its axis to the standard pen's.
                c.save();c.rotate(40);c.scale(.854f,.854f);fill(Color.WHITE);
                c.drawBitmap(proPen,null,new RectF(-43,-454,531,46),paint);c.restore();
            }
        }
        else if(standardPen!=null){
            // The artwork includes transparent padding and a shadow: its visible
            // tip is at (12,-1) in these coordinates, not the bitmap's left edge.
            if(topic!=0)c.translate(-12,1);
            fill(Color.WHITE);c.drawBitmap(standardPen,null,new RectF(0,-21,531.5f,50),paint);
            if(highlight){
                if(topic!=5&&longButton!=null){c.save();c.translate(141.211f,-.5f);fill(BLUE);c.drawPath(longButton,paint);c.restore();}
                if((topic==5||topic==0)&&button!=null){c.save();c.translate(199.106f,-.438f);fill(topic==0&&step>0?0xff777d88:BLUE);c.drawPath(button,paint);c.restore();}
            }}
        c.restore();
    }
    private void keys(Canvas c){
        float press=step==0?0:GuideMotion.keyPress(step,progress);
        float angle=-8,y0=300;
        pen(c,110,y0,angle,1.5f,step==0);
        if(pro){
            if(step==0){if(proPen!=null)label(c,"感应区域",530,450,24,BLUE);}
            else{
                c.save();c.translate(110,y0);c.rotate(angle);c.scale(1.5f,1.5f);
                if(step==1){
                    float slide=GuideMotion.travel(progress);
                    dot(c,245+70*slide,0,13,((int)(140*(1-GuideMotion.between(progress,.8f,.96f)))<<24)|(BLUE&0xffffff));
                }else{
                    dot(c,250,0,15,((int)(125*press)<<24)|(BLUE&0xffffff));
                    keyRipple(c,250,.20f,.34f);keyRipple(c,250,.39f,.53f);
                }
                c.restore();
            }
            return;
        }
        if(step==0){
            String[] names={"速记键","截图键","焦点键"};float[] xs={123.606f,158.817f,199.106f};
            for(int i=0;i<3;i++){float x=110+xs[i]*1.5f*(float)Math.cos(Math.toRadians(angle)),y=y0+xs[i]*1.5f*(float)Math.sin(Math.toRadians(angle));
                float tx=new float[]{240,350,460}[i];line(c,x,y-8,tx,182,muted(),2);dot(c,x,y,3,BLUE);label(c,names[i],tx,163,23,dark?0xffcccccc:0xff666666);}
            return;
        }
        c.save();c.translate(110,y0);c.rotate(angle);c.scale(1.5f,1.5f);
        if(button!=null&&press>0){c.save();c.translate(199.106f,-.438f);fill(((int)(170*press)<<24)|(BLUE&0xffffff));c.drawPath(button,paint);c.restore();}
        if(step==1)keyRipple(c,199.106f,.18f,.76f);
        else{keyRipple(c,199.106f,.20f,.34f);keyRipple(c,199.106f,.39f,.53f);}
        c.restore();
        label(c,step==1?"短暂按住焦点键":"快速连按焦点键",650,443,26,BLUE);
    }
    private void keyRipple(Canvas c,float x,float start,float end){
        float phase=GuideMotion.between(progress,start,end);
        if(phase<=0||phase>=1)return;
        for(int i=0;i<2;i++){
            float ripple=GuideMotion.between(phase,.12f+i*.25f,.35f+i*.25f)
                    *(1-GuideMotion.between(phase,.65f+i*.12f,.9f+i*.1f));
            fill(((int)(105*ripple)<<24)|(BLUE&0xffffff));paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(2);
            c.drawCircle(x,0,14+i*14+20*phase,paint);
        }
        paint.setStyle(Paint.Style.FILL);
    }
    private void ring(Canvas c){
        for(int i=0;i<4;i++)line(c,198,166+i*43,390+i%2*65,166+i*43,muted(),9);
        float open=GuideMotion.pulse(progress,.18f,.34f,.70f,.84f);
        int saved=c.saveLayerAlpha(400,100,760,450,Math.round(255*open));
        c.scale(.92f+.08f*open,.92f+.08f*open,545,275);
        fill(dark?0xff38475e:0xffe2edfc);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(88);paint.setStrokeCap(Paint.Cap.ROUND);
        c.drawArc(new RectF(425,155,665,395),-80,160,false,paint);
        for(int i=0;i<3;i++){double a=Math.toRadians(-80+i*80);float x=545+120*(float)Math.cos(a),y=275+120*(float)Math.sin(a);dot(c,x,y,32,paper());symbol(c,x,y,i);}
        float tap=step==1?GuideMotion.pulse(progress,.52f,.58f,.63f,.70f):0;
        if(tap>0){fill(((int)(85*tap)<<24)|0x3682ff);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(3);c.drawCircle(665,275,36-3*tap,paint);}
        c.restoreToCount(saved);
        float reach=step==0?0:GuideMotion.pulse(progress,.36f,.52f,.76f,.96f);
        float x=545+120*reach,y=275-20*(1-tap);
        halo(c,545,275,10);pen(c,x,y,-35+11*reach+5*tap,.62f,step==0);
    }
    private void symbol(Canvas c,float x,float y,int type){
        int color=type==0?0xffffaf23:type==1?BLUE:0xff7880db;
        if(type==0){box(c,x-15,y-19,x+15,y+19,5,color);for(int i=0;i<3;i++)line(c,x-8,y-7+i*7,x+8,y-7+i*7,Color.WHITE,2);}
        else if(type==1)line(c,x-12,y+12,x+12,y-12,color,8);
        else{line(c,x-16,y-18,x-16,y+11,color,3);line(c,x-16,y+11,x+20,y+11,color,3);line(c,x+13,y+18,x+13,y-11,color,3);line(c,x+13,y-11,x-20,y-11,color,3);}
    }
    private void halo(Canvas c,float x,float y,float r){for(int i=9;i>0;i--){fill(0x0b6fc7ff);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(1);c.drawCircle(x,y,r+i,paint);}fill(0xff7bcaff);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(2.5f);c.drawCircle(x,y,r,paint);}
    private void hover(Canvas c){
        if(step==1){Path path=new Path();path.moveTo(350,300);path.cubicTo(410,235,485,350,565,270);writing(c,path,0xff7a8290,3,.68f,true);return;}
        double phase=2*Math.PI*progress;
        float x=460+85*(float)Math.sin(phase),y=285+24*(float)Math.sin(2*phase);
        halo(c,x,y,16);pen(c,x,y-22,-43+9*(float)Math.sin(phase),.68f,false);
    }
    private void profiles(Canvas c){
        float active=step==0?0:GuideMotion.pulse(progress,.28f,.42f,.72f,.88f);
        for(int i=0;i<2;i++){float x=170+i*335;box(c,x,137,x+320,468,12,dark?0xff33363d:0xfff4f6fa);
            for(int j=0;j<4;j++)line(c,x+30,230+j*37,x+230+j%2*38,230+j*37,muted(),8);
            float amount=i==0?1-active:active;
            box(c,x+14,151,x+306,195,8,blend(muted(),0x293682ff,amount));label(c,i==0?"笔记":"视频",x+160,181,22,blend(0xff9298a3,BLUE,amount));
            int save=c.saveLayerAlpha(x,390,x+320,454,Math.round(255*amount));box(c,x+42,400,x+278,444,22,BLUE);label(c,"已应用专属配置",x+160,429,21,Color.WHITE);c.restoreToCount(save);}
        dot(c,330+335*active,356,9,BLUE);
    }
    private void handwriting(Canvas c){
        box(c,206,156,794,247,14,dark?0xff363b45:0xfff1f4fa);
        int inkColor=dark?0xffd7dce6:0xff566071;
        boolean finished=GuideMotion.pulse(progress,.55f,.68f,.80f,.95f)>.5f;
        switch(step){
            case 0->label(c,"记下此刻的灵感",500,213,32,inkColor);
            case 1->label(c,finished?"真诚":"真诚与热爱",500,213,32,inkColor);
            case 2->label(c,"真诚与热爱",500,213,32,finished?BLUE:inkColor);
            case 3->label(c,finished?"真诚 与热爱":"真诚与热爱",500,213,32,inkColor);
            case 4->{if(finished){label(c,"真诚",500,190,30,inkColor);label(c,"与热爱",500,229,30,inkColor);}else label(c,"真诚与热爱",500,213,32,inkColor);}
            default->label(c,finished?"真诚快乐与热爱":"真诚与热爱",500,213,32,inkColor);
        }
        Path ink=new Path();
        switch(step){
            case 0->{ink.moveTo(325,350);ink.cubicTo(365,300,347,414,390,357);ink.cubicTo(430,303,414,404,464,348);ink.cubicTo(500,407,560,300,650,339);}
            case 1->{ink.moveTo(380,195);ink.lineTo(610,218);ink.lineTo(400,185);ink.lineTo(590,205);}
            case 2->{ink.addOval(new RectF(366,169,632,231),Path.Direction.CW);}
            case 3->{ink.moveTo(500,163);ink.lineTo(500,238);}
            case 4->{ink.moveTo(440,170);ink.lineTo(440,226);ink.lineTo(555,226);}
            default->{ink.moveTo(442,237);ink.lineTo(500,169);ink.lineTo(558,237);}
        }
        writing(c,ink,BLUE,4,.64f,false);
    }
    private void presentation(Canvas c){
        if(step>=4){mouse(c);return;}
        if(step==0){
            float x=440+130*GuideMotion.travel(progress),y=277;
            halo(c,x,y,11);pen(c,x,y-19,-42,.57f,false);
            return;
        }
        if(step==1){
            Path ink=new Path();ink.moveTo(250,320);ink.cubicTo(340,185,440,380,520,230);ink.cubicTo(590,170,670,305,755,200);
            writing(c,ink,PenPresentation.COLORS[0],6,.56f,false);
            return;
        }
        if(step==3){
            float moved=GuideMotion.between(progress,.25f,.55f)*(1-GuideMotion.between(progress,.78f,.95f));
            label(c,moved>.5f?"第 2 页":"第 1 页",500,235,34,dark?0xffd7dce6:0xff566071);
            for(int i=0;i<3;i++)line(c,290,280+i*37,710-i*45,280+i*37,muted(),8);
            label(c,pro?"上滑 / 下滑":"应用上 / 应用下",500,455,24,BLUE);
            return;
        }
        colors(c);
    }
    private void mouse(Canvas c){
        if(step==4||step==7){
            mouseWorkspace(c);
            float visible=step==4?GuideMotion.mouseEntryCursor(progress):GuideMotion.mouseExitCursor(progress);
            int saved=c.saveLayerAlpha(0,0,1000,580,Math.round(255*visible));
            cursor(c,535,251);c.restoreToCount(saved);
            mousePen(c,185,445,GuideMotion.keyPress(2,progress),0,true,false);
            return;
        }
        if(step==5){
            float move=GuideMotion.mouseMove(progress),click=GuideMotion.mouseClick(progress);
            box(c,440,155,755,340,12,dark?0xff363b45:0xfff1f4fa);
            label(c,"文件",510,201,25,dark?0xffd7dce6:0xff566071);
            line(c,475,222,705,222,muted(),4);
            box(c,495,243,700,307,8,blend(dark?0xff515b69:0xffdce6f4,BLUE,click));
            label(c,"打开",597,284,23,blend(BLUE,Color.WHITE,click));
            cursor(c,330+195*move,205+58*move);
            if(click>0){int saved=c.saveLayerAlpha(0,0,1000,580,Math.round(180*click));halo(c,525,263,12);c.restoreToCount(saved);}
            mousePen(c,185+60*move,445-10*move,0,click,false,true);
            return;
        }
        float drag=GuideMotion.mouseDrag(progress),visible=GuideMotion.mouseDragVisibility(progress);
        box(c,260,177,430,310,10,dark?0xff363b45:0xfff1f4fa);
        box(c,560,177,730,310,10,dark?0xff363b45:0xfff1f4fa);
        label(c,"文件",345,340,22,dark?0xffb6bdca:0xff697384);
        label(c,"文件夹",645,340,22,dark?0xffb6bdca:0xff697384);
        int saved=c.saveLayerAlpha(0,0,1000,580,Math.round(255*visible));
        box(c,285+280*drag,199,405+280*drag,288,8,dark?0xff586f92:0xffc9ddf8);
        symbol(c,345+280*drag,243,0);
        cursor(c,306+280*drag,211);
        c.restoreToCount(saved);
        mousePen(c,185+50*drag,445-8*drag,0,GuideMotion.mouseDragPress(progress),false,true);
    }
    private void mouseWorkspace(Canvas c){
        box(c,440,155,755,340,12,dark?0xff363b45:0xfff1f4fa);
        label(c,"文件",510,201,25,dark?0xffd7dce6:0xff566071);
        line(c,475,222,705,222,muted(),4);
        line(c,475,253,670,253,muted(),4);
        line(c,475,284,635,284,muted(),4);
    }
    private void mousePen(Canvas c,float x,float y,float focus,float left,boolean focusRipple,boolean leftRipple){
        pen(c,x,y,-7,1.18f,false);
        c.save();c.translate(x,y);c.rotate(-7);c.scale(1.18f,1.18f);c.translate(-12,1);
        if(button!=null&&focus>0){c.save();c.translate(199.106f,-.438f);fill(((int)(190*focus)<<24)|(BLUE&0xffffff));c.drawPath(button,paint);c.restore();}
        if(left>0)box(c,106,-5,139,5,5,((int)(190*left)<<24)|(BLUE&0xffffff));
        if(focusRipple){keyRipple(c,199.106f,.20f,.34f);keyRipple(c,199.106f,.39f,.53f);}
        if(leftRipple)keyRipple(c,123.606f,step==5?.60f:.26f,step==5?.76f:.47f);
        c.restore();
    }
    private void cursor(Canvas c,float x,float y){
        Path arrow=new Path();arrow.moveTo(x,y);arrow.lineTo(x+2,y+30);arrow.lineTo(x+9,y+24);
        arrow.lineTo(x+16,y+39);arrow.lineTo(x+22,y+36);arrow.lineTo(x+15,y+21);
        arrow.lineTo(x+25,y+20);arrow.close();
        fill(dark?Color.WHITE:0xff252c38);c.drawPath(arrow,paint);
    }
    private void colors(Canvas c){
        for(int i=0;i<6;i++)dot(c,290+i*84,408,16,PenPresentation.COLORS[i]);
        Path p=new Path();p.moveTo(235,260);p.cubicTo(290,145,390,355,460,220);fill(0xffff001f);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(6);c.drawPath(p,paint);
        p.reset();p.moveTo(460,220);p.cubicTo(530,100,590,345,680,215);writing(c,p,0xff007aff,6,.56f,false);
        label(c,"已有笔迹保留原色",500,462,22,dark?0xffbbbbbb:0xff777777);
    }
    private void notes(Canvas c){
        if(step==0){
            for(int i=0;i<2;i++){float x=400+i*200;
                box(c,x-60,220,x+60,340,22,dark?0xff3a3e48:0xfff1f4fa);
                symbol(c,x,278,i);label(c,i==0?"笔记":"小米创作",x,380,23,0xff818896);}
            return;
        }
        float wake=GuideMotion.screenWake(progress);
        int saved=c.saveLayerAlpha(0,0,1000,580,Math.round(255*wake));
        label(c,"屏幕已点亮",500,345,29,dark?Color.WHITE:0xff455064);
        box(c,339,368,661,452,14,dark?0xff4a5260:Color.WHITE);
        label(c,"笔记 / 小米创作",500,419,23,BLUE);
        c.restoreToCount(saved);
        float tap=GuideMotion.pulse(progress,.35f,.40f,.52f,.60f);
        if(tap>0){
            fill(((int)(170*tap)<<24)|(BLUE&0xffffff));paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(4);
            c.drawCircle(500,285,12+54*GuideMotion.between(progress,.35f,.60f),paint);
            c.drawCircle(500,285,8+32*GuideMotion.between(progress,.35f,.60f),paint);
            paint.setStyle(Paint.Style.FILL);
        }
        pen(c,500,285-40*(1-GuideMotion.screenTouch(progress)),-45,.48f,false);
    }
    private void audio(Canvas c){
        Path stroke=new Path();stroke.moveTo(220,305);stroke.cubicTo(300,170,385,415,490,245);stroke.cubicTo(560,150,650,335,730,245);
        writing(c,stroke,0xff8992a1,3,.52f,false);
        float sound=GuideMotion.pulse(progress,.30f,.36f,.66f,.70f);
        for(int side:new int[]{-1,1})for(int row=0;row<2;row++){float x=500+side*355,y=185+row*210;for(int j=0;j<3;j++){fill((int)((70-j*18)*sound)<<24|0x3682ff);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(3);float r=15+j*14+4*(float)Math.sin(progress*Math.PI*12);c.drawArc(new RectF(x-r,y-r,x+r,y+r),side<0?-55:125,110,false,paint);}}
        if(step==1){line(c,330,438,670,438,muted(),8);line(c,330,438,520,438,BLUE,8);dot(c,520,438,12,BLUE);}
    }
    private void writing(Canvas c,Path path,int color,float width,float size,boolean preview){
        measure.setPath(path,false);float length=measure.getLength();
        float stroke=GuideMotion.between(progress,.30f,.70f),contact=GuideMotion.contact(progress);
        float fade=1-GuideMotion.between(progress,.80f,.95f);
        segment.reset();measure.getSegment(0,length*stroke,segment,true);
        fill((color&0xffffff)|Math.round(255*fade)<<24);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(width);paint.setStrokeCap(Paint.Cap.ROUND);c.drawPath(segment,paint);
        measure.getPosTan(length*GuideMotion.travel(progress),position,tangent);
        if(preview){int save=c.saveLayerAlpha(0,0,1000,580,Math.round(255*(1-contact)));halo(c,position[0],position[1],16);c.restoreToCount(save);}
        // A wrist turns gently with the stroke; the pen lifts before returning.
        float angle=-40+10*tangent[1]+5*contact;
        pen(c,position[0],position[1]-22*(1-contact),angle,size,false);
    }
    private static int blend(int a,int b,float t){
        return Color.argb(Math.round(Color.alpha(a)+(Color.alpha(b)-Color.alpha(a))*t),
                Math.round(Color.red(a)+(Color.red(b)-Color.red(a))*t),Math.round(Color.green(a)+(Color.green(b)-Color.green(a))*t),Math.round(Color.blue(a)+(Color.blue(b)-Color.blue(a))*t));
    }
    @Override public void setAlpha(int a){paint.setAlpha(a);}
    @Override public void setColorFilter(ColorFilter f){paint.setColorFilter(f);}
    @Override public int getOpacity(){return PixelFormat.TRANSLUCENT;}
    @Override public int getIntrinsicWidth(){return 1000;}
    @Override public int getIntrinsicHeight(){return 580;}
}
