package com.focuspen.enhance.v2;

/** Arc geometry shared by drawing, clipping and input. Angles are screen radians. */
final class RingLayout {
    static final int ITEMS_PER_PAGE = 12;
    final float x, y, unit, radius, hitRadius, halfWidth, start, sweep;
    final float left, top, right, bottom;
    final boolean edgeFallback;
    final int count;
    private RingLayout(float x, float y, float unit, int count, float direction, boolean fallback) {
        this.x = x; this.y = y; this.unit = unit; this.count = count;
        edgeFallback = fallback; radius = 132 * unit; hitRadius = 28 * unit; halfWidth = 34 * unit;
        sweep = (float) Math.toRadians(count == 12 ? 360 : Math.max(20, (count - 1) * 31)); start = direction - sweep / 2;
        float l = Float.MAX_VALUE, t = l, r = -l, b = -l;
        for (int i = 0; i <= 180; i++) {
            double a = start + sweep * i / 180;
            float px = x + (float) Math.cos(a) * radius, py = y + (float) Math.sin(a) * radius;
            l = Math.min(l, px); r = Math.max(r, px); t = Math.min(t, py); b = Math.max(b, py);
        }
        float margin = halfWidth + 8 * unit;
        left = l - margin; right = r + margin; top = t - margin; bottom = b + margin;
    }
    static RingLayout place(float tipX, float tipY, float left, float top, float right, float bottom,
                            float density, int count) {
        return place(tipX, tipY, left, top, right, bottom, density, count, Float.NaN);
    }
    static RingLayout place(float x, float y, float l, float t, float r, float b, float dp, int count, float direction) {
        if (count < 1 || count > 12 || !Float.isFinite(x + y + l + t + r + b + dp) || dp <= 0 || r <= l || b <= t)
            throw new IllegalArgumentException("Invalid arc geometry");
        if (Float.isFinite(direction)) {
            RingLayout desired = new RingLayout(x, y, dp, count, direction, false);
            if (desired.fits(l, t, r, b)) return desired;
        }
        float first = x < (l + r) / 2 ? 0 : (float) Math.PI;
        RingLayout a = new RingLayout(x, y, dp, count, first, true);
        RingLayout other = new RingLayout(x, y, dp, count, first == 0 ? (float) Math.PI : 0, true);
        if (a.fits(l, t, r, b)) return a;
        if (other.fits(l, t, r, b)) return other;
        if (a.right - a.left > r - l || a.bottom - a.top > b - t)
            throw new IllegalArgumentException("Not enough space for arc targets");
        float dx = Math.max(l - a.left, Math.min(0, r - a.right));
        float dy = Math.max(t - a.top, Math.min(0, b - a.bottom));
        return new RingLayout(x + dx, y + dy, dp, count, first, true);
    }
    boolean fits(float l, float t, float r, float b) { return left >= l && top >= t && right <= r && bottom <= b; }
    float itemX(int item) { check(item); return x + (float) Math.cos(angle(item)) * radius; }
    float itemY(int item) { check(item); return y + (float) Math.sin(angle(item)) * radius; }
    int hit(float px, float py) {
        for (int i = 0; i < count; i++) if (Math.hypot(px - itemX(i), py - itemY(i)) <= hitRadius) return i;
        return -1;
    }
    boolean fullCircle() { return count == 12; }
    private double angle(int item) { return count == 1 ? start + sweep / 2 : start + sweep * item / (fullCircle() ? count : count - 1); }
    private void check(int item) { if (item < 0 || item >= count) throw new IllegalArgumentException("Unknown ring slot"); }
}
