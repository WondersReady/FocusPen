package com.focuspen.enhance.v2;

import android.app.KeyguardManager;
import android.animation.ValueAnimator;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.database.ContentObserver;
import android.provider.Settings;
import android.widget.FrameLayout;
import java.util.List;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Insets;
import android.graphics.Region;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.WindowInsets;
import android.view.WindowMetrics;
import android.widget.Toast;
import java.lang.reflect.Proxy;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/** One reusable window owns both pen coordinates and ring selection. */
final class RingHost {
    private final Context context;
    private final ConfigClient config;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final WindowManager wm;
    private final WindowManager.LayoutParams params;
    private final RingSession session = new RingSession();
    private final RingView view;
    private final RuntimeBus bus;
    private final Object motionLock = new Object();
    private final PenMotionQueue<Bundle> pendingMotion = new PenMotionQueue<>();
    private boolean motionPosted;
    private final PenPointer pointer = new PenPointer();
    private RingLayout layout;
    private boolean attached, hover;
    private boolean openedLocked;
    private float tipX, tipY, anchorX, anchorY, direction = Float.NaN;
    private List<MenuEntry> entries = List.of();
    private long opened;
    private long interactionVersion;
    private String foreground = "";
    private final Runnable watchdog = new Runnable() {
        @Override public void run() {
            boolean pointerExpired = pointer.expire(SystemClock.uptimeMillis());
            if (pointerExpired) {
                hover = false;
                if (session.select(null)) view.focusChanged();
            }
            if (session.expire(SystemClock.uptimeMillis()) || !usable()
                    || session.active() && openedLocked != locked()) close();
            else updateWindow(pointerExpired);
            if (attached) main.postDelayed(this, 150);
        }
    };

    RingHost(Context c, ClassLoader cl, ConfigClient client) throws Exception {
        context = c; config = client; wm = c.getSystemService(WindowManager.class);
        if (c.getPackageManager().getPackageInfo(RuntimeBus.UI, 0).getLongVersionCode() != 202602260L)
            throw new IllegalStateException("Unsupported SystemUI version");
        params = new WindowManager.LayoutParams(-1, -1, 2018,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.LEFT; params.setTitle("FocusPenRing"); params.setFitInsetsTypes(0);
        // SystemUI already holds INTERNAL_SYSTEM_WINDOW. A touch region alone does not exempt
        // an opaque application overlay from InputDispatcher's untrusted-touch occlusion check.
        XposedHelpers.callMethod(params, "setTrustedOverlay");
        view = new RingView(c);
        ContentObserver materialObserver = new ContentObserver(main) {
            @Override public void onChange(boolean self) { view.surface.refresh(); view.invalidate(); }
        };
        c.getContentResolver().registerContentObserver(Settings.Secure.getUriFor("material_style"), false, materialObserver);
        c.getContentResolver().registerContentObserver(Settings.Secure.getUriFor("background_blur_enable"), false, materialObserver);
        c.getContentResolver().registerContentObserver(Settings.Global.getUriFor("animator_duration_scale"), false,
                new ContentObserver(main) {
                    @Override public void onChange(boolean self) { updateWindow(); }
                });
        bus = new RuntimeBus(c, (event, payload) -> {
            if ("motion".equals(event)) { enqueueMotion(payload); return; }
            main.post(() -> {
                try { receive(event, payload); } catch (Throwable e) { close(); Log.w("FocusPenRing", "Input failed", e); }
            });
        }, () -> {
            config.refresh(); main.post(this::publishState);
        });
        config.add(s -> { if (session.active() || !s.enabled() || !s.flag(ConfigSpec.RING)) close(); view.surface.refresh(); view.invalidate(); });
        IntentFilter filter = new IntentFilter(); filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_CLOSE_SYSTEM_DIALOGS); filter.addAction(Intent.ACTION_CONFIGURATION_CHANGED);
        filter.addAction(Intent.ACTION_USER_PRESENT);
        c.registerReceiver(new BroadcastReceiver() {
            @Override public void onReceive(Context ignored, Intent intent) {
                hover = false; pointer.clear(); close();
            }
        }, filter, Context.RECEIVER_EXPORTED);

    }

