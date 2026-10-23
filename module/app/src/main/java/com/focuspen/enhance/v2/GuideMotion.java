package com.focuspen.enhance.v2;

/** Lesson timing: return to the resting pose without playing the action backwards. */
final class GuideMotion {
    static float ease(float value){float t=Math.max(0,Math.min(1,value));return t*t*t*(t*(t*6-15)+10);}
    static float between(float phase,float start,float end){return ease((phase-start)/(end-start));}
    static float pulse(float phase,float in,float held,float out,float end){
        return between(phase,in,held)*(1-between(phase,out,end));
    }
    static float lerp(float from,float to,float amount){return from+(to-from)*amount;}
    static float contact(float phase){return pulse(phase,.20f,.30f,.70f,.80f);}
    static float travel(float phase){return between(phase,.30f,.70f)*(1-between(phase,.82f,.98f));}
    static float screenTouch(float phase){return between(phase,.22f,.35f)*(1-between(phase,.60f,.72f));}
    static float screenWake(float phase){return between(phase,.52f,.70f)*(1-between(phase,.80f,.97f));}
    static float mouseEntryCursor(float phase){return between(phase,.54f,.68f)*(1-between(phase,.82f,.96f));}
    static float mouseExitCursor(float phase){return between(phase,.08f,.18f)*(1-between(phase,.64f,.80f));}
    static float mouseMove(float phase){return between(phase,.22f,.53f)*(1-between(phase,.80f,.92f));}
    static float mouseClick(float phase){return pulse(phase,.60f,.65f,.69f,.75f);}
    static float mouseDrag(float phase){return between(phase,.34f,.68f)*(1-between(phase,.84f,.94f));}
    static float mouseDragPress(float phase){return pulse(phase,.26f,.34f,.72f,.78f);}
    static float mouseDragVisibility(float phase){return 1-between(phase,.80f,.84f)+between(phase,.94f,.99f);}
    static float keyPress(int step,float phase){return step==1?pulse(phase,.18f,.28f,.60f,.76f):
            pulse(phase,.20f,.25f,.28f,.34f)+pulse(phase,.39f,.44f,.47f,.53f);}
    static long duration(int topic,int step){return topic==0?(step==2?2800:3600):topic==1?4600:4800;}
    private GuideMotion(){}
}
