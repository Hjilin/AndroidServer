package com.zm920.androidserver;

import android.content.Intent;
import androidx.annotation.NonNull;
import android.content.pm.PackageManager;
import android.content.SharedPreferences;
import android.util.Log;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Bundle;
import android.view.View;
import android.content.res.ColorStateList;
import java.net.InetSocketAddress;
import java.net.Socket;
import com.zm920.androidserver.ui.widget.ToastUtil;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import com.zm920.androidserver.ui.DashboardFragment;
import com.zm920.androidserver.ui.SitesFragment;
import com.zm920.androidserver.ui.DownloadFragment;
import com.zm920.androidserver.ui.SettingsFragment;
import com.zm920.androidserver.util.PermissionUtil;
import com.zm920.androidserver.util.SiteScanner;
import com.zm920.androidserver.util.BatteryOptimizationHelper;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.appbar.MaterialToolbar;
import com.zm920.androidserver.service.ServerService;
import com.zm920.androidserver.service.MefrpService;
import com.zm920.androidserver.mefrp.MefrpConfig;

public class MainActivity extends AppCompatActivity {

    public static com.zm920.androidserver.server.WebServer webServerRef;
    public static String currentTheme = "slate";

    private Fragment dashboardFragment;
    private Fragment sitesFragment;
    private Fragment downloadFragment;
    private Fragment settingsFragment;
    private Fragment activeFragment;
    private MaterialToolbar toolbar;
    private BottomNavigationView bottomNav;
    private long backPressedTime;
    private static final String KEY_PRIVACY_AGREED = "privacy_agreed";
    private int pendingPermissionStep = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        toolbar = findViewById(R.id.toolbar);
        bottomNav = findViewById(R.id.bottom_navigation);
        // 读取主题偏好，应用颜色
        SharedPreferences prefs = getSharedPreferences("server_settings", 0);
        currentTheme = prefs.getString("theme_key", "slate");
        applyTheme(currentTheme);
        // 手动管理 Fragment：只创建当前可见的 dashboard，其他延迟到首次切换 Tab 时创建
        FragmentManager fm = getSupportFragmentManager();
        dashboardFragment = new DashboardFragment();
        fm.beginTransaction()
                .add(R.id.nav_host_fragment, dashboardFragment, "dashboard")
                .commit();
        activeFragment = dashboardFragment;

        bottomNav.setOnItemSelectedListener(item -> {
            int itemId = item.getItemId();
            Fragment target = null;
            if (itemId == R.id.dashboardFragment) target = dashboardFragment;
            else if (itemId == R.id.sitesFragment) {
                if (sitesFragment == null) {
                    sitesFragment = new SitesFragment();
                    fm.beginTransaction().add(R.id.nav_host_fragment, sitesFragment, "sites").hide(sitesFragment).commitNow();
                }
                target = sitesFragment;
            }
            else if (itemId == R.id.downloadFragment) {
                if (downloadFragment == null) {
                    downloadFragment = new DownloadFragment();
                    fm.beginTransaction().add(R.id.nav_host_fragment, downloadFragment, "download").hide(downloadFragment).commitNow();
                }
                target = downloadFragment;
            }
            else if (itemId == R.id.settingsFragment) {
                if (settingsFragment == null) {
                    settingsFragment = new SettingsFragment();
                    fm.beginTransaction().add(R.id.nav_host_fragment, settingsFragment, "settings").hide(settingsFragment).commitNow();
                }
                target = settingsFragment;
            }

            if (target != null && target != activeFragment) {
                while (fm.getBackStackEntryCount() > 0) {
                    fm.popBackStackImmediate();
                }
                animateFragmentSwitch(fm, activeFragment, target);
                activeFragment = target;
                // 切换 Tab 后将目标 Fragment 的 ScrollView 滚到顶部
                scrollToTop(target);
            }
            return true;
        });

