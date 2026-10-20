#!/usr/bin/env python3
"""Run the actual RingHost delayed-dispatch methods with a deterministic queue.

Only scheduling/action dispatch is exercised; no Android View/window rendering is
emulated. Lifecycle cancellation is represented by advancing the host generation.
"""
import os
from pathlib import Path
import subprocess
import tempfile

root = Path(__file__).resolve().parents[2]
source = (root / 'module/app/src/main/java/com/focuspen/enhance/v2/RingHost.java').read_text()

def method(signature):
    start = source.index(signature)
    opening = source.index('{', start)
    depth = 1
    end = opening + 1
    while depth:
        depth += (source[end] == '{') - (source[end] == '}')
        end += 1
    return source[start:end]

methods = method('private void finish(String packageName)')
methods += method('private void finishAfterFeedback(String target)')
methods += method('private boolean pendingActionValid(long version, long revision)')

harness = r'''
import java.util.*;
public class RingDispatchTest {
    long interactionVersion;
    boolean available=true;
    boolean openedLocked, lock;
    boolean locked(){return lock;}
    final Queue main=new Queue(); final Config config=new Config();
    final Session session=new Session(); final Bus bus=new Bus(); final Context context=new Context();
    boolean usable(){return available;}
    void close(boolean animate){interactionVersion++;session.active=false;}
    static class Queue {
        final ArrayDeque<Runnable> tasks=new ArrayDeque<>();
        void postDelayed(Runnable r,long delay){tasks.add(r);}
        void next(){if(tasks.isEmpty())throw new AssertionError("No callback");tasks.remove().run();}
    }
    static class Session {boolean active; boolean active(){return active;}}
    static class Snapshot {long revision=7;boolean ring=true;boolean flag(String key){return ring;}}
    static class Config {final Snapshot value=new Snapshot();Snapshot get(){return value;}}
    static class ConfigSpec {static final String RING="ring";}
    static class Bundle {void putString(String k,String v){}void putBoolean(String k,boolean v){}}
    static class RuntimeBus {static final String INPUT="input";}
    static class RingContent {static boolean isAction(String s){return s.startsWith("action:");}}
    static class Bus {int sent;boolean send(String to,String event,Bundle data){sent++;return true;}}
    static class Intent {static final int FLAG_ACTIVITY_NEW_TASK=1;Intent addFlags(int f){return this;}}
    static class PM {Intent getLaunchIntentForPackage(String p){return new Intent();}}
    static class Context {int launches;PM getPackageManager(){return new PM();}void startActivity(Intent i){launches++;}}
    static class Toast {static final int LENGTH_SHORT=0;static Toast makeText(Context c,String s,int t){return new Toast();}void show(){}}
    METHODS
    static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
    static void invalidate(RingDispatchTest h,int reason){
        switch(reason){
            case 0 -> h.close(false); // Focus change / screen-off / configuration cancellation.
            case 1 -> {h.interactionVersion++;h.session.active=true;} // A newer ring opened.
            case 2 -> h.config.get().revision++; // Config changed during the delay.
            case 3 -> h.config.get().ring=false;
            case 4 -> h.available=false;
            case 5 -> h.lock=!h.openedLocked;
        }
    }
    public static void main(String[] args){
        RingDispatchTest normal=new RingDispatchTest();normal.finish("action:notes");
        require(normal.bus.sent==0,"Official action must wait for overlay removal");
        normal.main.next();require(normal.bus.sent==1,"Normal official action executes once");
        for(int reason=0;reason<6;reason++){
            RingDispatchTest h=new RingDispatchTest();h.finish("action:notes");invalidate(h,reason);h.main.next();
            require(h.bus.sent==0,"Stale official action executed after cancellation reason "+reason);
        }
        RingDispatchTest app=new RingDispatchTest();app.finish("example.app");
        require(app.context.launches==1 && app.main.tasks.isEmpty(),"Application dispatch changed");
        RingDispatchTest locked=new RingDispatchTest();locked.lock=locked.openedLocked=true;
        locked.finish("example.app");require(locked.context.launches==0,"Locked ring launched an app");
        locked.finish("action:notes");locked.main.next();require(locked.bus.sent==1,"Locked official action blocked");
        FEEDBACK_TESTS
        System.out.println("Ring delayed dispatch checks passed (actual methods; lifecycle/Android doubles)");
    }
}
'''.replace('METHODS', methods)
feedback_tests = r'''
        for(int reason=0;reason<6;reason++){
            RingDispatchTest h=new RingDispatchTest();h.close(true);h.finishAfterFeedback("example.app");
            invalidate(h,reason);h.main.next();
            require(h.context.launches==0,"Stale application click after cancellation reason "+reason);
        }
        RingDispatchTest chain=new RingDispatchTest();chain.close(true);chain.finishAfterFeedback("action:notes");
        chain.main.next();require(chain.bus.sent==0,"Feedback phase bypassed official delay");
        chain.main.next();require(chain.bus.sent==1,"Normal two-phase action lost");
        RingDispatchTest between=new RingDispatchTest();between.close(true);between.finishAfterFeedback("action:notes");
        between.main.next();between.close(false);between.main.next();
        require(between.bus.sent==0,"Cancellation between phases ignored");
'''
harness = harness.replace('FEEDBACK_TESTS', feedback_tests)
jdk = Path(os.environ.get('JAVA_HOME', '/usr/lib/jvm/java-21-openjdk-amd64'))
with tempfile.TemporaryDirectory(prefix='focuspen-ring-dispatch-') as folder:
    path = Path(folder)
    (path/'RingDispatchTest.java').write_text(harness)
    subprocess.run([str(jdk/'bin/javac'), '-d', folder, str(path/'RingDispatchTest.java')], check=True)
    subprocess.run([str(jdk/'bin/java'), '-cp', folder, 'RingDispatchTest'], check=True)
