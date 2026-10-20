package com.focuspen.enhance.v2;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;

/**
 * F1 悬停光圈 Overlay。
 *
 * <h3>数据来源（2026-10-18 真笔实测）</h3>
 * 笔悬停时，{@link MotionListenerBridge} 收到的 MotionEvent 特征：
 * <pre>
 * action=0/1  tool=2 (STYLUS)  src=0x5002 (SOURCE_MOUSE|SOURCE_STYLUS)  p=0.0  x,y = 屏幕像素
 * </pre>
 * 关键点：
 * <ol>
 *   <li><b>坐标已经是屏幕像素</b>（3200×2136 范围内），不需要从 evdev 换算，也不需要旋转映射。</li>
 *   <li>悬停时 {@code src=0x5002}（笔被映射成鼠标）且 {@code pressure=0}。</li>
 *   <li>接触书写时 {@code src} 也是 0x5002，但 {@code pressure > 0}。</li>
 * </ol>
 * 因此判定"是否悬停"用 <b>pressure == 0</b>，而不是 action 值。
 *
 * <h3>窗口约束（Project.md §5.4.1）</h3>
 * Android S+ 起，{@code FLAG_NOT_TOUCHABLE} 窗口下方的触摸只有在
 * 同 UID / 受信任 / 完全不可见 / 完全透明 / 单层 SAW 且不透明度不超上限时才透传，
 * 而 {@code TYPE_APPLICATION_OVERLAY} <b>不属于</b>受信任窗口。
 *
 * 因此本实现的硬约束：
 * <ul>
 *   <li><b>窗口只有光圈那么大（约 96×96 px）</b>，绝不铺满全屏 —— 这样即使透传失败，
 *       被遮挡的也只有光圈覆盖的那一小块。</li>
 *   <li>背景 <b>完全透明</b>（{@code PixelFormat.TRANSLUCENT} + 不画背景）。</li>
 *   <li>只画细圆环（{@code STYLE_STROKE}），环内留空。</li>
 *   <li><b>单 View 复用</b>，只 {@code updateViewLayout} 改位置，禁止每帧新建窗口。</li>
 * </ul>
 */
final class HaloOverlay {

    private static final String TAG = "FocusPenHalo";

    /** 光圈直径（px）。屏幕 3200 宽，96px 约等于 2.5cm 视觉大小，合适。 */
    private static final int HALO_SIZE = 96;
    /** 环宽 */
    private static final int STROKE_W = 5;
    /** 悬停判定：压力低于此值视为未接触 */
    private static final float HOVER_PRESSURE_EPS = 0.005f;
    /** 多久没收到事件就隐藏（毫秒） */
    private static final long HIDE_TIMEOUT_MS = 1200L;

    private static HaloOverlay sInstance;

    private final Context mContext;
    private final Handler mMain = new Handler(Looper.getMainLooper());

    private WindowManager mWm;
    private HaloView mView;
    private WindowManager.LayoutParams mLp;
    private boolean mAdded;

    private volatile boolean mEnabled;
    /** 统计，供 L1/L2 断言读取 */
    private volatile long mUpdateCount;
    private volatile float mLastX, mLastY;
    private volatile boolean mLastHover;

    private final Runnable mHideRunnable = new Runnable() {
        @Override public void run() {
            hideWindow();
        }
    };

    private HaloOverlay(Context ctx) {
        mContext = ctx.getApplicationContext();
        mWm = (WindowManager) mContext.getSystemService(Context.WINDOW_SERVICE);
    }

    static synchronized HaloOverlay get(Context ctx) {
        if (sInstance == null) {
            sInstance = new HaloOverlay(ctx);
        }
        return sInstance;
    }

    void setEnabled(boolean on) {
        mEnabled = on;
        Log.i(TAG, "halo enabled=" + on);
        if (!on) {
            mMain.post(new Runnable() {
                @Override public void run() { hideWindow(); }
            });
        }
    }

    boolean isEnabled() { return mEnabled; }
    long getUpdateCount() { return mUpdateCount; }

