package com.focuspen.enhance.v2;

/** Deterministic pinch arbitration. Call tick before each event; stale sessions are canceled. */
final class PinchSequence {
    interface Sink {void gesture(int count,boolean hold,boolean release);}
    private boolean down,holding;
    private int completed;
    private long deadline=Long.MAX_VALUE;
    long deadline(){return deadline;}
    void down(long now,int holdMs){if(down)return;down=true;deadline=now+holdMs;}
    void up(long now,int interval,int max,Sink sink){
        if(!down)return;down=false;
        if(holding){sink.gesture(completed+1,true,true);clear();return;}
        completed++;if(completed>=max){int count=completed;clear();sink.gesture(count,false,false);}else deadline=now+interval;
    }
    void tick(long now,Sink sink){if(now<deadline)return;deadline=Long.MAX_VALUE;if(down){holding=true;sink.gesture(completed+1,true,false);}else if(completed>0){int count=completed;clear();sink.gesture(count,false,false);}}
    void cancel(Sink sink){if(holding)sink.gesture(completed+1,true,true);clear();}
    private void clear(){down=false;holding=false;completed=0;deadline=Long.MAX_VALUE;}
}
