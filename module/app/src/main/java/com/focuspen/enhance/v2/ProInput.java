package com.focuspen.enhance.v2;

import android.os.Handler;
import android.os.SystemClock;
import android.view.KeyEvent;
import java.util.*;

/** Pro pinches are a sequence of key194 pairs; key195 is the distinct native body double-tap. */
final class ProInput {
    interface Host {
        void action(String action,boolean release,KeyTarget target,KeyEvent event,long mouseSession);
        void single(KeyTarget target,List<KeyEvent> events);
        void nativeEvents(Object manager,List<KeyEvent> events);
        boolean valid(KeyTarget target);
    }
    private final Handler handler;
    private final Host host;
    private final PinchSequence sequence=new PinchSequence();
    private final List<KeyEvent> events=new ArrayList<>();
    private final Map<Integer,KeyEvent> owned=new HashMap<>();
    private ConfigSnapshot snapshot;
    private KeyTarget target;
    private Object manager;
    private boolean mouse;
    private long mouseSession;
    private KeyEvent origin;
    private boolean replayedHold;
    private final Runnable timer;
    ProInput(Handler handler,Host host,Object inputLock){this.handler=handler;this.host=host;timer=()->{synchronized(inputLock){synchronized(this){if(target==null)return;if(!host.valid(target)){cancel();return;}sequence.tick(SystemClock.uptimeMillis(),this::gesture);schedule();}}};}
    synchronized boolean owns(KeyEvent e){KeyEvent old=owned.get(e.getKeyCode());return old!=null&&old.getDeviceId()==e.getDeviceId()&&old.getDownTime()==e.getDownTime();}
    synchronized boolean event(Object manager,KeyEvent e,int slot,boolean eligible,ConfigSnapshot snapshot,KeyTarget target){
        KeyEvent owner=owned.get(e.getKeyCode());boolean paired=owner!=null&&owner.getDeviceId()==e.getDeviceId()&&owner.getDownTime()==e.getDownTime();
        if(slot==1){if(paired){if(e.getAction()==1)owned.remove(e.getKeyCode());return true;}if(!eligible||!PenMouse.active())return false;if(e.getAction()==0&&e.getRepeatCount()==0){owned.put(e.getKeyCode(),new KeyEvent(e));PenMouse.exit();}return true;}
        if(paired&&(!eligible||e.isCanceled())){if(e.getAction()==1)owned.remove(e.getKeyCode());cancel();return true;}
        if(!eligible)return paired;
        if(slot>=2){
            String action=snapshot.proBinding(target.pkg(),PenMouse.active()?slot+8:slot);
            if(paired){if(e.getAction()==1)owned.remove(e.getKeyCode());return true;}
            if(action.equals("default"))return false;
            if(e.getAction()!=0||e.getRepeatCount()!=0)return false;owned.put(e.getKeyCode(),new KeyEvent(e));
            long expectedMouse=PenMouse.session();
            handler.post(()->{if(host.valid(target))host.action(action,false,target,e,expectedMouse);});return true;
        }
        if(slot!=0)return false;
        if(e.getAction()==0&&e.getRepeatCount()==0){
            if(this.target!=null&&!this.target.matches(target.window(),target.focusEpoch(),target.revision()))cancel();
            sequence.tick(SystemClock.uptimeMillis(),this::gesture);
            if(events.isEmpty()){this.snapshot=snapshot;this.target=target;this.manager=manager;this.origin=new KeyEvent(e);mouse=PenMouse.active();mouseSession=PenMouse.session();}
            owned.put(e.getKeyCode(),new KeyEvent(e));events.add(new KeyEvent(e));sequence.down(SystemClock.uptimeMillis(),snapshot.number(ProBindings.HOLD));schedule();return true;
        }
        if(!paired)return false;
        if(this.target==null){if(e.getAction()==KeyEvent.ACTION_UP)owned.remove(e.getKeyCode());return true;}
        if(e.getAction()==1){owned.remove(e.getKeyCode());events.add(new KeyEvent(e));sequence.tick(SystemClock.uptimeMillis(),this::gesture);sequence.up(SystemClock.uptimeMillis(),snapshot.number(ProBindings.INTERVAL),max(),this::gesture);schedule();}
        return true;
    }
    private int max(){return !snapshot.proBinding(target.pkg(),4).equals("off")||!snapshot.proBinding(target.pkg(),5).equals("off")?4:2;}
    private void gesture(int count,boolean hold,boolean release){
        if(target==null)return;
        if(release){if(replayedHold&&!events.isEmpty()){KeyEvent last=events.get(events.size()-1);if(last.getAction()==1)host.nativeEvents(manager,List.copyOf(events));}replayedHold=false;host.action("release",true,target,origin,mouseSession);events.clear();return;}
        if(!host.valid(target)){events.clear();return;}
        int slot=count==4?(hold?5:4):mouse?(count==1?(hold?7:6):(hold?9:8)):(hold?1:0);
        if(count==3){events.clear();return;}
        if(!mouse&&count==1){if(!hold)host.single(target,List.copyOf(events));if(!hold)events.clear();return;}
        String action=snapshot.proBinding(target.pkg(),slot);
        if(action.equals("default")){if(!hold)host.nativeEvents(manager,List.copyOf(events));replayedHold=hold;}
        else if(!action.equals("off"))host.action(action,false,target,origin,mouseSession);
        if(!hold)events.clear();
    }
    private void schedule(){handler.removeCallbacks(timer);if(sequence.deadline()!=Long.MAX_VALUE)handler.postAtTime(timer,sequence.deadline());}
    synchronized void cancel(){replayedHold=false;sequence.cancel(this::gesture);events.clear();target=null;handler.removeCallbacks(timer);}
}
