package com.focuspen.enhance.v2;

/** Pro hardware already recognizes gestures; do not run the standard double-click timer again. */
final class ProGestureGate {
    enum Decision { PASS, CONSUME, DISPATCH }
    private record Press(int device,long time) {}
    private final Press[] pressed=new Press[4];
    void reset(){java.util.Arrays.fill(pressed,null);}
    static int gesture(int stylusType,int code) { return stylusType==8 && code>=194 && code<=197?code-194:-1; }
    Decision event(int slot,int device,long time,int action,int repeat,boolean cancelled,boolean eligible) {
        if(slot<0||slot>=pressed.length)return Decision.PASS;
        Press old=pressed[slot];
        boolean paired=old!=null&&old.device==device&&old.time==time;
        if(action==1) {
            if(paired){pressed[slot]=null;return Decision.CONSUME;}
            return Decision.PASS;
        }
        if(action!=0)return Decision.PASS;
        if(paired)return Decision.CONSUME;
        if(repeat!=0)return Decision.PASS;
        pressed[slot]=null;
        if(!eligible||cancelled)return Decision.PASS;
        pressed[slot]=new Press(device,time);
        return Decision.DISPATCH;
    }
}
