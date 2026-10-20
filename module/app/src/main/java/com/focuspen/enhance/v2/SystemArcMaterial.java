package com.focuspen.enhance.v2;

import android.content.Context;
import android.graphics.*;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.view.ViewOutlineProvider;
import java.util.ArrayList;
import de.robv.android.xposed.XposedHelpers;

/** System View material API recovered from the device sidebar; never full-window blur. */
final class SystemArcMaterial extends View {
    private final Path path = new Path();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF shapeBounds = new RectF();
    private Shader edgeLight, edgeShade;
    private int mode = -1;
    private boolean dark;
    // GlassToken.g() layout, MIUISecurityCenterPad 13.6.2, CustomGlassConfig.
    private static final float[] GLASS = {0,2,.5f,.8f,.15f,2.6f,.08f,.2f,.6f,1,
            .05f,1,1,1,.1f,.15f,.4f,1.2f,1,60,3.8f,100,600,1,.8f,
            -.6f,.6f,-.8f,.9f,.6f,1,1.15f,3,0,0,0,0,0,0,0,0,0};
    SystemArcMaterial(Context c) {
        super(c); setWillNotDraw(false); setClipToOutline(true);
        setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline outline) { if (!path.isEmpty()) outline.setPath(path); }
        });
    }
    void shape(RingLayout layout) {
        float cx = layout.x - layout.left, cy = layout.y - layout.top, r = layout.radius;
        Path line = new Path();
        if (layout.fullCircle()) line.addCircle(cx, cy, r, Path.Direction.CW);
        else line.addArc(cx-r, cy-r, cx+r, cy+r, (float)Math.toDegrees(layout.start), (float)Math.toDegrees(layout.sweep));
        Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG); stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND); stroke.setStrokeJoin(Paint.Join.ROUND); stroke.setStrokeWidth(layout.halfWidth * 2);
        stroke.getFillPath(line, path); path.computeBounds(shapeBounds,true);
        updateEdge(); invalidateOutline(); invalidate();
    }
    boolean glass() { return mode == 2; }
    void touchRegion(Region region, RingLayout layout) {
        Path global = new Path(path); global.offset(layout.left, layout.top);
        region.setPath(global, new Region((int)layout.left, (int)layout.top, (int)Math.ceil(layout.right), (int)Math.ceil(layout.bottom)));
    }
    void refresh() {
        Context c = getContext();
        boolean night = (getResources().getConfiguration().uiMode & 48) == 32;
        int wanted = 0;
        try {
            Class<?> props = Class.forName("android.os.SystemProperties");
            boolean supported = (Boolean) XposedHelpers.callStaticMethod(props, "getBoolean", "persist.sys.background_blur_supported", false);
            boolean bionic = (Boolean) XposedHelpers.callStaticMethod(props, "getBoolean", "persist.sys.bionic_material_supported", false);
            // The sidebar chooses material from material settings. Disabling animation
            // changes RingView motion only; it must not silently disable system glass.
            if (supported && Settings.Secure.getInt(c.getContentResolver(), "background_blur_enable", 0) == 1)
                wanted = bionic && Settings.Secure.getInt(c.getContentResolver(), "material_style", -1) == 1 ? 2 : 1;
        } catch (Throwable ignored) { }
        if (wanted == mode && dark == night) return;
        reset(); dark = night; mode = wanted;
        try {
            if (wanted > 0) {
                call("setPassWindowBlurEnabled", true);
                call("setMiBackgroundBlurMode", 1); call("setMiViewBlurMode", 1);
                if (wanted == 2) {
                    call("setMiViewMaterialType", 1); call("setMiGlassBlurRadius", 100, 500);
                    call("setMiGlass", (Object) GLASS.clone());
                } else {
                    call("setMiBackgroundBlurRadius", 100);
                    ArrayList<Point> colors = new ArrayList<>();
                    colors.add(new Point(dark ? -8947849 : -7237231, 107));
                    colors.add(new Point(dark ? -15043840 : -3343872, 106));
                    call("setMiBackgroundBlendColors", colors);
                }
            }
            Log.i("FocusPenMaterial", "System arc material=" + mode + " outlinePath=" + !path.isEmpty());
        } catch (Throwable failure) { reset(); mode = 0; Log.w("FocusPenMaterial", "System material unavailable", failure); }
        updateEdge(); invalidate();
    }
    private void updateEdge() {
        if(shapeBounds.isEmpty()) return;
        // The ROM's rounded-rectangle specular edge does not follow this concave Path.
        // Adapt only its contour light here; blur and glass body remain the system material.
        float l=shapeBounds.left,t=shapeBounds.top,r=shapeBounds.right,b=shapeBounds.bottom;
        edgeLight=new LinearGradient(l,t,r,b,
                new int[]{0xFFFFFFFF,0xEFFFFFFF,0x30FFFFFF,0xAAE7F3FF,0xF0FFFFFF},
                new float[]{0,.22f,.5f,.78f,1},Shader.TileMode.CLAMP);
        edgeShade=new LinearGradient(l,t,r,b,
                new int[]{0x06304860,0x50304860,0x16304860},new float[]{0,.65f,1},Shader.TileMode.CLAMP);
    }
    private void call(String method, Object... args) { XposedHelpers.callMethod(this, method, args); }
    private void reset() {
        for (String name : new String[]{"setMiViewMaterialType", "setMiBackgroundBlurMode", "setMiViewBlurMode"})
            try { call(name, 0); } catch (Throwable ignored) { }
        try { call("clearMiBackgroundBlendColor"); } catch (Throwable ignored) { }
    }
    @Override protected void onDraw(Canvas c) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(mode == 0 ? (dark ? 0xF02B3038 : 0xF0EDF3FA) : 0x01000000);
        c.drawPath(path, paint);
    }
    // Draw above the material RenderNode, which can composite over this View's onDraw.
    void drawEdge(Canvas c) {
        if(mode>0 && edgeLight!=null) {
            float dp=getResources().getDisplayMetrics().density;
            int save=c.save(); c.clipPath(path);
            edgePaint.setStyle(Paint.Style.STROKE); edgePaint.setStrokeJoin(Paint.Join.ROUND);
            edgePaint.setShader(edgeShade); edgePaint.setStrokeWidth(4*dp); edgePaint.setAlpha(dark?70:90);
            c.drawPath(path,edgePaint);
            edgePaint.setShader(edgeLight); edgePaint.setStrokeWidth(3.2f*dp); edgePaint.setAlpha(mode==2?64:28);
            c.drawPath(path,edgePaint);
            edgePaint.setStrokeWidth(2.2f*dp); edgePaint.setAlpha(mode==2?255:135);
            c.drawPath(path,edgePaint);
            edgePaint.setShader(null); edgePaint.setAlpha(255); c.restoreToCount(save);
        }
    }
    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        // RenderNode material must be applied again after a removed window is attached.
        mode = -1; refresh();
    }
    @Override protected void onDetachedFromWindow() { reset(); mode = -1; super.onDetachedFromWindow(); }
}
