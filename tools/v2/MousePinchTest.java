package com.focuspen.enhance.v2;
import java.util.*;
public final class MousePinchTest {
    static void check(boolean ok,String text){if(!ok)throw new AssertionError(text);}
    public static void main(String[] args){
        MouseButtons mouse=new MouseButtons();List<String> out=new ArrayList<>();MouseButtons.Sink sink=(a,b,c)->{out.add(a+":"+b+":"+c);return true;};
        mouse.press(1,sink);mouse.press(1,sink);mouse.press(2,sink);mouse.release(1,sink);mouse.clear(sink);mouse.clear(sink);
        check(out.equals(List.of("0:1:0","11:1:1","11:3:2","12:2:1","12:0:2","1:0:0")),"Paired mouse button order "+out);
        check(mouse.held()==0,"Stuck buttons");
        check(!mouse.press(1,(a,b,c)->false)&&mouse.held()==0,"Failed DOWN owns a release");
        PinchSequence p=new PinchSequence();out.clear();PinchSequence.Sink events=(n,h,r)->out.add(n+":"+h+":"+r);
        p.down(0,650);p.up(40,360,2,events);p.tick(399,events);check(out.isEmpty(),"Single before interval");p.tick(400,events);check(out.equals(List.of("1:false:false")),"Single missing");
        out.clear();p.down(500,650);p.up(520,360,2,events);p.down(700,650);p.tick(1349,events);check(out.isEmpty(),"Hold fired early");p.tick(1350,events);p.up(1500,360,2,events);check(out.equals(List.of("2:true:false","2:true:true")),"Right drag not paired "+out);
        out.clear();for(int i=0;i<4;i++){p.down(2000+i*100,650);p.up(2040+i*100,360,4,events);}p.tick(4000,events);check(out.equals(List.of("4:false:false")),"Four pinch opens earlier action "+out);
        out.clear();p.down(5000,650);p.tick(5650,events);p.cancel(events);p.up(5700,360,2,events);p.tick(7000,events);check(out.equals(List.of("1:true:false","1:true:true")),"Cancel must release once "+out);
        out.clear();p.down(8000,650);p.up(8030,360,2,events);p.cancel(events);p.tick(9000,events);check(out.isEmpty(),"Canceled pinch escaped");
        for(int slot=0;slot<ProBindings.KEYS.length;slot++){ProBindings.validate(slot,ProBindings.DEFAULTS[slot]);for(String option:ProBindings.options(slot))ProBindings.validate(slot,option);}
        for(int slot:new int[]{0,2,6,8,10})try{ProBindings.validate(slot,"leftHold");throw new AssertionError("Hold allowed in wrong slot");}catch(IllegalArgumentException expected){}
        System.out.println("Mouse pairing, drag cancellation, pinch arbitration and allowed-action matrix PASS");
    }
}
