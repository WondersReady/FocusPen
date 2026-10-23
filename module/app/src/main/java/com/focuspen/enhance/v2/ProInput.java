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
        void applicationEvents(KeyTarget target,List<KeyEvent> events);
        boolean valid(KeyTarget target);
    }
    private static final class Press {
        final KeyEvent down;
        final KeyTarget target;
        final boolean application;
        private int lastAction,lastRepeat,lastFlags;
        private long lastTime;
        Press(KeyEvent event,KeyTarget target,boolean application){
            down=new KeyEvent(event);this.target=target;this.application=application;record(event);
        }
        boolean record(KeyEvent event){
            if(lastTime==event.getEventTime()&&lastAction==event.getAction()
                    &&lastRepeat==event.getRepeatCount()&&lastFlags==event.getFlags())return false;
            lastTime=event.getEventTime();lastAction=event.getAction();
            lastRepeat=event.getRepeatCount();lastFlags=event.getFlags();return true;
        }
    }
    private final Handler handler;
    private final Host host;
    private final PinchSequence sequence=new PinchSequence();
    private final List<KeyEvent> events=new ArrayList<>();
    private final Map<Integer,Press> owned=new HashMap<>();
    private ConfigSnapshot snapshot;
    private KeyTarget target;
    private boolean mouse;
    private long mouseSession;
    private KeyEvent origin;
    private boolean applicationHold;
    private boolean mouseHoldAction;
    private boolean canceling;
    private final Runnable timer;
    ProInput(Handler handler,Host host,Object inputLock){this.handler=handler;this.host=host;timer=()->{synchronized(inputLock){synchronized(this){if(target==null)return;if(!host.valid(target)){cancel();return;}sequence.tick(SystemClock.uptimeMillis(),this::gesture);schedule();}}};}
    synchronized boolean owns(KeyEvent e){Press old=owned.get(e.getKeyCode());return old!=null&&old.down.getDeviceId()==e.getDeviceId()&&old.down.getDownTime()==e.getDownTime();}
    synchronized boolean event(Object manager,KeyEvent e,int slot,boolean eligible,ConfigSnapshot snapshot,KeyTarget target){
        Press owner=owned.get(e.getKeyCode());boolean paired=owner!=null&&owner.down.getDeviceId()==e.getDeviceId()&&owner.down.getDownTime()==e.getDownTime();
        if(paired&&(!eligible||e.isCanceled())){
            if(owner.application&&e.getAction()==KeyEvent.ACTION_UP&&owner.record(e))
                host.applicationEvents(owner.target,List.of(KeyEvent.changeFlags(e,e.getFlags()|KeyEvent.FLAG_CANCELED)));
            if(e.getAction()==KeyEvent.ACTION_UP)owned.remove(e.getKeyCode());
            if(slot==0)cancel();return true;
        }
        if(slot==1){
            if(paired){forward(owner,e);if(e.getAction()==KeyEvent.ACTION_UP)owned.remove(e.getKeyCode());return true;}
            if(!eligible||e.getAction()!=KeyEvent.ACTION_DOWN||e.getRepeatCount()!=0)return false;
            boolean application=!PenMouse.active();owned.put(e.getKeyCode(),new Press(e,target,application));
            if(application)host.applicationEvents(target,List.of(new KeyEvent(e)));
            else host.action("exitMouse",false,target,e,PenMouse.session());
            return true;
        }
        if(!eligible)return paired;
        if(slot>=2){
            // Firmware gesture names are UP(196)/DOWN(197); application page
            // direction uses a separate mapping and must not rename these slots.
            String action=snapshot.proBinding(target.pkg(),PenMouse.active()?slot+8:slot);
            if(paired){forward(owner,e);if(e.getAction()==KeyEvent.ACTION_UP)owned.remove(e.getKeyCode());return true;}
            if(e.getAction()!=KeyEvent.ACTION_DOWN||e.getRepeatCount()!=0)return false;
            boolean application=application(action);owned.put(e.getKeyCode(),new Press(e,target,application));
            if(application){host.applicationEvents(target,List.of(new KeyEvent(e)));return true;}
            long expectedMouse=PenMouse.session();
            handler.post(()->{if(host.valid(target))host.action(action,false,target,e,expectedMouse);});return true;
        }
        if(slot!=0)return false;
        if(paired&&e.getAction()==KeyEvent.ACTION_DOWN){if(owner.record(e))events.add(new KeyEvent(e));return true;}
        if(e.getAction()==0&&e.getRepeatCount()==0){
            if(this.target!=null&&!this.target.matches(target.window(),target.focusEpoch(),target.revision()))cancel();
            sequence.tick(SystemClock.uptimeMillis(),this::gesture);
            if(events.isEmpty()){this.snapshot=snapshot;this.target=target;this.origin=new KeyEvent(e);mouse=PenMouse.active();mouseSession=PenMouse.session();}
            owned.put(e.getKeyCode(),new Press(e,target,false));events.add(new KeyEvent(e));sequence.down(SystemClock.uptimeMillis(),snapshot.number(ProBindings.HOLD));schedule();return true;
        }
        if(!paired)return false;
        if(this.target==null){if(e.getAction()==KeyEvent.ACTION_UP)owned.remove(e.getKeyCode());return true;}
        if(e.getAction()==1){owned.remove(e.getKeyCode());if(owner.record(e))events.add(new KeyEvent(e));sequence.tick(SystemClock.uptimeMillis(),this::gesture);sequence.up(SystemClock.uptimeMillis(),snapshot.number(ProBindings.INTERVAL),max(),this::gesture);schedule();}
        return true;
    }
    private static boolean application(String action){return action.equals("default")||action.equals("none");}
    private void forward(Press owner,KeyEvent event){
        if(owner.application&&owner.record(event))host.applicationEvents(owner.target,List.of(new KeyEvent(event)));
    }
    private int max(){return !snapshot.proBinding(target.pkg(),4).equals("off")||!snapshot.proBinding(target.pkg(),5).equals("off")?4:2;}
    private void gesture(int count,boolean hold,boolean release){
        if(target==null)return;
        if(release){
            if(applicationHold&&!canceling&&!events.isEmpty())host.applicationEvents(target,List.copyOf(events));
            else if(!applicationHold)host.action("release",true,target,origin,mouseSession);
            applicationHold=false;mouseHoldAction=false;events.clear();return;
        }
        if(!host.valid(target)){events.clear();return;}
        int slot=count==4?(hold?5:4):mouse?(count==1?(hold?7:6):(hold?9:8)):(hold?1:0);
        if(count==3){if(hold)applicationHold=true;else {host.applicationEvents(target,List.copyOf(events));events.clear();}return;}
        if(!mouse&&count==1){if(hold)applicationHold=true;else {host.single(target,List.copyOf(events));events.clear();}return;}
        String action=snapshot.proBinding(target.pkg(),slot);
        if(application(action)){if(!hold)host.applicationEvents(target,List.copyOf(events));applicationHold=hold;}
        else if(!action.equals("off")){
            if(mouse&&hold&&(action.equals("leftHold")||action.equals("rightHold")))mouseHoldAction=true;
            host.action(action,false,target,origin,mouseSession);
        }
        if(!hold)events.clear();
    }
    private void schedule(){handler.removeCallbacks(timer);if(sequence.deadline()!=Long.MAX_VALUE)handler.postAtTime(timer,sequence.deadline());}
    synchronized void focusChanged(){if(!mouseHoldAction)cancel();}
    synchronized void docked(){cancel();owned.clear();}
    synchronized void cancel(){canceling=true;sequence.cancel(this::gesture);canceling=false;applicationHold=false;mouseHoldAction=false;events.clear();target=null;handler.removeCallbacks(timer);}
}
