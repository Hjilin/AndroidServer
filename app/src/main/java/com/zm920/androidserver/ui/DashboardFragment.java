package com.zm920.androidserver.ui;

import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.LinearLayout;
import com.zm920.androidserver.ui.widget.ToastUtil;
import com.zm920.androidserver.util.SiteScanner;

import com.zm920.androidserver.update.UpdateChecker;
import com.zm920.androidserver.update.UpdateInstaller;
import android.graphics.Color;
import androidx.core.content.ContextCompat;
import android.graphics.drawable.GradientDrawable;

import com.google.android.material.switchmaterial.SwitchMaterial;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.zm920.androidserver.R;
import com.zm920.androidserver.config.ConfigGenerator;
import com.zm920.androidserver.model.SystemStats;
import com.zm920.androidserver.network.NetworkUtil;
import com.zm920.androidserver.server.BinaryDeployer;
import com.zm920.androidserver.server.DatabaseManager;
import com.zm920.androidserver.server.PhpManager;
import com.zm920.androidserver.server.RedisManager;
import com.zm920.androidserver.server.StartupLogger;
import com.zm920.androidserver.server.WebServer;
import com.zm920.androidserver.service.FtpServerService;
import com.zm920.androidserver.service.WsServerService;
import com.zm920.androidserver.service.ProcessManager;
import com.zm920.androidserver.server.WebdavServer;
import com.zm920.androidserver.ui.widget.CircularProgressView;

