package com.focuspen.enhance.v2;

import android.content.Context;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.graphics.drawable.Drawable;
import android.view.View;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** The ROM LaserView renderer, with its resources supplied by the module. */
public final class PortedLaserView extends View {
    public static final int MODE_LASER = 0;
    public static final int MODE_PEN = 1;

    public record Style(
            float innerRadius,
            float blurRadius,
            float outerBlurRadius,
            float outerFillRadius,
            float outerStrokeWidth,
            float penCenterX,
            float penCenterY,
            float penHeight,
            float pathStrokeWidth,
            float penWidth,
            int laserColor,
            int pathColor) { }

    private static final class Stroke {
        final Path path = new Path();
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);

        Stroke(int color, float width, float x, float y) {
            paint.setColor(color);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(width);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setStrokeCap(Paint.Cap.ROUND);
            path.moveTo(x, y);
        }
    }

    private final Style style;
    private final Drawable penImage;
    private final Path penTail = new Path();
    private final Paint penTailPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint innerPaint = new Paint();
    private final Paint outerBlurPaint = new Paint();
    private final Paint outerFillPaint = new Paint();
    private final List<Stroke> strokes = new ArrayList<>();
    private Stroke activeStroke;
    private boolean keepPath;
    private boolean awaitingFirstPoint;
    private boolean visible = true;
    private boolean presentationVisible;
    private int mode = MODE_LASER;
    private int pathColor;
    private float x;
    private float y;

    public PortedLaserView(Context context, Style style, Drawable penImage) {
        super(context);
        this.style = Objects.requireNonNull(style);
        this.penImage = Objects.requireNonNull(penImage).mutate();
        this.pathColor = style.pathColor();
        // The cap above the separator, in the pen drawable's 29-unit viewport.
        penTail.moveTo(17.862f, 6.198f);
        penTail.lineTo(20.329f, 3.731f);
        penTail.cubicTo(21.021f, 3.04f, 21.267f, 2.802f, 21.502f, 2.667f);
        penTail.cubicTo(22.199f, 2.265f, 23.056f, 2.265f, 23.752f, 2.667f);
        penTail.cubicTo(23.987f, 2.802f, 24.234f, 3.04f, 24.926f, 3.731f);
        penTail.cubicTo(25.617f, 4.423f, 25.854f, 4.669f, 25.99f, 4.904f);
        penTail.cubicTo(26.392f, 5.601f, 26.392f, 6.458f, 25.99f, 7.154f);
        penTail.cubicTo(25.854f, 7.389f, 25.617f, 7.636f, 24.926f, 8.328f);
        penTail.lineTo(22.4595f, 10.7945f);
        penTail.close();

        innerPaint.setStyle(Paint.Style.FILL);
        outerBlurPaint.setStyle(Paint.Style.STROKE);
        outerBlurPaint.setStrokeWidth(style.outerStrokeWidth());
        outerBlurPaint.setMaskFilter(new BlurMaskFilter(style.blurRadius(), BlurMaskFilter.Blur.NORMAL));
        outerFillPaint.setStyle(Paint.Style.STROKE);
        outerFillPaint.setStrokeWidth(style.outerStrokeWidth());
        setLaserColor(style.laserColor());
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (!visible) return;
        for (Stroke stroke : strokes) canvas.drawPath(stroke.path, stroke.paint);
        if (!presentationVisible) return;
        if (mode == MODE_LASER) {
            canvas.drawCircle(x, y, style.outerBlurRadius(), outerBlurPaint);
            canvas.drawCircle(x, y, style.outerFillRadius(), outerFillPaint);
            canvas.drawCircle(x, y, style.innerRadius(), innerPaint);
        } else if (mode == MODE_PEN) {
            penImage.draw(canvas);
            int save = canvas.save();
            android.graphics.Rect bounds = penImage.getBounds();
            canvas.translate(bounds.left, bounds.top);
            canvas.scale(bounds.width() / 29f, bounds.height() / 29f);
            penTailPaint.setStyle(Paint.Style.FILL);
            penTailPaint.setColor(pathColor);
            canvas.drawPath(penTail, penTailPaint);
            penTailPaint.setStyle(Paint.Style.STROKE);
            penTailPaint.setStrokeWidth(1.5f);
            penTailPaint.setStrokeJoin(Paint.Join.ROUND);
            penTailPaint.setColor(Color.BLACK);
            canvas.drawPath(penTail, penTailPaint);
            canvas.restoreToCount(save);
        }
    }

    public void setVisible(boolean value) {
        visible = value;
        invalidate();
    }

    public void setPresentationVisible(boolean value) {
        presentationVisible = value;
        invalidate();
    }

    public void setMode(int value) {
        if (mode == value) return;
        mode = value;
        invalidate();
    }

    public int getMode() {
        return mode;
    }

    public void move(float deltaX, float deltaY) {
        setPosition(x + deltaX, y + deltaY);
    }

    public void setPosition(PointF position) {
        setPosition(position.x, position.y);
    }

    public void setPosition(float nextX, float nextY) {
        if (keepPath && awaitingFirstPoint) {
            awaitingFirstPoint = false;
        } else if (keepPath && (nextX != x || nextY != y)) {
            if (activeStroke == null) {
                activeStroke = new Stroke(pathColor, style.pathStrokeWidth(), x, y);
                strokes.add(activeStroke);
            }
            activeStroke.path.quadTo(x, y, (x + nextX) / 2f, (y + nextY) / 2f);
        }
        x = nextX;
        y = nextY;
        int left = (int) (x - style.penWidth() * style.penCenterX());
        int top = (int) (y - style.penHeight() * style.penCenterY());
        penImage.setBounds(left, top, (int) (left + style.penWidth()),
                (int) (top + style.penHeight()));
        invalidate();
    }

    public void getPosition(float[] outPosition) {
        outPosition[0] = x;
        outPosition[1] = y;
    }

    public void setLaserColor(int color) {
        int rgb = color & 0x00ffffff;
        innerPaint.setColor(Color.argb(0xcc, Color.red(rgb), Color.green(rgb), Color.blue(rgb)));
        outerBlurPaint.setColor(0xff000000 | rgb);
        outerFillPaint.setColor(0xff000000 | rgb);
        invalidate();
    }

    public void setPathColor(int color) {
        if (pathColor == color) return;
        finishActiveStroke();
        pathColor = color;
        invalidate();
    }

    public boolean isKeepPath() {
        return keepPath;
    }

    boolean hasInk() { return !strokes.isEmpty(); }

    public void setKeepPath(boolean value) {
        if (keepPath == value) return;
        if (!value) finishActiveStroke();
        keepPath = value;
        awaitingFirstPoint = value;
        invalidate();
    }

    private void finishActiveStroke() {
        if (activeStroke != null) {
            activeStroke.path.lineTo(x, y);
            activeStroke = null;
        }
    }

    public void clearPath() {
        strokes.clear();
        activeStroke = null;
        awaitingFirstPoint = false;
        invalidate();
    }

    public boolean needExist() {
        return visible && (!strokes.isEmpty() || presentationVisible);
    }
}