    /**
     * 由 {@link MotionListenerBridge} 每次收到 MotionEvent 时调用。
     *
     * @param x        屏幕像素 X
     * @param y        屏幕像素 Y
     * @param pressure 压力（0 表示未接触 = 悬停/离开）
     * @param toolType MotionEvent.getToolType(0)
     */
    void onPenEvent(final float x, final float y, final float pressure, final int toolType) {
        if (!mEnabled) {
            return;
        }
        // 只处理笔
        if (toolType != 2 /* TOOL_TYPE_STYLUS */) {
            return;
        }
        final boolean hover = pressure <= HOVER_PRESSURE_EPS;

        mUpdateCount++;
        mLastX = x;
        mLastY = y;
        mLastHover = hover;

        mMain.post(new Runnable() {
            @Override public void run() {
                showOrMove(x, y, hover);
                mMain.removeCallbacks(mHideRunnable);
                mMain.postDelayed(mHideRunnable, HIDE_TIMEOUT_MS);
            }
        });
    }

    // ---------------------------------------------------------------- 窗口操作

    private void ensureView() {
        if (mView != null) {
            return;
        }
        mView = new HaloView(mContext);
        mLp = new WindowManager.LayoutParams(
                HALO_SIZE, HALO_SIZE,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        mLp.gravity = android.view.Gravity.TOP | android.view.Gravity.LEFT;
        mLp.x = -10000;
        mLp.y = -10000;
        mLp.alpha = 1.0f;
        // 不使用 FLAG_LAYOUT_NO_LIMITS 之外的任何输入相关 flag
        mLp.setTitle("FocusPenHalo");
    }

    private void showOrMove(float screenX, float screenY, boolean hover) {
        if (mWm == null) {
            return;
        }
        try {
            ensureView();
            if (!mAdded) {
                mWm.addView(mView, mLp);
                mAdded = true;
                Log.i(TAG, "halo window ADDED");
            }
            // 光圈中心对准笔尖
            mLp.x = (int) (screenX - HALO_SIZE / 2f);
            mLp.y = (int) (screenY - HALO_SIZE / 2f);
            mLp.alpha = hover ? 0.85f : 0.55f;
            mView.setHover(hover);
            mWm.updateViewLayout(mView, mLp);
        } catch (Throwable t) {
            // 权限缺失（SYSTEM_ALERT_WINDOW）会在这里抛 SecurityException
            Log.e(TAG, "halo window op failed", t);
            mAdded = false;
        }
    }

    private void hideWindow() {
        try {
            if (mAdded && mView != null && mWm != null) {
                mWm.removeView(mView);
                Log.i(TAG, "halo window REMOVED");
            }
        } catch (Throwable t) {
            Log.e(TAG, "halo remove failed", t);
        } finally {
            mAdded = false;
            mView = null;
        }
    }

    boolean isWindowAdded() { return mAdded; }

    /** 打一行统计，供自动化断言 */
    void logSummary() {
        Log.i(TAG, "halo summary enabled=" + mEnabled + " shown=" + mAdded
                + " updates=" + mUpdateCount
                + " lastX=" + round1(mLastX) + " lastY=" + round1(mLastY)
                + " lastHover=" + mLastHover);
    }

    private static double round1(float v) {
        return Math.round(v * 10.0) / 10.0;
    }

    /** 光圈绘制：一个细圆环 + 中心小点 */
    private static final class HaloView extends View {
        private final Paint mRing = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mDot = new Paint(Paint.ANTI_ALIAS_FLAG);
        private boolean mHover = true;

        HaloView(Context c) {
            super(c);
            setBackgroundColor(Color.TRANSPARENT);
            mRing.setStyle(Paint.Style.STROKE);
            mRing.setStrokeWidth(STROKE_W);
            mRing.setColor(0xFF4FC3F7);
            mDot.setStyle(Paint.Style.FILL);
            mDot.setColor(0xFF4FC3F7);
        }

        void setHover(boolean hover) {
            mHover = hover;
            // 悬停时亮蓝，接触时橙
            int c = hover ? 0xFF4FC3F7 : 0xFFFFB74D;
            mRing.setColor(c);
            mDot.setColor(c);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            float r = Math.min(cx, cy) - STROKE_W;
            canvas.drawCircle(cx, cy, r, mRing);
            // 中心小点（帮助看清笔尖精确位置）
            canvas.drawCircle(cx, cy, STROKE_W * 0.8f, mDot);
        }
    }
}
