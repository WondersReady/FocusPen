package com.focuspen.enhance.v2;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RuntimeShader;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import de.robv.android.xposed.XposedHelpers;

/** Local circular compositor blur plus cached edge lighting. No screen capture or bitmap retention.
 * Design references and the remaining refraction work are recorded in docs/v2/MATERIALS.md.
 */
final class RingMaterialRenderer {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final WindowManager wm;
    private RuntimeShader edge;
    private Drawable blur;
    private boolean blurFailed;
    private int blurRadius = -1;

    RingMaterialRenderer(WindowManager wm) {
        this.wm = wm;
        if (Build.VERSION.SDK_INT >= 33) try { edge = new RuntimeShader(EDGE_SHADER); }
        catch (Throwable failure) { Log.w("FocusPenMaterial", "Edge shader unavailable; flat highlight retained", failure); }
    }
    void attach(View view) {
        blurFailed = false; blurRadius = -1;
        if (Build.VERSION.SDK_INT < 31) return;
        try {
            Object root = XposedHelpers.callMethod(view, "getViewRootImpl");
            blur = (Drawable) XposedHelpers.callMethod(root, "createBackgroundBlurDrawable");
            XposedHelpers.callMethod(blur, "setColor", Color.TRANSPARENT);
            blur.setVisible(false, false);
            Log.i("FocusPenMaterial", "Local compositor blur ready; edgeShader=" + (edge != null));
        } catch (Throwable failure) { blurFailed = true; Log.w("FocusPenMaterial", "Local blur unavailable; solid fallback retained", failure); }
    }
    void detach() { hide(); blur = null; }
    void hide() { if (blur != null) blur.setVisible(false, false); }

    void draw(Canvas canvas, float x, float y, float radius, float density, float opacity,
              boolean glass, boolean dark, boolean lowCost, float lightX, float lightY, float contact) {
        boolean blurred = false;
        if (!lowCost && !blurFailed && blur != null && canvas.isHardwareAccelerated()) {
            try {
                if (wm.isCrossWindowBlurEnabled()) {
                    int desired = (int) (density * (glass ? 14 : 32));
                    if (desired != blurRadius) { XposedHelpers.callMethod(blur, "setBlurRadius", desired); blurRadius = desired; }
                    blur.setBounds((int) (x - radius), (int) (y - radius), (int) (x + radius), (int) (y + radius));
                    XposedHelpers.callMethod(blur, "setCornerRadius", radius);
                    blur.setAlpha((int) (255 * opacity)); blur.setVisible(true, false); blur.draw(canvas);
                    blurred = true;
                }
            } catch (Throwable failure) { blurFailed = true; Log.w("FocusPenMaterial", "Local blur failed; fallback retained", failure); }
        }
        if (!blurred) hide();
        int tint = dark ? 0x00212A3B : 0x00F0F5FF;
        int alpha = blurred ? (glass ? (dark ? 108 : 76) : (dark ? 172 : 157)) : (dark ? 242 : 240);
        paint.setStyle(Paint.Style.FILL); paint.setShader(null);
        paint.setColor(tint | ((int) (alpha * opacity) << 24));
        canvas.drawCircle(x, y, radius, paint);
        if (edge != null && !lowCost && canvas.isHardwareAccelerated()) {
            try {
            edge.setFloatUniform("center", x, y); edge.setFloatUniform("radius", radius);
            edge.setFloatUniform("density", density); edge.setFloatUniform("opacity", opacity);
            edge.setFloatUniform("glass", glass ? 1 : 0); edge.setFloatUniform("dark", dark ? 1 : 0);
            edge.setFloatUniform("light", lightX, lightY); edge.setFloatUniform("contact", contact);
            paint.setShader(edge); paint.setAlpha(255);
            canvas.drawCircle(x, y, radius + density, paint); paint.setShader(null);
            } catch (Throwable failure) {
                edge = null; paint.setShader(null);
                Log.w("FocusPenMaterial", "Edge rendering failed; flat highlight retained", failure);
            }
        } else {
            paint.setColor(dark ? 0x557F94B4 : 0xDFFFFFFF); paint.setAlpha((int) (Color.alpha(paint.getColor()) * opacity));
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(density);
            canvas.drawCircle(x, y, radius, paint); paint.setStyle(Paint.Style.FILL);
        }
    }

    // Original AGSL: local edge shading only. It does not sample/refract another app's pixels.
    private static final String EDGE_SHADER = """
        uniform float2 center;
        uniform float radius;
        uniform float density;
        uniform float opacity;
        uniform float glass;
        uniform float dark;
        uniform float2 light;
        uniform float contact;
        half4 main(float2 p) {
            float2 q = p - center;
            float len = length(q);
            float2 n = q / max(len, 0.01);
            float d = radius - len;
            float band = (2.8 + glass * 1.2) * density;
            float rim = exp(-max(d, 0.0) / band) * smoothstep(-density, 0.0, d);
            float2 l = normalize(float2(-0.5, -0.8) + light * 0.48);
            float lit = dot(n, l);
            float peak = mix(max(lit, 0.0), lit * lit, glass);
            float glint = pow(peak, 4.0 - glass * 1.5);
            float wave = contact * sin(d / max(density, 0.1) * 0.4) * exp(-abs(d - band * 3.0) / (band * 3.0));
            float alpha = clamp(rim * (0.10 + glint * (0.68 - dark * 0.08)) + max(wave, 0.0) * 0.08, 0.0, 0.8) * opacity;
            half3 color = mix(half3(0.78, 0.88, 1.0), half3(1.0), half(glint));
            return half4(color * half(alpha), half(alpha));
        }
        """;
}
