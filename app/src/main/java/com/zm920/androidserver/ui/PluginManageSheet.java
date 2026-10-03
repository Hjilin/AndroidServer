package com.zm920.androidserver.ui;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.zm920.androidserver.R;
import com.zm920.androidserver.plugin.PluginManager;
import com.zm920.androidserver.plugin.PluginStore;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;

/**
 * 插件管理弹层：插件市场（在线下载）+ 本地导入（zip）+ 已安装列表（启停/日志/卸载）。
 * 按简云plus MIUI 卡片风格构建。
 */
public class PluginManageSheet {

    public static void show(androidx.fragment.app.Fragment fragment) {
        new PluginManageSheet(fragment).open();
    }

    private final androidx.fragment.app.Fragment fragment;
    private final Activity activity;
    private final PluginManager pm;
    private BottomSheetDialog sheet;
    private LinearLayout marketRoot, installedRoot;
    private Handler handler = new Handler(Looper.getMainLooper());
    private int accent = 0xFF1A73E8;

    private PluginManageSheet(androidx.fragment.app.Fragment fragment) {
        this.fragment = fragment;
        this.activity = fragment.requireActivity();
        this.pm = PluginManager.getInstance(fragment.requireContext());
    }

    private void open() {
        try {
            accent = resolveColor();
            sheet = new BottomSheetDialog(activity);
            ScrollView sv = new ScrollView(activity);
            LinearLayout root = new LinearLayout(activity);
            root.setOrientation(LinearLayout.VERTICAL);
            int pad = dp(20);
            root.setPadding(pad, pad, pad, dp(28));
            sv.addView(root);

            root.addView(header());

            // —— 插件市场 ——
            root.addView(sectionTitle("插件市场"));
        marketRoot = new LinearLayout(activity);
        marketRoot.setOrientation(LinearLayout.VERTICAL);
        root.addView(marketRoot);

        // —— 本地导入 ——
        root.addView(sectionTitle("本地导入"));
        root.addView(buildImportCard());

        // —— 已安装 ——
        root.addView(sectionTitle("已安装插件"));
        installedRoot = new LinearLayout(activity);
        installedRoot.setOrientation(LinearLayout.VERTICAL);
        root.addView(installedRoot);

        sheet.setContentView(sv);
        sheet.show();

        try {
            renderMarket();
            renderInstalled();
        } catch (Throwable t) {
            try { sheet.dismiss(); } catch (Throwable ignored) {}
            toast("插件管理加载失败: " + t.getClass().getSimpleName());
        }
        } catch (Throwable t) {
            try { if (sheet != null) sheet.dismiss(); } catch (Throwable ignored) {}
            toast("插件管理打开失败: " + t.getClass().getSimpleName());
        }
    }

    private TextView header() {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, 0, 0, dp(12));

