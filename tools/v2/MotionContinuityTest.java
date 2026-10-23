package com.focuspen.enhance.v2;

/** Numeric continuity checks; visual feel still needs the actual display and stylus. */
public final class MotionContinuityTest {
    private static void check(boolean pass,String reason){if(!pass)throw new AssertionError(reason);}
    public static void main(String[] args){
        for(boolean glass:new boolean[]{false,true}){
            int duration=glass?360:220;
            RingMotion motion=new RingMotion();motion.target(1,0,glass,false);
            check(motion.value(0)==0,"animation starts from current position");
            check(Math.abs(motion.value(duration-1)-1)<.0001,"last frame cannot snap to target");
            check(motion.value(duration)==1&&!motion.running(duration),"finite endpoint");
            motion.reset();motion.target(1,0,glass,false);
            for(int i=1;i<160;i+=7){
                float before=motion.value(i),speed=before-motion.value(i-1);
                motion.target(i%2, i,glass,false);
                check(Math.abs(motion.value(i)-before)<.00001,"interrupt cannot jump");
                check(Math.abs(motion.value(i+1)-before-speed)<.002,"interrupt preserves velocity");
                check(Float.isFinite(motion.value(i+100)),"repeated retarget stays finite");
            }
            motion.target(0,200,glass,true);check(motion.value(200)==0&&!motion.running(200),"disabled animation settles immediately");
        }
        for(int step=1;step<=2;step++){
            int peaks=0;boolean down=false;
            for(int i=0;i<=1000;i++){
                float phase=i/1000f,contact=GuideMotion.contact(phase),travel=GuideMotion.travel(phase),press=GuideMotion.keyPress(step,phase);
                check(contact>=0&&contact<=1.00001&&travel>=0&&travel<=1.00001,"bounded lesson motion");
                if(press>.5f&&!down)peaks++;down=press>.5f;
                if(i>820)check(contact==0,"return stroke happens with pen lifted");
            }
            check(peaks==step,"hold is one pulse, double press is exactly two pulses");
            check(GuideMotion.keyPress(step,0)==GuideMotion.keyPress(step,1),"press loop seam");
        }
        check(GuideMotion.travel(0)==GuideMotion.travel(1)&&GuideMotion.contact(0)==GuideMotion.contact(1),"pose loop seam");
        check(GuideMotion.screenTouch(0)==0&&GuideMotion.screenTouch(1)==0,"screen-off pen returns to hover before loop");
        check(GuideMotion.screenWake(0)==0&&GuideMotion.screenWake(1)==0,"screen returns fully dark before loop");
        check(GuideMotion.screenTouch(.38f)>.99f&&GuideMotion.screenWake(.38f)==0,"pen contacts dark screen before wake");
        check(GuideMotion.screenTouch(.82f)==0&&GuideMotion.screenWake(.82f)>.8f,"pen lifts while display remains lit");
        for(int i=0;i<=1000;i++){
            float phase=i/1000f,wake=GuideMotion.screenWake(phase),touch=GuideMotion.screenTouch(phase);
            check(wake>=0&&wake<=1&&touch>=0&&touch<=1,"screen-off sequence remains bounded");
            if(i>970)check(wake==0&&touch==0,"tail holds dark screen and lifted pen");
        }
        check(GuideMotion.mouseEntryCursor(.50f)==0&&GuideMotion.mouseEntryCursor(.70f)>0.99f,"entry pointer follows the double press");
        check(GuideMotion.mouseExitCursor(.30f)>0.99f&&GuideMotion.mouseExitCursor(.82f)==0,"exit pointer disappears after the double press");
        check(GuideMotion.mouseMove(.56f)>0.99f&&GuideMotion.mouseClick(.65f)>0.99f,"left click lands after pointer reaches its target");
        check(GuideMotion.mouseDragPress(.40f)>0.99f&&GuideMotion.mouseDrag(.40f)>0,"drag starts with the left button held");
        for(int i=0;i<=1000;i++){
            float phase=i/1000f;
            for(float value:new float[]{GuideMotion.mouseEntryCursor(phase),GuideMotion.mouseExitCursor(phase),
                    GuideMotion.mouseMove(phase),GuideMotion.mouseClick(phase),GuideMotion.mouseDrag(phase),
                    GuideMotion.mouseDragPress(phase),GuideMotion.mouseDragVisibility(phase)})
                check(value>=0&&value<=1,"mouse lesson motion remains bounded");
        }
        check(GuideMotion.mouseEntryCursor(0)==GuideMotion.mouseEntryCursor(1)&&GuideMotion.mouseExitCursor(0)==GuideMotion.mouseExitCursor(1),"toggle pointer loop seams");
        check(GuideMotion.mouseMove(0)==GuideMotion.mouseMove(1)&&GuideMotion.mouseClick(0)==GuideMotion.mouseClick(1),"click loop seam");
        check(GuideMotion.mouseDrag(0)==GuideMotion.mouseDrag(1)&&GuideMotion.mouseDragPress(0)==GuideMotion.mouseDragPress(1)
                &&GuideMotion.mouseDragVisibility(0)==GuideMotion.mouseDragVisibility(1),"drag loop seam");
        check(GuideMotion.ease(-1)==0&&GuideMotion.ease(2)==1,"phase clamping");
        System.out.println("Spring endpoint / interruption velocity / lesson contact / loop continuity PASS");
    }
}
