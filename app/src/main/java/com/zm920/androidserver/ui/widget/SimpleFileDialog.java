package com.zm920.androidserver.ui.widget;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import com.zm920.androidserver.ui.widget.ToastUtil;

import com.zm920.androidserver.R;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * 简易文件查看/编辑弹窗。
 * 用 Activity 风格全屏 dialog（windowIsFloating=false），
 * 系统的 adjustResize 会自动压缩可用空间，按钮自然在 IME 之上。
 */
public class SimpleFileDialog {

    public static void show(Context context, String title, File file, boolean editable) {
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            if (!file.exists()) file.createNewFile();
        } catch (Exception e) {
            ToastUtil.showShort(context, "文件准备失败: " + e.getMessage());
            return;
        }

        DisplayMetrics dm = context.getResources().getDisplayMetrics();
        float density = dm.density;
        int pad = (int) (12 * density);

        int primaryColor = resolvePrimaryColor(context);
        int r = Color.red(primaryColor);
        int g = Color.green(primaryColor);
        int b = Color.blue(primaryColor);

        // 用普通 Dialog（非全屏），保持美观
        final android.app.Dialog dialog = new android.app.Dialog(context);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(buildRoot(context, dialog, title, file, editable, primaryColor, r, g, b, density, pad, dm));

        Window window = dialog.getWindow();
        if (window != null) {
            window.setLayout(
                    (int) (dm.widthPixels * 0.92f),
                    (int) (Math.min(dm.heightPixels * 0.75f, 480 * density))
            );
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
                    | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
            // 圆角白色背景
            GradientDrawable winBg = new GradientDrawable();
            winBg.setShape(GradientDrawable.RECTANGLE);
            winBg.setCornerRadius(16 * density);
            winBg.setColor(0xFFFFFFFF);
            window.setBackgroundDrawable(winBg);
            // 关键：decorView 和 contentView 都设白色，遮挡底部透出的黑色
            window.getDecorView().setBackgroundColor(0xFFFFFFFF);
        }

        // editable 模式下主动拉起 IME
        if (editable) {
            dialog.setOnShowListener(d -> {
                EditText[] ref = new EditText[1];
                traverseFindEditText(dialog.getWindow().getDecorView(), ref);
                if (ref[0] != null) {
                    ref[0].post(() -> {
                        try {
                            ref[0].setSelection(0);
                            ref[0].requestFocus();
                            ref[0].requestFocusFromTouch();
                            InputMethodManager imm = (InputMethodManager) context.getSystemService(Context.INPUT_METHOD_SERVICE);
                            if (imm != null) imm.showSoftInput(ref[0], InputMethodManager.SHOW_FORCED);
                        } catch (Exception ignored) {}
                    });
                }
            });
        }