        TextView title = new TextView(activity);
        title.setText("插件管理");
        title.setTextSize(20f);
        title.setTextColor(0xFF202124);
        title.setTypeface(null, Typeface.BOLD);
        title.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        row.addView(title);
        return title;
    }

    private TextView sectionTitle(String s) {
        TextView t = new TextView(activity);
        t.setText(s);
        t.setTextSize(12f);
        t.setTextColor(0xFF5F6368);
        t.setTypeface(null, Typeface.BOLD);
        t.setPadding(0, dp(6), 0, dp(8));
        return t;
    }

    // ============ 插件市场 ============
    private void renderMarket() {
        marketRoot.removeAllViews();
        for (PluginStore.StoreItem item : PluginStore.list()) {
            marketRoot.addView(marketCard(item));
        }
        if (PluginStore.list().isEmpty()) {
            marketRoot.addView(emptyTip("暂无可用插件"));
        }
    }

    private View marketCard(final PluginStore.StoreItem item) {
        LinearLayout card = baseCard();
        boolean installed = pm.isInstalled(item.id);

        LinearLayout left = new LinearLayout(activity);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        TextView name = new TextView(activity);
        name.setText(item.name + "  v" + item.version);
        name.setTextSize(15f);
        name.setTextColor(0xFF202124);
        name.setTypeface(null, Typeface.BOLD);
        left.addView(name);

        TextView desc = new TextView(activity);
        desc.setText(item.desc);
        desc.setTextSize(12f);
        desc.setTextColor(0xFF5F6368);
        desc.setPadding(0, dp(4), 0, 0);
        left.addView(desc);

        card.addView(left);

        TextView action = pill(installed ? "已安装" : "下载");
        action.setTextColor(installed ? 0xFF5F6368 : Color.WHITE);
        if (!installed) {
            action.setBackgroundColor(accent);
            action.setOnClickListener(v -> downloadPlugin(item));
        } else {
            action.setBackgroundColor(0xFFE8EAED);
        }
        card.addView(action);
        return card;
    }

    // ============ 本地导入 ============
    private View buildImportCard() {
        LinearLayout card = baseCard();
        TextView label = new TextView(activity);
        label.setText("从本地文件导入插件包 (.zip)");
        label.setTextSize(13f);
        label.setTextColor(0xFF202124);
        label.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        card.addView(label);

        TextView btn = pill("导入");
        btn.setTextColor(Color.WHITE);
        btn.setBackgroundColor(accent);
        btn.setOnClickListener(v -> pickZip());
        card.addView(btn);
        return card;
    }

    // ============ 已安装列表 ============
    private void renderInstalled() {
        installedRoot.removeAllViews();
        List<String> ids = pm.listInstalledIds();
        if (ids.isEmpty()) {
            installedRoot.addView(emptyTip("尚未安装任何插件"));
            return;
        }
        for (String id : ids) {
            installedRoot.addView(installedCard(id));
        }
    }

    private View installedCard(final String id) {
        JSONObject meta = pm.loadMeta(id);
        final String name = meta != null ? meta.optString("name", id) : id;
        final int port = meta != null ? meta.optInt("port", 0) : 0;
        final boolean running = pm.isRunning(id);

        LinearLayout card = baseCard();
        card.setOrientation(LinearLayout.HORIZONTAL);

        LinearLayout left = new LinearLayout(activity);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        TextView nameTv = new TextView(activity);
        nameTv.setText(name + (running ? "  ●运行中" : "  ○已停止"));
        nameTv.setTextSize(15f);
        nameTv.setTextColor(running ? 0xFF34A853 : 0xFF202124);
        nameTv.setTypeface(null, Typeface.BOLD);
        left.addView(nameTv);

        TextView portTv = new TextView(activity);
        portTv.setText(port > 0 ? "端口 " + port : "—");
        portTv.setTextSize(12f);
        portTv.setTextColor(0xFF5F6368);
        left.addView(portTv);

        card.addView(left);

        // 操作按钮：启停 / 日志 / 删除
        TextView toggle = pill(running ? "停止" : "启动");
        toggle.setTextColor(running ? 0xFF202124 : Color.WHITE);
        toggle.setBackgroundColor(running ? 0xFFE8EAED : accent);
        toggle.setOnClickListener(v -> {
            if (running) pm.stop(id); else pm.start(id);
            renderInstalled();
        });
        card.addView(toggle);

        TextView logBtn = pill("日志");
        logBtn.setTextColor(0xFF1A73E8);
        logBtn.setBackgroundColor(0xFFE8F0FE);
        logBtn.setOnClickListener(v -> showLog(id));
        card.addView(logBtn);

        TextView del = pill("删除");
        del.setTextColor(0xFFD93025);
        del.setBackgroundColor(0xFFFCE8E6);
        del.setOnClickListener(v -> {
            new MaterialAlertDialogBuilder(activity)
                    .setTitle("卸载插件")
                    .setMessage("确定卸载「" + name + "」？其数据和配置将被删除。")
                    .setPositiveButton("卸载", (d, w) -> { pm.uninstall(id); renderInstalled(); renderMarket(); })
                    .setNegativeButton("取消", null)
                    .show();
        });
        card.addView(del);
        return card;
    }

    private void showLog(final String id) {
        String path = pm.getLogPath(id);
        final java.io.File f = new java.io.File(path);
        final TextView tv = new TextView(activity);
        tv.setTextSize(11f);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextColor(0xFF202124);
        tv.setPadding(dp(12), dp(10), dp(12), dp(10));
        tv.setBackgroundColor(0xFFF1F3F4);

        final ScrollView sv = new ScrollView(activity);
        sv.addView(tv);
        sv.setMinimumHeight(dp(300));

        final BottomSheetDialog d = new BottomSheetDialog(activity);
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(20), dp(20), dp(24));

        LinearLayout hdr = new LinearLayout(activity);
        hdr.setOrientation(LinearLayout.HORIZONTAL);
        hdr.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = new TextView(activity);
        t.setText("插件日志 · " + id);
        t.setTextSize(16f);
        t.setTextColor(0xFF202124);
        t.setTypeface(null, Typeface.BOLD);
        t.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        hdr.addView(t);
        TextView refresh = pill("刷新");
        refresh.setTextColor(0xFF1A73E8);
        refresh.setBackgroundColor(0xFFE8F0FE);
        refresh.setOnClickListener(v -> { tv.setText(readLog(f)); scrollBottom(sv); });
        hdr.addView(refresh);
        root.addView(hdr);
        root.addView(sv);

        d.setContentView(root);
        d.show();
        tv.setText(readLog(f));
        scrollBottom(sv);
    }

    private void scrollBottom(final ScrollView sv) {
        sv.post(() -> sv.fullScroll(View.FOCUS_DOWN));
    }

    private String readLog(File f) {
        try {
            if (!f.exists() || f.length() <= 0) return "(暂无日志)";
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            try (InputStream in = new FileInputStream(f)) {
                byte[] buf = new byte[8192]; int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            String s = new String(out.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
            int len = s.length();
            int from = Math.max(0, len - 8000);
            return s.substring(from);
        } catch (Exception e) {
            return "读取失败: " + e.getMessage();
        }
    }

    // ============ 下载插件 ============
    private void downloadPlugin(final PluginStore.StoreItem item) {
        final String[] state = {"准备下载..."};
        final BottomSheetDialog d = new BottomSheetDialog(activity);
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(24), dp(20), dp(28));

        TextView title = new TextView(activity);
        title.setText("下载 " + item.name);
        title.setTextSize(16f);
        title.setTextColor(0xFF202124);
        title.setTypeface(null, Typeface.BOLD);
        root.addView(title);

        final ProgressBar pb = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        plp.setMargins(0, dp(16), 0, dp(8));
        pb.setLayoutParams(plp);
        pb.setMax(100);
        root.addView(pb);

        final TextView st = new TextView(activity);
        st.setText(state[0]);
        st.setTextSize(12f);
        st.setTextColor(0xFF5F6368);
        root.addView(st);

        d.setContentView(root);
        d.show();

        new Thread(() -> {
            try {
                final java.io.File tmp = new java.io.File(activity.getCacheDir(), item.id + ".zip");
                downloadFile(item.downloadUrl, tmp, pct -> {
                    handler.post(() -> { pb.setProgress(pct); st.setText("下载 " + pct + "%"); });
                });
                final StringBuilder err = new StringBuilder();
                final boolean ok = pm.importFromZip(tmp, err);
                handler.post(() -> {
                    d.dismiss();
                    if (ok) {
                        toast("插件「" + item.name + "」安装成功");
                        renderMarket();
                        renderInstalled();
                    } else {
                        toast("安装失败: " + err);
                    }
                });
            } catch (Exception e) {
                handler.post(() -> { d.dismiss(); toast("下载失败: " + e.getMessage()); });
            }
        }).start();
    }

    private void downloadFile(String url, File out, java.util.function.IntConsumer prog) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        conn.connect();
        int total = conn.getContentLength();
        try (InputStream in = conn.getInputStream(); FileOutputStream fos = new FileOutputStream(out)) {
            byte[] buf = new byte[8192];
            int n, done = 0;
            while ((n = in.read(buf)) > 0) {
                fos.write(buf, 0, n);
                done += n;
                if (total > 0) {
                    final int p = (int) (done * 100L / total);
                    prog.accept(p);
                }
            }
        }
        conn.disconnect();
    }

    // ============ 本地导入 ============
    private static final int REQ_IMPORT = 0x7711;

    private void pickZip() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/zip");
        try {
            fragment.startActivityForResult(i, REQ_IMPORT);
        } catch (Exception e) {
            // 用普通文件选择兜底
            Intent i2 = new Intent(Intent.ACTION_GET_CONTENT);
            i2.setType("application/zip");
            try { fragment.startActivityForResult(i2, REQ_IMPORT); }
            catch (Exception e2) { toast("无法打开文件选择器"); }
        }
    }

    /** 由宿主 Fragment 的 onActivityResult 转发 */
    public static void handleImportResult(androidx.fragment.app.Fragment fragment, int requestCode, int resultCode, Intent data) {
        if (requestCode != REQ_IMPORT || resultCode != Activity.RESULT_OK || data == null || data.getData() == null) return;
        new PluginManageSheet(fragment).handleImportUri(data.getData());
    }

    private void handleImportUri(Uri uri) {
        if (uri == null) return;
        new Thread(() -> {
            try {
                File tmp = new File(activity.getCacheDir(), "import_" + System.currentTimeMillis() + ".zip");
                try (InputStream in = activity.getContentResolver().openInputStream(uri);
                     FileOutputStream fos = new FileOutputStream(tmp)) {
                    byte[] buf = new byte[8192]; int n;
                    while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
                }
                StringBuilder err = new StringBuilder();
                final boolean ok = pm.importFromZip(tmp, err);
                handler.post(() -> {
                    if (ok) {
                        toast("插件导入成功");
                        renderMarket();
                        renderInstalled();
                    } else {
                        toast("导入失败: " + err);
                    }
                });
            } catch (Exception e) {
                handler.post(() -> toast("导入失败: " + e.getMessage()));
            }
        }).start();
    }

    // ============ 工具 ============
    private LinearLayout baseCard() {
        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(dp(12));
        bg.setColor(0xFFF7F8FA);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dp(10));
        card.setLayoutParams(lp);
        card.setBackground(bg);
        return card;
    }

    private TextView pill(String s) {
        TextView t = new TextView(activity);
        t.setText(s);
        t.setTextSize(12f);
        t.setTextColor(0xFF202124);
        t.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(dp(16));
        bg.setColor(0xFFE8EAED);
        t.setBackground(bg);
        t.setPadding(dp(14), dp(6), dp(14), dp(6));
        t.setClickable(true);
        t.setFocusable(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(6), 0, 0, 0);
        t.setLayoutParams(lp);
        return t;
    }

    private TextView emptyTip(String s) {
        TextView t = new TextView(activity);
        t.setText(s);
        t.setTextSize(13f);
        t.setTextColor(0xFF9AA0A6);
        t.setPadding(0, dp(8), 0, dp(8));
        return t;
    }

    private int dp(int v) {
        return Math.round(activity.getResources().getDisplayMetrics().density * v);
    }

    private int resolveColor() {
        try {
            android.content.SharedPreferences p = activity.getSharedPreferences("server_settings", 0);
            int resId;
            switch (p.getString("theme_key", "slate")) {
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
            return ContextCompat.getColor(activity, resId);
        } catch (Exception e) {
            return 0xFF1A73E8;
        }
    }

    private static void toast(android.content.Context c, String msg) {
        android.widget.Toast.makeText(c, msg, android.widget.Toast.LENGTH_LONG).show();
    }

    private void toast(String msg) {
        toast(activity, msg);
    }
}
