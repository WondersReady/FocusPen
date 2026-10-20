package com.focuspen.enhance.v2;

/** Finite spring with continuous position/velocity, including interrupted transitions. */
final class RingMotion {
    private long start;
    private float from, target, velocity, damping = 1;
    private int duration;

    void target(float value, long now, boolean glass, boolean reduce) {
        float current=value(now), speed=speed(now);
        from=current; target=value; start=now;
        damping=glass&&value>from?.78f:1;
        duration=reduce?0:value<from?(glass?200:140):(glass?360:220);
        velocity=speed*duration;
    }
    float value(long now) {
        if(duration==0||now-start>=duration)return target;
        double t=Math.max(0,now-start)/(double)duration;
        // Remove remaining displacement and speed smoothly instead of snapping
        // to the target on the last frame. Corrections preserve the starting speed.
        return (float)(raw(t,false)-(raw(1,false)-target)*h01(t)-raw(1,true)*h11(t));
    }
    private float speed(long now){
        if(duration==0||now-start>=duration)return 0;
        double t=Math.max(0,now-start)/(double)duration;
        return (float)((raw(t,true)-(raw(1,false)-target)*(6*t-6*t*t)
                -raw(1,true)*(3*t*t-2*t))/duration);
    }
    private double raw(double t,boolean derivative){
        double a=from-target,w=8*damping,e=Math.exp(-w*t);
        if(damping>=1){double b=velocity+w*a;
            return derivative?e*(b-w*(a+b*t)):target+e*(a+b*t);}
        double k=8*Math.sqrt(1-damping*damping),b=(velocity+w*a)/k;
        double wave=a*Math.cos(k*t)+b*Math.sin(k*t);
        return derivative?e*(-w*wave+k*(-a*Math.sin(k*t)+b*Math.cos(k*t))):target+e*wave;
    }
    private static double h01(double t){return t*t*(3-2*t);}
    private static double h11(double t){return t*t*(t-1);}
    boolean running(long now){return duration>0&&now-start<duration&&(from!=target||velocity!=0);}
    void reset(){from=target=velocity=0;duration=0;}
}
