package com.focuspen.enhance.v2;

import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.WindowManager;
import java.lang.reflect.Method;
import java.util.Objects;

/** Presents the ported laser and ink; gesture state and coordinates belong to the caller. */
public final class PortedPresentationWindow implements AutoCloseable {
    private static final String TAG = "FocusPenPresentation";
    private static final String MODULE_PACKAGE = BuildConfig.APPLICATION_ID;
    private static final int LASER_WINDOW_TYPE = 2018;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Object queueLock = new Object();
    private final WindowManager windows;
    private final WindowManager.LayoutParams params;
    private final PortedLaserView view;
    private PresentationState.Mode mode = PresentationState.Mode.IDLE;
    private Motion pendingMotion;
    private boolean positioned;
    private boolean drawing;
    private boolean attached;
    private boolean closed;
    private final java.util.function.LongConsumer inkStored;
    private long inkEpoch;

    private static final class Motion {
        float x;
        float y;

        Motion(float x, float y) {
            this.x = x;
            this.y = y;
        }
    }

    /** Construction and all WindowManager work must begin on the system main thread. */
    public PortedPresentationWindow(Context systemContext) throws PackageManager.NameNotFoundException {
        this(systemContext,epoch->{});
    }

    PortedPresentationWindow(Context systemContext,java.util.function.LongConsumer inkStored) throws PackageManager.NameNotFoundException {
        if (Looper.myLooper() != Looper.getMainLooper())
            throw new IllegalStateException("Construct on the main thread");
        Objects.requireNonNull(systemContext);
        this.inkStored=Objects.requireNonNull(inkStored);
        Context moduleContext = systemContext.createPackageContext(
                MODULE_PACKAGE, Context.CONTEXT_IGNORE_SECURITY);
        Resources resources = moduleContext.getResources();
        PortedLaserView.Style style = new PortedLaserView.Style(
                resources.getDimension(R.dimen.focuspen_port_laser_in_radius),
                resources.getDimension(R.dimen.focuspen_port_laser_out_blur),
                resources.getDimension(R.dimen.focuspen_port_laser_out_blur_radius),
                resources.getDimension(R.dimen.focuspen_port_laser_out_fill_radius),
                resources.getDimension(R.dimen.focuspen_port_laser_out_width),
                resources.getFloat(R.dimen.focuspen_port_laser_pen_center_x),
                resources.getFloat(R.dimen.focuspen_port_laser_pen_center_y),
                resources.getDimension(R.dimen.focuspen_port_laser_pen_height),
                resources.getDimension(R.dimen.focuspen_port_laser_pen_path_width),
                resources.getDimension(R.dimen.focuspen_port_laser_pen_width),
                0xffff001f, 0xffff0000);
        Drawable pen = Objects.requireNonNull(
                moduleContext.getDrawable(R.drawable.focuspen_port_laser_pen));
        view = new PortedLaserView(moduleContext, style, pen);
        view.setForceDarkAllowed(false);
        windows = Objects.requireNonNull(systemContext.getSystemService(WindowManager.class));
        params = layoutParams();
    }

    public void render(PresentationState.Snapshot snapshot) {
        Objects.requireNonNull(snapshot);
        submit(() -> {
            mode = snapshot.mode();
            drawing = snapshot.drawing();
            view.setMode(mode == PresentationState.Mode.ANNOTATION
                    ? PortedLaserView.MODE_PEN : PortedLaserView.MODE_LASER);
            view.setKeepPath(mode == PresentationState.Mode.ANNOTATION && drawing && positioned);
            refreshWindow();
        });
    }

    /** Consecutive motion samples coalesce; render/color/clear calls remain ordering barriers. */
    public void position(float x, float y) {
        synchronized (queueLock) {
            if (closed) return;
            if (pendingMotion != null) {
                pendingMotion.x = x;
                pendingMotion.y = y;
                return;
            }
            Motion motion = new Motion(x, y);
            pendingMotion = motion;
            main.post(() -> {
                synchronized (queueLock) {
                    if (closed) return;
                    if (pendingMotion == motion) pendingMotion = null;
                }
                positioned = Float.isFinite(motion.x) && Float.isFinite(motion.y)
                        && motion.x >= 0 && motion.y >= 0;
                view.setKeepPath(mode == PresentationState.Mode.ANNOTATION && drawing && positioned);
                boolean hadInk=view.hasInk();
                if (positioned) view.setPosition(motion.x, motion.y);
                if(!hadInk && view.hasInk()) inkStored.accept(inkEpoch);
                refreshWindow();
            });
        }
    }

    public void clearInk() {
        clearInk(inkEpoch+1);
    }

    void clearInk(long epoch) {
        submit(() -> {
            inkEpoch=epoch;
            view.clearPath();
            refreshWindow();
        });
    }

    /** Applies an ARGB color to the current pointer and future ink, never existing strokes. */
    public void setColor(int color) {
        submit(() -> {
            view.setLaserColor(color);
            view.setPathColor(color);
        });
    }

    @Override
    public void close() {
        synchronized (queueLock) {
            if (closed) return;
            closed = true;
            pendingMotion = null;
            main.post(() -> {
                view.setKeepPath(false);
                view.clearPath();
                view.setPresentationVisible(false);
                if (attached) {
                    windows.removeViewImmediate(view);
                    attached = false;
                }
            });
        }
    }

    private void submit(Runnable action) {
        synchronized (queueLock) {
            if (closed) return;
            pendingMotion = null;
            main.post(() -> {
                if (!closed) action.run();
            });
        }
    }

    private void refreshWindow() {
        boolean showPointer = positioned && (mode == PresentationState.Mode.LASER
                || mode == PresentationState.Mode.ANNOTATION);
        view.setPresentationVisible(showPointer);
        if (view.needExist() && !attached) {
            try {
                windows.addView(view, params);
                attached = true;
            } catch (RuntimeException e) {
                Log.w(TAG, "Cannot add presentation window", e);
            }
        } else if (!view.needExist() && attached) {
            windows.removeViewImmediate(view);
            attached = false;
        }
    }

    private static WindowManager.LayoutParams layoutParams() {
        WindowManager.LayoutParams params = new WindowManager.LayoutParams();
        params.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
        params.layoutInDisplayCutoutMode = 1;
        params.type = LASER_WINDOW_TYPE;
        params.format = PixelFormat.TRANSLUCENT;
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = 0;
        params.y = 0;
        params.width = WindowManager.LayoutParams.MATCH_PARENT;
        params.height = WindowManager.LayoutParams.MATCH_PARENT;
        try {
            Method trusted = WindowManager.LayoutParams.class.getDeclaredMethod("setTrustedOverlay");
            trusted.setAccessible(true);
            trusted.invoke(params);
        } catch (ReflectiveOperationException | SecurityException e) {
            throw new IllegalStateException("Trusted laser overlay unavailable", e);
        }
        params.setTitle("laser");
        return params;
    }
}
