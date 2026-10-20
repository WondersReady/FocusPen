package com.focuspen.enhance.v2;

import java.util.*;

/** Explicit Pro gesture slots; legacy body-double-tap is never reused as a pinch combo. */
final class ProBindings {
    static final String[] KEYS={"proTwoPinch","proTwoHold","proNormalUp","proNormalDown","proFourPinch","proFourHold","proMouseOne","proMouseHold","proMouseTwo","proMouseTwoHold","proMouseUp","proMouseDown"};
    static final String[] TITLES={"轻捏两次","轻捏两次并按住","向上滑动","向下滑动","轻捏四次","轻捏四次并按住","轻捏一次","轻捏并按住","轻捏两次","轻捏两次并按住","向上滑动","向下滑动"};
    static final String[] DEFAULTS={"default","default","default","default","off","off","left","leftHold","right","rightHold","volumeUp","volumeDown"};
    static final String INTERVAL="proPinchInterval", HOLD="proHoldTime";
    static List<String> options(int slot){
        ArrayList<String> a=new ArrayList<>();
        if(slot<4)a.add("default");else if(slot<6)a.add("off");
        a.add("none");
        if(slot>=6){a.add("left");a.add("right");if(slot==7||slot==9){a.add("leftHold");a.add("rightHold");}}
        if(slot<2||slot==4||slot==5){a.add("mouse");a.add("laser");}
        a.addAll(List.of("volumeUp","volumeDown","back","home","recents"));
        if(slot!=4&&slot!=5)a.addAll(List.of("scrollUp","scrollDown"));
        return List.copyOf(a);
    }
    static void validate(int slot,String value){if((slot==4||slot==5)&&value.startsWith("app:")&&RingContent.validId(value.substring(4))&&!RingContent.isAction(value.substring(4)))return;if(!options(slot).contains(value))throw new IllegalArgumentException("Action not allowed for Pro gesture");}
    static String label(String value){return switch(value){case "default"->"使用默认行为";case "off"->"不开启此组合";case "none"->"不执行操作";case "mouse"->"开启手写笔鼠标";case "laser"->"开启虚拟激光";case "left"->"鼠标左键";case "right"->"鼠标右键";case "leftHold"->"按住鼠标左键";case "rightHold"->"按住鼠标右键";case "volumeUp"->"音量加";case "volumeDown"->"音量减";case "back"->"返回";case "home"->"桌面";case "recents"->"最近任务";case "scrollUp"->"向上滚动";case "scrollDown"->"向下滚动";default->value.startsWith("app:")?"打开应用 · "+value.substring(4):value;};}
    private ProBindings(){}
}
