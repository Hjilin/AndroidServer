package com.zm920.androidserver.update;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;

/**
 * APK 下载安装工具，处理权限引导。
 * 将 pending APK 路径写入 SharedPreferences key "pending_apk_update"。
 *
 * 特性：
 * - 自定义进度对话框（与其他更新弹窗风格一致）
 * - 失败自动重试（最多 3 次）
 * - 支持取消下载
 * - 可选 MD5 校验
 * - 安装后保留 APK，由下次启动清理（避免用户在系统安装器取消后无法恢复）
 */
public class UpdateInstaller {

    private static final String TAG = "UpdateInstaller";
    private static final int MAX_RETRY = 3;
    private static final int CONNECT_TIMEOUT = 15000;
    private static final int READ_TIMEOUT = 30000;

    private static volatile boolean sCancelled = false;

    public interface InstallCallback {
        void onSuccess();
        void onError(String msg);
    }

    /** 取消正在进行的下载 */
    public static void cancelDownload() {
        sCancelled = true;
    }

    /** 下载 APK 并安装（或引导权限）。md5 为空时跳过校验。 */
    public static void downloadAndInstall(final Context ctx, final String url,
                                          final String expectedMd5,
                                          final InstallCallback cb) {
        sCancelled = false;
        final ProgressHolder holder = new ProgressHolder();
        final Dialog progressDialog = createProgressDialog(ctx, holder);
        progressDialog.show();

        new Thread(() -> {
            File apkFile = new File(ctx.getFilesDir(), "updates/update.apk");
            try {
                File dir = apkFile.getParentFile();
                if (dir != null && !dir.exists()) dir.mkdirs();

                boolean ok = false;
                String lastErr = null;

                for (int attempt = 1; attempt <= MAX_RETRY; attempt++) {
                    if (sCancelled) {
                        lastErr = "已取消";
                        break;
                    }
                    try {
                        postProgress(progressDialog, holder, 0, 0, 0,
                                "开始下载（第 " + attempt + "/" + MAX_RETRY + " 次）...");
                        downloadWithProgress(ctx, url, apkFile, progressDialog, holder, attempt);

                        // MD5 校验
                        if (expectedMd5 != null && !expectedMd5.trim().isEmpty()) {
                            postProgress(progressDialog, holder, 100,
                                    apkFile.length(), apkFile.length(), "校验文件完整性...");
                            String actualMd5 = calcMd5(apkFile);
                            if (!actualMd5.equalsIgnoreCase(expectedMd5.trim())) {
                                safeDelete(apkFile);
                                lastErr = "MD5 校验失败，请重试";
                                continue;
                            }
                        }
                        ok = true;
                        break;
                    } catch (Exception e) {
                        lastErr = e.getMessage() == null ? "下载失败" : e.getMessage();
                        Log.w(TAG, "download attempt " + attempt + " failed", e);
                        safeDelete(apkFile);
                    }
                }

                final boolean success = ok;
                final String errMsg = lastErr;
                new Handler(Looper.getMainLooper()).post(() -> {
                    if (progressDialog.isShowing()) progressDialog.dismiss();
                    if (sCancelled) {
                        if (cb != null) cb.onError("已取消");
                        return;
                    }
                    if (!success) {
                        if (cb != null) cb.onError(errMsg);
                        return;
                    }
                    installApk(ctx, apkFile, cb);
                });

            } catch (Exception e) {
                final String errMsg = e.getMessage() == null ? "下载失败" : e.getMessage();
                Log.e(TAG, "download fatal", e);
                new Handler(Looper.getMainLooper()).post(() -> {
                    if (progressDialog.isShowing()) progressDialog.dismiss();
                    if (cb != null) cb.onError(errMsg);
                });
            }
        }, "UpdateInstaller-download").start();
    }

    /** 向后兼容：不带 MD5 的重载 */
    public static void downloadAndInstall(final Context ctx, final String url,
                                          final InstallCallback cb) {
        downloadAndInstall(ctx, url, null, cb);
    }

    private static void downloadWithProgress(Context ctx, String urlStr, File apkFile,
                                             Dialog dialog, ProgressHolder holder,
                                             int attempt) throws Exception {
        URL downloadUrl = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) downloadUrl.openConnection();
        conn.setConnectTimeout(CONNECT_TIMEOUT);
        conn.setReadTimeout(READ_TIMEOUT);
        conn.connect();

        int total = conn.getContentLength();
        InputStream is = conn.getInputStream();
        FileOutputStream fos = new FileOutputStream(apkFile);

