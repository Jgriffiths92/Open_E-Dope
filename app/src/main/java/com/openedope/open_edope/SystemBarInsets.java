package com.openedope.open_edope;

import android.app.Activity;
import android.graphics.Insets;
import android.os.Build;
import android.view.View;
import android.view.WindowInsets;

public final class SystemBarInsets {
    private SystemBarInsets() {}

    public static void apply(Activity activity) {
        if (Build.VERSION.SDK_INT < 35) {
            return;
        }

        View content = activity.findViewById(android.R.id.content);
        if (content == null) {
            throw new IllegalStateException("Android content view is unavailable");
        }

        final int initialLeft = content.getPaddingLeft();
        final int initialTop = content.getPaddingTop();
        final int initialRight = content.getPaddingRight();
        final int initialBottom = content.getPaddingBottom();

        content.setOnApplyWindowInsetsListener((view, windowInsets) -> {
            Insets insets = windowInsets.getInsets(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            view.setPadding(
                    initialLeft + insets.left,
                    initialTop + insets.top,
                    initialRight + insets.right,
                    initialBottom + insets.bottom);
            return windowInsets;
        });
        content.requestApplyInsets();
    }
}
