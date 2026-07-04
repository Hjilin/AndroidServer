package com.zm920.androidserver.util;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 电池白名单 / OEM 自启动 / 后台设置辅助类。
 *
 * 参考 nousath/battery_optimization_permission 的 OEM 适配逻辑，
 * 适配小米/红米/Poco、OPPO/Realme/OnePlus、Vivo/iQOO、三星等不同款式手机。
 */
public class BatteryOptimizationHelper {

    private static final String TAG = "BatteryOptHelper";

    private BatteryOptimizationHelper() {}

    /**
     * 检查应用是否已加入电池白名单（忽略电池优化）。
     * Android < 6.0 没有 Doze 机制，直接返回 true。
     */
    public static boolean isIgnoringBatteryOptimizations(Context context) {
        if (context == null) return false;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true;
        try {
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            if (pm == null) return false;
            return pm.isIgnoringBatteryOptimizations(context.getPackageName());
        } catch (Exception e) {
            Log.e(TAG, "isIgnoringBatteryOptimizations failed", e);
            return false;
        }
    }

    /**
     * 弹出系统对话框，请求用户将应用加入电池白名单。
     * 需要 Activity 上下文。
     *
     * @return true 已加入白名单；false 用户拒绝或失败
     */
    public static boolean requestIgnoreBatteryOptimizations(Activity activity) {
        if (activity == null) return false;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true;

        Context ctx = activity.getApplicationContext();
        PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
        if (pm != null && pm.isIgnoringBatteryOptimizations(ctx.getPackageName())) {
            return true;
        }

        try {
            Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            intent.setData(Uri.parse("package:" + ctx.getPackageName()));
            activity.startActivity(intent);
            return false;
        } catch (Exception e) {
            Log.e(TAG, "requestIgnoreBatteryOptimizations failed", e);
            return false;
        }
    }

    /**
     * 打开系统电池优化列表页。
     */
    public static boolean openBatteryOptimizationSettings(Activity activity) {
        if (activity == null) return false;
        try {
            Intent intent = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
            activity.startActivity(intent);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "openBatteryOptimizationSettings failed", e);
            return false;
        }
    }

    /**
     * 打开应用详情页。
     */
    public static boolean openAppSettings(Activity activity) {
        if (activity == null) return false;
        try {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.fromParts("package", activity.getPackageName(), null));
            activity.startActivity(intent);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "openAppSettings failed", e);
            return false;
        }
    }

    /**
     * 尝试打开 OEM 厂商的"自启动 / 后台运行"设置页。
     * 适配小米/红米/Poco、OPPO/Realme/OnePlus、Vivo/iQOO、三星等。
     *
     * @return true 成功打开了某个 OEM 设置页；false 没有匹配的 OEM 页
     */
    public static boolean openOemAutoStartSettings(Activity activity) {
        if (activity == null) return false;
        Context ctx = activity.getApplicationContext();
        String manufacturer = (Build.MANUFACTURER == null ? "" : Build.MANUFACTURER)
                .toLowerCase(Locale.getDefault());
        String pkg = ctx.getPackageName();

        List<Intent> candidates = new ArrayList<>();

        // 小米 / 红米 / Poco (MIUI / HyperOS)
        if (manufacturer.contains("xiaomi") || manufacturer.contains("redmi")
                || manufacturer.contains("poco")) {
            // MIUI 自启动管理
            candidates.add(new Intent().setComponent(new ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity")));

            // MIUI 后台省电隐藏应用配置
            Intent miuiHidden = new Intent().setComponent(new ComponentName(
                    "com.miui.powerkeeper",
                    "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"));
            miuiHidden.putExtra("package_name", pkg);
            miuiHidden.putExtra("package_label", getAppLabel(ctx));
            candidates.add(miuiHidden);

            // MIUI 通用 action
            candidates.add(new Intent("miui.intent.action.OP_AUTO_START"));
            candidates.add(new Intent("miui.intent.action.POWER_HIDE_MODE_APP_LIST"));
        }

        // OPPO / Realme / OnePlus (ColorOS 系列)
        if (manufacturer.contains("oppo") || manufacturer.contains("realme")
                || manufacturer.contains("oneplus")) {
            candidates.add(new Intent().setComponent(new ComponentName(
                    "com.coloros.safecenter",
                    "com.coloros.safecenter.startupapp.StartupAppListActivity")));
            candidates.add(new Intent().setComponent(new ComponentName(
                    "com.coloros.safecenter",
                    "com.coloros.safecenter.permission.startup.StartupAppListActivity")));
            candidates.add(new Intent().setComponent(new ComponentName(
                    "com.oppo.safe",
                    "com.oppo.safe.permission.startup.StartupAppListActivity")));
            candidates.add(new Intent().setComponent(new ComponentName(
                    "com.coloros.oppoguardelf",
                    "com.coloros.powermanager.fuelgaue.PowerConsumptionActivity")));
        }

        // Vivo / iQOO
        if (manufacturer.contains("vivo") || manufacturer.contains("iqoo")) {
            candidates.add(new Intent().setComponent(new ComponentName(
                    "com.vivo.permissionmanager",
                    "com.vivo.permissionmanager.activity.BgStartUpManagerActivity")));
            candidates.add(new Intent().setComponent(new ComponentName(
                    "com.iqoo.secure",
                    "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity")));
        }

        // 三星 (Device Care / Battery)
        if (manufacturer.contains("samsung")) {
            candidates.add(new Intent().setComponent(new ComponentName(
                    "com.samsung.android.lool",
                    "com.samsung.android.sm.ui.battery.BatteryActivity")));
            // 通用电池省电设置兜底
            candidates.add(new Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS));
        }

        // 依次尝试启动第一个可用的
        for (Intent intent : candidates) {
            if (tryStartActivity(activity, intent)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 最佳用户体验的一站式流程（仅电池白名单，不含 OEM 自启动）：
     * 1) 已加入白名单 -> 返回 true
     * 2) 弹系统对话框（直接定位到本 App） -> 成功则结束
     * 3) 系统不支持/被拒绝 -> 打开电池优化列表 -> 失败则打开应用详情
     *
     * @return true 当前已加入白名单；false 用户未完成授权（已打开相应设置页）
     */
    public static boolean ensureBatteryWhitelist(Activity activity) {
        if (activity == null) return false;

        // 1) 已加入白名单
        if (isIgnoringBatteryOptimizations(activity)) return true;

        // 2) 优先弹系统对话框（直接定位到本 App 的省电策略页）
        boolean systemDialogOk = false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                intent.setData(Uri.parse("package:" + activity.getPackageName()));
                activity.startActivity(intent);
                systemDialogOk = true;
            } catch (Exception e) {
                Log.w(TAG, "system dialog failed, fallback to list", e);
            }
        }

        // 3) 仅在系统弹窗失败时才走兑底：电池优化列表 -> 应用详情
        if (!systemDialogOk) {
            if (!openBatteryOptimizationSettings(activity)) {
                openAppSettings(activity);
            }
        }
        return false;
    }

    private static boolean tryStartActivity(Activity activity, Intent intent) {
        try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            PackageManager pm = activity.getPackageManager();
            if (pm.resolveActivity(intent, 0) != null) {
                activity.startActivity(intent);
                return true;
            }
        } catch (SecurityException ignored) {
        } catch (Exception e) {
            Log.w(TAG, "tryStartActivity failed: " + intent, e);
        }
        return false;
    }

    private static String getAppLabel(Context ctx) {
        try {
            PackageManager pm = ctx.getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(ctx.getPackageName(), 0)).toString();
        } catch (Exception e) {
            return "App";
        }
    }
}