    private boolean usable() {
        return config.get().enabled() && context.getSystemService(PowerManager.class).isInteractive();
    }
    private boolean locked() { return context.getSystemService(KeyguardManager.class).isKeyguardLocked(); }
    private void enqueueMotion(Bundle payload) {
        if (payload == null) return;
        synchronized (motionLock) {
            pendingMotion.add(payload.getInt("action"), payload.getInt("device"),
                    payload.getInt("display", -1), new Bundle(payload));
            if (motionPosted) return;
            motionPosted = true;
        }
        main.post(() -> {
            java.util.ArrayList<Bundle> batch = new java.util.ArrayList<>();
            synchronized (motionLock) {
                Bundle next;
                while ((next = pendingMotion.poll()) != null) batch.add(next);
                motionPosted = false;
            }
            for (Bundle next : batch) {
                try { receive("motion", next); } catch (Throwable e) { close(); Log.w("FocusPenRing", "Input failed", e); }
            }
        });
    }
    private void receive(String event, Bundle b) {
        if ("preview".equals(event)) {
            if (!open(0))
                Toast.makeText(context, "快捷环暂不可用，请检查弧形菜单设置", Toast.LENGTH_SHORT).show();
            return;
        }
        if ("actionError".equals(event)) { Toast.makeText(context, "此官方功能暂不可用", Toast.LENGTH_SHORT).show(); return; }
        if ("motion".equals(event)) { motion(b); return; }
        if ("focus".equals(event)) {
            String next = b.getString("package", "");
            foreground = next;
            if (!"com.android.systemui".equals(next)) { hover = false; pointer.clear(); close(); }
            return;
        }
        if ("cancel".equals(event)) { close(true); return; }
        if (!"action".equals(event) || !usable()) return;
        PenAction action = PenAction.parse(b.getString("action", "none"));
        if (locked() && action != PenAction.RING) return;
        if (action == PenAction.RING && config.get().flag(ConfigSpec.RING)) {
            // Take the latest pen pose at invocation time. The layout is then frozen for
            // this menu session so the targets do not move while the user selects one.
            open(b.getInt("button", 1));
        } else if (action == PenAction.RECOGNITION) {
            try {
                Intent i = new Intent(Intent.ACTION_ASSIST).setClassName("com.miui.voiceassist", "com.xiaomi.voiceassistant.VoiceService")
                        .putExtra("voice_assist_start_from_key", "long_press_home_key")
                        .putExtra("voice_assist_function_key", "start_screen_recognition");
                context.startService(i);
            } catch (Throwable e) { Toast.makeText(context, "小爱识屏暂不可用", Toast.LENGTH_SHORT).show(); }
        } else if (action == PenAction.MEDIA) {
            android.media.AudioManager audio = context.getSystemService(android.media.AudioManager.class);
            audio.dispatchMediaKeyEvent(new android.view.KeyEvent(0, 85));
            audio.dispatchMediaKeyEvent(new android.view.KeyEvent(1, 85));
        }
    }
    private void motion(Bundle b) {
        if (!usable()) { hover = false; pointer.clear(); close(); return; }
        long now = SystemClock.uptimeMillis(), time = b.getLong("time");
        float x = b.getFloat("x"), y = b.getFloat("y");
        int action = b.getInt("action");
        PenPointer.Update update = pointer.accept(action, b.getInt("device"), b.getInt("display", -1),
                wm.getDefaultDisplay().getDisplayId(), time, now, x, y);
        if (!update.accepted()) return;
        if (update.deviceChanged()) close();
        tipX = pointer.x(); tipY = pointer.y(); hover = pointer.hover();
        direction = b.getBoolean("orientationValid") ? b.getFloat("orientation") - (float)Math.PI / 2 : Float.NaN;
        if (hover) {
            if (session.active() && session.select(view.hit(x, y))) {
                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); view.focusChanged();
            }
        } else {
            // Leaving hover cannot leave a stale target that a later key release accidentally launches.
            if (session.select(null)) view.focusChanged();
            if (update.contact() && session.active()) view.ripple(x, y);
        }
        updateWindow();
    }
    private boolean open(int button) {
        if (!usable() || !config.get().flag(ConfigSpec.RING)) return false;
        try {
            openedLocked = locked();
            entries = openedLocked ? MenuEntry.locked(context) : MenuEntry.resolve(context, config.get().text(ConfigSpec.CONTENT));
            if (entries.isEmpty()) { Toast.makeText(context, "请先在弧形菜单中添加可用项目", Toast.LENGTH_SHORT).show(); return false; }
            WindowMetrics metrics = wm.getCurrentWindowMetrics(); Rect bounds = metrics.getBounds();
            Insets insets = metrics.getWindowInsets().getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            boolean recent = !openedLocked && pointer.recent(SystemClock.uptimeMillis());
            float x = recent ? pointer.x() : bounds.centerX();
            float y = recent ? pointer.y() : bounds.centerY();
            float requestedDirection = openedLocked ? -(float)Math.PI / 2 : recent && Float.isFinite(direction) ? direction : Float.NaN;
            layout = RingLayout.place(x, y, bounds.left + insets.left, bounds.top + insets.top,
                    bounds.right - insets.right, bounds.bottom - insets.bottom, view.dp(1), entries.size(),
                    requestedDirection);
            anchorX = layout.x; anchorY = layout.y; view.configureSurface();
            interactionVersion++;
            session.open(button, SystemClock.uptimeMillis());
            view.pointPressed = false;
            opened = SystemClock.uptimeMillis();
            view.begin();
            updateWindow(); publishState(); view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            return attached;
        } catch (Throwable e) { close(); Log.w("FocusPenRing", "Cannot open ring", e); return false; }
    }
    private boolean pendingActionValid(long version, long revision) {
        return version == interactionVersion && revision == config.get().revision
                && !session.active() && usable() && openedLocked == locked() && config.get().flag(ConfigSpec.RING);
    }
    private void finishAfterFeedback(String target) {
        long version = interactionVersion, revision = config.get().revision;
        main.postDelayed(() -> {
            if (pendingActionValid(version, revision)) finish(target);
        }, 90);
    }
    private void finish(String packageName) {
        close(false);
        if (packageName == null) return;
        if (RingContent.isAction(packageName)) {
            Bundle action = new Bundle(); action.putString("id", packageName); action.putBoolean("locked", openedLocked);
            long version = interactionVersion, revision = config.get().revision;
            // Allow the removed overlay to leave the compositor before capture/quick-note.
            main.postDelayed(() -> {
                if (!pendingActionValid(version, revision)) return;
                if (!bus.send(RuntimeBus.INPUT, "officialAction", action))
                    Toast.makeText(context, "系统动作服务暂不可用", Toast.LENGTH_SHORT).show();
            }, 100);
            return;
        }
        if (locked()) return;
        try {
            Intent i = context.getPackageManager().getLaunchIntentForPackage(packageName);
            if (i == null) throw new IllegalStateException("No launch entry");
            context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Throwable e) { Toast.makeText(context, "此应用暂时无法打开", Toast.LENGTH_SHORT).show(); }
    }
    private void close() { close(false); }
    private void close(boolean animate) {
        interactionVersion++;
        boolean wasActive = session.active();
        boolean wasVisible = wasActive || view.visibility.value(SystemClock.uptimeMillis()) > 0;
        if (wasActive) view.renderState = session.state();
        session.cancel(); view.pointPressed = false; view.pressedId = null; view.pressedUntil = 0;
        if (animate && wasVisible && usable()) view.end();
        else if (!animate) view.stop();
        view.requestLayout(); updateWindow(); publishState();
    }
    private void publishState() {
        if (bus == null) return;
        Bundle b = new Bundle(); b.putBoolean("ready", usable()); b.putBoolean("ring", session.active());
        b.putBoolean("hold", false); b.putInt("button", session.opener());
        bus.send(RuntimeBus.INPUT, "uiState", b);
    }
    private void updateWindow() {
        updateWindow(true);
    }
    private void updateWindow(boolean redraw) {
        boolean show = usable() && (session.active() || view.exiting() || hover && config.get().preview(foreground));
        try {
            if (!show) {
                if (attached) wm.removeViewImmediate(view);
                attached = false; main.removeCallbacks(watchdog); return;
            }
            int flags = params.flags;
            if (session.active()) params.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            else params.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            if (!attached) { wm.addView(view, params); attached = true; main.postDelayed(watchdog, 150); }
            else if (flags != params.flags) wm.updateViewLayout(view, params);
            // A liveness check is not a visual change. Motion and animations invalidate
            // explicitly; keep the timeout guard without redrawing a stationary ring.
            if (redraw) view.invalidate();
        } catch (Throwable e) {
            session.cancel(); hover = false;
            if (view.getParent() != null) try { wm.removeViewImmediate(view); } catch (Throwable ignored) { }
            attached = false; Log.w("FocusPenRing", "Window unavailable", e);
        }
    }

    private final class RingView extends FrameLayout {
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        final Object insetsListener;
        final SystemArcMaterial surface;
        final RingMotion visibility = new RingMotion();
        final java.util.Map<String,ItemFeedback> feedback=new java.util.HashMap<>();
        final class ItemFeedback {
            final RingMotion focus=new RingMotion(),press=new RingMotion();
            boolean selected,pressed;
            void update(boolean nextSelected,boolean nextPressed,long now,boolean reduced){
                if(selected!=nextSelected){selected=nextSelected;focus.target(selected?1:0,now,glass(),reduced);}
                if(pressed!=nextPressed){pressed=nextPressed;press.target(pressed?1:0,now,false,reduced);}
            }
        }
        RingSession.State renderState = RingSession.State.IDLE;
        long rippleStart; float rippleX, rippleY;
        boolean pointPressed;
        String pressedId;
        long pressedUntil;
        RingView(Context c) throws Exception {
            super(c); setWillNotDraw(false); setClipChildren(false); setClipToPadding(false);
            surface = new SystemArcMaterial(c); addView(surface, new FrameLayout.LayoutParams(1, 1));
            surface.setVisibility(INVISIBLE);
            try { XposedHelpers.callMethod(this, "setPassWindowBlurEnabled", true); } catch (Throwable ignored) { }
            Class<?> iface = Class.forName("android.view.ViewTreeObserver$OnComputeInternalInsetsListener");
            insetsListener = Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[]{iface}, (proxy, method, args) -> {
                if ("onComputeInternalInsets".equals(method.getName())) {
                    try {
                    Object info = args[0]; XposedHelpers.callMethod(info, "setTouchableInsets", 3);
                    Region region = (Region) XposedHelpers.getObjectField(info, "touchableRegion"); region.setEmpty();
                    if (session.active()) region.set(0, 0, getWidth(), getHeight());
                    } catch (Throwable failure) { failInputRegion(failure); }
                    return null;
                }
                if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
                if ("equals".equals(method.getName())) return proxy == args[0];
                return "FocusPenInputRegion";
            });
        }
        @Override protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            try { XposedHelpers.callMethod(getViewTreeObserver(), "addOnComputeInternalInsetsListener", insetsListener); }
            catch (Throwable failure) { failInputRegion(failure); }
            surface.refresh();
        }
        @Override protected void onDetachedFromWindow() {
            try { XposedHelpers.callMethod(getViewTreeObserver(), "removeOnComputeInternalInsetsListener", insetsListener); }
            catch (Throwable failure) { Log.w("FocusPenRing", "Insets listener cleanup failed", failure); }
            pointPressed = false; pressedId = null; pressedUntil = 0;

            super.onDetachedFromWindow();
        }
        private void failInputRegion(Throwable failure) {
            session.cancel(); hover = false;
            params.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            main.post(RingHost.this::close);
            Log.w("FocusPenRing", "Input region unavailable; ring closed", failure);
        }
        float dp(float value) { return value * getResources().getDisplayMetrics().density; }
        boolean glass() { return surface.glass(); }
        boolean reduce() { return !ValueAnimator.areAnimatorsEnabled(); }
        void begin() {
            long now = SystemClock.uptimeMillis();
            renderState = session.state(); feedback.clear(); rippleStart = 0;
            visibility.target(1, now, glass(), reduce()); requestLayout();
        }
        void end() { visibility.target(0, SystemClock.uptimeMillis(), glass(), reduce()); }
        void stop() { visibility.reset(); feedback.clear(); rippleStart = 0; surface.setVisibility(INVISIBLE); }
        boolean exiting() { return !session.active() && visibility.running(SystemClock.uptimeMillis()); }
        String hit(float x, float y) {
            int index = layout == null ? -1 : layout.hit(x,y);
            return index >= 0 && index < entries.size() ? entries.get(index).id : null;
        }
        void configureSurface() {
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams((int)Math.ceil(layout.right-layout.left), (int)Math.ceil(layout.bottom-layout.top));
            lp.leftMargin = (int)layout.left; lp.topMargin = (int)layout.top;
            surface.setLayoutParams(lp); surface.shape(layout); surface.refresh();
        }
        void ripple(float x, float y) { rippleX = x; rippleY = y; rippleStart = SystemClock.uptimeMillis(); invalidate(); }
        void focusChanged() {
            invalidate();
        }
        @Override public boolean onTouchEvent(MotionEvent e) {
            if (!session.active() || e.getDownTime() < opened) return false;
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
                String id = hit(e.getX(), e.getY());
                if (id != null) { if (session.select(id)) focusChanged(); }
                else { close(true); return true; }
                pointPressed = true; pressedId = id; pressedUntil = SystemClock.uptimeMillis() + 180;
                ripple(e.getX(), e.getY());
            }
            if (e.getActionMasked() == MotionEvent.ACTION_UP && pointPressed) {
                String id = hit(e.getX(), e.getY());
                if (id != null && id.equals(pressedId)) {
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                    String target = session.click(id);
                    if (reduce()) finish(target);
                    else {
                        close(true);
                        pressedId = id;
                        pressedUntil = SystemClock.uptimeMillis() + 90;
                        finishAfterFeedback(target);
                    }
                }
            }
            if (e.getActionMasked() == MotionEvent.ACTION_UP || e.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                pointPressed = false;
                if (session.active()) { pressedId = null; pressedUntil = 0; }
            }
            invalidate();
            return true;
        }
        @Override protected void onConfigurationChanged(Configuration config) { super.onConfigurationChanged(config); hover = false; pointer.clear(); close(); }
        @Override protected void dispatchDraw(Canvas canvas) {
            long now = SystemClock.uptimeMillis(); boolean reduced = reduce();
            float progress = reduced ? (session.active() ? 1 : 0) : visibility.value(now);
            float opacity = Math.max(0, Math.min(1, progress));
            float scale = reduced ? 1 : (glass() ? .93f : .96f) + (glass() ? .07f : .04f) * progress;
            surface.setVisibility(opacity > 0 ? VISIBLE : INVISIBLE); surface.setAlpha(opacity);
            surface.setPivotX(anchorX - surface.getLeft()); surface.setPivotY(anchorY - surface.getTop());
            surface.setScaleX(scale); surface.setScaleY(scale);
            super.dispatchDraw(canvas);
            if (layout != null && opacity > 0) {
                boolean dark = (getResources().getConfiguration().uiMode & 48) == 32;
                int arcSave = canvas.save(); canvas.scale(scale, scale, anchorX, anchorY);
                int save = canvas.saveLayerAlpha(layout.left, layout.top, layout.right, layout.bottom, (int)(255*opacity));
                int edgeSave = canvas.save();
                canvas.translate(surface.getLeft(), surface.getTop());
                surface.drawEdge(canvas);
                canvas.restoreToCount(edgeSave);
                for (int i = 0; i < entries.size(); i++) {
                    MenuEntry entry = entries.get(i); float cx=layout.itemX(i), cy=layout.itemY(i);
                    boolean active = entry.id.equals(session.selection());
                    boolean pressed = entry.id.equals(pressedId) && (pointPressed || now < pressedUntil);
                    ItemFeedback item=feedback.computeIfAbsent(entry.id,key->new ItemFeedback());
                    item.update(active,pressed,now,reduced);
                    float focus = reduced?(active?1:0):Math.max(0,Math.min(1,item.focus.value(now)));
                    float press = reduced?(pressed?1:0):Math.max(0,Math.min(1,item.press.value(now)));
                    paint.setShader(null); paint.setStyle(Paint.Style.FILL);
                    paint.setColor(blendColor(dark?0x183A526B:0x20FFFFFF,0x708AC8FF,focus));
                    canvas.drawCircle(cx,cy,dp(27+3*focus-1.5f*press),paint);
                    if(press>0){paint.setColor(Color.argb((int)(64*press),138,200,255));canvas.drawCircle(cx,cy,dp(27+3*focus-1.5f*press),paint);}
                    if (entry.icon != null) {
                        int h=(int)dp(18+1.5f*focus-.7f*press);
                        entry.icon.setBounds((int)cx-h,(int)cy-h,(int)cx+h,(int)cy+h); entry.icon.draw(canvas);
                        if (RingContent.isAction(entry.id)) {
                            paint.setColor(0xFF7CC6FF); canvas.drawCircle(cx+dp(16),cy+dp(16),dp(5),paint);
                        }
                    } else {
                        paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(dp(2)); paint.setColor(dark ? Color.WHITE : 0xFF294C69);
                        canvas.drawRoundRect(cx-dp(13),cy-dp(10),cx+dp(13),cy+dp(10),dp(3),dp(3),paint); paint.setStyle(Paint.Style.FILL);
                    }
                }
                canvas.restoreToCount(save);
                canvas.restoreToCount(arcSave);
                // Labels appear only for the hovered item and never fill the arc's empty centre.
                if (session.selection()!=null) {
                    for (MenuEntry entry:entries) if (entry.id.equals(session.selection())) {
                        paint.setTextSize(dp(13)); paint.setTextAlign(Paint.Align.CENTER);
                        float w=paint.measureText(entry.label)+dp(24), x=Math.max(w/2+dp(8),Math.min(anchorX,getWidth()-w/2-dp(8)));
                        float y=Math.max(dp(35),Math.min(anchorY,getHeight()-dp(30)));
                        paint.setColor(dark?0xE0222932:0xE0F3F8FF); canvas.drawRoundRect(x-w/2,y-dp(19),x+w/2,y+dp(10),dp(12),dp(12),paint);
                        paint.setColor(dark?Color.WHITE:0xFF263C50); canvas.drawText(entry.label,x,y,paint);
                    }
                }
            }
            if (hover && config.get().preview(foreground)) {
                float r=dp(7), outer=dp(12);
                paint.setStyle(Paint.Style.FILL);
                paint.setShader(new RadialGradient(tipX,tipY,outer,new int[]{0x0099D9FF,0x0099D9FF,0x5599D9FF,0x0099D9FF},new float[]{0,r/outer,(r+dp(1))/outer,1},Shader.TileMode.CLAMP));
                canvas.drawCircle(tipX,tipY,outer,paint); paint.setShader(null);
                paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(dp(1.4f)); paint.setColor(0xE0A6DFFF);
                canvas.drawCircle(tipX,tipY,r-dp(.7f),paint); paint.setStyle(Paint.Style.FILL);
            }
            if (session.active() && !reduced && rippleStart>0 && now-rippleStart<320) {
                float p=(now-rippleStart)/320f; paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(dp(2)*(1-p));
                paint.setColor(Color.argb((int)(140*(1-p)),145,210,255)); canvas.drawCircle(rippleX,rippleY,dp(8+p*35),paint); paint.setStyle(Paint.Style.FILL);
                postInvalidateOnAnimation();
            }
            if (!reduced && visibility.running(now)) postInvalidateOnAnimation();
            if(!reduced)for(ItemFeedback item:feedback.values())if(item.focus.running(now)||item.press.running(now)){postInvalidateOnAnimation();break;}
            if (!session.active() && opacity>0) main.post(RingHost.this::updateWindow);
        }
        private int blendColor(int a,int b,float t){return Color.argb(
                Math.round(Color.alpha(a)+(Color.alpha(b)-Color.alpha(a))*t),Math.round(Color.red(a)+(Color.red(b)-Color.red(a))*t),
                Math.round(Color.green(a)+(Color.green(b)-Color.green(a))*t),Math.round(Color.blue(a)+(Color.blue(b)-Color.blue(a))*t));}
    }
}
