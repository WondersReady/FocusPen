package com.focuspen.enhance.v2;

/** Stable persisted identifiers shared by settings and input routing. */
enum PenAction {
    NATIVE("native", "原生处理"), NONE("none", "交给前台应用"),
    RING("ring", "弧形菜单"),
    NOTE("note", "灵感速记：笔记"), CREATION("creation", "灵感速记：小米创作"),
    SCREENSHOT("screenshot", "区域截屏"), RECOGNITION("recognition", "小爱识屏"),
    UP("up", "应用上动作"), DOWN("down", "应用下动作"),
    MEDIA("media", "播放或暂停媒体");
    final String id, label;
    PenAction(String id, String label) { this.id = id; this.label = label; }
    static PenAction parse(String id) {
        for (PenAction a : values()) if (a.id.equals(id)) return a;
        throw new IllegalArgumentException("Unknown pen action");
    }
}
