package com.zm920.androidserver.util;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

import com.zm920.androidserver.ui.widget.ToastUtil;

/**
 * 文件管理权限统一检查工具。
 * Android 11+（API 30+）：检查 MANAGE_EXTERNAL_STORAGE
 * Android 10 及以下（API 29-）：检查 WRITE_EXTERNAL_STORAGE
 */
public class PermissionUtil {

    private static final String TAG = "PermissionUtil";

    /** 是否有文件管理权限 */
    public static boolean hasStoragePermission(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+：需要 MANAGE_EXTERNAL_STORAGE 才能访问共享存储
            return android.os.Environment.isExternalStorageManager();
        } else {
            // Android 10 及以下：检查 WRITE_EXTERNAL_STORAGE
            int ret = ContextCompat.checkSelfPermission(context,
                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE);
            return ret == android.content.pm.PackageManager.PERMISSION_GRANTED;
        }
    }

    /**
     * 跳转到文件管理权限设置页。
     * Android 11+：跳转到 MANAGE_APP_ALL_FILES_ACCESS 页面
     * Android 10 及以下：请求 WRITE_EXTERNAL_STORAGE 运行时权限
     */
    public static void requestStoragePermission(Activity activity, int requestCode) {
        if (hasStoragePermission(activity)) {
            ToastUtil.showShort(activity, "文件管理权限已授权");
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                Intent intent = new Intent(
                        android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                intent.setData(android.net.Uri.parse("package:" + activity.getPackageName()));
                activity.startActivity(intent);
            } catch (Exception e) {
                Intent intent = new Intent(
                        android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                activity.startActivity(intent);
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            activity.requestPermissions(new String[]{
                    android.Manifest.permission.READ_EXTERNAL_STORAGE,
                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE
            }, requestCode);
        }
    }

    /**
     * 检查权限，如果没有则 Toast 提示并返回 false。
     * 适用于 Service / 非 Activity 上下文（只能弹 Toast，无法发起请求）。
     */
    /**
     * 检查权限，如果没有则弹确认对话框，用户点击后跳转到权限设置页。
     * 适用于 Fragment/Activity 场景，可以直接拉起系统权限页。
     */
    public static boolean ensureStoragePermission(Activity activity) {
        if (hasStoragePermission(activity)) return true;
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(activity)
                .setTitle("需要文件管理权限")
                .setMessage("此操作需要访问存储空间来管理服务器文件。\n\n点击「去授权」后，请在系统设置中开启「允许管理所有文件」。\n开启后返回即可继续操作。")
                .setPositiveButton("去授权", (d, w) -> requestStoragePermission(activity, 1001))
                .setNegativeButton("暂不", null)
                .setCancelable(false)
                .show();
        return false;
    }
}
