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
        check(GuideMotion.ease(-1)==0&&GuideMotion.ease(2)==1,"phase clamping");
        System.out.println("Spring endpoint / interruption velocity / lesson contact / loop continuity PASS");
    }
}
