#!/usr/bin/env python3
"""Exercise RingView's production touch handler with deterministic input events."""
import os
from pathlib import Path
import subprocess
import tempfile


root = Path(__file__).resolve().parents[2]
source = (root / 'module/app/src/main/java/com/focuspen/enhance/v2/RingHost.java').read_text()
signature = '@Override public boolean onTouchEvent(MotionEvent e)'
start = source.index(signature)
opening = source.index('{', start)
depth = 1
end = opening + 1
while depth:
    depth += (source[end] == '{') - (source[end] == '}')
    end += 1
method = source[start:end].replace('@Override ', '', 1)

harness = r'''
import java.util.Objects;

public class RingTouchTest {
    static class MotionEvent {
        static final int ACTION_DOWN=0, ACTION_UP=1, ACTION_MOVE=2, ACTION_CANCEL=3;
        static final int ACTION_POINTER_DOWN=5, ACTION_POINTER_UP=6;
        final int action; final float x; final long downTime;
        MotionEvent(int action, float x) { this.action=action; this.x=x; this.downTime=100; }
        int getActionMasked() { return action; }
        float getX() { return x; }
        float getY() { return 0; }
        long getDownTime() { return downTime; }
    }
    static class SystemClock { static long uptimeMillis() { return 200; } }
    static class HapticFeedbackConstants { static final int CLOCK_TICK=1; }
    static class Session {
        boolean active=true; String selection;
        boolean active() { return active; }
        boolean select(String id) { boolean changed=!Objects.equals(selection,id); selection=id; return changed; }
        String click(String id) { if (!active || id==null) return null; active=false; return id; }
    }
    final Session session=new Session();
    long opened=100;
    int executions, closes;
    void close(boolean animate) { session.active=false; closes++; }
    void finish(String target) { if (target!=null) executions++; }
    void finishAfterFeedback(String target) { finish(target); }
    class RingView {
        boolean pointPressed; String pressedId; long pressedUntil;
        String hit(float x,float y) { return x>=0 && x<10 ? "notes" : x>=10 && x<20 ? "screenshot" : null; }
        void focusChanged() {}
        void ripple(float x,float y) {}
        boolean reduce() { return true; }
        void performHapticFeedback(int feedback) {}
        void invalidate() {}
        METHOD
    }
    void send(RingView view,int action,float x) { view.onTouchEvent(new MotionEvent(action,x)); }
    static void require(boolean condition,String message) { if (!condition) throw new AssertionError(message); }
    public static void main(String[] args) {
        RingTouchTest tap=new RingTouchTest(); RingView v=tap.new RingView();
        tap.send(v,MotionEvent.ACTION_DOWN,5); tap.send(v,MotionEvent.ACTION_UP,5);
        require(tap.executions==1,"Single-finger tap did not activate its icon");

        RingTouchTest multi=new RingTouchTest(); v=multi.new RingView();
        multi.send(v,MotionEvent.ACTION_DOWN,5);
        multi.send(v,MotionEvent.ACTION_POINTER_DOWN,15);
        multi.send(v,MotionEvent.ACTION_POINTER_UP,15);
        multi.send(v,MotionEvent.ACTION_UP,5);
        require(multi.executions==0 && multi.session.active(),"Second finger activated first icon");

        RingTouchTest slide=new RingTouchTest(); v=slide.new RingView();
        slide.send(v,MotionEvent.ACTION_DOWN,5);
        slide.send(v,MotionEvent.ACTION_MOVE,15);
        slide.send(v,MotionEvent.ACTION_MOVE,5);
        slide.send(v,MotionEvent.ACTION_UP,5);
        require(slide.executions==0 && slide.session.active(),"Leaving and returning rearmed the icon");

        RingTouchTest other=new RingTouchTest(); v=other.new RingView();
        other.send(v,MotionEvent.ACTION_DOWN,5); other.send(v,MotionEvent.ACTION_UP,15);
        require(other.executions==0,"Releasing over another icon activated a target");

        RingTouchTest cancel=new RingTouchTest(); v=cancel.new RingView();
        cancel.send(v,MotionEvent.ACTION_DOWN,5); cancel.send(v,MotionEvent.ACTION_CANCEL,5);
        cancel.send(v,MotionEvent.ACTION_UP,5);
        require(cancel.executions==0,"Cancelled touch activated an icon");

        RingTouchTest outside=new RingTouchTest(); v=outside.new RingView();
        outside.send(v,MotionEvent.ACTION_DOWN,30);
        require(outside.closes==1 && outside.executions==0,"Outside tap did not close the ring");
        System.out.println("Ring touch checks passed (production handler; input doubles)");
    }
}
'''.replace('METHOD', method)

jdk = Path(os.environ.get('JAVA_HOME', '/usr/lib/jvm/java-21-openjdk-amd64'))
with tempfile.TemporaryDirectory(prefix='focuspen-ring-touch-') as folder:
    path = Path(folder)
    (path / 'RingTouchTest.java').write_text(harness)
    subprocess.run([str(jdk / 'bin/javac'), '-d', folder, str(path / 'RingTouchTest.java')], check=True)
    subprocess.run([str(jdk / 'bin/java'), '-cp', folder, 'RingTouchTest'], check=True)