        dialog.show();
        // dialog show 后 decorView 完整，强制覆盖底部
        if (window != null) {
            window.getDecorView().setBackgroundColor(0xFFFFFFFF);
            View dv = window.getDecorView();
            if (dv instanceof android.view.ViewGroup) {
                android.view.ViewGroup dvg = (android.view.ViewGroup) dv;
                for (int i = 0; i < dvg.getChildCount(); i++) {
                    dvg.getChildAt(i).setBackgroundColor(0xFFFFFFFF);
                }
            }
        }
    }

    private static void traverseFindEditText(View v, EditText[] ref) {
        if (v instanceof EditText) { ref[0] = (EditText) v; return; }
        if (v instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) v;
            for (int i = 0; i < vg.getChildCount(); i++) {
                traverseFindEditText(vg.getChildAt(i), ref);
                if (ref[0] != null) return;
            }
        }
    }

    private static View buildRoot(Context context, android.app.Dialog dialog, String title,
                                  File file, boolean editable,
                                  int primaryColor, int r, int g, int b,
                                  float density, int pad, DisplayMetrics dm) {

        // 包装一层：让 root 撑满 dialog 整个窗口（contentView 默认 MATCH_PARENT）
        // 但 root 在 dialog 内容区，需要吃掉 window 默认 padding
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFFFFFFFF);
        root.setFitsSystemWindows(false);
        // 强制吃掉 dialog 内容区 padding
        root.setPadding(0, 0, 0, 0);

        // 标题栏
        LinearLayout titleBar = new LinearLayout(context);
        titleBar.setOrientation(LinearLayout.HORIZONTAL);
        titleBar.setGravity(Gravity.CENTER_VERTICAL);
        titleBar.setBackgroundColor(primaryColor);
        int tPad = (int) (12 * density);
        titleBar.setPadding(tPad, tPad, tPad, tPad);

        TextView titleTv = new TextView(context);
        titleTv.setText(title);
        titleTv.setTextSize(15f);
        titleTv.setTextColor(0xFFFFFFFF);
        titleTv.setTypeface(null, Typeface.BOLD);
        titleTv.setSingleLine(true);
        titleTv.setEllipsize(android.text.TextUtils.TruncateAt.END);
        titleTv.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        titleBar.addView(titleTv);

        // 路径
        TextView pathTv = new TextView(context);
        pathTv.setText(file.getAbsolutePath());
        pathTv.setTextSize(11f);
        pathTv.setTextColor(0xFF888888);
        pathTv.setPadding(tPad, 0, tPad, (int) (6 * density));
        pathTv.setSingleLine(true);
        pathTv.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        pathTv.setBackgroundColor(0xFFF5F5F5);

        // 内容区：白色背景容器，里面直接放 EditText/TextView 自带滚动，不再用 ScrollView 包装
        // ScrollView 在某些 ROM 上未填满时底部会显示黑色背景块，去掉它
        LinearLayout contentWrap = new LinearLayout(context);
        contentWrap.setOrientation(LinearLayout.VERTICAL);
        contentWrap.setBackgroundColor(0xFFFFFFFF);
        int cPad = (int) (6 * density);

        String text = readText(file);
        final EditText editor;
        final TextView viewer;

        if (editable) {
            editor = new EditText(context);
            editor.setText(text);
            editor.setTextSize(13f);
            editor.setTypeface(Typeface.MONOSPACE);
            editor.setTextColor(0xFFD4D4D4);
            editor.setHintTextColor(0xFF666666);
            editor.setBackgroundColor(0xFF1E1E1E);
            editor.setGravity(Gravity.TOP | Gravity.START);
            editor.setInputType(InputType.TYPE_CLASS_TEXT
                    | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                    | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            editor.setImeOptions(EditorInfo.IME_FLAG_NO_FULLSCREEN);
            editor.setSingleLine(false);
            editor.setVerticalScrollBarEnabled(true);
            editor.setHorizontalScrollBarEnabled(true);
            editor.setHorizontallyScrolling(true);
            editor.setPadding(cPad, cPad, cPad, cPad);
            // 不用 minLines/maxLines，让 EditText wrap_content 自适应内容高度
            // wrap_content 在 weight=1 容器中会按内容伸展，超出区域用自带滚动
            contentWrap.addView(editor, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT, 1f));
            viewer = null;
        } else {
            editor = null;
            viewer = new TextView(context);
            viewer.setText(text);
            viewer.setTextSize(12f);
            viewer.setTypeface(Typeface.MONOSPACE);
            viewer.setTextColor(0xFFD4D4D4);
            viewer.setBackgroundColor(0xFF1E1E1E);
            viewer.setPadding(cPad, cPad, cPad, cPad);
            viewer.setTextIsSelectable(true);
            contentWrap.addView(viewer, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        }

        // 按钮区
        LinearLayout btnRow = new LinearLayout(context);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams btnRowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        btnRowLp.topMargin = pad;
        btnRowLp.bottomMargin = pad;
        btnRow.setLayoutParams(btnRowLp);

        if (editable) {
            TextView closeBtn = makeBtn(context, "关闭", primaryColor, r, g, b, density, false);
            closeBtn.setOnClickListener(v -> {
                try {
                    InputMethodManager imm = (InputMethodManager) context.getSystemService(Context.INPUT_METHOD_SERVICE);
                    if (imm != null && editor != null) imm.hideSoftInputFromWindow(editor.getWindowToken(), 0);
                } catch (Exception ignored) {}
                dialog.dismiss();
            });

            TextView reloadCfgBtn = makeBtn(context, "重载配置", primaryColor, r, g, b, density, false);
            reloadCfgBtn.setOnClickListener(v -> {
                if (editor != null) {
                    editor.setText(readText(file));
                    ToastUtil.showLong(context, "已重载");
                }
            });

            TextView saveBtn = makeBtn(context, "保存", primaryColor, r, g, b, density, true);
            saveBtn.setOnClickListener(v -> {
                if (editor == null) return;
                try {
                    writeText(file, editor.getText().toString());
                    ToastUtil.showShort(context, "保存成功");
                } catch (Exception ex) {
                    ToastUtil.showShort(context, "保存失败: " + ex.getMessage());
                }
            });

            btnRow.addView(closeBtn);
            btnRow.addView(reloadCfgBtn);
            btnRow.addView(saveBtn);
        } else {
            TextView closeBtn = makeBtn(context, "关闭", primaryColor, r, g, b, density, false);
            closeBtn.setOnClickListener(v -> {
                try {
                    InputMethodManager imm = (InputMethodManager) context.getSystemService(Context.INPUT_METHOD_SERVICE);
                    if (imm != null) imm.hideSoftInputFromWindow(dialog.getWindow().getDecorView().getWindowToken(), 0);
                } catch (Exception ignored) {}
                dialog.dismiss();
            });

            TextView reloadLogBtn = makeBtn(context, "重载日志", primaryColor, r, g, b, density, true);
            reloadLogBtn.setOnClickListener(v -> {
                if (viewer != null) {
                    viewer.setText(readText(file));
                    ToastUtil.showShort(context, "已重载");
                }
            });

            btnRow.addView(closeBtn);
            btnRow.addView(reloadLogBtn);
        }

        // 拼装：标题 + 路径固定，内容区 weight=1 可压缩，按钮固定在底部
        root.addView(titleBar);
        root.addView(pathTv);
        root.addView(contentWrap, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(btnRow);

        return root;
    }

    private static TextView makeBtn(Context context, String text, int primaryColor,
                                    int r, int g, int b, float density, boolean primary) {
        TextView btn = new TextView(context);
        btn.setText(text);
        btn.setTextSize(14f);
        btn.setGravity(Gravity.CENTER);
        btn.setClickable(true);
        btn.setFocusable(true);
        btn.setPadding((int) (12 * density), (int) (10 * density),
                (int) (12 * density), (int) (10 * density));

        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(8 * density);
        if (primary) {
            bg.setColor(primaryColor);
            btn.setTextColor(0xFFFFFFFF);
        } else {
            bg.setColor(Color.argb(18, r, g, b));
            bg.setStroke((int) (1 * density), Color.argb(120, r, g, b));
            btn.setTextColor(primaryColor);
        }
        btn.setBackground(bg);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.leftMargin = (int) (4 * density);
        lp.rightMargin = (int) (4 * density);
        btn.setLayoutParams(lp);
        return btn;
    }



    private static int resolvePrimaryColor(Context context) {
        try {
            SharedPreferences prefs = context.getSharedPreferences("server_settings", 0);
            String key = prefs.getString("theme_key", "slate");
            int resId;
            switch (key) {
                case "green":  resId = R.color.theme_green_primary; break;
                case "purple": resId = R.color.theme_purple_primary; break;
                case "orange": resId = R.color.theme_orange_primary; break;
                case "blue":   resId = R.color.blue_primary; break;
                case "red":    resId = R.color.theme_red_primary; break;
                case "cyan":   resId = R.color.theme_cyan_primary; break;
                case "pink":   resId = R.color.theme_pink_primary; break;
                case "indigo": resId = R.color.theme_indigo_primary; break;
                case "brown":  resId = R.color.theme_brown_primary; break;
                default:       resId = R.color.theme_slate_primary;
            }
            return androidx.core.content.ContextCompat.getColor(context, resId);
        } catch (Exception e) {
            return 0xFF1A73E8;
        }
    }

    private static String readText(File file) {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append('\n');
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static void writeText(File file, String text) throws Exception {
        try (FileOutputStream fos = new FileOutputStream(file, false)) {
            fos.write(text.getBytes(StandardCharsets.UTF_8));
            fos.flush();
        }
    }
}
