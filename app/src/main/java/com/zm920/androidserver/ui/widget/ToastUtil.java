package com.zm920.androidserver.ui.widget;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 自定义 Toast 工具,完全替换系统 Toast。
 * 移除系统 Toast 左边的应用图标,统一深色圆角胶囊样式。
 */
public final class ToastUtil {

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static Toast current;

    private ToastUtil() {}

    public static void showShort(Context ctx, String msg) {
        show(ctx, msg, false);
    }

    public static void showLong(Context ctx, String msg) {
        show(ctx, msg, true);
    }

    public static void showError(Context ctx, String msg) {
        show(ctx, "⚠ " + (msg == null ? "" : msg), true, 0xFFD32F2F);
    }

    private static void show(Context ctx, String msg, boolean longDuration) {
        show(ctx, msg, longDuration, 0xEE202124);
    }

    private static void show(Context ctx, String msg, boolean longDuration, int bgColor) {
        if (ctx == null || msg == null) return;
        Context app = ctx.getApplicationContext();
        MAIN.post(() -> {
            try {
                if (current != null) {
                    try { current.cancel(); } catch (Exception ignored) {}
                }
                Toast t = new Toast(app);
                t.setView(buildView(app, msg, bgColor));
                t.setDuration(longDuration ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT);
                t.setGravity(Gravity.BOTTOM, 0, (int) dp(app, 80));
                try { t.show(); } catch (Exception ignored) {}
                current = t;
            } catch (Exception e) {
                android.util.Log.w("ToastUtil", "fallback to system toast", e);
                Toast.makeText(app, msg, longDuration ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT).show();
            }
        });
    }

    private static View buildView(Context ctx, String msg, int bgColor) {
        TextView tv = new TextView(ctx);
        tv.setText(msg);
        tv.setTextSize(14f);
        tv.setTextColor(0xFFFFFFFF);
        int hPad = (int) dp(ctx, 16);
        int vPad = (int) dp(ctx, 10);
        tv.setPadding(hPad, vPad, hPad, vPad);
        tv.setMaxWidth((int) (ctx.getResources().getDisplayMetrics().widthPixels * 0.86f));
        tv.setSingleLine(false);
        tv.setLineSpacing(0f, 1.1f);

        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(dp(ctx, 22));
        bg.setColor(bgColor);
        tv.setBackground(bg);
        tv.setElevation(dp(ctx, 6));

        int wrapH = android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
        int wrapW = android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
        android.widget.FrameLayout.LayoutParams lp =
                new android.widget.FrameLayout.LayoutParams(wrapW, wrapH);
        tv.setLayoutParams(lp);
        return tv;
    }

    private static float dp(Context ctx, int dp) {
        return dp * ctx.getResources().getDisplayMetrics().density;
    }
}