import java.io.File;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class DashboardFragment extends Fragment {

    private static final String TAG = "Dashboard";

    // 前台可见时状态刷新 5 秒，后台 6 分钟，IP 刷新 10 分钟
    private static final int REFRESH_INTERVAL = 5000;
    private static final int REFRESH_INTERVAL_BG = 360000; // 6分钟
    private static final int IP_INTERVAL = 600000;

    private CircularProgressView circularMem, circularStorage;
    private TextView tvLocalIp, tvExternalIp, tvServerStatus, tvUptime, tvHeaderGreeting;
    // 公网 IP 缓存：refreshIp 异步获取后存这里，refreshFtpStatus 同步读取
    private String cachedExternalIp = "";
    private String cachedLocalIp = "";
    private LinearLayout layoutSites;
    private TextView tvNoSites;
    private LinearLayout layoutPlugins;
    private TextView tvNoPlugins;
    private TextView tvPluginMgr;
    private TextView tvLoadValue, tvTxBytes, tvRxBytes;
    private View ivStatusDot;
    private SystemStats stats;
    private SharedPreferences prefs;
    private boolean siteScanRunning = false;

    // 各组件状态文字
    private TextView tvNginxStatus, tvPhpStatus, tvMysqlStatus, tvRedisStatus;
    private SwitchMaterial switchNginx, switchPhp, switchMysql, switchRedis;
    private View dotNginx, dotPhp, dotMysql, dotRedis;
    private boolean syncingSwitches = false;

    // WebDAV 文件共享
    private TextView tvWebdavStatus;
    private SwitchMaterial switchWebdav;
    private View dotWebdav;
    private boolean syncingWebdav = false;

    private WebServer webServer;
    private PhpManager phpManager;
    private DatabaseManager dbManager;
    private RedisManager redisManager;

    // FTP
    private View dotFtp;
    private TextView tvFtpInfo, tvFtpStatus;
    private TextView btnFtpStart, btnFtpStop, btnFtpLog;
    private BroadcastReceiver ftpStatusReceiver;

    // WebSocket
    private View dotWs;
    private TextView tvWsInfo, tvWsStatus;
    private TextView btnWsStart, btnWsStop, btnWsLog, btnWsCopy, btnWsUsers;
    private BroadcastReceiver wsLogReceiver;

    private final Handler refreshHandler = new Handler(Looper.getMainLooper());
    private boolean isVisible = true;

    // 监听网站变更自动刷新
    private final SharedPreferences.OnSharedPreferenceChangeListener prefListener = (sp, key) -> {
        if ("sites".equals(key) && isAdded()) {
            refreshHandler.post(this::refreshSites);
        }
    };

    // 状态+IP刷新每5秒
    private final Runnable refreshTask = this::refreshAll;
    // 运行时间每秒更新
    private final Handler ipHandler = new Handler(Looper.getMainLooper());
    private final Runnable ipTask = this::refreshIp;

    private static final String KEY_SERVER_START_TIME = "server_start_time";
    private static final String KEY_STARTING_NGINX = "starting_nginx";
    private static final String KEY_STARTING_PHP = "starting_php";
    private static final String KEY_STARTING_MARIADB = "starting_mariadb";
    private static final String KEY_STARTING_REDIS = "starting_redis";
    private long startTime = 0;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_dashboard, container, false);
        return v;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        try {
            prefs = requireContext().getSharedPreferences("server_settings", 0);
            prefs.registerOnSharedPreferenceChangeListener(prefListener);

            // 绑定控件（必须在主线程）
            ivStatusDot = view.findViewById(R.id.iv_status_dot);
            tvHeaderGreeting = view.findViewById(R.id.tv_header_greeting);
            tvServerStatus = view.findViewById(R.id.tv_server_status);
            tvUptime = view.findViewById(R.id.tv_uptime);
            circularMem = view.findViewById(R.id.circular_mem);
            circularStorage = view.findViewById(R.id.circular_storage);
            tvLocalIp = view.findViewById(R.id.tv_local_ip);
            tvExternalIp = view.findViewById(R.id.tv_external_ip);
            layoutSites = view.findViewById(R.id.layout_dashboard_sites);
            tvNoSites = view.findViewById(R.id.tv_no_sites);
            layoutPlugins = view.findViewById(R.id.layout_dashboard_plugins);
            tvNoPlugins = view.findViewById(R.id.tv_no_plugins);
            tvPluginMgr = view.findViewById(R.id.tv_plugin_mgr);
            if (tvPluginMgr != null) {
                tvPluginMgr.setOnClickListener(v -> PluginManageSheet.show(this));
            }
            tvLoadValue = view.findViewById(R.id.tv_load_value);
            tvTxBytes = view.findViewById(R.id.tv_tx_bytes);
            tvRxBytes = view.findViewById(R.id.tv_rx_bytes);

            // 组件卡片
            dotNginx = view.findViewById(R.id.dot_nginx);
            dotPhp = view.findViewById(R.id.dot_php);
            dotMysql = view.findViewById(R.id.dot_mysql);
            dotRedis = view.findViewById(R.id.dot_redis);
            tvNginxStatus = view.findViewById(R.id.tv_nginx_status);
            tvPhpStatus = view.findViewById(R.id.tv_php_status);
            tvMysqlStatus = view.findViewById(R.id.tv_mysql_status);
            tvRedisStatus = view.findViewById(R.id.tv_redis_status);
            switchNginx = view.findViewById(R.id.switch_nginx);
            switchPhp = view.findViewById(R.id.switch_php);
            switchMysql = view.findViewById(R.id.switch_mysql);
            switchRedis = view.findViewById(R.id.switch_redis);

            bindServiceSwitch(switchNginx, "Nginx", "auto_start_nginx");
            bindServiceSwitch(switchPhp, "PHP", "auto_start_php");
            bindServiceSwitch(switchMysql, "MariaDB", "auto_start_mariadb");
            bindServiceSwitch(switchRedis, "Redis", "auto_start_redis");

            // WebDAV 文件共享控件绑定
            dotWebdav = view.findViewById(R.id.dot_webdav);
            tvWebdavStatus = view.findViewById(R.id.tv_webdav_status);
            switchWebdav = view.findViewById(R.id.switch_webdav);
            view.findViewById(R.id.btn_webdav_start).setOnClickListener(v -> startWebdav());
            view.findViewById(R.id.btn_webdav_stop).setOnClickListener(v -> stopWebdav());
            view.findViewById(R.id.btn_webdav_log).setOnClickListener(v -> showWebdavLogDialog());
            view.findViewById(R.id.btn_webdav_config).setOnClickListener(v -> showWebdavConfigDialog());
            switchWebdav.setOnCheckedChangeListener((bv, checked) -> {
                if (syncingWebdav) return;
                if (checked) startWebdav(); else stopWebdav();
            });

            // FTP 控件绑定
            dotFtp = view.findViewById(R.id.dot_ftp);
            tvFtpInfo = view.findViewById(R.id.tv_ftp_info);
            tvFtpStatus = view.findViewById(R.id.tv_ftp_status);
            btnFtpStart = view.findViewById(R.id.btn_ftp_start);
            btnFtpStop = view.findViewById(R.id.btn_ftp_stop);
            btnFtpLog = view.findViewById(R.id.btn_ftp_log);
            btnFtpStart.setOnClickListener(v -> startFtpService());
            btnFtpStop.setOnClickListener(v -> stopFtpService());
            btnFtpLog.setOnClickListener(v -> showFtpLogDialog());
            refreshFtpStatus();
            applyThemeToFtpButtons();
            // WebSocket 控件绑定
            dotWs = view.findViewById(R.id.dot_ws);
            tvWsInfo = view.findViewById(R.id.tv_ws_info);
            tvWsStatus = view.findViewById(R.id.tv_ws_status);
            btnWsStart = view.findViewById(R.id.btn_ws_start);
            btnWsStop = view.findViewById(R.id.btn_ws_stop);
            btnWsLog = view.findViewById(R.id.btn_ws_log);
            btnWsCopy = view.findViewById(R.id.btn_ws_copy);
            btnWsUsers = view.findViewById(R.id.btn_ws_users);
            btnWsStart.setOnClickListener(v -> startWsService());
            btnWsStop.setOnClickListener(v -> stopWsService());
            btnWsLog.setOnClickListener(v -> showWsLogDialog());
            btnWsCopy.setOnClickListener(v -> copyWsConnectInfo());
            if (btnWsUsers != null) btnWsUsers.setOnClickListener(v -> showWsUsersDialog());
            refreshWsStatus();
            applyThemeToWsButtons();

            stats = new SystemStats();
            startTime = prefs.getLong(KEY_SERVER_START_TIME, 0L);
            cachedLocalIp = NetworkUtil.getLocalIpAddress();

            // 创建管理器对象移到后台线程，避免阻塞主线程导致 ANR
            new Thread(() -> {
                try {
                    ProcessManager pm = ProcessManager.getInstance(requireContext().getFilesDir());
                    BinaryDeployer deployer = new BinaryDeployer(requireContext(), prefs);
                    StartupLogger startupLogger = new StartupLogger(requireContext());
                    String privateDataDir = requireContext().getFilesDir().getAbsolutePath() + "/server";
                    ConfigGenerator cg = new ConfigGenerator(new File(privateDataDir + "/config"), prefs);

                    webServer = new WebServer(requireContext(), pm, cg, prefs, deployer, startupLogger);
                    com.zm920.androidserver.MainActivity.webServerRef = webServer;
                    phpManager = new PhpManager(requireContext(), pm, cg, prefs, deployer, startupLogger);
                    dbManager = new DatabaseManager(requireContext(), pm, cg, prefs, deployer, startupLogger);
                    redisManager = new RedisManager(requireContext(), pm, cg, prefs, deployer, startupLogger);

                    // 创建完成后在主线程中刷新 UI
                    if (getActivity() != null) {
                        getActivity().runOnUiThread(() -> {
                            if (!isAdded()) return;
                            if (savedInstanceState == null) {
                                prepareAutoStartStates();
                            }
                            refreshAll();
                            applyThemeToHeader();
                            applyThemeToCircular();
                            applyThemeToSwitches();
                            refreshIp();
                            refreshSites();
                            // 没有站点且无 phpMyAdmin 时自动关闭 nginx
                            boolean nginxRunningCheck = webServer != null && webServer.isRunning();
                            if (nginxRunningCheck && !webServer.hasSites() && !hasPhpMyAdmin()) {
                                Log.i(TAG, "没有站点和 phpMyAdmin，自动关闭 nginx");
                                prefs.edit().putBoolean("auto_start_nginx", false)
                                    .putBoolean(KEY_STARTING_NGINX, false).apply();
                                new Thread(() -> { try { webServer.stop(); } catch (Exception ignored) {} }).start();
                            }
                            if (savedInstanceState == null) {
                                runAutoStartServices();
                            }
                            scheduleNextRefresh();
                            ipHandler.postDelayed(ipTask, IP_INTERVAL);
                        });
                    }
                } catch (Exception e) {
                    Log.e(TAG, "后台初始化失败", e);
                }
            }).start();

            // 启动时清理旧 APK 缓存
            new Thread(() -> {
                try {
                    java.io.File oldApk = new java.io.File(requireContext().getFilesDir(), "updates/update.apk");
                    if (oldApk.exists()) oldApk.delete();
                    prefs.edit().remove("pending_apk_update").apply();
                } catch (Exception ignored) {}
            }).start();
            // 启动时自动检查更新
            UpdateChecker.check(requireContext(), new UpdateChecker.Callback() {
                @Override
                public void onResult(UpdateChecker.UpdateInfo info) {
                    int localCode = UpdateChecker.getLocalVersionCode(requireContext());
                    if (info.versionCode > localCode) {
                        new android.os.Handler(android.os.Looper.getMainLooper()).post(() ->
                                showAutoUpdateDialog(info));
                    }
                }
                @Override
                public void onError(String msg) {
                    if (isAdded() && getContext() != null) {
                        new android.os.Handler(android.os.Looper.getMainLooper()).post(() ->
                                ToastUtil.showShort(getContext(), "检查更新失败: " + msg));
                    }
                }
            });

        } catch (Exception e) {
            Log.e(TAG, "初始化失败", e);
            ToastUtil.showShort(getContext(), "初始化错误: " + e.getMessage());
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        isVisible = true;
        scheduleNextRefresh();
        // 从其他页面返回时，检查是否有已下载的 APK 等待安装
        if (UpdateInstaller.tryInstallPending(requireContext())) {
            ToastUtil.showShort(requireContext(), "权限已获取，开始安装...");
        }
        if (isHidden() || getView() == null) return;
        refreshAll();
        refreshFtpStatus();
        renderPlugins();
        applyThemeToFtpButtons();
        refreshWsStatus();
        applyThemeToWsButtons();
    }

    @Override
    public void onPause() {
        super.onPause();
        isVisible = false;
        scheduleNextRefresh();
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        isVisible = !hidden;
        if (isVisible && isAdded()) {
            refreshAll();
            refreshFtpStatus();
            applyThemeToFtpButtons();
            refreshWsStatus();
            applyThemeToWsButtons();
        } else {
            scheduleNextRefresh();
        }
    }
    public void onDestroyView() {
        prefs.unregisterOnSharedPreferenceChangeListener(prefListener);
        super.onDestroyView();
        refreshHandler.removeCallbacks(refreshTask);
        ipHandler.removeCallbacks(ipTask);
        if (ftpStatusReceiver != null) {
            try { requireContext().unregisterReceiver(ftpStatusReceiver); } catch (Exception ignored) {}
            ftpStatusReceiver = null;
        }
        if (wsLogReceiver != null) {
            try { requireContext().unregisterReceiver(wsLogReceiver); } catch (Exception ignored) {}
            wsLogReceiver = null;
        }
    }

    private void bindServiceSwitch(SwitchMaterial sw, String component, String key) {
        sw.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (syncingSwitches) return;
            if (isChecked) {
                boolean installed = prefs.contains("active_" + component.toLowerCase());
                if (!installed) {
                    ToastUtil.showLong(getContext(), "请先在「组件」页下载");
                    sw.setChecked(false);
                    return;
                }
            }
            prefs.edit().putBoolean(key, isChecked).apply();
            toggleServiceAsync(component, isChecked);
        });
    }

    private void prepareAutoStartStates() {
        SharedPreferences.Editor editor = prefs.edit();
        editor.putBoolean(KEY_STARTING_REDIS,
                prefs.getBoolean("auto_start_redis", false) && redisManager != null && !redisManager.isRunning());
        editor.putBoolean(KEY_STARTING_MARIADB,
                prefs.getBoolean("auto_start_mariadb", false) && dbManager != null && !dbManager.isRunning());
        editor.putBoolean(KEY_STARTING_PHP,
                prefs.getBoolean("auto_start_php", false) && phpManager != null && !phpManager.isRunning());
        editor.putBoolean(KEY_STARTING_NGINX,
                prefs.getBoolean("auto_start_nginx", false) && webServer != null && !webServer.isRunning() && (webServer.hasSites() || hasPhpMyAdmin()));
        editor.apply();
    }

    private void runAutoStartServices() {
        new Thread(() -> {
            // 逐个启动已打开开关的组件，单个失败不影响后续，间隔 150ms
            // start() 方法内部会自行检查能否启动（有无站点等）
            tryStartOne("Redis",
                    prefs.getBoolean("auto_start_redis", false) && prefs.contains("active_redis") && redisManager != null && !redisManager.isRunning(),
                    () -> redisManager.start(), KEY_STARTING_REDIS);
            sleepMs(150);
            tryStartOne("MariaDB",
                    prefs.getBoolean("auto_start_mariadb", false) && prefs.contains("active_mariadb") && dbManager != null && !dbManager.isRunning(),
                    () -> dbManager.start(), KEY_STARTING_MARIADB);
            sleepMs(150);
            tryStartOne("PHP",
                    prefs.getBoolean("auto_start_php", false) && prefs.contains("active_php") && phpManager != null && !phpManager.isRunning(),
                    () -> phpManager.start(), KEY_STARTING_PHP);
            sleepMs(150);
            tryStartOne("Nginx",
                    prefs.getBoolean("auto_start_nginx", false) && prefs.contains("active_nginx") && webServer != null && !webServer.isRunning() && (webServer.hasSites() || hasPhpMyAdmin()),
                    () -> webServer.start(), KEY_STARTING_NGINX);
        }).start();
    }


    private void sleepMs(long ms) {
        try { Thread.sleep(ms); } catch (Exception ignored) {}
    }

    private void tryStartOne(String name, boolean shouldStart,
                             java.util.concurrent.Callable<Boolean> starter, String startingKey) {
        if (!shouldStart) return;
        try {
            boolean ok = starter.call();
            prefs.edit().putBoolean(startingKey, false).apply();
            if (ok && startTime <= 0) markServerStartTime();
            if (isAdded()) refreshHandler.post(this::refreshUi);
            if (!ok) {
                Log.w(TAG, name + " 自动启动返回失败");
            }
        } catch (Exception e) {
            Log.e(TAG, name + " 自动启动异常", e);
            prefs.edit().putBoolean(startingKey, false).apply();
            if (isAdded()) refreshHandler.post(this::refreshUi);
        }
    }

    private String startingKey(String component) {
        switch (component) {
            case "Nginx": return KEY_STARTING_NGINX;
            case "PHP": return KEY_STARTING_PHP;
            case "MariaDB": return KEY_STARTING_MARIADB;
            case "Redis": return KEY_STARTING_REDIS;
            default: return "";
        }
    }

    private void toggleServiceAsync(String component, boolean enable) {
        if (enable) {
            boolean isInstalled = prefs.contains("active_" + component.toLowerCase());
            if (!isInstalled) {
                refreshHandler.post(() -> ToastUtil.showShort(getContext(), "请先在「组件」页下载"));
                return;
            }
        }
        if (!enable) {
            // Must set auto_start=false BEFORE refreshAll to prevent auto-restart
            prefs.edit().putBoolean(autoStartKey(component), false).apply();
        }
        prefs.edit().putBoolean(startingKey(component), enable).apply();
        refreshAll();
        new Thread(() -> {
            try {
                if (enable) {
                    boolean alreadyRunning = isAnyRunning();
                    // 启动过程中每 500ms 轮询一次状态，实时更新 UI
                    final boolean[] started = {false};
                    Thread pollThread = new Thread(() -> {
                        while (!started[0]) {
                            try { Thread.sleep(500); } catch (Exception ignored) {}
                            refreshHandler.post(this::refreshAll);
                        }
                    });
                    pollThread.setDaemon(true);
                    pollThread.start();

                    boolean ok = startOneService(component, alreadyRunning);
                    started[0] = true;
                    try { pollThread.join(200); } catch (Exception ignored) {}

                    prefs.edit().putBoolean(startingKey(component), false).apply();
                    refreshHandler.post(() -> {
                        if (!ok) {
                            prefs.edit().putBoolean(autoStartKey(component), false).apply();
                        }
                        refreshAll();
                    });
                } else {
                    stopOneService(component);
                    prefs.edit().putBoolean(startingKey(component), false).apply();
                    refreshHandler.post(this::refreshAll);
                    // 每隔 300ms 轮询端口释放，最多 3s，实时更新 UI
                    for (int i = 0; i < 10; i++) {
                        try { Thread.sleep(300); } catch (Exception ignored) {}
                        boolean stillRunning = false;
                        if ("MariaDB".equals(component) && dbManager != null) stillRunning = dbManager.isRunning();
                        else if ("Redis".equals(component) && redisManager != null) stillRunning = redisManager.isRunning();
                        else if ("Nginx".equals(component) && webServer != null) stillRunning = webServer.isRunning();
                        else if ("PHP".equals(component) && phpManager != null) stillRunning = phpManager.isRunning();
                        if (!stillRunning) break;
                        refreshHandler.post(this::refreshAll);
                    }
                    refreshHandler.post(this::refreshAll);
                }
            } catch (Exception e) {
                prefs.edit().putBoolean(startingKey(component), false).apply();
                refreshHandler.post(() -> {
                    ToastUtil.showShort(getContext(), component + " 错误: " + e.getMessage());
                    refreshAll();
                });
            }
        }).start();
    }

    private String autoStartKey(String component) {
        switch (component) {
            case "Nginx": return "auto_start_nginx";
            case "PHP": return "auto_start_php";
            case "MariaDB": return "auto_start_mariadb";
            case "Redis": return "auto_start_redis";
            default: return "";
        }
    }

    public boolean startOneService(String component, boolean alreadyRunning) throws Exception {
        switch (component) {
            case "Nginx":
                // 启动条件：网站数据存在 或 phpMyAdmin 存在（与状态显示规则一致）
                if (!webServer.hasSites() && !hasPhpMyAdmin()) {
                    throw new Exception("请先添加网站或安装 phpMyAdmin");
                }
                boolean nginxOk = webServer.start();
                if (nginxOk && !alreadyRunning) markServerStartTime();
                return nginxOk;
            case "PHP":
                boolean phpOk = phpManager.start();
                if (phpOk && !alreadyRunning) markServerStartTime();
                return phpOk;
            case "MariaDB":
                boolean dbOk = dbManager.start();
                if (dbOk && !alreadyRunning) markServerStartTime();
                return dbOk;
            case "Redis":
                boolean redisOk = redisManager.start();
                if (redisOk && !alreadyRunning) markServerStartTime();
                return redisOk;
            default:
                return false;
        }
    }

    private void stopOneService(String component) {
        switch (component) {
            case "Nginx": webServer.stop(); break;
            case "PHP": phpManager.stop(); break;
            case "MariaDB": dbManager.stop(); break;
            case "Redis": redisManager.stop(); break;
        }
    }


    private void markServerStartTime() {
        startTime = System.currentTimeMillis();
        prefs.edit().putLong(KEY_SERVER_START_TIME, startTime).apply();
    }

    private boolean isAnyRunning() {
        return (webServer != null && webServer.isRunning())
            || (phpManager != null && phpManager.isRunning())
            || (dbManager != null && dbManager.isRunning())
            || (redisManager != null && redisManager.isRunning());
    }


    private String getGreetingText() {
        java.util.Calendar calendar = java.util.Calendar.getInstance();
        int hour = calendar.get(java.util.Calendar.HOUR_OF_DAY);
        if (hour < 6) return "夜深了";
        if (hour < 12) return "上午好";
        if (hour < 18) return "下午好";
        return "晚上好";
    }

    private void showAutoUpdateDialog(UpdateChecker.UpdateInfo info) {
        if (getView() == null || !isAdded()) return;
        Context ctx = requireContext();
        int accentColor = getPrimaryColor();
        int r = Color.red(accentColor);
        int g = Color.green(accentColor);
        int b = Color.blue(accentColor);
        float density = getResources().getDisplayMetrics().density;

        final android.app.Dialog dialog = new android.app.Dialog(ctx);
        dialog.setTitle(null);

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * density);
        root.setPadding(pad, pad, pad, (int) (24 * density));

        // 标题
        TextView title = new TextView(ctx);
        title.setText("发现新版本");
        title.setTextSize(18f);
        title.setTextColor(0xFF202124);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-1, -2);
        tl.bottomMargin = (int) (4 * density);
        title.setLayoutParams(tl);
        root.addView(title);

        // 版本信息
        TextView tvVer = new TextView(ctx);
        tvVer.setText("v" + info.versionName + "  \u00b7  " + info.releaseDate + "  \u00b7  " + info.fileSize);
        tvVer.setTextSize(12f);
        tvVer.setTextColor(0xFF5F6368);
        LinearLayout.LayoutParams vl = new LinearLayout.LayoutParams(-1, -2);
        vl.bottomMargin = (int) (16 * density);
        tvVer.setLayoutParams(vl);
        root.addView(tvVer);

        // 更新内容
        if (info.changelog != null && !info.changelog.isEmpty()) {
            TextView clTitle = new TextView(ctx);
            clTitle.setText("更新内容");
            clTitle.setTextSize(13f);
            clTitle.setTextColor(0xFF202124);
            clTitle.setTypeface(null, android.graphics.Typeface.BOLD);
            LinearLayout.LayoutParams cltl = new LinearLayout.LayoutParams(-1, -2);
            cltl.bottomMargin = (int) (8 * density);
            clTitle.setLayoutParams(cltl);
            root.addView(clTitle);

            for (String item : info.changelog) {
                LinearLayout itemRow = new LinearLayout(ctx);
                itemRow.setOrientation(LinearLayout.HORIZONTAL);
                itemRow.setPadding((int) (4 * density), (int) (2 * density), 0, (int) (2 * density));

                TextView dot = new TextView(ctx);
                dot.setText("\u00b7");
                dot.setTextSize(13f);
                dot.setTextColor(0xFF5F6368);
                dot.setPadding(0, 0, (int) (8 * density), 0);
                itemRow.addView(dot);

                TextView tvItem = new TextView(ctx);
                tvItem.setText(item);
                tvItem.setTextSize(13f);
                tvItem.setTextColor(0xFF3C4043);
                tvItem.setLineSpacing(0f, 1.3f);
                itemRow.addView(tvItem);
                root.addView(itemRow);
            }
        }

        // 强制更新提示
        if (info.forceUpdate) {
            TextView forceHint = new TextView(ctx);
            forceHint.setText("此版本为强制更新，请下载后安装");
            forceHint.setTextSize(12f);
            forceHint.setTextColor(0xFFD93025);
            LinearLayout.LayoutParams fl = new LinearLayout.LayoutParams(-1, -2);
            fl.topMargin = (int) (12 * density);
            fl.bottomMargin = (int) (4 * density);
            forceHint.setLayoutParams(fl);
            root.addView(forceHint);
        }

        // 按钮行
        LinearLayout btnRow = new LinearLayout(ctx);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-1, -2);
        bl.topMargin = (int) (20 * density);
        btnRow.setLayoutParams(bl);

        if (!info.forceUpdate) {
            TextView btnLater = new TextView(ctx);
            btnLater.setText("稍后再说");
            btnLater.setTextSize(14f);
            btnLater.setTextColor(accentColor);
            btnLater.setGravity(Gravity.CENTER);
            android.graphics.drawable.GradientDrawable laterBg = new android.graphics.drawable.GradientDrawable();
            laterBg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
            laterBg.setCornerRadius((int) (10 * density));
            laterBg.setColor(Color.argb(18, r, g, b));
            laterBg.setStroke((int) (1 * density), Color.argb(80, r, g, b));
            btnLater.setBackground(laterBg);
            btnLater.setClickable(true);
            btnLater.setFocusable(true);
            btnLater.setOnClickListener(v -> dialog.dismiss());
            LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(0, (int) (44 * density), 1);
            ll.rightMargin = (int) (8 * density);
            btnLater.setLayoutParams(ll);
            btnRow.addView(btnLater);
        }

        TextView btnUpdate = new TextView(ctx);
        btnUpdate.setText("立即更新");
        btnUpdate.setTextSize(14f);
        btnUpdate.setTextColor(0xFFFFFFFF);
        btnUpdate.setGravity(Gravity.CENTER);
        android.graphics.drawable.GradientDrawable upBg = new android.graphics.drawable.GradientDrawable();
        upBg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        upBg.setCornerRadius((int) (10 * density));
        upBg.setColor(accentColor);
        btnUpdate.setBackground(upBg);
        btnUpdate.setClickable(true);
        btnUpdate.setFocusable(true);
        btnUpdate.setOnClickListener(v -> {
            dialog.dismiss();
            UpdateInstaller.downloadAndInstall(ctx, info.downloadUrl, new UpdateInstaller.InstallCallback() {
                @Override public void onSuccess() {}
                @Override public void onError(String msg) {
                    ToastUtil.showLong(ctx, "更新失败: " + msg);
                }
            });
        });

        LinearLayout.LayoutParams ul = new LinearLayout.LayoutParams(0, (int) (44 * density), 1);
        if (info.forceUpdate) {
            ul.leftMargin = 0;
        } else {
            ul.leftMargin = (int) (8 * density);
        }
        btnUpdate.setLayoutParams(ul);
        btnRow.addView(btnUpdate);

        root.addView(btnRow);
        dialog.setContentView(root);

        android.view.Window window = dialog.getWindow();
        if (window != null) {
            android.view.WindowManager.LayoutParams lp = window.getAttributes();
            lp.width = (int) (ctx.getResources().getDisplayMetrics().widthPixels * 0.88f);
            window.setAttributes(lp);
        }
        dialog.show();
    }

    private void refreshUi() {
        try {
            stats.collect();
            circularMem.setProgressAnimated(stats.getMemPercent());
            circularMem.setSubText(stats.getUsedMemStr() + " / " + stats.getTotalMemStr());
            circularStorage.setProgressAnimated(stats.getStoragePercent());
            circularStorage.setSubText(stats.getUsedStorageStr() + " / " + stats.getTotalStorageStr());
            if (tvLoadValue != null) tvLoadValue.setText(stats.getLoadStr());
            if (tvTxBytes != null) tvTxBytes.setText(stats.getTxStr());
            if (tvRxBytes != null) tvRxBytes.setText(stats.getRxStr());
            // 缓存组件运行状态，避免后续重复调用 isRunning()（每次涉及 isPortOpen + 文件读取）
            boolean nginxRunning = webServer != null && webServer.isRunning();
            boolean phpRunning = phpManager != null && phpManager.isRunning();
            boolean mysqlRunning = dbManager != null && dbManager.isRunning();
            boolean redisRunning = redisManager != null && redisManager.isRunning();

            int running = 0;
            int enabled = 0;
            if (nginxRunning) running++;
            if (phpRunning) running++;
            if (mysqlRunning) running++;
            if (redisRunning) running++;
            if (prefs.getBoolean("auto_start_nginx", false)) enabled++;
            if (prefs.getBoolean("auto_start_php", false)) enabled++;
            if (prefs.getBoolean("auto_start_mariadb", false)) enabled++;
            if (prefs.getBoolean("auto_start_redis", false)) enabled++;
            String status;
            if (running > 0) {
                status = "运行中";
            } else if (enabled > 0) {
                status = "启动中";
            } else {
                status = "未启动";
            }
            tvServerStatus.setText(status);
            if (tvHeaderGreeting != null) tvHeaderGreeting.setText(getGreetingText());
            int dotRes;
            if (running > 0) {
                dotRes = R.drawable.circle_green;
            } else if (enabled > 0) {
                dotRes = R.drawable.circle_amber;
            } else {
                dotRes = R.drawable.circle_gray;
            }
            ivStatusDot.setBackgroundResource(dotRes);
            
            // 所有组件停止时重置计时
            if (running == 0 && startTime > 0) {
                startTime = 0;
                prefs.edit().remove(KEY_SERVER_START_TIME).apply();
            }
            updateUptime();
            refreshSites();
            // 没有站点且无 phpMyAdmin 时自动关闭 nginx
            if (nginxRunning && !webServer.hasSites() && !hasPhpMyAdmin()) {
                Log.i(TAG, "REMOVED");
                prefs.edit().putBoolean("auto_start_nginx", false)
                    .putBoolean(KEY_STARTING_NGINX, false).apply();
                new Thread(() -> { try { webServer.stop(); } catch (Exception ignored) {} }).start();
            }
            syncSwitchStates();
            updateComponentStatus();
            refreshFtpStatus();
            refreshWsStatus();
        } catch (Exception e) {
            Log.e(TAG, "刷新异常", e);
        }
    }

    // ----- FTP -----

    private void startFtpService() {
        if (FtpServerService.isRunning()) {
            ToastUtil.showShort(getContext(), "FTP 已在运行");
            return;
        }
        // 检查是否配置了端口和用户
        int port = prefs.getInt("ftp_port", 2121);
        String users = prefs.getString("ftp_users", "");
        if (users == null || users.isEmpty() || "[]".equals(users)) {
            ToastUtil.showLong(getContext(), "请先在「设置」中配置 FTP 端口和用户");
            return;
        }
        try {
            // 注册状态广播接收器
            ensureFtpReceiver();
            requireContext().startForegroundService(new Intent(requireContext(), FtpServerService.class));
            ToastUtil.showShort(getContext(), "FTP 启动中...");
        } catch (Exception e) {
            ToastUtil.showShort(getContext(), "启动失败: " + e.getMessage());
        }
    }

    private void stopFtpService() {
        if (!FtpServerService.isRunning()) {
            ToastUtil.showShort(getContext(), "FTP 未运行");
            return;
        }
        FtpServerService.stopFtp(requireContext());
        ToastUtil.showShort(getContext(), "FTP 停止中...");
        refreshHandler.postDelayed(this::refreshFtpStatus, 500);
    }

    /* ----- WebSocket ----- */

    private void startWsService() {
        if (WsServerService.isRunning()) {
            ToastUtil.showShort(getContext(), "WebSocket 已在运行");
            return;
        }
        ensureWsReceiver();
        int port = WsServerService.getConfiguredPort(requireContext());
        Intent i = new Intent(requireContext(), WsServerService.class);
        i.setAction(WsServerService.ACTION_START);
        i.putExtra(WsServerService.EXTRA_PORT, port);
        try {
            requireContext().startForegroundService(i);
            ToastUtil.showShort(getContext(), "WebSocket 启动中...");
        } catch (Exception e) {
            ToastUtil.showShort(getContext(), "启动失败: " + e.getMessage());
        }
    }

    private void stopWsService() {
        if (!WsServerService.isRunning()) {
            ToastUtil.showShort(getContext(), "WebSocket 未运行");
            return;
        }
        Intent i = new Intent(requireContext(), WsServerService.class);
        i.setAction(WsServerService.ACTION_STOP);
        try { requireContext().startService(i); } catch (Exception ignored) {}
        ToastUtil.showShort(getContext(), "WebSocket 停止中...");
        refreshHandler.postDelayed(this::refreshWsStatus, 500);
    }

    private void ensureWsReceiver() {
        if (wsLogReceiver != null) return;
        wsLogReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(android.content.Context context, Intent intent) {
                if (intent == null) return;
                if (!isAdded() || getView() == null) return;
                String suggestion = intent.getStringExtra(WsServerService.EXTRA_SUGGESTION);
                if (suggestion != null && !suggestion.isEmpty()) {
                    int code = intent.getIntExtra(WsServerService.EXTRA_ERROR_CODE, 0);
                    showWsFailureHint(suggestion, code);
                }
                refreshWsStatus();
            }
        };
        androidx.core.content.ContextCompat.registerReceiver(requireContext(),
            wsLogReceiver, new android.content.IntentFilter(WsServerService.ACTION_LOG),
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    private void refreshWsStatus() {
        if (dotWs == null) return;
        if (WsServerService.isRunning()) {
            int port = WsServerService.getCurrentPort();
            int count = WsServerService.getClientCount();
            String ip = cachedLocalIp.isEmpty() ? NetworkUtil.getLocalIpAddress() : cachedLocalIp;
            String ext = cachedExternalIp == null ? "" : cachedExternalIp;
            dotWs.setBackgroundResource(R.drawable.circle_green);
            tvWsInfo.setText("运行中 · " + port);
            tvWsInfo.setTextColor(getResources().getColor(R.color.status_green, null));
            String main = "ws://" + ip + ":" + port;
            String line = count > 0
                    ? main + "  ·  " + count + " 个客户端"
                    : main;
            tvWsStatus.setText(line);
            tvWsStatus.setTextColor(getResources().getColor(R.color.text_hint, null));
        } else {
            int port = WsServerService.getConfiguredPort(requireContext());
            String err = WsServerService.getLastError();
            if (err != null && !err.isEmpty()) {
                // 上次启动失败过：红色提示，让用户去改端口
                dotWs.setBackgroundResource(R.drawable.circle_red);
                tvWsInfo.setText("启动失败");
                tvWsInfo.setTextColor(getResources().getColor(R.color.status_red, null));
                String shortErr = err.length() > 60 ? err.substring(0, 60) + "..." : err;
                tvWsStatus.setText(shortErr + "  ·  点击「设置」改端口后重试");
                tvWsStatus.setTextColor(getResources().getColor(R.color.status_red, null));
            } else {
                dotWs.setBackgroundResource(R.drawable.circle_gray);
                tvWsInfo.setText("未启动");
                tvWsInfo.setTextColor(getResources().getColor(R.color.text_secondary, null));
                tvWsStatus.setText("启动后监听 0.0.0.0:" + port + "，客户端可用 ws://手机IP:" + port + " 连接");
                tvWsStatus.setTextColor(getResources().getColor(R.color.text_hint, null));
            }
        }
    }

    /**
     * WebSocket 启动失败提示：弹一个对话框，让用户跳到设置页改端口
     */
    private void showWsFailureHint(String message, int errorCode) {
        try {
            new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                    .setTitle(errorCode == 1 ? "端口被占用" : "WebSocket 启动失败")
                    .setMessage(message)
                    .setPositiveButton("去设置修改", (d, w) -> {
                        // 用 bottomNav.setSelectedItemId 触发 NavigationUI 的 tab 切换，
                        // 这条路径会正确处理 back stack 和 launchSingleTop，
                        // 比直接 NavController.navigate 更稳。
                        try {
                            android.view.View nav = requireActivity().findViewById(R.id.bottom_navigation);
                            if (nav != null) {
                                nav.performClick();
                                ((com.google.android.material.bottomnavigation.BottomNavigationView) nav)
                                        .setSelectedItemId(R.id.settingsFragment);
                            } else {
                                androidx.navigation.fragment.NavHostFragment.findNavController(DashboardFragment.this)
                                        .navigate(R.id.settingsFragment);
                            }
                        } catch (Exception e) {
                            ToastUtil.showShort(getContext(), "无法跳转，请手动切换到设置页");
                        }
                    })
                    .setNegativeButton("关闭", null)
                    .show();
        } catch (Exception e) {
            // 弹窗失败时退化为 Toast
            ToastUtil.showLong(getContext(), message);
        }
    }

    /**
     * 用户管理弹窗：在线用户 / 已封禁 IP / 最近消息
     */
    private void showWsUsersDialog() {
        final android.app.Dialog dialog = new android.app.Dialog(requireContext());
        dialog.setTitle("WebSocket 用户管理");
        android.widget.LinearLayout root = new android.widget.LinearLayout(requireContext());
        root.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, 0);

        final int primaryColor = getPrimaryColor();
        final int r = Color.red(primaryColor);
        final int g = Color.green(primaryColor);
        final int b = Color.blue(primaryColor);
        final float density = getResources().getDisplayMetrics().density;
        final int dividerColor = Color.argb(40, r, g, b);

        // 顶部信息：当前服务状态
        final android.widget.TextView header = new android.widget.TextView(requireContext());
        boolean running = WsServerService.isRunning();
        header.setText(running
                ? "服务运行中 · 端口 " + WsServerService.getCurrentPort()
                : "服务未启动");
        header.setTextSize(12f);
        header.setTextColor(running ? 0xFF4CAF50 : 0xFF9E9E9E);
        header.setPadding(0, 0, 0, pad / 2);
        root.addView(header);

        android.widget.ScrollView scroll = new android.widget.ScrollView(requireContext());
        scroll.setBackgroundColor(0xFF1E1E1E);
        android.widget.LinearLayout.LayoutParams scrollLp = new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, (int) (420 * density));
        scroll.setLayoutParams(scrollLp);

        final android.widget.LinearLayout container = new android.widget.LinearLayout(requireContext());
        container.setOrientation(android.widget.LinearLayout.VERTICAL);
        container.setPadding(pad, pad, pad, pad);

        // 给容器一个标记：刷新时清空重建
        scroll.addView(container);
        root.addView(scroll);

        Runnable rebuild = new Runnable() {
            @Override
            public void run() {
                container.removeAllViews();
                // ----- 在线用户 -----
                addSectionTitle(container, "在线用户", dividerColor);
                java.util.List<WsServerService.ClientInfo> clients = WsServerService.getActiveClients();
                if (clients.isEmpty()) {
                    addHint(container, "当前无在线客户端");
                } else {
                    for (WsServerService.ClientInfo ci : clients) {
                        addOnlineClientRow(container, ci, primaryColor, r, g, b, density, dividerColor, dialog);
                    }
                }
                container.addView(makeDivider(dividerColor));
                // ----- 已封禁 IP -----
                addSectionTitle(container, "已封禁 IP", dividerColor);
                java.util.List<WsServerService.BannedIp> banned = WsServerService.getBannedIps();
                if (banned.isEmpty()) {
                    addHint(container, "无封禁记录");
                } else {
                    for (WsServerService.BannedIp bi : banned) {
                        addBannedRow(container, bi, primaryColor, r, g, b, density, dividerColor, dialog);
                    }
                }
                container.addView(makeDivider(dividerColor));
                // ----- 最近消息 -----
                addSectionTitle(container, "最近消息（最多 200 条）", dividerColor);
                java.util.List<WsServerService.RecentMessage> msgs = WsServerService.getRecentMessages();
                if (msgs.isEmpty()) {
                    addHint(container, "暂无消息");
                } else {
                    java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault());
                    // 倒序显示：最新在上
                    for (int i = msgs.size() - 1; i >= 0; i--) {
                        WsServerService.RecentMessage m = msgs.get(i);
                        addMessageRow(container, sdf.format(new java.util.Date(m.time)), m.ip, m.content, dividerColor);
                    }
                }
            }
        };
        rebuild.run();

        // 底部按钮：刷新 / 清空消息 / 关闭
        android.widget.LinearLayout btnRow = new android.widget.LinearLayout(requireContext());
        btnRow.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        android.widget.LinearLayout.LayoutParams btnRowLp = new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        btnRowLp.topMargin = pad;
        btnRowLp.bottomMargin = pad;
        btnRow.setLayoutParams(btnRowLp);

        android.widget.TextView refreshBtn = makeDialogButton("刷新", primaryColor, 0xFFFFFFFF, density, pad);
        android.widget.LinearLayout.LayoutParams rlp = new android.widget.LinearLayout.LayoutParams(
                0, (int) (40 * density), 1);
        rlp.rightMargin = pad / 2;
        refreshBtn.setLayoutParams(rlp);
        refreshBtn.setOnClickListener(v -> rebuild.run());
        btnRow.addView(refreshBtn);

        android.widget.TextView clearBtn = makeDialogButton("清空消息", primaryColor,
                primaryColor, density, pad);
        clearBtn.setBackground(makeStrokeBg(r, g, b, density));
        android.widget.LinearLayout.LayoutParams clp = new android.widget.LinearLayout.LayoutParams(
                0, (int) (40 * density), 1);
        clp.leftMargin = pad / 2;
        clp.rightMargin = pad / 2;
        clearBtn.setLayoutParams(clp);
        clearBtn.setOnClickListener(v -> {
            WsServerService.clearRecentMessages();
            WsServerService.clearLog(requireContext());
            rebuild.run();
            ToastUtil.showShort(getContext(), "已清空消息");
        });
        btnRow.addView(clearBtn);

        android.widget.TextView closeBtn = makeDialogButton("关闭", primaryColor,
                primaryColor, density, pad);
        closeBtn.setBackground(makeStrokeBg(r, g, b, density));
        android.widget.LinearLayout.LayoutParams dlp = new android.widget.LinearLayout.LayoutParams(
                0, (int) (40 * density), 1);
        dlp.leftMargin = pad / 2;
        closeBtn.setLayoutParams(dlp);
        closeBtn.setOnClickListener(v -> dialog.dismiss());
        btnRow.addView(closeBtn);

        root.addView(btnRow);
        dialog.setContentView(root);
        android.view.Window window = dialog.getWindow();
        if (window != null) {
            android.view.WindowManager.LayoutParams lp = window.getAttributes();
            lp.width = (int) (getResources().getDisplayMetrics().widthPixels * 0.92f);
            window.setAttributes(lp);
        }
        dialog.show();
    }

    private void addSectionTitle(android.widget.LinearLayout parent, String text, int dividerColor) {
        android.widget.TextView tv = new android.widget.TextView(requireContext());
        tv.setText(text);
        tv.setTextSize(13f);
        tv.setTextColor(0xFF80CBC4);
        tv.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = (int) (8 * getResources().getDisplayMetrics().density);
        lp.bottomMargin = (int) (4 * getResources().getDisplayMetrics().density);
        tv.setLayoutParams(lp);
        parent.addView(tv);
    }

    private void addHint(android.widget.LinearLayout parent, String text) {
        android.widget.TextView tv = new android.widget.TextView(requireContext());
        tv.setText(text);
        tv.setTextSize(12f);
        tv.setTextColor(0xFF9E9E9E);
        android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = (int) (6 * getResources().getDisplayMetrics().density);
        tv.setLayoutParams(lp);
        parent.addView(tv);
    }

    private void addOnlineClientRow(android.widget.LinearLayout parent, WsServerService.ClientInfo ci,
                                    int primaryColor, int r, int g, int b, float density, int dividerColor,
                                    final android.app.Dialog dialog) {
        android.widget.LinearLayout row = new android.widget.LinearLayout(requireContext());
        row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        android.widget.LinearLayout.LayoutParams rlp = new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.bottomMargin = (int) (6 * density);
        row.setLayoutParams(rlp);

        android.widget.LinearLayout info = new android.widget.LinearLayout(requireContext());
        info.setOrientation(android.widget.LinearLayout.VERTICAL);
        android.widget.LinearLayout.LayoutParams ilp = new android.widget.LinearLayout.LayoutParams(
                0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        info.setLayoutParams(ilp);

        // IP + 在线时长
        android.widget.TextView ipTv = new android.widget.TextView(requireContext());
        long dur = (System.currentTimeMillis() - ci.connectTime) / 1000;
        ipTv.setText(ci.ip + ":" + ci.remotePort + "   在线 " + dur + "s");
        ipTv.setTextSize(12f);
        ipTv.setTypeface(android.graphics.Typeface.MONOSPACE);
        ipTv.setTextColor(0xFFE0E0E0);
        info.addView(ipTv);

        // 消息数 + 最近消息
        android.widget.TextView msgTv = new android.widget.TextView(requireContext());
        String lastMsg = ci.lastMessage == null || ci.lastMessage.isEmpty() ? "(无消息)" : ci.lastMessage;
        if (lastMsg.length() > 60) lastMsg = lastMsg.substring(0, 60) + "...";
        msgTv.setText("消息 " + ci.messageCount + "  ·  " + lastMsg);
        msgTv.setTextSize(11f);
        msgTv.setTextColor(0xFFB0B0B0);
        info.addView(msgTv);

        row.addView(info);

        // 封禁按钮
        android.widget.TextView banBtn = new android.widget.TextView(requireContext());
        banBtn.setText("封禁");
        banBtn.setTextSize(12f);
        banBtn.setTextColor(0xFFFF6E6E);
        banBtn.setGravity(android.view.Gravity.CENTER);
        banBtn.setBackground(makeStrokeBg(255, 110, 110, density));
        android.widget.LinearLayout.LayoutParams blp = new android.widget.LinearLayout.LayoutParams(
                (int) (56 * density), (int) (32 * density));
        blp.leftMargin = (int) (8 * density);
        banBtn.setLayoutParams(blp);
        final String ip = ci.ip;
        banBtn.setOnClickListener(v -> {
            WsServerService.banIp(ip, 0L);
            ToastUtil.showShort(getContext(), "已封禁 " + ip);
            dialog.dismiss();
            showWsUsersDialog();
        });
        row.addView(banBtn);
        parent.addView(row);
    }

    private void addBannedRow(android.widget.LinearLayout parent, WsServerService.BannedIp bi,
                              int primaryColor, int r, int g, int b, float density, int dividerColor,
                              final android.app.Dialog dialog) {
        android.widget.LinearLayout row = new android.widget.LinearLayout(requireContext());
        row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        android.widget.LinearLayout.LayoutParams rlp = new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.bottomMargin = (int) (6 * density);
        row.setLayoutParams(rlp);

        android.widget.LinearLayout info = new android.widget.LinearLayout(requireContext());
        info.setOrientation(android.widget.LinearLayout.VERTICAL);
        android.widget.LinearLayout.LayoutParams ilp = new android.widget.LinearLayout.LayoutParams(
                0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        info.setLayoutParams(ilp);

        android.widget.TextView ipTv = new android.widget.TextView(requireContext());
        ipTv.setText(bi.ip);
        ipTv.setTextSize(12f);
        ipTv.setTypeface(android.graphics.Typeface.MONOSPACE);
        ipTv.setTextColor(0xFFFFAB91);
        info.addView(ipTv);

        android.widget.TextView infoTv = new android.widget.TextView(requireContext());
        String dur;
        if (bi.banUntil <= 0L) {
            dur = "永久封禁";
        } else {
            long left = bi.banUntil - System.currentTimeMillis();
            if (left <= 0) dur = "已过期";
            else dur = "剩余 " + (left / 1000) + "s";
        }
        infoTv.setText(dur);
        infoTv.setTextSize(11f);
        infoTv.setTextColor(0xFFB0B0B0);
        info.addView(infoTv);

        row.addView(info);

        android.widget.TextView unbanBtn = new android.widget.TextView(requireContext());
        unbanBtn.setText("解封");
        unbanBtn.setTextSize(12f);
        unbanBtn.setTextColor(0xFF80CBC4);
        unbanBtn.setGravity(android.view.Gravity.CENTER);
        unbanBtn.setBackground(makeStrokeBg(128, 203, 196, density));
        android.widget.LinearLayout.LayoutParams blp = new android.widget.LinearLayout.LayoutParams(
                (int) (56 * density), (int) (32 * density));
        blp.leftMargin = (int) (8 * density);
        unbanBtn.setLayoutParams(blp);
        final String ip = bi.ip;
        unbanBtn.setOnClickListener(v -> {
            WsServerService.unbanIp(ip);
            ToastUtil.showShort(getContext(), "已解封 " + ip);
            dialog.dismiss();
            showWsUsersDialog();
        });
        row.addView(unbanBtn);
        parent.addView(row);
    }

    private void addMessageRow(android.widget.LinearLayout parent, String time, String ip, String content, int dividerColor) {
        android.widget.TextView tv = new android.widget.TextView(requireContext());
        String text = "[" + time + "] [" + ip + "] " + content;
        tv.setText(text);
        tv.setTextSize(11f);
        tv.setTypeface(android.graphics.Typeface.MONOSPACE);
        tv.setTextColor(0xFFD4D4D4);
        android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = (int) (2 * getResources().getDisplayMetrics().density);
        tv.setLayoutParams(lp);
        parent.addView(tv);
    }

    private android.view.View makeDivider(int color) {
        android.view.View v = new android.view.View(requireContext());
        v.setBackgroundColor(color);
        android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, 1);
        lp.topMargin = (int) (6 * getResources().getDisplayMetrics().density);
        lp.bottomMargin = (int) (6 * getResources().getDisplayMetrics().density);
        v.setLayoutParams(lp);
        return v;
    }

    private android.graphics.drawable.GradientDrawable makeStrokeBg(int r, int g, int b, float density) {
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        bg.setCornerRadius((int) (8 * density));
        bg.setColor(Color.argb(18, r, g, b));
        bg.setStroke((int) (1 * density), Color.argb(120, r, g, b));
        return bg;
    }

    private android.widget.TextView makeDialogButton(String text, int primaryColor, int textColor, float density, int pad) {
        android.widget.TextView tv = new android.widget.TextView(requireContext());
        tv.setText(text);
        tv.setTextSize(13f);
        tv.setTextColor(textColor);
        tv.setGravity(android.view.Gravity.CENTER);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        bg.setCornerRadius(pad);
        bg.setColor(primaryColor);
        tv.setBackground(bg);
        tv.setClickable(true);
        tv.setFocusable(true);
        return tv;
    }

    private void showWsLogDialog() {
        final android.app.Dialog dialog = new android.app.Dialog(requireContext());
        dialog.setTitle("WebSocket 日志");
        android.widget.LinearLayout root = new android.widget.LinearLayout(requireContext());
        root.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, 0);

        final int primaryColor = getPrimaryColor();
        final int r = Color.red(primaryColor);
        final int g = Color.green(primaryColor);
        final int b = Color.blue(primaryColor);
        final float density = getResources().getDisplayMetrics().density;

        // 外层滚动条：内嵌一个垂直 LinearLayout，承载"最近消息"+"系统日志"两段
        android.widget.ScrollView scroll = new android.widget.ScrollView(requireContext());
        scroll.setBackgroundColor(0xFF1E1E1E);
        android.widget.LinearLayout.LayoutParams scrollLp = new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, (int) (360 * getResources().getDisplayMetrics().density));
        scroll.setLayoutParams(scrollLp);
        final android.widget.LinearLayout contentBox = new android.widget.LinearLayout(requireContext());
        contentBox.setOrientation(android.widget.LinearLayout.VERTICAL);
        contentBox.setPadding(pad, pad, pad, pad);
        // --- 最近消息段 ---
        final android.widget.TextView recentHeader = new android.widget.TextView(requireContext());
        recentHeader.setText("最近消息");
        recentHeader.setTextSize(12f);
        recentHeader.setTextColor(0xFF80CBC4);
        recentHeader.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        contentBox.addView(recentHeader);
        final android.widget.TextView recentTv = new android.widget.TextView(requireContext());
        recentTv.setTextSize(11f);
        recentTv.setTypeface(android.graphics.Typeface.MONOSPACE);
        recentTv.setTextColor(0xFFB0B0B0);
        android.widget.LinearLayout.LayoutParams rp = new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        rp.bottomMargin = pad / 2;
        recentTv.setLayoutParams(rp);
        contentBox.addView(recentTv);
        // --- 分隔 ---
        android.view.View divv = new android.view.View(requireContext());
        divv.setBackgroundColor(Color.argb(60, r, g, b));
        android.widget.LinearLayout.LayoutParams dvp = new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, 1);
        dvp.topMargin = pad / 2;
        dvp.bottomMargin = pad / 2;
        divv.setLayoutParams(dvp);
        contentBox.addView(divv);
        // --- 系统日志段 ---
        final android.widget.TextView logHeader = new android.widget.TextView(requireContext());
        logHeader.setText("系统日志");
        logHeader.setTextSize(12f);
        logHeader.setTextColor(0xFF80CBC4);
        logHeader.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        contentBox.addView(logHeader);
        final android.widget.TextView logTv = new android.widget.TextView(requireContext());
        logTv.setTextSize(11f);
        logTv.setTextColor(0xFFD4D4D4);
        logTv.setTypeface(android.graphics.Typeface.MONOSPACE);
        logTv.setPadding(0, (int) (4 * density), 0, 0);
        logTv.setText(WsServerService.readLog(requireContext()));
        contentBox.addView(logTv);
        scroll.addView(contentBox);
        root.addView(scroll);

        // 刷新回调：两段一起更新
        final Runnable refreshBoth = new Runnable() {
            @Override
            public void run() {
                java.util.List<WsServerService.RecentMessage> msgs = WsServerService.getRecentMessages();
                java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault());
                StringBuilder sb = new StringBuilder();
                if (msgs.isEmpty()) {
                    sb.append("（暂无消息）");
                } else {
                    int start = Math.max(0, msgs.size() - 50);
                    for (int i = msgs.size() - 1; i >= start; i--) {
                        WsServerService.RecentMessage m = msgs.get(i);
                        sb.append("[").append(sdf.format(new java.util.Date(m.time)))
                          .append("] [").append(m.ip).append("] ")
                          .append(m.content).append("\n");
                    }
                }
                recentTv.setText(sb.toString());
                logTv.setText(WsServerService.readLog(requireContext()));
            }
        };
        refreshBoth.run();

        android.widget.LinearLayout btnRow = new android.widget.LinearLayout(requireContext());
        btnRow.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        android.widget.LinearLayout.LayoutParams btnRowLp = new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        btnRowLp.topMargin = pad;
        btnRowLp.bottomMargin = pad;
        btnRow.setLayoutParams(btnRowLp);

        android.widget.TextView refreshBtn = new android.widget.TextView(requireContext());
        refreshBtn.setText("刷新");
        refreshBtn.setTextColor(0xFFFFFFFF);
        refreshBtn.setTextSize(13f);
        refreshBtn.setGravity(android.view.Gravity.CENTER);
        android.graphics.drawable.GradientDrawable rbg = new android.graphics.drawable.GradientDrawable();
        rbg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        rbg.setCornerRadius(pad);
        rbg.setColor(primaryColor);
        refreshBtn.setBackground(rbg);
        android.widget.LinearLayout.LayoutParams rlp = new android.widget.LinearLayout.LayoutParams(
                0, (int) (40 * getResources().getDisplayMetrics().density), 1);
        rlp.rightMargin = pad / 2;
        refreshBtn.setLayoutParams(rlp);
        refreshBtn.setOnClickListener(v -> refreshBoth.run());
        btnRow.addView(refreshBtn);

        android.widget.TextView clearBtn = new android.widget.TextView(requireContext());
        clearBtn.setText("清空");
        clearBtn.setTextColor(primaryColor);
        clearBtn.setTextSize(13f);
        clearBtn.setGravity(android.view.Gravity.CENTER);
        android.graphics.drawable.GradientDrawable cbg = new android.graphics.drawable.GradientDrawable();
        cbg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        cbg.setCornerRadius(pad);
        cbg.setColor(Color.argb(18, r, g, b));
        cbg.setStroke((int) (1 * density), Color.argb(80, r, g, b));
        clearBtn.setBackground(cbg);
        android.widget.LinearLayout.LayoutParams clp = new android.widget.LinearLayout.LayoutParams(
                0, (int) (40 * getResources().getDisplayMetrics().density), 1);
        clp.leftMargin = pad / 2;
        clp.rightMargin = pad / 2;
        clearBtn.setLayoutParams(clp);
        clearBtn.setOnClickListener(v -> {
            WsServerService.clearLog(requireContext());
            logTv.setText("");
            ToastUtil.showShort(getContext(), "日志已清空");
        });
        btnRow.addView(clearBtn);

        android.widget.TextView closeBtn = new android.widget.TextView(requireContext());
        closeBtn.setText("关闭");
        closeBtn.setTextColor(primaryColor);
        closeBtn.setTextSize(13f);
        closeBtn.setGravity(android.view.Gravity.CENTER);
        android.graphics.drawable.GradientDrawable dbg = new android.graphics.drawable.GradientDrawable();
        dbg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        dbg.setCornerRadius(pad);
        dbg.setColor(Color.argb(18, r, g, b));
        dbg.setStroke((int) (1 * density), Color.argb(80, r, g, b));
        closeBtn.setBackground(dbg);
        android.widget.LinearLayout.LayoutParams dlp = new android.widget.LinearLayout.LayoutParams(
                0, (int) (40 * getResources().getDisplayMetrics().density), 1);
        dlp.leftMargin = pad / 2;
        closeBtn.setLayoutParams(dlp);
        closeBtn.setOnClickListener(v -> dialog.dismiss());
        btnRow.addView(closeBtn);

        root.addView(btnRow);
        dialog.setContentView(root);
        android.view.Window window = dialog.getWindow();
        if (window != null) {
            android.view.WindowManager.LayoutParams wlp = window.getAttributes();
            wlp.width = (int) (getResources().getDisplayMetrics().widthPixels * 0.92f);
            wlp.height = android.view.WindowManager.LayoutParams.WRAP_CONTENT;
            window.setAttributes(wlp);
        }
        dialog.show();
    }

    private void copyWsConnectInfo() {
        try {
            String url = WsServerService.buildConnectUrl(requireContext());
            android.content.ClipboardManager cm =
                    (android.content.ClipboardManager) requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(android.content.ClipData.newPlainText("ws_url", url));
                ToastUtil.showLong(getContext(), "已复制（注意含 token，请勿公开）\n" + url);
            }
        } catch (Exception e) {
            ToastUtil.showShort(getContext(), "复制失败: " + e.getMessage());
        }
    }

        private void applyThemeToWsButtons() {
        if (btnWsStart == null || btnWsStop == null || btnWsLog == null || btnWsCopy == null) return;
        int primaryColor = getPrimaryColor();
        int r = Color.red(primaryColor);
        int g = Color.green(primaryColor);
        int b = Color.blue(primaryColor);
        float density = getResources().getDisplayMetrics().density;

        GradientDrawable startBg = new GradientDrawable();
        startBg.setShape(GradientDrawable.RECTANGLE);
        startBg.setCornerRadius(10 * density);
        startBg.setColor(primaryColor);
        btnWsStart.setBackground(startBg);
        btnWsStart.setTextColor(0xFFFFFFFF);

        GradientDrawable stopBg = new GradientDrawable();
        stopBg.setShape(GradientDrawable.RECTANGLE);
        stopBg.setCornerRadius(10 * density);
        stopBg.setColor(Color.argb(18, r, g, b));
        stopBg.setStroke((int) (1 * density), Color.argb(80, r, g, b));
        btnWsStop.setBackground(stopBg);
        btnWsStop.setTextColor(primaryColor);

        GradientDrawable logBg = new GradientDrawable();
        logBg.setShape(GradientDrawable.RECTANGLE);
        logBg.setCornerRadius(10 * density);
        logBg.setColor(Color.argb(18, r, g, b));
        logBg.setStroke((int) (1 * density), Color.argb(80, r, g, b));
        btnWsLog.setBackground(logBg);
        btnWsLog.setTextColor(primaryColor);

        btnWsCopy.setBackground(logBg);
        btnWsCopy.setTextColor(primaryColor);
    }

    private void ensureFtpReceiver() {
        if (ftpStatusReceiver != null) return;
        ftpStatusReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(android.content.Context context, Intent intent) {
                if (isAdded()) refreshHandler.post(() -> {
                    refreshFtpStatus();
                    String err = FtpServerService.getLastError();
                    if (err != null && !err.isEmpty()) {
                        ToastUtil.showShort(getContext(), "FTP: " + err);
                    }
                });
            }
        };
        androidx.core.content.ContextCompat.registerReceiver(requireContext(),
            ftpStatusReceiver, new android.content.IntentFilter("com.zm920.androidserver.FTP_STATUS"),
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    private void refreshFtpStatus() {
        if (dotFtp == null) return;
        if (FtpServerService.isRunning()) {
            int port = FtpServerService.getCurrentPort();
            boolean wan = prefs.getBoolean("ftp_bind_wan", false);
            // 关闭公网访问：只监听 127.0.0.1，访问地址显示回环 IP
            // 开启公网访问：监听 0.0.0.0，访问地址用公网 IP（外网从公网进，内网从内网进）
            String ip = wan ? cachedExternalIp : "127.0.0.1";
            if (wan && (ip == null || ip.isEmpty() || "网络受限".equals(ip))) {
                // 公网 IP 还没拿到或失败，临时用内网 IP 兜底
                ip = NetworkUtil.getLocalIpAddress();
            }
            dotFtp.setBackgroundResource(R.drawable.circle_green);
            tvFtpInfo.setText("运行中 · 端口 " + port);
            tvFtpInfo.setTextColor(getResources().getColor(R.color.status_green, null));
            String accessLine = wan
                    ? "访问地址: ftp://" + ip + ":" + port + "（内网+外网）"
                    : "访问地址: ftp://" + ip + ":" + port + "（仅本机 127.0.0.1）";
            tvFtpStatus.setText(accessLine);
            tvFtpStatus.setTextColor(getResources().getColor(R.color.text_hint, null));
        } else {
            dotFtp.setBackgroundResource(R.drawable.circle_gray);
            tvFtpInfo.setText("未启动");
            tvFtpInfo.setTextColor(getResources().getColor(R.color.text_secondary, null));
            // 未启动时也提示当前配置会监听的 IP，避免切换后不知道会监听到哪
            boolean wan = prefs.getBoolean("ftp_bind_wan", false);
            int port = prefs.getInt("ftp_port", 2121);
            String ip = wan ? cachedExternalIp : "127.0.0.1";
            if (wan && (ip == null || ip.isEmpty() || "网络受限".equals(ip))) {
                ip = NetworkUtil.getLocalIpAddress();
            }
            String tip = wan
                    ? "启动后将监听 0.0.0.0:" + port + "（内网+外网）"
                    : "启动后将监听 127.0.0.1:" + port + "（仅本机）";
            tvFtpStatus.setText(tip);
            tvFtpStatus.setTextColor(getResources().getColor(R.color.text_hint, null));
        }
    }

    private void showFtpLogDialog() {
        final android.app.Dialog dialog = new android.app.Dialog(requireContext());
        dialog.setTitle("FTP 日志");
        android.widget.LinearLayout root = new android.widget.LinearLayout(requireContext());
        root.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, 0);

        // 主题色（实时读，保证对话框内按钮颜色跟当前主题一致）
        final int primaryColor = getPrimaryColor();
        final int r = Color.red(primaryColor);
        final int g = Color.green(primaryColor);
        final int b = Color.blue(primaryColor);
        final float density = getResources().getDisplayMetrics().density;

        android.widget.ScrollView scroll = new android.widget.ScrollView(requireContext());
        scroll.setBackgroundColor(0xFF1E1E1E);
        android.widget.LinearLayout.LayoutParams scrollLp = new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, (int) (360 * getResources().getDisplayMetrics().density));
        scroll.setLayoutParams(scrollLp);
        final android.widget.TextView logTv = new android.widget.TextView(requireContext());
        logTv.setTextSize(11f);
        logTv.setTextColor(0xFFD4D4D4);
        logTv.setTypeface(android.graphics.Typeface.MONOSPACE);
        logTv.setPadding(pad, pad, pad, pad);
        logTv.setText(FtpServerService.readLog(requireContext()));
        scroll.addView(logTv);
        root.addView(scroll);

        android.widget.LinearLayout btnRow = new android.widget.LinearLayout(requireContext());
        btnRow.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        android.widget.LinearLayout.LayoutParams btnRowLp = new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        btnRowLp.topMargin = pad;
        btnRowLp.bottomMargin = pad;
        btnRow.setLayoutParams(btnRowLp);

        android.widget.TextView refreshBtn = new android.widget.TextView(requireContext());
        refreshBtn.setText("刷新");
        refreshBtn.setTextColor(0xFFFFFFFF);
        refreshBtn.setTextSize(13f);
        refreshBtn.setGravity(android.view.Gravity.CENTER);
        android.graphics.drawable.GradientDrawable rbg = new android.graphics.drawable.GradientDrawable();
        rbg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        rbg.setCornerRadius(pad);
        rbg.setColor(primaryColor);
        refreshBtn.setBackground(rbg);
        android.widget.LinearLayout.LayoutParams rlp = new android.widget.LinearLayout.LayoutParams(
                0, (int) (40 * getResources().getDisplayMetrics().density), 1);
        rlp.rightMargin = pad / 2;
        refreshBtn.setLayoutParams(rlp);
        refreshBtn.setOnClickListener(v -> logTv.setText(FtpServerService.readLog(requireContext())));
        btnRow.addView(refreshBtn);

        android.widget.TextView clearBtn = new android.widget.TextView(requireContext());
        clearBtn.setText("清空");
        clearBtn.setTextColor(primaryColor);
        clearBtn.setTextSize(13f);
        clearBtn.setGravity(android.view.Gravity.CENTER);
        android.graphics.drawable.GradientDrawable cbg = new android.graphics.drawable.GradientDrawable();
        cbg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        cbg.setCornerRadius(pad);
        cbg.setColor(Color.argb(18, r, g, b));
        cbg.setStroke((int) (1 * density), Color.argb(80, r, g, b));
        clearBtn.setBackground(cbg);
        android.widget.LinearLayout.LayoutParams clp = new android.widget.LinearLayout.LayoutParams(
                0, (int) (40 * getResources().getDisplayMetrics().density), 1);
        clp.leftMargin = pad / 2;
        clp.rightMargin = pad / 2;
        clearBtn.setLayoutParams(clp);
        clearBtn.setOnClickListener(v -> {
            FtpServerService.clearLog(requireContext());
            logTv.setText("");
            ToastUtil.showShort(getContext(), "日志已清空");
        });
        btnRow.addView(clearBtn);

        android.widget.TextView closeBtn = new android.widget.TextView(requireContext());
        closeBtn.setText("关闭");
        closeBtn.setTextColor(primaryColor);
        closeBtn.setTextSize(13f);
        closeBtn.setGravity(android.view.Gravity.CENTER);
        android.graphics.drawable.GradientDrawable dbg = new android.graphics.drawable.GradientDrawable();
        dbg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        dbg.setCornerRadius(pad);
        dbg.setColor(Color.argb(18, r, g, b));
        dbg.setStroke((int) (1 * density), Color.argb(80, r, g, b));
        closeBtn.setBackground(dbg);
        android.widget.LinearLayout.LayoutParams dlp = new android.widget.LinearLayout.LayoutParams(
                0, (int) (40 * getResources().getDisplayMetrics().density), 1);
        dlp.leftMargin = pad / 2;
        closeBtn.setLayoutParams(dlp);
        closeBtn.setOnClickListener(v -> dialog.dismiss());
        btnRow.addView(closeBtn);

        root.addView(btnRow);
        dialog.setContentView(root);
        android.view.Window window = dialog.getWindow();
        if (window != null) {
            android.view.WindowManager.LayoutParams wlp = window.getAttributes();
            wlp.width = (int) (getResources().getDisplayMetrics().widthPixels * 0.92f);
            wlp.height = android.view.WindowManager.LayoutParams.WRAP_CONTENT;
            window.setAttributes(wlp);
        }
        dialog.show();
    }

    public void refreshAll() {
        if (isVisible) {
            refreshUi();
        }
        checkAutoStart();
        scheduleNextRefresh();
    }

    private void scheduleNextRefresh() {
        refreshHandler.removeCallbacks(refreshTask);
        long delay = isVisible ? REFRESH_INTERVAL : REFRESH_INTERVAL_BG;
        refreshHandler.postDelayed(refreshTask, delay);
    }

    private void checkAutoStart() {
        if (prefs.getBoolean("auto_start_php", false) && phpManager != null && phpManager.isInstalled() && !phpManager.isRunning()) {
            new Thread(() -> { try { phpManager.start(); } catch (Exception ignored) {} }).start();
        }
        // 跳过 nginx 自动启动（添加网站后标记），避免添加网站自动拉起 nginx
        boolean skipNginx = prefs.getBoolean("skip_nginx_auto_start", false);
        if (skipNginx) {
            prefs.edit().remove("skip_nginx_auto_start").apply();
        } else if (prefs.getBoolean("auto_start_nginx", false) && webServer != null && webServer.isInstalled() && !webServer.isRunning() && (webServer.hasSites() || hasPhpMyAdmin())) {
            new Thread(() -> { try { webServer.start(); } catch (Exception ignored) {} }).start();
        }
    }

    /** 更新运行时间显示（由 refreshUi 调用，不自调度） */
    private void updateUptime() {
        if (startTime <= 0) {
            tvUptime.setText("");
            return;
        }
        boolean anyRunning = (webServer != null && webServer.isRunning())
            || (phpManager != null && phpManager.isRunning())
            || (dbManager != null && dbManager.isRunning())
            || (redisManager != null && redisManager.isRunning());

        if (!anyRunning) {
            tvUptime.setText("");
            return;
        }

        long elapsed = (System.currentTimeMillis() - startTime) / 1000;
        tvUptime.setText(formatUptime(elapsed));
    }

    private void syncSwitchStates() {
        syncingSwitches = true;
        try {
        // 开关状态仅反映 auto_start 设置
        if (switchNginx != null) {
            boolean nginxAuto = prefs.getBoolean("auto_start_nginx", false);
            if (!webServer.hasSites() && !hasPhpMyAdmin()) {
                switchNginx.setChecked(false);
            } else {
                switchNginx.setChecked(nginxAuto);
            }
        }
        if (switchPhp != null) {
            boolean val = prefs.getBoolean("auto_start_php", false);
            if (val != switchPhp.isChecked()) switchPhp.setChecked(val);
        }
        if (switchMysql != null) {
            boolean val = prefs.getBoolean("auto_start_mariadb", false);
            if (val != switchMysql.isChecked()) switchMysql.setChecked(val);
        }
        if (switchRedis != null) {
            boolean val = prefs.getBoolean("auto_start_redis", false);
            if (val != switchRedis.isChecked()) switchRedis.setChecked(val);
        }
        syncWebdavSwitch();
        } finally {
            syncingSwitches = false;
        }
    }

    // ===== WebDAV 文件共享 =====
    private int webdavPort() { return prefs.getInt("webdav_port", 5309); }
    private String webdavRoot() {
        String r = prefs.getString("webdav_root", "");
        if (r == null || r.isEmpty()) {
            r = new File(requireContext().getFilesDir(), "wwwroot/www").getAbsolutePath();
        }
        return r;
    }
    private String webdavPassword() {
        String p = prefs.getString("webdav_password", "");
        return p == null ? "" : p;
    }
    private String webdavUrl() {
        String ip = com.zm920.androidserver.network.NetworkUtil.getLocalIpAddress();
        return "http://" + (ip == null ? "127.0.0.1" : ip) + ":" + webdavPort();
    }

    private void syncWebdavSwitch() {
        if (switchWebdav == null) return;
        boolean running = WebdavServer.isRunning();
        syncingWebdav = true;
        try {
            if (switchWebdav.isChecked() != running) switchWebdav.setChecked(running);
        } finally {
            syncingWebdav = false;
        }
        if (dotWebdav != null)
            dotWebdav.setBackgroundResource(running ? R.drawable.circle_green : R.drawable.circle_gray);
        if (tvWebdavStatus != null) {
            if (running) {
                tvWebdavStatus.setText("运行中 · " + webdavUrl() + " · 根目录 " + webdavRoot());
            } else {
                tvWebdavStatus.setText("未启动 · 端口 " + webdavPort() + " · 密码在设置页配置");
            }
        }
    }

    private void startWebdav() {
        tvWebdavStatus.setText("正在启动…");
        new Thread(() -> {
            boolean ok = WebdavServer.start(requireContext(), webdavPort(), webdavRoot(), webdavPassword());
            if (isAdded()) refreshHandler.post(() -> {
                if (!ok) ToastUtil.showShort(getContext(), "WebDAV 启动失败，见日志");
                syncWebdavSwitch();
            });
        }).start();
    }

    private void stopWebdav() {
        new Thread(() -> {
            WebdavServer.stop();
            if (isAdded()) refreshHandler.post(this::syncWebdavSwitch);
        }).start();
    }

    private void showWebdavConfigDialog() {
        final android.app.Dialog dialog = new android.app.Dialog(requireContext());
        dialog.setTitle("WebDAV 配置");
        android.widget.LinearLayout root = new android.widget.LinearLayout(requireContext());
        root.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, 0);
        float density = getResources().getDisplayMetrics().density;

        android.widget.TextView hint = new android.widget.TextView(requireContext());
        hint.setText("用电脑浏览器或文件管理器挂载 WebDAV，即可访问手机文件（类似网盘）。");
        hint.setTextSize(12f);
        hint.setTextColor(getResources().getColor(R.color.text_secondary, null));
        root.addView(hint);

        // 端口
        android.widget.TextView portLabel = new android.widget.TextView(requireContext());
        portLabel.setText("端口");
        portLabel.setTextSize(14f);
        portLabel.setTextColor(getResources().getColor(R.color.text_primary, null));
        portLabel.setPadding(0, pad, 0, 4);
        root.addView(portLabel);
        final android.widget.EditText etPort = new android.widget.EditText(requireContext());
        etPort.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        etPort.setText(String.valueOf(webdavPort()));
        root.addView(etPort);

        // 根目录
        android.widget.TextView rootLabel = new android.widget.TextView(requireContext());
        rootLabel.setText("共享根目录（留空=默认 wwwroot）");
        rootLabel.setTextSize(14f);
        rootLabel.setTextColor(getResources().getColor(R.color.text_primary, null));
        rootLabel.setPadding(0, pad, 0, 4);
        root.addView(rootLabel);
        final android.widget.EditText etRoot = new android.widget.EditText(requireContext());
        etRoot.setText(webdavRoot());
        root.addView(etRoot);

        // 密码
        android.widget.TextView passLabel = new android.widget.TextView(requireContext());
        passLabel.setText("访问密码（留空=免密）");
        passLabel.setTextSize(14f);
        passLabel.setTextColor(getResources().getColor(R.color.text_primary, null));
        passLabel.setPadding(0, pad, 0, 4);
        root.addView(passLabel);
        final android.widget.EditText etPass = new android.widget.EditText(requireContext());
        etPass.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        etPass.setText(webdavPassword());
        root.addView(etPass);

        android.widget.LinearLayout btnRow = new android.widget.LinearLayout(requireContext());
        btnRow.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        android.widget.LinearLayout.LayoutParams brp = new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        brp.topMargin = pad;
        brp.bottomMargin = pad;
        btnRow.setLayoutParams(brp);

        android.widget.TextView cancelBtn = new android.widget.TextView(requireContext());
        cancelBtn.setText("取消");
        cancelBtn.setGravity(android.view.Gravity.CENTER);
        cancelBtn.setTextSize(14f);
        android.graphics.drawable.GradientDrawable cbg = new android.graphics.drawable.GradientDrawable();
        cbg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        cbg.setCornerRadius(pad);
        cbg.setStroke((int) (1 * density), 0xFFCCCCCC);
        cancelBtn.setBackground(cbg);
        android.widget.LinearLayout.LayoutParams clp = new android.widget.LinearLayout.LayoutParams(
                0, (int) (44 * density), 1);
        clp.rightMargin = pad / 2;
        cancelBtn.setLayoutParams(clp);
        cancelBtn.setOnClickListener(v -> dialog.dismiss());
        btnRow.addView(cancelBtn);

        android.widget.TextView okBtn = new android.widget.TextView(requireContext());
        okBtn.setText("保存");
        okBtn.setGravity(android.view.Gravity.CENTER);
        okBtn.setTextSize(14f);
        okBtn.setTextColor(0xFFFFFFFF);
        android.graphics.drawable.GradientDrawable obg = new android.graphics.drawable.GradientDrawable();
        obg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        obg.setCornerRadius(pad);
        obg.setColor(getPrimaryColor());
        okBtn.setBackground(obg);
        android.widget.LinearLayout.LayoutParams olp = new android.widget.LinearLayout.LayoutParams(
                0, (int) (44 * density), 1);
        olp.leftMargin = pad / 2;
        okBtn.setLayoutParams(olp);
        okBtn.setOnClickListener(v -> {
            String p = etPort.getText().toString().trim();
            int port;
            try { port = Integer.parseInt(p); if (port <= 0 || port > 65535) port = 5309; }
            catch (Exception e) { port = 5309; }
            prefs.edit().putInt("webdav_port", port)
                .putString("webdav_root", etRoot.getText().toString().trim())
                .putString("webdav_password", etPass.getText().toString()).apply();
            dialog.dismiss();
            ToastUtil.showShort(getContext(), "已保存，需停止后重新启动生效");
            syncWebdavSwitch();
        });
        btnRow.addView(okBtn);
        root.addView(btnRow);
        dialog.setContentView(root);
        dialog.show();
    }

    private void showWebdavLogDialog() {
        final android.app.Dialog dialog = new android.app.Dialog(requireContext());
        dialog.setTitle("WebDAV 日志");
        android.widget.LinearLayout root = new android.widget.LinearLayout(requireContext());
        root.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, 0);

        final int primaryColor = getPrimaryColor();
        final int r = Color.red(primaryColor);
        final int g = Color.green(primaryColor);
        final int b = Color.blue(primaryColor);
        final float density = getResources().getDisplayMetrics().density;

        android.widget.ScrollView scroll = new android.widget.ScrollView(requireContext());
        scroll.setBackgroundColor(0xFF1E1E1E);
        android.widget.LinearLayout.LayoutParams scrollLp = new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, (int) (360 * density));
        scroll.setLayoutParams(scrollLp);
        final android.widget.TextView logTv = new android.widget.TextView(requireContext());
        logTv.setTextSize(11f);
        logTv.setTextColor(0xFFD4D4D4);
        logTv.setTypeface(android.graphics.Typeface.MONOSPACE);
        logTv.setPadding(pad, pad, pad, pad);
        logTv.setText(WebdavServer.readLog(requireContext()));
        scroll.addView(logTv);
        root.addView(scroll);

        android.widget.LinearLayout btnRow = new android.widget.LinearLayout(requireContext());
        btnRow.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        android.widget.LinearLayout.LayoutParams btnRowLp = new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        btnRowLp.topMargin = pad;
        btnRowLp.bottomMargin = pad;
        btnRow.setLayoutParams(btnRowLp);

        android.widget.TextView refreshBtn = new android.widget.TextView(requireContext());
        refreshBtn.setText("刷新");
        refreshBtn.setTextColor(0xFFFFFFFF);
        refreshBtn.setTextSize(13f);
        refreshBtn.setGravity(android.view.Gravity.CENTER);
        android.graphics.drawable.GradientDrawable rbg = new android.graphics.drawable.GradientDrawable();
        rbg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        rbg.setCornerRadius(pad);
        rbg.setColor(primaryColor);
        refreshBtn.setBackground(rbg);
        android.widget.LinearLayout.LayoutParams rlp = new android.widget.LinearLayout.LayoutParams(
                0, (int) (40 * density), 1);
        rlp.rightMargin = pad / 2;
        refreshBtn.setLayoutParams(rlp);
        refreshBtn.setOnClickListener(v -> logTv.setText(WebdavServer.readLog(requireContext())));
        btnRow.addView(refreshBtn);

        android.widget.TextView clearBtn = new android.widget.TextView(requireContext());
        clearBtn.setText("清空");
        clearBtn.setTextColor(primaryColor);
        clearBtn.setTextSize(13f);
        clearBtn.setGravity(android.view.Gravity.CENTER);
        android.graphics.drawable.GradientDrawable cbg = new android.graphics.drawable.GradientDrawable();
        cbg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        cbg.setCornerRadius(pad);
        cbg.setColor(Color.argb(18, r, g, b));
        cbg.setStroke((int) (1 * density), Color.argb(80, r, g, b));
        clearBtn.setBackground(cbg);
        android.widget.LinearLayout.LayoutParams clp = new android.widget.LinearLayout.LayoutParams(
                0, (int) (40 * density), 1);
        clp.leftMargin = pad / 2;
        clp.rightMargin = pad / 2;
        clearBtn.setLayoutParams(clp);
        clearBtn.setOnClickListener(v -> {
            WebdavServer.clearLog(requireContext());
            logTv.setText("");
            ToastUtil.showShort(getContext(), "日志已清空");
        });
        btnRow.addView(clearBtn);

        android.widget.TextView exportBtn = new android.widget.TextView(requireContext());
        exportBtn.setText("导出");
        exportBtn.setTextColor(0xFFFFFFFF);
        exportBtn.setTextSize(13f);
        exportBtn.setGravity(android.view.Gravity.CENTER);
        android.graphics.drawable.GradientDrawable ebg = new android.graphics.drawable.GradientDrawable();
        ebg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        ebg.setCornerRadius(pad);
        ebg.setColor(primaryColor);
        exportBtn.setBackground(ebg);
        android.widget.LinearLayout.LayoutParams elp = new android.widget.LinearLayout.LayoutParams(
                0, (int) (40 * density), 1);
        elp.leftMargin = pad / 2;
        exportBtn.setLayoutParams(elp);
        exportBtn.setOnClickListener(v -> {
            try {
                File dir = android.os.Environment.getExternalStoragePublicDirectory(
                        android.os.Environment.DIRECTORY_DOWNLOADS);
                if (!dir.exists()) dir.mkdirs();
                File out = new File(dir, "webdav.log");
                try (java.io.FileOutputStream fo = new java.io.FileOutputStream(out)) {
                    fo.write(logTv.getText().toString().getBytes("UTF-8"));
                }
                ToastUtil.showShort(getContext(), "已导出到下载目录 webdav.log");
            } catch (Exception e) {
                ToastUtil.showShort(getContext(), "导出失败: " + e.getMessage());
            }
        });
        btnRow.addView(exportBtn);

        root.addView(btnRow);
        dialog.setContentView(root);
        dialog.show();
    }
    private void updateComponentStatus() {
        // 缓存 isInstalled 结果（涉及文件检查）
        boolean nginxInstalled = webServer != null && webServer.isInstalled();
        boolean phpInstalled = phpManager != null && phpManager.isInstalled();
        boolean mysqlInstalled = dbManager != null && dbManager.isInstalled();
        boolean redisInstalled = redisManager != null && redisManager.isInstalled();
        // 缓存 isRunning 结果（涉及 isPortOpen + 文件读取）
        boolean nginxRunning = webServer != null && webServer.isRunning();
        boolean phpRunning = phpManager != null && phpManager.isRunning();
        boolean mysqlRunning = dbManager != null && dbManager.isRunning();
        boolean redisRunning = redisManager != null && redisManager.isRunning();

        updateOneComponent(dotNginx, tvNginxStatus, switchNginx,
            nginxRunning, nginxInstalled,
            switchNginx != null && switchNginx.isChecked());
        updateOneComponent(dotPhp, tvPhpStatus, switchPhp,
            phpRunning, phpInstalled,
            switchPhp != null && switchPhp.isChecked());
        updateOneComponent(dotMysql, tvMysqlStatus, switchMysql,
            mysqlRunning, mysqlInstalled,
            switchMysql != null && switchMysql.isChecked());
        updateOneComponent(dotRedis, tvRedisStatus, switchRedis,
            redisRunning, redisInstalled,
            switchRedis != null && switchRedis.isChecked());
    }

    private void updateOneComponent(View dot, TextView statusText, SwitchMaterial sw,
                                     boolean running, boolean installed, boolean switchOn) {
        // Nginx 专属状态逻辑（按 5 条规则）
        if (statusText == tvNginxStatus) {
            boolean hasSites = webServer != null && webServer.hasSites();
            boolean hasPma = hasPhpMyAdmin();

            if (!installed) {
                // 未安装
                dot.setBackgroundResource(R.drawable.circle_gray);
                statusText.setText("未安装");
                statusText.setTextColor(getResources().getColor(R.color.text_secondary, null));
            } else if (!switchOn) {
                // 规则5: 开关已关闭 -> 已就绪
                dot.setBackgroundResource(R.drawable.circle_blue);
                statusText.setText("已就绪");
                statusText.setTextColor(getResources().getColor(R.color.blue_accent, null));
            } else if (!hasSites && !hasPma) {
                // 规则4: 都不存在 -> 自动关闭开关 + 已就绪
                if (prefs.getBoolean("auto_start_nginx", false)) {
                    prefs.edit().putBoolean("auto_start_nginx", false)
                        .putBoolean(KEY_STARTING_NGINX, false).apply();
                    if (sw != null) {
                        syncingSwitches = true;
                        try { sw.setChecked(false); } finally { syncingSwitches = false; }
                    }
                }
                dot.setBackgroundResource(R.drawable.circle_blue);
                statusText.setText("已就绪");
                statusText.setTextColor(getResources().getColor(R.color.blue_accent, null));
            } else if (running) {
                // 规则1/2/3: 开关打开 + 有站点或pma + 运行中 -> 运行中
                dot.setBackgroundResource(R.drawable.circle_green);
                statusText.setText("运行中");
                statusText.setTextColor(getResources().getColor(R.color.status_green, null));
            } else {
                // 开关打开 + 有站点或pma + 未运行 -> 启动中
                dot.setBackgroundResource(R.drawable.circle_amber);
                statusText.setText("启动中");
                statusText.setTextColor(getResources().getColor(R.color.status_amber, null));
            }
            if (sw != null) sw.setEnabled(true);
            return;
        }

        // 其他组件（PHP/MariaDB/Redis）保持原有逻辑
        String key = null;
        if (statusText == tvPhpStatus) {
            key = KEY_STARTING_PHP;
        } else if (statusText == tvMysqlStatus) {
            key = KEY_STARTING_MARIADB;
        } else if (statusText == tvRedisStatus) {
            key = KEY_STARTING_REDIS;
        }
        boolean starting = key != null && prefs.getBoolean(key, false);

        // 开关关闭但进程还在运行 -> 停止中
        if (!switchOn && running) {
            dot.setBackgroundResource(R.drawable.circle_amber);
            statusText.setText("停止中");
            statusText.setTextColor(getResources().getColor(R.color.status_amber, null));
        } else if (running) {
            dot.setBackgroundResource(R.drawable.circle_green);
            statusText.setText("运行中");
            statusText.setTextColor(getResources().getColor(R.color.status_green, null));
        } else if (starting) {
            dot.setBackgroundResource(R.drawable.circle_amber);
            statusText.setText("启动中");
            statusText.setTextColor(getResources().getColor(R.color.status_amber, null));
        } else if (switchOn && installed) {
            dot.setBackgroundResource(R.drawable.circle_amber);
            statusText.setText("启动中");
            statusText.setTextColor(getResources().getColor(R.color.status_amber, null));
        } else if (installed) {
            dot.setBackgroundResource(R.drawable.circle_blue);
            statusText.setText("已就绪");
            statusText.setTextColor(getResources().getColor(R.color.blue_accent, null));
        } else {
            dot.setBackgroundResource(R.drawable.circle_gray);
            statusText.setText("未安装");
            statusText.setTextColor(getResources().getColor(R.color.text_secondary, null));
        }

        // 开关控制: 所有状态都允许操作（启动中可取消启动，停止中可取消停止，运行中可关闭，已就绪可开启）
        if (sw != null) {
            sw.setEnabled(true);
        }
    }

    private String formatUptime(long sec) {
        long h = sec / 3600, m = (sec % 3600) / 60, s = sec % 60;
        return String.format("已运行 %02d:%02d:%02d", h, m, s);
    }

    private void refreshIp() {
        cachedLocalIp = NetworkUtil.getLocalIpAddress();
        tvLocalIp.setText(cachedLocalIp);
        NetworkUtil.fetchExternalIp(result -> {
            if (result != null && !result.isEmpty() && !"获取失败".equals(result)) {
                cachedExternalIp = result;
                tvExternalIp.setText(result);
                // 拿到公网 IP 后立即刷一次 FTP 状态，让访问地址实时同步
                refreshFtpStatus();
            } else {
                tvExternalIp.setText("网络受限");
            }
        });
        ipHandler.postDelayed(ipTask, IP_INTERVAL);
    }

    private boolean hasPhpMyAdmin() {
        java.io.File binPma = new java.io.File(requireContext().getFilesDir(), "bin/phpmyadmin");
        if (!binPma.exists() || !binPma.isDirectory()) return false;
        java.io.File[] versions = binPma.listFiles();
        if (versions == null || versions.length == 0) return false;
        for (java.io.File v : versions) {
            if (v.isDirectory() && v.getName().matches("\\d+\\.\\d+\\.\\d+.*")) return true;
        }
        return false;
    }


    private void refreshSites() {
        if (layoutSites == null || tvNoSites == null) return;
        layoutSites.removeAllViews();
        
        Set<String> siteSet = prefs.getStringSet("sites", new HashSet<>());
        if (siteSet.isEmpty()) {
            tvNoSites.setVisibility(View.VISIBLE);
            layoutSites.addView(tvNoSites);
            return;
        }
        tvNoSites.setVisibility(View.GONE);
        if (cachedLocalIp.isEmpty()) cachedLocalIp = NetworkUtil.getLocalIpAddress();
        String localIp = cachedLocalIp;
        for (String site : siteSet) {
            String[] parts = site.split("\\|");
            String name = parts.length > 0 ? parts[0] : site;
            String port = parts.length > 1 ? parts[1] : "8080";
            View item = getLayoutInflater().inflate(R.layout.item_site_dash, layoutSites, false);
            TextView siteNameTv = item.findViewById(R.id.tv_site_name);
            if (siteNameTv != null) siteNameTv.setText(name);
            View sitePortTv = item.findViewById(R.id.tv_site_port);
            if (sitePortTv != null) sitePortTv.setVisibility(View.GONE);
            
            // 设置"打开"按钮
            String url = "http://" + localIp + ":" + port;
            final String fUrl = url;
            TextView btnOpen = item.findViewById(R.id.btn_open_site);
            int primaryColor = getPrimaryColor();
            btnOpen.setTextColor(primaryColor);
            GradientDrawable btnBg = new GradientDrawable();
            btnBg.setShape(GradientDrawable.RECTANGLE);
            btnBg.setCornerRadius(10 * getResources().getDisplayMetrics().density);
            btnBg.setColor(Color.argb(18, Color.red(primaryColor), Color.green(primaryColor), Color.blue(primaryColor)));
            btnBg.setStroke((int)(1 * getResources().getDisplayMetrics().density),
                Color.argb(80, Color.red(primaryColor), Color.green(primaryColor), Color.blue(primaryColor)));
            btnOpen.setBackground(btnBg);
            btnOpen.setOnClickListener(v -> {
                try {
                    Intent intent = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(fUrl));
                    startActivity(intent);
                } catch (Exception e) {
                    ToastUtil.showShort(getContext(), "\u6253\u5f00\u5931\u8d25");
                }
            });
            
            layoutSites.addView(item);
        }
    }


    private int getPrimaryColor() {
        String themeKey = prefs.getString("theme_key", "slate");
        switch (themeKey) {
            case "green":  return ContextCompat.getColor(requireContext(), R.color.theme_green_primary);
            case "purple": return ContextCompat.getColor(requireContext(), R.color.theme_purple_primary);
            case "orange": return ContextCompat.getColor(requireContext(), R.color.theme_orange_primary);
            case "blue":   return ContextCompat.getColor(requireContext(), R.color.blue_primary);
            case "red":    return ContextCompat.getColor(requireContext(), R.color.theme_red_primary);
            case "cyan":   return ContextCompat.getColor(requireContext(), R.color.theme_cyan_primary);
            case "pink":   return ContextCompat.getColor(requireContext(), R.color.theme_pink_primary);
            case "indigo": return ContextCompat.getColor(requireContext(), R.color.theme_indigo_primary);
            case "brown":  return ContextCompat.getColor(requireContext(), R.color.theme_brown_primary);
            default:       return ContextCompat.getColor(requireContext(), R.color.theme_slate_primary);
        }
    }

    // ===== 插件卡片动态渲染 =====
    private void renderPlugins() {
        if (layoutPlugins == null) return;
        layoutPlugins.removeAllViews();
        List<String> ids = com.zm920.androidserver.plugin.PluginManager
                .getInstance(requireContext()).listInstalledIds();
        if (ids.isEmpty()) {
            if (tvNoPlugins != null) tvNoPlugins.setVisibility(View.VISIBLE);
            return;
        }
        if (tvNoPlugins != null) tvNoPlugins.setVisibility(View.GONE);
        int primaryColor = getPrimaryColor();
        for (String id : ids) {
            try {
                layoutPlugins.addView(buildPluginCard(id, primaryColor));
            } catch (Exception ignored) {}
        }
    }

    private View buildPluginCard(final String id, int primaryColor) {
        org.json.JSONObject meta = com.zm920.androidserver.plugin.PluginManager
                .getInstance(requireContext()).loadMeta(id);
        String name = meta != null ? meta.optString("name", id) : id;
        String desc = meta != null ? meta.optString("desc", "") : "";
        int port = meta != null ? meta.optInt("port", 0) : 0;
        boolean running = com.zm920.androidserver.plugin.PluginManager
                .getInstance(requireContext()).isRunning(id);

        LinearLayout card = new LinearLayout(requireContext());
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp2(12), dp2(12), dp2(12), dp2(12));
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(dp2(12));
        bg.setColor(Color.argb(18, Color.red(primaryColor), Color.green(primaryColor), Color.blue(primaryColor)));
        bg.setStroke(dp2(1), Color.argb(60, Color.red(primaryColor), Color.green(primaryColor), Color.blue(primaryColor)));
        card.setBackground(bg);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.setMargins(0, 0, 0, dp2(10));
        card.setLayoutParams(clp);

        // 标题行
        LinearLayout row1 = new LinearLayout(requireContext());
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.setGravity(Gravity.CENTER_VERTICAL);
        TextView nameTv = new TextView(requireContext());
        nameTv.setText(name);
        nameTv.setTextSize(15f);
        nameTv.setTextColor(0xFF202124);
        nameTv.setTypeface(null, android.graphics.Typeface.BOLD);
        nameTv.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        row1.addView(nameTv);

        TextView state = new TextView(requireContext());
        state.setText(running ? "●运行中" : "○已停止");
        state.setTextSize(11f);
        state.setTextColor(running ? 0xFF34A853 : 0xFF9AA0A6);
        row1.addView(state);
        card.addView(row1);

        // 描述 + 端口
        TextView descTv = new TextView(requireContext());
        descTv.setText(desc + (port > 0 ? "  ·  端口 " + port : ""));
        descTv.setTextSize(12f);
        descTv.setTextColor(0xFF5F6368);
        descTv.setPadding(0, dp2(4), 0, dp2(8));
        card.addView(descTv);

        // 操作行
        LinearLayout row2 = new LinearLayout(requireContext());
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.setGravity(Gravity.CENTER_VERTICAL);
        TextView openBtn = pluginPill("启动/停止", primaryColor, true);
        openBtn.setOnClickListener(v -> {
            com.zm920.androidserver.plugin.PluginManager pm = com.zm920.androidserver.plugin.PluginManager.getInstance(requireContext());
            if (pm.isRunning(id)) pm.stop(id); else pm.start(id);
            renderPlugins();
        });
        row2.addView(openBtn);

        TextView openWeb = pluginPill("打开界面", primaryColor, false);
        final int fPort = port;
        openWeb.setOnClickListener(v -> {
            if (fPort <= 0) { ToastUtil.showShort(getContext(), "该插件无网页界面"); return; }
            try {
                String ip = cachedLocalIp.isEmpty() ? NetworkUtil.getLocalIpAddress() : cachedLocalIp;
                if (ip == null || ip.isEmpty()) ip = "127.0.0.1";
                startActivity(new Intent(Intent.ACTION_VIEW,
                        android.net.Uri.parse("http://" + ip + ":" + fPort)));
            } catch (Exception e) {
                ToastUtil.showShort(getContext(), "打开失败");
            }
        });
        row2.addView(openWeb);

        TextView del = pluginPill("卸载", 0xFFD93025, false);
        del.setOnClickListener(v -> {
            new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                    .setTitle("卸载插件")
                    .setMessage("确定卸载「" + name + "」？")
                    .setPositiveButton("卸载", (d, w) -> {
                        com.zm920.androidserver.plugin.PluginManager.getInstance(requireContext()).uninstall(id);
                        renderPlugins();
                    })
                    .setNegativeButton("取消", null)
                    .show();
        });
        row2.addView(del);
        card.addView(row2);
        return card;
    }

    private TextView pluginPill(String text, int color, boolean filled) {
        TextView t = new TextView(requireContext());
        t.setText(text);
        t.setTextSize(12f);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(dp2(14));
        bg.setColor(filled ? color : 0xFFFFFFFF);
        if (!filled) bg.setStroke(dp2(1), Color.argb(80, Color.red(color), Color.green(color), Color.blue(color)));
        t.setBackground(bg);
        t.setPadding(dp2(12), dp2(6), dp2(12), dp2(6));
        t.setClickable(true);
        t.setFocusable(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, dp2(6), 0);
        t.setLayoutParams(lp);
        return t;
    }

    private int dp2(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }

    private void applyThemeToCircular() {
        int primaryColor = getPrimaryColor();
        int bgRingColor = Color.argb(30, Color.red(primaryColor), Color.green(primaryColor), Color.blue(primaryColor));
        if (circularMem != null) circularMem.setThemeColors(bgRingColor, primaryColor);
        if (circularStorage != null) circularStorage.setThemeColors(bgRingColor, primaryColor);
    }

    private void applyThemeToSwitches() {
        int primaryColor = getPrimaryColor();
        int trackColor = Color.argb(77, Color.red(primaryColor), Color.green(primaryColor), Color.blue(primaryColor));
        int[][] states = new int[][]{
            {android.R.attr.state_checked},
            {-android.R.attr.state_checked}
        };
        int[] thumbColors = new int[]{primaryColor, android.graphics.Color.parseColor("#FFFAFAFA")};
        int[] trackColors = new int[]{trackColor, android.graphics.Color.parseColor("#33000000")};
        android.content.res.ColorStateList thumbCsl = new android.content.res.ColorStateList(states, thumbColors);
        android.content.res.ColorStateList trackCsl = new android.content.res.ColorStateList(states, trackColors);
        if (switchNginx != null) {
            switchNginx.setThumbTintList(thumbCsl);
            switchNginx.setTrackTintList(trackCsl);
        }
        if (switchPhp != null) {
            switchPhp.setThumbTintList(thumbCsl);
            switchPhp.setTrackTintList(trackCsl);
        }
        if (switchMysql != null) {
            switchMysql.setThumbTintList(thumbCsl);
            switchMysql.setTrackTintList(trackCsl);
        }
        if (switchRedis != null) {
            switchRedis.setThumbTintList(thumbCsl);
            switchRedis.setTrackTintList(trackCsl);
        }
    }

    private void applyThemeToFtpButtons() {
        if (btnFtpStart == null || btnFtpStop == null || btnFtpLog == null) return;
        int primaryColor = getPrimaryColor();
        int r = Color.red(primaryColor);
        int g = Color.green(primaryColor);
        int b = Color.blue(primaryColor);
        float density = getResources().getDisplayMetrics().density;

        // 启动按钮：实心主题色背景 + 白色文字
        GradientDrawable startBg = new GradientDrawable();
        startBg.setShape(GradientDrawable.RECTANGLE);
        startBg.setCornerRadius(10 * density);
        startBg.setColor(primaryColor);
        btnFtpStart.setBackground(startBg);
        btnFtpStart.setTextColor(0xFFFFFFFF);

        // 停止按钮：主题色 18% 背景 + 主题色 80% 边框 + 主题色文字
        GradientDrawable stopBg = new GradientDrawable();
        stopBg.setShape(GradientDrawable.RECTANGLE);
        stopBg.setCornerRadius(10 * density);
        stopBg.setColor(Color.argb(18, r, g, b));
        stopBg.setStroke((int) (1 * density), Color.argb(80, r, g, b));
        btnFtpStop.setBackground(stopBg);
        btnFtpStop.setTextColor(primaryColor);

        // 查看日志按钮：同停止按钮
        GradientDrawable logBg = new GradientDrawable();
        logBg.setShape(GradientDrawable.RECTANGLE);
        logBg.setCornerRadius(10 * density);
        logBg.setColor(Color.argb(18, r, g, b));
        logBg.setStroke((int) (1 * density), Color.argb(80, r, g, b));
        btnFtpLog.setBackground(logBg);
        btnFtpLog.setTextColor(primaryColor);
    }
    private void applyThemeToHeader() {
        if (getView() == null) return;
        String themeKey = prefs.getString("theme_key", "slate");
        int startColor, endColor;

        switch (themeKey) {
            case "green":
                startColor = getResources().getColor(R.color.theme_green_gradient_start, null);
                endColor = getResources().getColor(R.color.theme_green_gradient_end, null);
                break;
            case "purple":
                startColor = getResources().getColor(R.color.theme_purple_gradient_start, null);
                endColor = getResources().getColor(R.color.theme_purple_gradient_end, null);
                break;
            case "orange":
                startColor = getResources().getColor(R.color.theme_orange_gradient_start, null);
                endColor = getResources().getColor(R.color.theme_orange_gradient_end, null);
                break;
            case "blue":
                startColor = getResources().getColor(R.color.theme_blue_gradient_start, null);
                endColor = getResources().getColor(R.color.theme_blue_gradient_end, null);
                break;
            case "red":
                startColor = getResources().getColor(R.color.theme_red_gradient_start, null);
                endColor = getResources().getColor(R.color.theme_red_gradient_end, null);
                break;
            case "cyan":
                startColor = getResources().getColor(R.color.theme_cyan_gradient_start, null);
                endColor = getResources().getColor(R.color.theme_cyan_gradient_end, null);
                break;
            case "pink":
                startColor = getResources().getColor(R.color.theme_pink_gradient_start, null);
                endColor = getResources().getColor(R.color.theme_pink_gradient_end, null);
                break;
            case "indigo":
                startColor = getResources().getColor(R.color.theme_indigo_gradient_start, null);
                endColor = getResources().getColor(R.color.theme_indigo_gradient_end, null);
                break;
            case "brown":
                startColor = getResources().getColor(R.color.theme_brown_gradient_start, null);
                endColor = getResources().getColor(R.color.theme_brown_gradient_end, null);
                break;
            default:
                startColor = getResources().getColor(R.color.theme_slate_gradient_start, null);
                endColor = getResources().getColor(R.color.theme_slate_gradient_end, null);
                break;
        }

        android.graphics.drawable.GradientDrawable gradient = new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.BL_TR,
                new int[]{startColor, endColor}
        );
        gradient.setCornerRadii(new float[]{0, 0, 0, 0, 24, 24, 24, 24});
        gradient.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);

        View header = getView().findViewById(R.id.header_container);
        if (header != null) {
            header.setBackground(gradient);
        }
    }

    public void refreshTheme() {
        applyThemeToHeader();
        applyThemeToCircular();
        applyThemeToSwitches();
        applyThemeToFtpButtons();
        applyThemeToWsButtons();
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        PluginManageSheet.handleImportResult(this, requestCode, resultCode, data);
    }

}
