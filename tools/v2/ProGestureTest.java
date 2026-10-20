package com.focuspen.enhance.v2;

public final class ProGestureTest {
    static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    public static void main(String[] args){
        for(int code=190;code<=200;code++) {
            check(ProGestureGate.gesture(3,code)==-1,"Standard focus key confused with Pro pinch");
            check(ProGestureGate.gesture(0,code)==-1,"Keyboard confused with Pro");
            check(ProGestureGate.gesture(8,code)==(code>=194&&code<=197?code-194:-1),"Wrong Pro slot");
        }
        for(int slot=0;slot<4;slot++) {
            ProGestureGate gate=new ProGestureGate();
            check(gate.event(slot,1,100,0,0,false,true)==ProGestureGate.Decision.DISPATCH,"Gesture did not fire");
            check(gate.event(slot,1,100,0,1,false,true)==ProGestureGate.Decision.CONSUME,"Repeat fired twice");
            check(gate.event(slot,1,100,0,0,false,true)==ProGestureGate.Decision.CONSUME,"Duplicate down fired twice");
            check(gate.event(slot,2,100,1,0,false,true)==ProGestureGate.Decision.PASS,"Other device up stolen");
            check(gate.event(slot,1,100,1,0,true,false)==ProGestureGate.Decision.CONSUME,"Captured up leaked after lock/config/laser change");
            check(gate.event(slot,1,100,1,0,false,true)==ProGestureGate.Decision.PASS,"Unowned up consumed");
            check(gate.event(slot,1,200,0,0,false,false)==ProGestureGate.Decision.PASS,"Native mode taken over");
            check(gate.event(slot,1,200,1,0,false,true)==ProGestureGate.Decision.PASS,"Native down lost its up");
            check(gate.event(slot,1,300,0,0,true,true)==ProGestureGate.Decision.PASS,"Cancelled event executed");
            gate.event(slot,1,400,0,0,false,true);
            check(gate.event(slot,2,500,0,0,false,true)==ProGestureGate.Decision.DISPATCH,"Lost up blocked reconnected pen");
        }
        for(PenAction action:PenAction.values())if(action!=PenAction.NATIVE){
            KeyArbiter keys=new KeyArbiter();
            keys.down(1,0,250,action,PenAction.RING,PenAction.SCREENSHOT,280,true);
            check(keys.up(1,50,false).effects().isEmpty(),"Short did not defer");
            check(keys.tick(329).isEmpty(),"Short fired before interval");
            var effects=keys.tick(330);
            check(effects.size()==1&&effects.get(0).action()==action&&!effects.get(0).longPress(),"Custom short incorrect");
            check(keys.tick(1000).isEmpty(),"Short repeated");
            keys=new KeyArbiter();keys.down(1,0,250,action,PenAction.RING,PenAction.SCREENSHOT,280,true);keys.up(1,50,false);
            keys.down(1,100,250,action,PenAction.RING,PenAction.SCREENSHOT,280,true);
            effects=keys.up(1,150,false).effects();
            check(effects.size()==1&&effects.get(0).kind()==KeyArbiter.Kind.DOUBLE_ACTION&&effects.get(0).action()==PenAction.SCREENSHOT,"Double sent short");
            check(keys.tick(1000).isEmpty(),"Delayed short leaked after double");
        }
        System.out.println("Pro event ownership/type isolation and all customizable short/double actions PASS (simulation only)");
    }
}
