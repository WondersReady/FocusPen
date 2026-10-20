package com.focuspen.enhance.v2;

/** Real mouse press semantics; pure state so transitions can be tested without Android. */
final class MouseButtons {
    interface Sink { boolean send(int action,int buttons,int changed); }
    private int held;
    int held(){return held;}
    boolean press(int button,Sink sink){
        if((held&button)!=0)return true;
        int next=held|button;
        if(held==0 && !sink.send(0,next,0))return false;
        held=next;
        return sink.send(11,held,button);
    }
    boolean release(int button,Sink sink){
        if((held&button)==0)return true;
        held&=~button;
        boolean ok=sink.send(12,held,button);
        if(held==0)ok=sink.send(1,0,0)&&ok;
        return ok;
    }
    void clear(Sink sink){for(int button:new int[]{1,2})release(button,sink);}
}
