package com.focuspen.enhance.v2;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import java.util.function.IntConsumer;
import de.robv.android.xposed.XposedHelpers;

/** Local drag preview; the configuration receives one edit when the gesture finishes. */
final class SettingsSlider {
    private static final String DRAG = "focuspen.slider.dragging";
    static boolean dragging(Object pref) {
        return Boolean.TRUE.equals(XposedHelpers.getAdditionalInstanceField(pref, DRAG));
    }

    static void bind(Object pref, Object holder, IntConsumer commit) {
        View root = (View) XposedHelpers.getObjectField(holder, "itemView");
        Context context = root.getContext();
        TextView title = root.findViewById(android.R.id.title);
        if (title == null || !(title.getParent() instanceof LinearLayout row)) return;
        TextView value = row.findViewWithTag("focuspen.slider.value");
        if (value == null) {
            value = new TextView(context); value.setTag("focuspen.slider.value");
            value.setTextSize(15); value.setTextColor(title.getTextColors()); value.setAlpha(.55f);
            title.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
            row.addView(value, new LinearLayout.LayoutParams(-2, -2));
        }
        String key = (String) XposedHelpers.callMethod(pref, "getKey");
        boolean timing = key.endsWith(ConfigSpec.TIMING);
        boolean percent = key.endsWith(ConfigSpec.AUDIO_VOLUME);
        SeekBar seek = root.findViewById(GuideNative.resource(context, "id", "seekbar"));
        if (seek == null || dragging(pref)) return;
        int min = XposedHelpers.getIntField(pref, "c");
        int offset = min - seek.getMin();
        int current = XposedHelpers.getIntField(pref, "a");
        for (String tag : new String[]{"focuspen.slider.stops", "focuspen.slider.ticks"}) {
            View obsolete = root.findViewWithTag(tag);
            if (obsolete != null) obsolete.setVisibility(View.GONE);
        }
        seek.setTickMark(null); seek.setKeyProgressIncrement(1);
        // The host's optional middle-only snapping would conflict with our 200/300 anchors.
        try { XposedHelpers.callMethod(seek, "setMiddleEnabled", false); } catch (Throwable ignored) { }
        float density = context.getResources().getDisplayMetrics().density;
        int inset = context.getResources().getDimensionPixelSize(GuideNative.resource(context,
                "dimen", "miuix_appcompat_seekbar_progress_custom_bg_radius"));
        seek.setPadding(inset, seek.getPaddingTop(), inset, seek.getPaddingBottom());
        // Keep the host's original LayerDrawable thumb and its cached GradientDrawable.
        // MIUIX's spring animation mutates layer 0; replacing the thumb breaks that contract.
        // Separate progress/background layers also let MIUIX supply the animated bounds.
        LayerDrawable rail = new LayerDrawable(new Drawable[]{
                new Rail(seek, density, timing, false), new Rail(seek, density, timing, true)});
        rail.setId(0, android.R.id.background);
        rail.setId(1, android.R.id.progress);
        seek.setProgressDrawable(rail);
        TextView display = value;
        IntConsumer preview = number -> {
            String label = number + (percent ? "%" : " ms");
            display.setText(label); seek.setStateDescription(label); seek.invalidate();
        };
        preview.accept(current);
        value.setEnabled(seek.isEnabled());
        final int[] start = {current}, last = {current};
        final boolean[] cancelled = {false}, adjusting = {false};
        seek.setOnTouchListener((v, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) cancelled[0] = false;
            if (event.getActionMasked() == MotionEvent.ACTION_CANCEL) cancelled[0] = true;
            return false;
        });
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onStartTrackingTouch(SeekBar bar) {
                start[0] = last[0] = offset + bar.getProgress();
                XposedHelpers.setAdditionalInstanceField(pref, DRAG, true);
            }
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (adjusting[0]) return;
                int number = offset + progress;
                if (fromUser && timing) number = SliderValue.snapTiming(number);
                if (number - offset != progress) {
                    adjusting[0] = true; bar.setProgress(number - offset); adjusting[0] = false;
                }
                preview.accept(number);
                if (fromUser && !dragging(pref) && number != last[0]) {
                    last[0] = number;
                    XposedHelpers.setIntField(pref, "a", number);
                    commit.accept(number); // Keyboard and accessibility actions are complete edits.
                }
            }
            @Override public void onStopTrackingTouch(SeekBar bar) {
                XposedHelpers.setAdditionalInstanceField(pref, DRAG, false);
                int number = cancelled[0] ? start[0] : offset + bar.getProgress();
                bar.setProgress(number - offset); preview.accept(number);
                XposedHelpers.setIntField(pref, "a", number);
                if (!cancelled[0] && number != start[0]) { last[0] = number; commit.accept(number); }
            }
        });
    }

    private static final class Rail extends Drawable {
        private final SeekBar seek;
        private final float density;
        private final boolean markers;
        private final boolean foreground;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        Rail(SeekBar seek, float density, boolean markers, boolean foreground) {
            this.seek = seek; this.density = density; this.markers = markers;
            this.foreground = foreground;
        }
        @Override public void draw(Canvas canvas) {
            Rect b = getBounds(); float cy = b.exactCenterY(), radius = b.height() / 2f;
            boolean dark = (seek.getResources().getConfiguration().uiMode & 0x30) == 0x20;
            boolean rtl = seek.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
            paint.setColor(foreground ? (seek.isEnabled() ? 0xff3482ff : 0xffa7c5f4)
                    : (dark ? 0xff303033 : 0xfff0f0f0));
            canvas.drawRoundRect(b.left, b.top, b.right, b.bottom, radius, radius, paint);
            float start = seek.getPaddingLeft(), end = seek.getWidth() - seek.getPaddingRight();
            if (markers) for (int i = 0; i < 4; i++) {
                float at = i / 3f, x = rtl ? end - (end - start) * at : start + (end - start) * at;
                if (x < b.left + radius - 1 || x > b.right - radius + 1) continue;
                paint.setColor(foreground ? 0xff8ebcff : (dark ? 0xff595e66 : 0xffd9dee6));
                canvas.drawCircle(x, cy, 3.5f * density, paint);
            }
        }
        @Override protected boolean onLevelChange(int level) { invalidateSelf(); return true; }
        @Override public int getIntrinsicHeight() { return Math.round(28 * density); }
        @Override public void setAlpha(int alpha) { }
        @Override public void setColorFilter(ColorFilter filter) { }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
    private SettingsSlider() { }
}
