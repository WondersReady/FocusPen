#!/usr/bin/env python3
"""Compile and exercise the writing-audio input policy without Android runtime."""
import os
import subprocess
from pathlib import Path

root = Path(__file__).resolve().parents[2]
out = root / "work/v2/writing-audio-gate-test"
sources = {
    "android/view/InputDevice.java": "package android.view; public class InputDevice {public static final int SOURCE_CLASS_POINTER=2,SOURCE_STYLUS=0x4002,SOURCE_TOUCHSCREEN=0x1002;}",
    "android/view/MotionEvent.java": "package android.view; public class MotionEvent {public static final int ACTION_DOWN=0,ACTION_UP=1,ACTION_MOVE=2,ACTION_CANCEL=3,ACTION_HOVER_MOVE=7,TOOL_TYPE_STYLUS=2,TOOL_TYPE_MOUSE=3,TOOL_TYPE_ERASER=4;}",
    "com/focuspen/enhance/v2/PenDockMonitor.java": "package com.focuspen.enhance.v2; final class PenDockMonitor {static final int UNKNOWN=-1,DOCKED=0,DETACHED=1;}",
    "com/focuspen/enhance/v2/WritingAudioGateTest.java": """package com.focuspen.enhance.v2;
import android.view.InputDevice;import android.view.MotionEvent;
public class WritingAudioGateTest {
 static void check(boolean result,String name){if(!result)throw new AssertionError(name);}
 static boolean move(WritingAudioGate gate,int tool,int source,int action,float pressure,boolean physical,boolean mouse){
  return gate.allowMotion(tool,source,action,pressure,physical,mouse);
 }
 public static void main(String[] args){
  int[] stops={0};WritingAudioGate gate=new WritingAudioGate();gate.onStop(()->stops[0]++);
  int pen=MotionEvent.TOOL_TYPE_STYLUS, move=MotionEvent.ACTION_MOVE;
  int stylus=InputDevice.SOURCE_STYLUS, touch=InputDevice.SOURCE_TOUCHSCREEN;
  check(!move(gate,pen,stylus,move,0.5f,true,false),"initially blocked");
  gate.updateScreen(true);gate.updateLocked(false);
  check(!move(gate,pen,stylus,move,0.5f,true,false),"unknown dock blocked");
  gate.updateDock(PenDockMonitor.DETACHED);
  check(move(gate,pen,stylus,MotionEvent.ACTION_DOWN,0.5f,true,false),"pen down");
  check(move(gate,pen,touch,move,0.5f,true,false),"touchscreen-sourced pen move");
  check(!move(gate,pen,stylus,MotionEvent.ACTION_HOVER_MOVE,0f,true,false)&&stops[0]==1,"hover stops");
  check(move(gate,pen,stylus,move,0.5f,true,false),"resume pen");
  check(!move(gate,MotionEvent.TOOL_TYPE_ERASER,stylus,move,0.5f,true,false)&&stops[0]==2,"eraser stops");
  check(!move(gate,pen,stylus,move,0.5f,false,false),"virtual device blocked");
  check(!move(gate,pen,stylus,move,0.5f,true,true),"mouse mode blocked");
  check(!move(gate,pen,stylus,move,0f,true,false),"zero pressure blocked");
  check(!move(gate,pen,stylus,move,Float.NaN,true,false),"invalid pressure blocked");
  check(!move(gate,pen,0,move,0.5f,true,false),"non-pointer source blocked");
  check(move(gate,pen,stylus,move,0.5f,true,false),"normal pen resumes");
  gate.updateLocked(true);check(stops[0]==3,"lock stops");
  check(!move(gate,pen,stylus,move,0.5f,true,false),"locked blocked");
  gate.updateLocked(false);check(move(gate,pen,stylus,move,0.5f,true,false),"unlock resumes");
  gate.updateScreen(false);check(stops[0]==4,"screen off stops");
  gate.updateScreen(true);check(move(gate,pen,stylus,move,0.5f,true,false),"screen on resumes");
  gate.updateDock(PenDockMonitor.DOCKED);check(stops[0]==5,"dock stops");
  gate.updateDock(PenDockMonitor.DETACHED);check(move(gate,pen,stylus,move,0.5f,true,false),"detach resumes");
  gate.reset();check(stops[0]==6,"reset stops");
  check(!move(gate,pen,stylus,MotionEvent.ACTION_UP,0f,true,false)&&stops[0]==6,"up is silent");
  int[] finishes={0};gate.onFinish(()->finishes[0]++);
  check(move(gate,pen,stylus,MotionEvent.ACTION_DOWN,0.5f,true,false),"new stroke");
  check(!move(gate,pen,stylus,MotionEvent.ACTION_UP,0f,true,false)&&finishes[0]==1&&stops[0]==6,"up fades instead of hard stop");
  move(gate,pen,stylus,MotionEvent.ACTION_HOVER_MOVE,0f,true,false);
  check(stops[0]==6,"hover preserves up release tail");
  gate.updateLocked(true);check(stops[0]==7,"lock stops release tail");
  gate.updateLocked(false);
  move(gate,pen,stylus,MotionEvent.ACTION_DOWN,0.5f,true,false);
  move(gate,pen,stylus,MotionEvent.ACTION_UP,0f,true,false);
  gate.updateDock(PenDockMonitor.DOCKED);check(stops[0]==8,"dock stops release tail");
  gate.updateDock(PenDockMonitor.DETACHED);
  move(gate,pen,stylus,MotionEvent.ACTION_DOWN,0.5f,true,false);
  move(gate,pen,stylus,MotionEvent.ACTION_CANCEL,0f,true,false);
  check(stops[0]==9&&finishes[0]==2,"cancel hard stops");
  System.out.println("WritingAudioGate policy and transitions: PASS");
 }
}""",
}

files = []
for name, source in sources.items():
    path = out / name
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(source, encoding="utf-8")
    files.append(str(path))
files.append(str(root / "module/app/src/main/java/com/focuspen/enhance/v2/WritingAudioGate.java"))
jdk = Path(os.environ.get("JAVA_HOME", "/usr/lib/jvm/java-21-openjdk-amd64")) / "bin"
subprocess.run([str(jdk / "javac"), "-d", str(out), *files], check=True)
subprocess.run([str(jdk / "java"), "-cp", str(out), "com.focuspen.enhance.v2.WritingAudioGateTest"], check=True)