        // 返回键
        getOnBackPressedDispatcher().addCallback(this, new androidx.activity.OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                FragmentManager fmLocal = getSupportFragmentManager();
                if (fmLocal.getBackStackEntryCount() > 0) {
                    fmLocal.popBackStack();
                    return;
                }
                // 任意窗口按返回键都提示退出，不切回仪表盘
                long currentTime = System.currentTimeMillis();
                if (currentTime - backPressedTime < 2000) {
                    finishAffinity();
                    System.exit(0);
                } else {
                    backPressedTime = currentTime;
                    ToastUtil.showShort(MainActivity.this, "再按一次退出软件");
                }
            }
        });

        if (prefs.getBoolean(KEY_PRIVACY_AGREED, false)) {
            startAppAfterPrivacyConsent(prefs);
        } else {
            showPrivacyConsentDialog(prefs);
        }
    }

    private void showPrivacyConsentDialog(SharedPreferences prefs) {
        String msg = "欢迎使用简云服务器管理。为保障您的知情权，请先阅读并同意以下隐私说明：\n\n"
                + "1. 本应用用于在本机运行 Web、数据库、FTP、WebSocket 和内网穿透服务。\n"
                + "2. 本应用会根据用户配置读取和管理服务器目录中的网站文件、数据库文件和组件文件。\n"
                + "3. 本应用会读取网络状态、本机局域网 IP、公网 IP 和端口状态，用于展示访问地址和检测服务状态。\n"
                + "4. 启用内网穿透后，用户配置的本地端口流量可能通过第三方穿透服务转发。\n"
                + "5. 本应用集成友盟统计 SDK，用于运行统计、崩溃分析和基础设备环境分析，可能涉及设备型号、系统版本、网络状态、应用版本、崩溃日志等信息；仅在您同意后初始化。\n"
                + "6. 本应用不会读取通讯录、短信、通话记录、定位信息，不会主动上传用户网站文件、数据库文件或服务器配置内容。\n\n"
                + "不同意将无法继续使用应用。";

        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("隐私说明")
                .setMessage(msg)
                .setPositiveButton("同意并继续", (d, w) -> {
                    prefs.edit().putBoolean(KEY_PRIVACY_AGREED, true).apply();
                    startAppAfterPrivacyConsent(prefs);
                })
                .setNegativeButton("不同意", (d, w) -> finishAffinity())
                .setCancelable(false)
                .show();
    }

    private void startAppAfterPrivacyConsent(SharedPreferences prefs) {
        // 延后扫描 data_dir，避免启动首屏与外部存储 I/O 抢资源导致黑屏/卡顿
        new Handler(Looper.getMainLooper()).postDelayed(() ->
                new Thread(() -> SiteScanner.scanAndRegister(getApplicationContext(), prefs)).start(), 5000);
        // 用户同意隐私说明后再启动前台 Service 和申请权限。
        startForegroundService(new Intent(this, ServerService.class));
        scheduleMefrpAutoStart();
        checkPermissionsAtStartup(prefs);
    }

    private void scheduleMefrpAutoStart() {
        scheduleMefrpAutoStartRetry(15000);
    }

    private void scheduleMefrpAutoStartRetry(long delayMs) {
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (isFinishing() || isDestroyed()) return;
            if (!MefrpConfig.isAutoStart(this)) return;
            if (MefrpService.isRunning()) return;
            if (!MefrpConfig.hasRequiredConfig(this)) {
                Log.i("MainActivity", "ME Frp 自动启动已开启，但配置未填写完整");
                scheduleMefrpAutoStartRetry(15000);
                return;
            }
            if (!com.zm920.androidserver.network.NetworkUtil.isNetworkAvailable(this)) {
                Log.i("MainActivity", "ME Frp 自动启动等待网络可用");
                scheduleMefrpAutoStartRetry(15000);
                return;
            }
            int localPort = MefrpConfig.getLocalPort(this);
            new Thread(() -> {
                boolean portOpen = isLocalPortOpen(localPort);
                new Handler(Looper.getMainLooper()).post(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    if (!MefrpConfig.isAutoStart(this) || MefrpService.isRunning()) return;
                    if (!portOpen) {
                        Log.i("MainActivity", "ME Frp 自动启动等待本地端口 " + localPort + " 打开");
                        scheduleMefrpAutoStartRetry(15000);
                        return;
                    }
                    // 端口已打开，额外等 10 秒让本地服务稳定后再启动 ME Frp
                    Log.i("MainActivity", "ME Frp 本地端口已打开，10 秒后启动");
                    new Handler(Looper.getMainLooper()).postDelayed(() -> {
                        if (isFinishing() || isDestroyed()) return;
                        if (!MefrpConfig.isAutoStart(this) || MefrpService.isRunning()) return;
                        startMefrpService();
                    }, 10000);
                });
            }, "MefrpAutoStart-check").start();
        }, delayMs);
    }

    private boolean isLocalPortOpen(int port) {
        if (port <= 0 || port > 65535) return false;
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 1200);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void startMefrpService() {
        Intent i = new Intent(this, MefrpService.class);
        i.setAction(MefrpService.ACTION_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(i);
        } else {
            startService(i);
        }
    }
    @Override
    protected void onResume() {
        super.onResume();
        if (pendingPermissionStep >= 0) {
            int nextStep = pendingPermissionStep + 1;
            pendingPermissionStep = -1;
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                () -> { if (!isFinishing()) requestNextPermission(nextStep); },
                300);
        }
    }

    private int getNextStep() {
        int s = 0;
        if (PermissionUtil.hasStoragePermission(this)) s++;
        if (!needsBatteryOptimization()) s++;
        if (!needsNotificationPermission()) s++;
        return s;
    }

    private void checkPermissionsAtStartup(SharedPreferences prefs) {
        if (PermissionUtil.hasStoragePermission(this) && !needsBatteryOptimization() && !needsNotificationPermission()) {
            return;
        }
        // 只有第一次启动才弹
        if (!prefs.getBoolean("perm_flow_shown", false)) {
            prefs.edit().putBoolean("perm_flow_shown", true).apply();
            showPermissionGuide();
        }
    }

    private boolean needsBatteryOptimization() {
        return !BatteryOptimizationHelper.isIgnoringBatteryOptimizations(this);
    }

    private boolean needsNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            return checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED;
        }
        return false;
    }

    private void showPermissionGuide() {
        boolean hasStorage = PermissionUtil.hasStoragePermission(this);
        boolean hasBattery = !needsBatteryOptimization();
        boolean hasNotification = !needsNotificationPermission();
        if (hasStorage && hasBattery && hasNotification) return;

        StringBuilder msg = new StringBuilder();
        msg.append("简云需要在后台持续运行服务器服务，需要您授权以下权限：\n\n");
        if (!hasStorage) {
            msg.append("📁 文件管理权限\n用于读取网站文件、数据库文件，管理服务器数据。\n")
                .append("  合规说明：仅访问 App 指定目录，不上传不收集任何个人信息。\n\n");
        }
        if (!hasBattery) {
            msg.append("🔋 电池白名单（省电优化忽略）\n防止系统在后台杀死服务器进程，保证网站持续在线。\n")
                .append("  合规说明：仅用于保持服务运行，不影响其他应用耗电。\n\n");
        }
        if (!hasNotification) {
            msg.append("🔔 通知栏权限\n用于显示服务器运行状态，方便您随时查看。\n")
                .append("  合规说明：仅显示运行状态通知，无广告推送。\n\n");
        }
        msg.append("点击「开始授权」将逐一引导您完成申请。");

        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("权限申请")
                .setMessage(msg.toString())
                .setPositiveButton("开始授权", (d, w) -> requestNextPermission(getNextStep()))
                .setNegativeButton("跳过", null)
                .setCancelable(false)
                .show();
    }

    private void requestNextPermission(int step) {
        if (isFinishing() || isDestroyed()) return;
        switch (step) {
            case 0:
                new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                        .setTitle("① 文件管理权限")
                        .setMessage("用于读取和管理服务器文件（网站文件、数据库等）。\n\n合规说明：仅访问 App 数据目录，不收集个人信息。")
                        .setPositiveButton("去授权", (d, w) -> {
                            pendingPermissionStep = step;
                            PermissionUtil.requestStoragePermission(this, 1001);
                        })
                        .setNegativeButton("跳过", (d, w) -> requestNextPermission(step + 1))
                        .setCancelable(false)
                        .show();
                break;
            case 1:
                new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                        .setTitle("② 电池白名单")
                        .setMessage("防止系统在后台杀死服务器进程，保证服务持续运行。\n\n合规说明：仅用于保持服务运行。")
                        .setPositiveButton("去授权", (d, w) -> {
                            pendingPermissionStep = step;
                            requestBatteryOptimizationWhitelist();
                        })
                        .setNegativeButton("跳过", (d, w) -> requestNextPermission(step + 1))
                        .setCancelable(false)
                        .show();
                break;
            case 2:
                new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                        .setTitle("③ 通知栏权限")
                        .setMessage("用于显示服务器运行状态通知，方便您随时查看。\n\n合规说明：仅显示运行状态，无广告推送。")
                        .setPositiveButton("去授权", (d, w) -> {
                            requestNotificationPermission();
                        })
                        .setNegativeButton("跳过", null)
                        .setCancelable(false)
                        .show();
                break;
            default:
                ToastUtil.showShort(this, "所有权限已授权，可以开始使用了");
        }
    }

    public void applyTheme(String themeKey) {
        int primaryColor;
        int statusBarColor;

        switch (themeKey) {
            case "green":
                primaryColor = ContextCompat.getColor(this, R.color.theme_green_primary);
                statusBarColor = ContextCompat.getColor(this, R.color.theme_green_status_bar);
                break;
            case "purple":
                primaryColor = ContextCompat.getColor(this, R.color.theme_purple_primary);
                statusBarColor = ContextCompat.getColor(this, R.color.theme_purple_status_bar);
                break;
            case "orange":
                primaryColor = ContextCompat.getColor(this, R.color.theme_orange_primary);
                statusBarColor = ContextCompat.getColor(this, R.color.theme_orange_status_bar);
                break;
            case "blue":
                primaryColor = ContextCompat.getColor(this, R.color.blue_primary);
                statusBarColor = ContextCompat.getColor(this, R.color.theme_blue_status_bar);
                break;
            case "red":
                primaryColor = ContextCompat.getColor(this, R.color.theme_red_primary);
                statusBarColor = ContextCompat.getColor(this, R.color.theme_red_status_bar);
                break;
            case "cyan":
                primaryColor = ContextCompat.getColor(this, R.color.theme_cyan_primary);
                statusBarColor = ContextCompat.getColor(this, R.color.theme_cyan_status_bar);
                break;
            case "pink":
                primaryColor = ContextCompat.getColor(this, R.color.theme_pink_primary);
                statusBarColor = ContextCompat.getColor(this, R.color.theme_pink_status_bar);
                break;
            case "indigo":
                primaryColor = ContextCompat.getColor(this, R.color.theme_indigo_primary);
                statusBarColor = ContextCompat.getColor(this, R.color.theme_indigo_status_bar);
                break;
            case "brown":
                primaryColor = ContextCompat.getColor(this, R.color.theme_brown_primary);
                statusBarColor = ContextCompat.getColor(this, R.color.theme_brown_status_bar);
                break;
            default:
                primaryColor = ContextCompat.getColor(this, R.color.theme_slate_primary);
                statusBarColor = ContextCompat.getColor(this, R.color.theme_slate_status_bar);
                break;
        }

        getWindow().setStatusBarColor(statusBarColor);
        WindowInsetsControllerCompat controller = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        if (controller != null) {
            controller.setAppearanceLightStatusBars(false);
        }
        if (toolbar != null) {
            toolbar.setBackgroundColor(primaryColor);
        }
        if (bottomNav != null) {
            int activeColor = primaryColor;
            int inactiveColor = ContextCompat.getColor(this, R.color.text_secondary);
            ColorStateList csl = new ColorStateList(
                new int[][]{
                    new int[]{android.R.attr.state_checked},
                    new int[]{-android.R.attr.state_checked}
                },
                new int[]{activeColor, inactiveColor}
            );
            bottomNav.setItemIconTintList(csl);
            bottomNav.setItemTextColor(csl);
        }
        currentTheme = themeKey;
    }

    public void requestNotificationPermission() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 1002);
                return;
            }
            Intent intent = new Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, getPackageName());
            startActivity(intent);
        } catch (Exception e) {
            Intent intent = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(android.net.Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        }
    }

    public void requestBatteryOptimizationWhitelist() {
        // 已加入白名单时直接提示
        if (BatteryOptimizationHelper.isIgnoringBatteryOptimizations(this)) {
            ToastUtil.showShort(this, "已加入电池白名单");
            return;
        }
        // 一站式流程：系统弹窗 -> 电池优化列表 -> 应用详情
        BatteryOptimizationHelper.ensureBatteryWhitelist(this);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 1002 && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            stopService(new Intent(this, ServerService.class));
            startForegroundService(new Intent(this, ServerService.class));
        }
    }

    public void doRequestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (android.os.Environment.isExternalStorageManager()) {
                ToastUtil.showShort(this, "文件管理权限已授权");
                return;
            }
            try {
                Intent intent = new Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                intent.setData(android.net.Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            } catch (Exception e) {
                Intent intent = new Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                startActivity(intent);
            }
        } else {
            String[] perms = {
                android.Manifest.permission.READ_EXTERNAL_STORAGE,
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE
            };
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                boolean needRequest = false;
                for (String p : perms) {
                    if (checkSelfPermission(p) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        needRequest = true;
                        break;
                    }
                }
                if (needRequest) {
                    requestPermissions(perms, 1001);
                } else {
                    ToastUtil.showShort(this, "文件管理权限已授权");
                }
            }
        }
    }

    /** 供 Fragment 调用来切换 Tab */
    public void switchToTab(int menuItemId) {
        if (bottomNav != null) {
            bottomNav.setSelectedItemId(menuItemId);
        }
    }

    public DashboardFragment getDashboardFragment() {
        return (DashboardFragment) dashboardFragment;
    }

    public SitesFragment getSitesFragment() {
        return (SitesFragment) sitesFragment;
    }

    public DownloadFragment getDownloadFragment() {
        return (DownloadFragment) downloadFragment;
    }

    public SettingsFragment getSettingsFragment() {
        return (SettingsFragment) settingsFragment;
    }

    private void animateFragmentSwitch(FragmentManager fm, Fragment from, Fragment to) {
        View fromView = from.getView();
        View toView = to.getView();

        fm.beginTransaction()
          .setReorderingAllowed(true)
          .show(to)
          .commitNowAllowingStateLoss();

        if (fromView != null && toView != null) {
            // 硬件层渲染：把视图提前绘制到 GPU 纹理，动画零掉帧
            toView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
            toView.setAlpha(0f);
            toView.setTranslationY(dpToPx(12));

            toView.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(240)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(2.0f))
                .withEndAction(() -> toView.setLayerType(View.LAYER_TYPE_NONE, null))
                .start();
        } else {
            if (fromView != null) fromView.setAlpha(0f);
            if (toView != null) toView.setAlpha(1f);
        }

        if (from != null) {
            fm.beginTransaction()
              .setReorderingAllowed(true)
              .hide(from)
              .commitAllowingStateLoss();
        }
    }


    /** 将 Fragment 的 ScrollView 滚动到顶部 */
    private void scrollToTop(Fragment f) {
        if (f == null || f.getView() == null) return;
        int[] scrollIds = {R.id.scroll_dashboard, R.id.scroll_download, R.id.scroll_settings};
        for (int id : scrollIds) {
            android.widget.ScrollView sv = f.getView().findViewById(id);
            if (sv != null) {
                sv.smoothScrollTo(0, 0);
                break;
            }
        }
    }
    private float dpToPx(float dp) {
        return dp * getResources().getDisplayMetrics().density;
    }
}