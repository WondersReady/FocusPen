package com.focuspen.enhance.v2;

import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import java.util.WeakHashMap;

/** Application rows use one icon slot, independent of each drawable's intrinsic size. */
final class AppRowStyle {
    private record TextStyle(int maxLines, TextUtils.TruncateAt ellipsize) {
        static TextStyle of(TextView view) {
            return view == null ? null : new TextStyle(view.getMaxLines(), view.getEllipsize());
        }
        void restore(TextView view) {
            if (view != null) { view.setMaxLines(maxLines); view.setEllipsize(ellipsize); }
        }
    }
    // Store only scalar style values; retaining a child view here would retain the weak key's parent.
    private record Style(int width, int height, boolean adjustBounds, ImageView.ScaleType scale,
                         TextStyle title, TextStyle summary) { }
    private static final WeakHashMap<View, Style> originals = new WeakHashMap<>();

    static void bind(View root, boolean applicationRow) {
        ImageView icon = root.findViewById(android.R.id.icon);
        TextView title = root.findViewById(android.R.id.title);
        TextView summary = root.findViewById(android.R.id.summary);
        if (icon == null || icon.getLayoutParams() == null) return;
        ViewGroup.LayoutParams params = icon.getLayoutParams();
        if (!applicationRow) {
            Style old = originals.remove(root);
            if (old == null) return;
            params.width = old.width; params.height = old.height;
            icon.setLayoutParams(params); icon.setAdjustViewBounds(old.adjustBounds); icon.setScaleType(old.scale);
            if (old.title != null) old.title.restore(title);
            if (old.summary != null) old.summary.restore(summary);
            return;
        }
        originals.computeIfAbsent(root, key -> new Style(params.width, params.height,
                icon.getAdjustViewBounds(), icon.getScaleType(), TextStyle.of(title), TextStyle.of(summary)));
        int size = Math.round(36 * root.getResources().getDisplayMetrics().density);
        if (params.width != size || params.height != size) {
            params.width = size; params.height = size; icon.setLayoutParams(params);
        }
        icon.setAdjustViewBounds(false); icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        // Keep the native row's padding and scalable text height. Long labels/package names
        // ellipsize instead of making individual applications occupy extra lines.
        singleLine(title); singleLine(summary);
    }
    private static void singleLine(TextView view) {
        if (view != null) { view.setMaxLines(1); view.setEllipsize(TextUtils.TruncateAt.END); }
    }
    private AppRowStyle() { }
}
