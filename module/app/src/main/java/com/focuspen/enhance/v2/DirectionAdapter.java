package com.focuspen.enhance.v2;

/** Persisted per-direction adapters. Reversal changes execution, never the physical key mapping. */
enum DirectionAdapter {
    NATIVE("native", "native", false, "系统默认方向动作"),
    PAGE("page", "page", false, "翻页键（Page Up / Down）"),
    PAGE_REVERSE("page_reverse", "page", true, "翻页键 · 反向"),
    DPAD("dpad", "dpad", false, "方向键（上 / 下）"),
    DPAD_REVERSE("dpad_reverse", "dpad", true, "方向键 · 反向"),
    SCROLL("scroll", "scroll", false, "窗口内滚轮滚动"),
    SCROLL_REVERSE("scroll_reverse", "scroll", true, "窗口内滚轮滚动 · 反向"),
    SWIPE("swipe", "swipe", false, "窗口内滑动（翻页／切换视频）"),
    SWIPE_REVERSE("swipe_reverse", "swipe", true, "窗口内滑动 · 反向"),
    NONE("none", "none", false, "不执行操作");

    final String id, kind, label;
    final boolean reversed;
    DirectionAdapter(String id, String kind, boolean reversed, String label) {
        this.id=id; this.kind=kind; this.reversed=reversed; this.label=label;
    }
    boolean executionUp(boolean requestedUp) { return requestedUp != reversed; }
    boolean pointer() { return kind.equals("scroll") || kind.equals("swipe"); }
    static DirectionAdapter parse(String id) {
        for(DirectionAdapter adapter:values()) if(adapter.id.equals(id)) return adapter;
        throw new IllegalArgumentException("Unknown direction adapter");
    }
}
