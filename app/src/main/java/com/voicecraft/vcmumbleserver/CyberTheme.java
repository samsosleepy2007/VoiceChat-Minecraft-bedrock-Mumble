package com.voicecraft.vcmumbleserver;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.View;
import android.view.Window;
import android.widget.TextView;

final class CyberTheme {
    final boolean dark;
    final int background;
    final int surface;
    final int surfaceStrong;
    final int textPrimary;
    final int textSecondary;
    final int accent;
    final int accentSoft;
    final int border;
    final int success;
    final int danger;
    final int warning;
    final int terminalBackground;
    final int terminalText;

    private CyberTheme(boolean dark) {
        this.dark = dark;
        if (dark) {
            background = Color.rgb(3, 9, 20);
            surface = Color.rgb(6, 20, 35);
            surfaceStrong = Color.rgb(8, 30, 50);
            textPrimary = Color.rgb(229, 249, 255);
            textSecondary = Color.rgb(126, 172, 190);
            accent = Color.rgb(0, 229, 255);
            accentSoft = Color.rgb(0, 112, 137);
            border = Color.rgb(0, 156, 190);
            terminalBackground = Color.rgb(1, 8, 14);
            terminalText = Color.rgb(132, 246, 255);
        } else {
            background = Color.rgb(232, 247, 255);
            surface = Color.rgb(246, 252, 255);
            surfaceStrong = Color.rgb(215, 241, 251);
            textPrimary = Color.rgb(5, 31, 48);
            textSecondary = Color.rgb(63, 105, 123);
            accent = Color.rgb(0, 169, 203);
            accentSoft = Color.rgb(167, 229, 241);
            border = Color.rgb(0, 150, 181);
            terminalBackground = Color.rgb(3, 20, 30);
            terminalText = Color.rgb(118, 242, 255);
        }
        success = Color.rgb(0, 230, 164);
        danger = Color.rgb(255, 70, 104);
        warning = Color.rgb(255, 193, 73);
    }

    static CyberTheme from(Context context) {
        int night = context.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK;
        return new CyberTheme(night == Configuration.UI_MODE_NIGHT_YES);
    }

    void applyWindow(Activity activity) {
        Window window = activity.getWindow();
        window.setStatusBarColor(background);
        window.setNavigationBarColor(surface);
        int flags = window.getDecorView().getSystemUiVisibility();
        if (dark) {
            flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            flags &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        } else {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        window.getDecorView().setSystemUiVisibility(flags);
    }

    GradientDrawable panel(float radiusPx, boolean strong) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(strong ? surfaceStrong : surface);
        drawable.setCornerRadius(radiusPx);
        drawable.setStroke(1, border);
        return drawable;
    }

    GradientDrawable field(float radiusPx) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(surface);
        drawable.setCornerRadius(radiusPx);
        drawable.setStroke(1, dark ? accentSoft : border);
        return drawable;
    }

    RippleDrawable button(float radiusPx, boolean primary) {
        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(radiusPx);
        shape.setColor(primary ? accent : surfaceStrong);
        shape.setStroke(primary ? 0 : 1, accent);
        int ripple = withAlpha(accent, dark ? 72 : 48);
        return new RippleDrawable(ColorStateList.valueOf(ripple), shape, null);
    }

    RippleDrawable navButton(float radiusPx, boolean selected) {
        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(radiusPx);
        shape.setColor(selected ? surfaceStrong : surface);
        shape.setStroke(1, selected ? accent : border);
        return new RippleDrawable(ColorStateList.valueOf(withAlpha(accent, 60)), shape, null);
    }

    void glowTitle(TextView view) {
        view.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        view.setShadowLayer(dark ? 10f : 5f, 0f, 0f, withAlpha(accent, dark ? 180 : 90));
    }

    private static int withAlpha(int color, int alpha) {
        return Color.argb(
                Math.max(0, Math.min(255, alpha)),
                Color.red(color),
                Color.green(color),
                Color.blue(color)
        );
    }
}