        byte[] buf = new byte[8192];
        int len;
        long downloaded = 0;
        try {
            while ((len = is.read(buf)) != -1) {
                if (sCancelled) {
                    throw new RuntimeException("已取消");
                }
                fos.write(buf, 0, len);
                downloaded += len;
                if (total > 0) {
                    final int percent = (int) (downloaded * 100 / total);
                    final long fd = downloaded;
                    final int t = total;
                    postProgress(dialog, holder, percent, fd, t,
                            "下载 " + formatSize(fd) + " / " + formatSize(t) + " (" + percent + "%)");
                }
            }
        } finally {
            try { fos.close(); } catch (Exception ignored) {}
            try { is.close(); } catch (Exception ignored) {}
            conn.disconnect();
        }
    }

    private static void postProgress(Dialog dialog, ProgressHolder holder,
                                     int percent, long downloaded, long total, String msg) {
        new Handler(Looper.getMainLooper()).post(() -> {
            if (dialog == null || !dialog.isShowing()) return;
            if (holder.tvMsg != null) holder.tvMsg.setText(msg);
            if (holder.tvPercent != null) holder.tvPercent.setText(percent + "%");
            if (holder.pb != null) holder.pb.setProgress(percent);
        });
    }

    /** 进度对话框视图持有者，避免依赖 R.id */
    private static class ProgressHolder {
        TextView tvMsg;
        TextView tvPercent;
        ProgressBar pb;
    }

    private static Dialog createProgressDialog(Context ctx, ProgressHolder holder) {
        final Dialog dialog = new Dialog(ctx);
        dialog.setTitle(null);
        dialog.setCancelable(false);

        float density = ctx.getResources().getDisplayMetrics().density;

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * density);
        root.setPadding(pad, pad, pad, (int) (24 * density));

        TextView title = new TextView(ctx);
        title.setText("正在下载更新");
        title.setTextSize(18f);
        title.setTextColor(0xFF202124);
        title.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-1, -2);
        tl.bottomMargin = (int) (16 * density);
        title.setLayoutParams(tl);
        root.addView(title);

        ProgressBar pb = new ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal);
        pb.setMax(100);
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(-1, (int) (8 * density));
        pl.bottomMargin = (int) (8 * density);
        pb.setLayoutParams(pl);
        root.addView(pb);
        holder.pb = pb;

        TextView tvMsg = new TextView(ctx);
        tvMsg.setTextSize(13f);
        tvMsg.setTextColor(0xFF5F6368);
        LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(-1, -2);
        ml.bottomMargin = (int) (4 * density);
        tvMsg.setLayoutParams(ml);
        root.addView(tvMsg);
        holder.tvMsg = tvMsg;

        TextView tvPercent = new TextView(ctx);
        tvPercent.setTextSize(12f);
        tvPercent.setTextColor(0xFF5F6368);
        tvPercent.setGravity(Gravity.END);
        tvPercent.setText("0%");
        LinearLayout.LayoutParams pl2 = new LinearLayout.LayoutParams(-1, -2);
        tvPercent.setLayoutParams(pl2);
        root.addView(tvPercent);
        holder.tvPercent = tvPercent;

        dialog.setContentView(root);

        Window window = dialog.getWindow();
        if (window != null) {
            WindowManager.LayoutParams lp = window.getAttributes();
            lp.width = (int) (ctx.getResources().getDisplayMetrics().widthPixels * 0.88f);
            window.setAttributes(lp);
        }
        return dialog;
    }

    private static String calcMd5(File file) throws Exception {
        MessageDigest md = MessageDigest.getInstance("MD5");
        FileInputStream fis = new FileInputStream(file);
        byte[] buf = new byte[8192];
        int len;
        try {
            while ((len = fis.read(buf)) != -1) {
                md.update(buf, 0, len);
            }
        } finally {
            try { fis.close(); } catch (Exception ignored) {}
        }
        byte[] digest = md.digest();
        StringBuilder sb = new StringBuilder();
        for (byte b : digest) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private static void safeDelete(File f) {
        try { if (f != null && f.exists()) f.delete(); } catch (Exception ignored) {}
    }

    /** 检查权限并安装/引导 */
    public static void installApk(final Context ctx, final File apkFile,
                                  final InstallCallback cb) {
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                if (!ctx.getPackageManager().canRequestPackageInstalls()) {
                    ctx.getSharedPreferences("server_settings", 0)
                            .edit().putString("pending_apk_update", apkFile.getAbsolutePath()).apply();

                    new AlertDialog.Builder(ctx)
                            .setTitle("需要安装权限")
                            .setMessage("请允许安装未知来源应用，以便完成更新")
                            .setPositiveButton("去设置", (d, w) -> {
                                Intent intent = new Intent(
                                        android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES);
                                intent.setData(Uri.parse("package:" + ctx.getPackageName()));
                                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                                ctx.startActivity(intent);
                            })
                            .setNegativeButton("取消", (d, w) -> {
                                ctx.getSharedPreferences("server_settings", 0)
                                        .edit().remove("pending_apk_update").apply();
                            })
                            .show();
                    return;
                }
            }
            // 有权限，直接安装
            ctx.getSharedPreferences("server_settings", 0)
                    .edit().remove("pending_apk_update").apply();
            doInstall(ctx, apkFile);
            if (cb != null) cb.onSuccess();
        } catch (Exception e) {
            if (cb != null) cb.onError("安装失败: " + e.getMessage());
        }
    }

    /** 实际拉起系统安装器 */
    public static void doInstall(Context ctx, File apkFile) {
        try {
            Uri apkUri = FileProvider.getUriForFile(ctx,
                    ctx.getPackageName() + ".fileprovider", apkFile);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(apkUri, "application/vnd.android.package-archive");
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            ctx.startActivity(intent);
            // 不再立即删除 APK：保留到下次启动清理（DashboardFragment 启动时会清理），
            // 这样用户在系统安装器取消后，pending 恢复机制仍能使用。
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** 检查是否有 pending APK 需要安装 */
    public static boolean tryInstallPending(Context ctx) {
        String path = ctx.getSharedPreferences("server_settings", 0)
                .getString("pending_apk_update", "");
        if (path.isEmpty()) return false;
        File f = new File(path);
        if (!f.exists()) return false;
        if (Build.VERSION.SDK_INT >= 26 && !ctx.getPackageManager().canRequestPackageInstalls()) {
            return false; // 权限还没开，继续等待
        }
        ctx.getSharedPreferences("server_settings", 0)
                .edit().remove("pending_apk_update").apply();
        doInstall(ctx, f);
        return true;
    }

    private static String formatSize(long bytes) {
        if (bytes <= 0) return "0B";
        String[] units = {"B", "KB", "MB", "GB"};
        int idx = 0;
        double v = bytes;
        while (v >= 1024 && idx < units.length - 1) { v /= 1024; idx++; }
        return String.format("%.1f%s", v, units[idx]);
    }
}
