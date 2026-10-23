package com.focuspen.enhance.v2;
import android.content.Context;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.util.Log;

/** Uses the installed host's official animation resources and its existing Lottie renderer. */
final class GuideMedia extends FrameLayout {
    private final ImageView fallback;
    private View animation;
    private android.animation.ValueAnimator motion;
    private boolean prepared,playing,playedNative;
    private long playTime;
    private float nativeProgress;
    GuideMedia(Context c){super(c);fallback=new ImageView(c);fallback.setScaleType(ImageView.ScaleType.FIT_CENTER);addView(fallback,new LayoutParams(-1,-1));setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);}
    void prepare(int topic,int step,boolean pro){
        if(prepared)return;prepared=true;
        GuideScene scene=new GuideScene(getContext(),topic,step,pro);fallback.setImageDrawable(scene);
        motion=android.animation.ValueAnimator.ofFloat(0,1);motion.setDuration(GuideMotion.duration(topic,step));
        motion.setInterpolator(new android.view.animation.LinearInterpolator());
        motion.setRepeatCount(-1);motion.setRepeatMode(android.animation.ValueAnimator.RESTART);
        motion.addUpdateListener(a->{scene.progress=(Float)a.getAnimatedValue();scene.invalidateSelf();});
        // Standard-pen key lessons use the fixed-pen overlay.
        if(topic==0&&step>0&&!pro)return;
        String name=GuideContent.animation(topic,step,pro);if(name==null)return;
        try {
            Context c=getContext();if(!c.getPackageName().equals("com.miui.securitycore"))return;
            int id=c.getResources().getIdentifier(name,"raw","com.miui.securitycore");if(id==0)return;
            // Some ROM variants declare the Pro images but ship empty files.
            // Check the assets used by this lesson before hiding its fallback.
            if(pro&&topic!=4&&!hasProImages(c,id))return;
            Class<?> type=c.getClassLoader().loadClass("com.airbnb.lottie.LottieAnimationView");
            animation=(View)type.getConstructor(Context.class).newInstance(c);
            installFailureListener(animation);
            // Match stylus_guide_full_layout and FullGuide: handwriting lessons use
            // img/ plus the host delegate that resolves image_0 to its packaged raw
            // resource. The image layer resolves during drawing, after composition
            // failure callbacks have already completed.
            if(topic==4){
                type.getMethod("setImageAssetsFolder",String.class).invoke(animation,"img/");
                Class<?> delegate=c.getClassLoader().loadClass("t3.d");
                Object imageDelegate=delegate.getConstructor(Context.class).newInstance(c);
                type.getMethod("setImageAssetDelegate",delegate.getInterfaces()[0]).invoke(animation,imageDelegate);
            }
            if(pro&&topic!=4){
                Object delegate=c.getClassLoader().loadClass("t3.d").getConstructor(Context.class).newInstance(c);
                de.robv.android.xposed.XposedHelpers.callMethod(animation,"setImageAssetDelegate",delegate);
            }
            // setAnimation loads asynchronously. Keep this view visible so a swiped-in
            // page shows its own first frame when ready, without starting playback.
            animation.setVisibility(VISIBLE);addView(animation,new LayoutParams(-1,-1));type.getMethod("setAnimation",int.class).invoke(animation,id);
            type.getMethod("setRepeatCount",int.class).invoke(animation,-1);
            type.getMethod("setProgress",float.class).invoke(animation,0f);
            fallback.setVisibility(GONE);
        }catch(Exception e){Log.w("FocusPenGuide","Native guide setup failed topic="+topic+" step="+step+" pro="+pro,e);useFallback();}
    }
    void show(int topic,int step,boolean pro){
        prepare(topic,step,pro);if(playing)return;
        if(!android.animation.ValueAnimator.areAnimatorsEnabled())return;
        playing=true;
        if(animation!=null){
            try{
                animation.getClass().getMethod("setProgress",float.class).invoke(animation,nativeProgress);
                nativeCall(animation,playedNative?"resumeAnimation":"playAnimation",playedNative?"k":"i");
                playedNative=true;
            }
            catch(Exception e){Log.w("FocusPenGuide","Native guide playback failed topic="+topic+" step="+step+" pro="+pro,e);useFallback();return;}
            return;
        }
        if(motion!=null){motion.start();motion.setCurrentPlayTime(playTime);}
    }
    void stop(){
        if(!playing)return;
        playing=false;
        if(motion!=null&&motion.isStarted()){playTime=motion.getCurrentPlayTime();motion.cancel();}
        if(animation!=null){
            if(playedNative)try{nativeProgress=((Number)animation.getClass().getMethod("getProgress").invoke(animation)).floatValue();}catch(Exception ignored){}
            try{nativeCall(animation,"pauseAnimation","d");}catch(Exception ignored){animation.setVisibility(INVISIBLE);}
        }
    }
    private static void nativeCall(View view,String name,String obfuscated)throws Exception{
        java.lang.reflect.Method method;
        try{method=view.getClass().getMethod(name);}catch(NoSuchMethodException e){method=view.getClass().getMethod(obfuscated);}
        method.invoke(view);
    }
    private void useFallback(){
        if(animation!=null){
            try{nativeCall(animation,"cancelAnimation","d");}catch(Exception ignored){}
            removeView(animation);animation=null;
        }
        fallback.setVisibility(VISIBLE);
        if(playing&&motion!=null&&!motion.isStarted()&&android.animation.ValueAnimator.areAnimatorsEnabled()){
            motion.start();motion.setCurrentPlayTime(playTime);
        }
    }
    private void installFailureListener(View view){
        for(java.lang.reflect.Method method:view.getClass().getMethods()){
            if(!method.getName().equals("setFailureListener")||method.getParameterCount()!=1)continue;
            Class<?> listener=method.getParameterTypes()[0];if(!listener.isInterface())return;
            try{method.invoke(view,java.lang.reflect.Proxy.newProxyInstance(listener.getClassLoader(),new Class[]{listener},(proxy,callback,args)->{
                if(callback.getName().equals("hashCode"))return System.identityHashCode(proxy);
                if(callback.getName().equals("equals"))return proxy==args[0];
                if(callback.getName().equals("toString"))return "FocusPenGuideFailure";
                Log.w("FocusPenGuide","Native guide composition failed",args!=null&&args.length>0&&args[0] instanceof Throwable?(Throwable)args[0]:new IllegalStateException(callback.getName()));
                post(()->{if(animation==view)useFallback();});return null;
            }));}catch(Exception ignored){}
            return;
        }
    }
    private static boolean hasProImages(Context c,int resource)throws Exception{
        org.json.JSONArray assets;
        try(java.io.InputStream in=c.getResources().openRawResource(resource)){
            assets=new org.json.JSONObject(new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8)).optJSONArray("assets");
        }
        if(assets==null)return true;
        for(int i=0;i<assets.length();i++){
            String file=assets.getJSONObject(i).optString("p");if(file.isEmpty()||file.startsWith("data:"))continue;
            if(!file.matches("img_[0-9]+\\.png"))return false;
            int image=c.getResources().getIdentifier("touch_film_stylus_guide_"+file.substring(0,file.length()-4),"raw",c.getPackageName());
            if(image==0)return false;
            android.graphics.BitmapFactory.Options bounds=new android.graphics.BitmapFactory.Options();bounds.inJustDecodeBounds=true;
            android.graphics.BitmapFactory.decodeResource(c.getResources(),image,bounds);
            if(bounds.outWidth<=0||bounds.outHeight<=0)return false;
        }
        return true;
    }
    @Override protected void onDetachedFromWindow(){stop();super.onDetachedFromWindow();}
}
