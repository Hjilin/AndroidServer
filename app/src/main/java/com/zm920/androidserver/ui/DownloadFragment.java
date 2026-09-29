package com.zm920.androidserver.ui;

import android.util.Log;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import com.zm920.androidserver.ui.widget.ToastUtil;
import com.zm920.androidserver.util.PermissionUtil;
import com.zm920.androidserver.util.StoragePaths;
import com.zm920.androidserver.util.SiteScanner;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.fragment.app.Fragment;

import com.zm920.androidserver.MainActivity;
import com.zm920.androidserver.config.ConfigGenerator;
import com.zm920.androidserver.server.BinaryDeployer;
import com.zm920.androidserver.server.PhpManager;
import com.zm920.androidserver.server.DatabaseManager;
import com.zm920.androidserver.server.StartupLogger;
import com.zm920.androidserver.service.ProcessManager;
import com.zm920.androidserver.R;
import com.zm920.androidserver.download.BinaryDownloader;
import com.zm920.androidserver.ui.widget.SimpleFileDialog;
import com.zm920.androidserver.ui.widget.RedisClient;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.net.Socket;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Set;

class DownloadTaskHolder {
    static final java.util.Map<String, BinaryDownloader.DownloadTask> TASKS = new java.util.HashMap<>();
    static final java.util.Map<String, int[]> PROGRESS = new java.util.HashMap<>();
    static final java.util.Set<String> COMPLETED = new java.util.HashSet<>();
    static final java.util.Map<String, String> ERRORS = new java.util.HashMap<>();
}

public class DownloadFragment extends Fragment {
    private static final String TAG = "DownloadFragment";

    private BinaryDownloader downloader;
    private SharedPreferences prefs;
    private static final String KEY_CREATED_DBS = "created_databases";
    private boolean destroyed = false;
    private final Handler progressHandler = new Handler(Looper.getMainLooper());
    private final Runnable progressPoller = new Runnable() {
        @Override
        public void run() {
            if (destroyed) return;
            View sv = getView();
            if (sv == null) return;
            boolean hasActive = false;
            for (String comp : views.keySet()) {
                V v = views.get(comp);
                ProgressBar pb = sv.findViewById(v.progBar);
                TextView pt = sv.findViewById(v.progText);
                TextView pbBtn = sv.findViewById(v.pause);
                LinearLayout progLay = sv.findViewById(v.progLay);
                if (DownloadTaskHolder.COMPLETED.contains(comp)) {
                    DownloadTaskHolder.COMPLETED.remove(comp);
                    progLay.setVisibility(View.GONE);
                    refreshOne(comp);
                    continue;
                }
                String errMsg = DownloadTaskHolder.ERRORS.remove(comp);
                if (errMsg != null) {
                    progLay.setVisibility(View.GONE);
                    refreshOne(comp);
                    toast("下载失败: " + errMsg);
                    continue;
                }
                if (DownloadTaskHolder.TASKS.containsKey(comp)) {
                    hasActive = true;
                    int[] saved = DownloadTaskHolder.PROGRESS.get(comp);
                    progLay.setVisibility(View.VISIBLE);
                    pbBtn.setVisibility(View.VISIBLE);
                    if (saved != null) {
                        if (pb != null) pb.setProgress(saved[0]);
                        if (pt != null) pt.setText(saved[0] + "%  " + fmt(saved[1]) + " / " + fmt(saved[2]));
                    }
                    BinaryDownloader.DownloadTask t = DownloadTaskHolder.TASKS.get(comp);
                    if (pbBtn != null) pbBtn.setText(t != null && t.isPaused() ? "继续" : "暂停");
                }
            }
            if (hasActive) progressHandler.postDelayed(this, 500);
        }
    };

    private static final Map<String, String[]> VERSIONS = new HashMap<>();
    static {
        VERSIONS.put("Nginx", new String[]{"1.31.2"});
        VERSIONS.put("PHP", new String[]{"8.5.1", "8.3.0", "7.4.0"});
        VERSIONS.put("MariaDB", new String[]{"12.3.2"});
        VERSIONS.put("Redis", new String[]{"8.8.0"});
        VERSIONS.put("phpMyAdmin", new String[]{"5.2.1"});
    }

    // 通用内置扩展不写 extension=；版本差异扩展在 isPhpBuiltinExtension() 中判断。
    private static final Set<String> PHP_BUILTIN_EXTENSIONS = new LinkedHashSet<>(java.util.Arrays.asList(
            "fileinfo", "mbstring", "curl", "json"
    ));

    // UI 展示/选择的扩展列表。redis/gd/sg11 等外部 .so 随对应 PHP 压缩包一起提供。
    private static final String[] PHP_EXTENSIONS = new String[]{
            "fileinfo", "mbstring", "curl", "json", "gd", "redis", "sg11"
    };

    private static final Set<String> PHP_DISPLAY_ONLY_EXTENSIONS = new LinkedHashSet<>();
    private static final Set<String> PHP_DISABLED_EXTENSIONS = new LinkedHashSet<>();
    static {
    }

    private String cleanVer(String s) { return s.replace(" ✅", "").trim(); }

    static class V {
        int status, select, progLay, progBar, progText, pause;
        V(int s, int sl, int pl, int pb, int pt, int pa) {
            status = s; select = sl; progLay = pl; progBar = pb; progText = pt; pause = pa;
        }
    }

    private final Map<String, V> views = new HashMap<>();
    {
        views.put("Nginx", new V(R.id.tv_nginx_version, R.id.btn_nginx_select,
                R.id.layout_nginx_progress, R.id.progress_nginx,
                R.id.tv_nginx_progress, R.id.btn_nginx_pause));
        views.put("PHP", new V(R.id.tv_php_version, R.id.btn_php_select,
                R.id.layout_php_progress, R.id.progress_php,
                R.id.tv_php_progress, R.id.btn_php_pause));
        views.put("MariaDB", new V(R.id.tv_mysql_version, R.id.btn_mysql_select,
                R.id.layout_mysql_progress, R.id.progress_mysql,
                R.id.tv_mysql_progress, R.id.btn_mysql_pause));
        views.put("Redis", new V(R.id.tv_redis_version, R.id.btn_redis_select,
                R.id.layout_redis_progress, R.id.progress_redis,
                R.id.tv_redis_progress, R.id.btn_redis_pause));
        views.put("phpMyAdmin", new V(R.id.tv_phpmyadmin_version, R.id.btn_phpmyadmin_select,
                R.id.layout_phpmyadmin_progress, R.id.progress_phpmyadmin,
                R.id.tv_phpmyadmin_progress, R.id.btn_phpmyadmin_pause));
    }

    private final Map<String, BinaryDownloader.DownloadTask> tasks = DownloadTaskHolder.TASKS;

    private ActivityResultLauncher<Intent> safLauncher;
    private String pendingSiteKeyForRoot = "";
    private EditText pendingRootEditText;
    private String pendingSiteKey = "";
    private String siteKey = "";
    private TextView btnPickDir;

    

    private String resolveSafUri(Uri treeUri) {
        try {
            String path = treeUri.getPath();
            if (path == null) return treeUri.toString();
            Log.i("resolveSafUri", "raw path=" + path);
            // 提取 /tree/ 后面的编码路径
            int treeIdx = path.indexOf("/tree/");
            if (treeIdx < 0) return treeUri.toString();
            String afterTree = path.substring(treeIdx + 6);
            // 去掉 /document/... 后缀
            int docIdx = afterTree.indexOf("/document/");
            if (docIdx >= 0) afterTree = afterTree.substring(0, docIdx);
            String decoded = java.net.URLDecoder.decode(afterTree, "UTF-8");
            Log.i("resolveSafUri", "decoded=" + decoded);
            // primary:path -> android.os.Environment.getExternalStorageDirectory().getAbsolutePath() + "/path"
            if (decoded.startsWith("primary:")) {
                return android.os.Environment.getExternalStorageDirectory().getAbsolutePath() + "/" + decoded.substring("primary:".length());
            }
            if (decoded.startsWith("primary/")) {
                return android.os.Environment.getExternalStorageDirectory().getAbsolutePath() + "/" + decoded.substring("primary/".length());
            }
            if ("primary".equals(decoded)) {
                return android.os.Environment.getExternalStorageDirectory().getAbsolutePath();
            }
            // 其他卷: XXXX-XXXX:path -> /storage/XXXX-XXXX/path
            if (decoded.contains(":")) {
                return "/storage/" + decoded.replace(":", "/");
            }
            return "/storage/" + decoded;
        } catch (Exception e) {
            Log.e("resolveSafUri", "parse failed", e);
            return treeUri.toString();
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_download, container, false);
    }
    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        destroyed = false;

        safLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == getActivity().RESULT_OK && result.getData() != null) {
                        Uri treeUri = result.getData().getData();
                        if (treeUri != null) {
                            String path = resolveSafUri(treeUri);
                            if (path != null && !path.isEmpty()) {
                                prefs.edit().putString("pending_site_root_" + pendingSiteKeyForRoot, path).apply();
                                if (pendingRootEditText != null) pendingRootEditText.setText(path);
                                toast("目录已选择: " + path);
                            }
                        }
                    }
                });

        prefs = requireContext().getSharedPreferences("server_settings", 0);
        downloader = new BinaryDownloader(new File(requireContext().getFilesDir(), "bin"));

        for (String comp : VERSIONS.keySet()) {
            V v = views.get(comp);
            view.findViewById(v.select).setOnClickListener(vv -> showVersionDialog(comp));
            view.findViewById(v.pause).setOnClickListener(vv -> togglePause(comp));
        }

        bindActions(view);
        refreshAll();
        if (!DownloadTaskHolder.TASKS.isEmpty()) {
            progressHandler.removeCallbacks(progressPoller);
            progressHandler.postDelayed(progressPoller, 200);
        }
    }

    private void bindActions(View view) {
        view.findViewById(R.id.btn_export_all_logs).setOnClickListener(v -> exportAllLogs());
        view.findViewById(R.id.btn_nginx_access_log).setOnClickListener(v -> openFileEditor("Nginx 访问日志", nginxAccessLog(), true));
        view.findViewById(R.id.btn_nginx_conf).setOnClickListener(v -> openFileEditor("nginx.conf", configFile("nginx.conf"), true));
        view.findViewById(R.id.btn_nginx_site_config).setOnClickListener(v -> showNginxSiteConfig());
        view.findViewById(R.id.btn_nginx_error_log).setOnClickListener(v -> openFileEditor("Nginx v1日志", nginxErrorLog(), true));
        view.findViewById(R.id.btn_php_ini).setOnClickListener(v -> openFileEditor("php.ini", configFile("php.ini"), true));
        view.findViewById(R.id.btn_php_fpm).setOnClickListener(v -> openFileEditor("PHP FPM 配置", configFile("php-fpm.conf"), true));
        view.findViewById(R.id.btn_php_error_log).setOnClickListener(v -> openFileEditor("PHP v1日志", resolvePhpErrorLogFile(), true));
        view.findViewById(R.id.btn_php_extensions).setOnClickListener(v -> showPhpExtensionsDialog());

        view.findViewById(R.id.btn_mysql_error_log).setOnClickListener(v -> openFileEditor("MariaDB v1日志", configFile("mysqld.log"), true));
        view.findViewById(R.id.btn_mysql_conf).setOnClickListener(v -> openFileEditor("my.cnf", configFile("my.cnf"), true));
        view.findViewById(R.id.btn_mysql_data_dir).setOnClickListener(v -> showCreatedDatabasesDialog());
        view.findViewById(R.id.btn_mysql_change_pwd).setOnClickListener(v -> showMysqlChangePwdDialog());
        view.findViewById(R.id.btn_mysql_add_db).setOnClickListener(v -> showMysqlDataDirDialog());

        view.findViewById(R.id.btn_redis_error_log).setOnClickListener(v -> openFileEditor("Redis 错误日志", configFile("redis.log"), true));
        view.findViewById(R.id.btn_redis_conf).setOnClickListener(v -> openFileEditor("redis.conf", configFile("redis.conf"), true));
        view.findViewById(R.id.btn_redis_keys).setOnClickListener(v -> showRedisKeysDialog());
        view.findViewById(R.id.btn_redis_flush).setOnClickListener(v -> confirmRedisFlush());
        view.findViewById(R.id.btn_phpmyadmin_open).setOnClickListener(v -> openPhpMyAdminFromComponents());
    }

    @Override
    public void onDestroyView() {
        destroyed = true;
        progressHandler.removeCallbacks(progressPoller);
        super.onDestroyView();
    }

    private View safeView() {
        if (destroyed) return null;
        return getView();
    }

    private void showVersionDialog(String comp) {
        final String[] rawVersions = VERSIONS.get(comp);
        if (rawVersions == null || rawVersions.length == 0) {
            new ThemedDialogBuilder(requireContext())
                    .setTitle(comp)
                    .setMessage("暂无版本")
                    .addButton("关闭", false, d -> d.dismiss())
                    .show();
            return;
        }
        final String currentVer = prefs.getString("active_" + comp.toLowerCase(), downloader.getInstalledVersion(comp));
        RadioGroup rg = new RadioGroup(requireContext());
        rg.setPadding(dp(24), dp(8), dp(24), dp(8));
        int checked = -1;
        for (int i = 0; i < rawVersions.length; i++) {
            RadioButton rb = new RadioButton(requireContext());
            String raw = rawVersions[i];
            String ver = cleanVer(raw);
            boolean installed = downloader.isInstalled(comp, ver);
            boolean isCurrent = installed && ver.equals(currentVer);
            String label = raw;
            if (isCurrent) { label += "  ✓ 当前"; checked = i; }
            else if (installed) { label += "  已安装"; if (checked < 0) checked = i; }
            rb.setText(label);
            rb.setId(i);
            rg.addView(rb);
        }
        if (checked >= 0) rg.check(checked);
        new ThemedDialogBuilder(requireContext())
                .setTitle("选择 " + comp + " 版本")
                .setContentView(rg)
                .addButton("取消", false, d -> d.dismiss())
                .addButton("确定", true, d -> {
                    int id = rg.getCheckedRadioButtonId();
                    if (id >= 0 && id < rawVersions.length) {
                        String ver = cleanVer(rawVersions[id]);
                        if (downloader.isInstalled(comp, ver)) {
                            File localPkg = new File(android.os.Environment.getExternalStorageDirectory().getAbsolutePath() + "/Download/" + comp.toLowerCase() + "-" + ver + "-combined.tar.gz");
                            if (localPkg.exists() && "PHP".equals(comp)) {
                                startDownload(comp, ver);
                            } else {
                                prefs.edit().putString("active_" + comp.toLowerCase(), ver).apply();
                                refreshOne(comp);
                                if ("PHP".equals(comp)) {
                                    restartPhpVersion(ver);
                                }
                            }
                        } else {
                            startDownload(comp, ver);
                        }
                    }
                    d.dismiss();
                })
                .show();
    }

    private void restartPhpVersion(String ver) {
        new Thread(() -> {
            try {
                android.content.Context appCtx = requireContext().getApplicationContext();
                com.zm920.androidserver.service.ProcessManager pMgr =
                    com.zm920.androidserver.service.ProcessManager.getInstance(appCtx.getFilesDir());
                if (pMgr == null) return;
                String[] oldProcs = {"php-fpm-8.5.1", "php-fpm-8.3.0", "php-fpm-7.4.0", "php-fpm-" + ver};
                for (String proc : oldProcs) {
                    if (pMgr.isAlive(proc)) pMgr.stop(proc);
                }
                // 设标记让 DashboardFragment 自动检测并启动
                requireActivity().getSharedPreferences("server_settings", 0)
                    .edit().putBoolean("auto_start_php", true).apply();
            } catch (Exception e) {
                android.util.Log.e("PHP", "restartPhpVersion", e);
            }
        }).start();
    }

    private void startDownload(String comp, String version) {
        V v = views.get(comp);
        if (v == null) return;
        if (!PermissionUtil.ensureStoragePermission(requireActivity())) return;
        View view = safeView();
        if (view == null) return;
        
        if (v == null) return;
        LinearLayout progLay = view.findViewById(v.progLay);
        ProgressBar progBar = view.findViewById(v.progBar);
        TextView progText = view.findViewById(v.progText);
        TextView pauseBtn = view.findViewById(v.pause);
        progLay.setVisibility(View.VISIBLE);
        progBar.setProgress(0);
        progText.setText("连接中...");
        pauseBtn.setVisibility(View.VISIBLE);
        pauseBtn.setText("暂停");
        DownloadTaskHolder.PROGRESS.put(comp, new int[]{0, 0, 0});
        progressHandler.removeCallbacks(progressPoller);
        progressHandler.postDelayed(progressPoller, 200);

        BinaryDownloader.DownloadTask task = downloader.createTask(comp, version,
                (pct, dled, total) -> DownloadTaskHolder.PROGRESS.put(comp, new int[]{pct, (int) dled, (int) total}),
                new BinaryDownloader.CompleteCallback() {
                    @Override public void onSuccess(File f) {
                        prefs.edit().putString("active_" + comp.toLowerCase(), version).apply();
                        tasks.remove(comp);
                        DownloadTaskHolder.PROGRESS.remove(comp);
                        DownloadTaskHolder.COMPLETED.add(comp);
                        if ("phpMyAdmin".equals(comp)) promptDeployPma(version);
                        if ("PHP".equals(comp)) {
                            try { deployBundledPhpExtensions(); } catch (Exception e) { android.util.Log.e("PHP", "deployBundledPhpExtensions", e); }
                            // 自动重启 PHP 使新版本生效
                            try {
                                android.content.Context appCtx = requireContext().getApplicationContext();
                                com.zm920.androidserver.service.ProcessManager pm = 
                                    com.zm920.androidserver.service.ProcessManager.getInstance(appCtx.getFilesDir());
                                if (pm != null) {
                                    String procName = "php-fpm-8.5.1";
                                    if (pm.isAlive(procName)) pm.stop(procName);
                                    procName = "php-fpm-8.3.0";
                                    if (pm.isAlive(procName)) pm.stop(procName);
                                    procName = "php-fpm-" + version;
                                    if (pm.isAlive(procName)) pm.stop(procName);
                                }
                            } catch (Exception ignored) {}
                        }
                    }
                    @Override public void onError(String msg) {
                        tasks.remove(comp);
                        DownloadTaskHolder.PROGRESS.remove(comp);
                        DownloadTaskHolder.ERRORS.put(comp, msg);
                    }
                });
        tasks.put(comp, task);
        task.start();
    }

    private android.app.Dialog pmaProgressDialog;
    private android.app.Dialog dbListDialog;
    private TextView pmaProgressText;

    private void showPmaProgress(String text) {
        if (!isAdded() || getContext() == null) return;
        requireActivity().runOnUiThread(() -> {
            try {
                if (pmaProgressDialog == null) {
                    LinearLayout layout = new LinearLayout(requireContext());
                    layout.setOrientation(LinearLayout.VERTICAL);
                    layout.setPadding(dp(24), dp(20), dp(24), dp(12));

                    ProgressBar progressBar = new ProgressBar(requireContext());
                    progressBar.setIndeterminate(true);
                    layout.addView(progressBar);

                    pmaProgressText = new TextView(requireContext());
                    pmaProgressText.setTextSize(14f);
                    pmaProgressText.setTextColor(0xff333333);
                    pmaProgressText.setPadding(0, dp(14), 0, 0);
                    layout.addView(pmaProgressText);

                    pmaProgressDialog = new ThemedDialogBuilder(requireContext())
                            .setTitle("部署 phpMyAdmin")
                            .setContentView(layout)
                            .setCancelable(false)
                            .setCanceledOnTouchOutside(false)
                            .show();
                }
                if (pmaProgressText != null) pmaProgressText.setText(text);
                if (pmaProgressDialog != null && !pmaProgressDialog.isShowing()) pmaProgressDialog.show();
            } catch (Exception ignored) {}
        });
    }

    private void hidePmaProgress() {
        if (!isAdded()) return;
        requireActivity().runOnUiThread(() -> {
            try {
                if (pmaProgressDialog != null && pmaProgressDialog.isShowing()) {
                    pmaProgressDialog.dismiss();
                }
            } catch (Exception ignored) {}
        });
    }

    private void promptDeployPma(String version) {
        if (getContext() == null || safeView() == null) return;
        // 检查必需组件是否已安装
        boolean phpInstalled = downloader.getInstalledVersion("PHP") != null
                && !downloader.getInstalledVersion("PHP").isEmpty();
        boolean nginxInstalled = downloader.getInstalledVersion("Nginx") != null
                && !downloader.getInstalledVersion("Nginx").isEmpty();
        if (!phpInstalled || !nginxInstalled) {
            StringBuilder sb = new StringBuilder("phpMyAdmin 需要以下组件：");
            if (!phpInstalled) sb.append(" PHP");
            if (!nginxInstalled) sb.append(" Nginx");
            sb.append("，请先在「组件」页下载");
            toast(sb.toString());
            return;
        }
        // 下载后自动初始化配置
        deployPma(version);
    }

    private void deployPma(String version) {
        if (getContext() == null) return;
        new Thread(() -> {
            try {
                File privateDir = new File(requireContext().getFilesDir(), "bin/phpmyadmin/" + version);
                File indexFile = new File(privateDir, "index.php");

                showPmaProgress("检查部署目录...");
                if (!privateDir.exists() || !indexFile.exists()) {
                    hidePmaProgress();
                    toast("phpMyAdmin 文件未找到，请重新下载");
                    return;
                }

                showPmaProgress("正在写入配置...");
                writePmaConfig(privateDir);

                hidePmaProgress();
                // Nginx 运行中则刷新配置
                if (MainActivity.webServerRef != null && MainActivity.webServerRef.isRunning()) {
                    restartNginxAndRefreshConfig();
                }
                checkAndPromptPmaReady("phpMyAdmin 部署完成");
            } catch (Exception e) {
                hidePmaProgress();
                toast("部署失败: " + e.getMessage());
            }
        }).start();
    }

    private void openPhpMyAdminFromComponents() {
        if (getContext() == null) return;
        String version = downloader.getInstalledVersion("phpMyAdmin");
        if (version == null || version.isEmpty()) {
            toast("请先下载 phpMyAdmin");
            return;
        }
        new Thread(() -> {
            try {
                File privateDir = new File(requireContext().getFilesDir(), "bin/phpmyadmin/" + version);
                File indexFile = new File(privateDir, "index.php");
                File configFile = new File(privateDir, "config.inc.php");

                if (!indexFile.exists()) {
                    toast("phpMyAdmin 文件未找到，请重新下载");
                    return;
                }

                if (!configFile.exists()) {
                    writePmaConfig(privateDir);
                }
                if (!checkPmaServicesReady()) {
                    return;
                }
                waitAndOpenPma();
            } catch (Exception e) {
                toast("打开失败: " + e.getMessage());
            }
        }).start();
    }

    private void waitAndOpenPma() {
        String localIp = com.zm920.androidserver.network.NetworkUtil.getLocalIpAddress();
        String url = "http://" + localIp + ":15237/";
        for (int i = 0; i < 12; i++) {
            try {
                Thread.sleep(500);
                java.net.Socket s = new java.net.Socket("127.0.0.1", 15237);
                s.close();
                openUrl(url);
                return;
            } catch (Exception ignored) {}
        }
        openUrl(url);
    }

    private boolean checkPmaServicesReady() {
        try {
            android.content.Context appContext = requireContext().getApplicationContext();
            ProcessManager pm = ProcessManager.getInstance(appContext.getFilesDir());
            if (pm == null) return false;
            SharedPreferences sp = prefs;
            BinaryDeployer bd = new BinaryDeployer(appContext, sp);
            bd.deployAll();
            String privateDataDir = appContext.getFilesDir().getAbsolutePath() + "/server";
            ConfigGenerator cg = new ConfigGenerator(new File(privateDataDir + "/config"), sp);
            PhpManager phpManager = new PhpManager(appContext, pm, cg, sp, bd, new StartupLogger(appContext));
            boolean phpOk = phpManager.isRunning();
            boolean nginxOk = MainActivity.webServerRef != null && MainActivity.webServerRef.isRunning();
            if (!phpOk || !nginxOk) {
                StringBuilder sb = new StringBuilder("需要启动以下服务：");
                if (!phpOk) sb.append(" PHP");
                if (!nginxOk) sb.append(" Nginx");
                sb.append("，是否前往仪表盘启动？");
                final String msg = sb.toString();
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        new ThemedDialogBuilder(requireContext())
                                .setTitle("服务未就绪")
                                .setMessage(msg)
                                .addButton("取消", false, d -> d.dismiss())
                                .addButton("前往仪表盘", true, d -> {
                                    try {
                                        com.google.android.material.bottomnavigation.BottomNavigationView nav =
                                            requireActivity().findViewById(R.id.bottom_navigation);
                                        if (nav != null) {
                                            nav.setSelectedItemId(R.id.dashboardFragment);
                                        } else {
                                            androidx.navigation.Navigation.findNavController(requireView())
                                                .navigate(R.id.dashboardFragment);
                                        }
                                    } catch (Exception e) {
                                        try { androidx.navigation.Navigation.findNavController(requireView())
                                            .navigate(R.id.dashboardFragment); } catch (Exception ignored) {}
                                    }
                                    d.dismiss();
                                })
                                .show();
                    });
                }
                return false;
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void checkAndPromptPmaReady(String title) {
        try {
            android.content.Context appContext = requireContext().getApplicationContext();
            ProcessManager pm = ProcessManager.getInstance(appContext.getFilesDir());
            if (pm == null) return;
            SharedPreferences sp = prefs;
            BinaryDeployer bd = new BinaryDeployer(appContext, sp);
            bd.deployAll();
            String privateDataDir = appContext.getFilesDir().getAbsolutePath() + "/server";
            ConfigGenerator cg = new ConfigGenerator(new File(privateDataDir + "/config"), sp);
            PhpManager phpManager = new PhpManager(appContext, pm, cg, sp, bd, new StartupLogger(appContext));
            boolean phpOk = phpManager.isRunning();
            boolean nginxOk = MainActivity.webServerRef != null && MainActivity.webServerRef.isRunning();
            if (phpOk && nginxOk) {
                toast(title + "，服务已就绪");
            } else {
                StringBuilder sb = new StringBuilder(title + "。");
                if (!phpOk) sb.append(" PHP 未启动");
                if (!nginxOk) sb.append(" Nginx 未启动");
                sb.append("，请先在仪表盘启动");
                final String msg = sb.toString();
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        new ThemedDialogBuilder(requireContext())
                                .setTitle("提示")
                                .setMessage(msg)
                                .addButton("取消", false, d -> d.dismiss())
                                .addButton("前往仪表盘", true, d -> {
                                    try {
                                        com.google.android.material.bottomnavigation.BottomNavigationView nav =
                                            requireActivity().findViewById(R.id.bottom_navigation);
                                        if (nav != null) {
                                            nav.setSelectedItemId(R.id.dashboardFragment);
                                        } else {
                                            androidx.navigation.Navigation.findNavController(requireView())
                                                .navigate(R.id.dashboardFragment);
                                        }
                                    } catch (Exception e) {
                                        try { androidx.navigation.Navigation.findNavController(requireView())
                                            .navigate(R.id.dashboardFragment); } catch (Exception ignored) {}
                                    }
                                    d.dismiss();
                                })
                                .show();
                    });
                }
            }
        } catch (Exception e) {
            toast(title + "，但检查服务状态失败");
        }
    }

    private void writePmaConfig(File destDir) throws Exception {
        String config = "<?php\n"
                + "$cfg['blowfish_secret'] = '" + System.currentTimeMillis() + "';\n"
                + "$cfg['DefaultLang'] = 'zh_CN';\n"
                + "$cfg['Lang'] = 'zh_CN';\n"
                + "$cfg['Servers'][1]['auth_type'] = 'cookie';\n"
                + "$cfg['Servers'][1]['host'] = '127.0.0.1';\n"
                + "$cfg['Servers'][1]['port'] = '" + prefs.getInt("mysql_port", 3306) + "';\n"
                + "$cfg['Servers'][1]['AllowNoPassword'] = false;\n"
                + "$cfg['Servers'][1]['connect_type'] = 'tcp';\n"
                + "?>";
        FileWriter fw = new FileWriter(new File(destDir, "config.inc.php"));
        fw.write(config);
        fw.close();
    }

    private void restartNginxForPma() {
        if (MainActivity.webServerRef == null) return;
        try {
            MainActivity.webServerRef.stop();
            Thread.sleep(1000);
            MainActivity.webServerRef.start();
        } catch (Exception ignored) {}
    }

    private void openUrl(String url) {
        androidx.fragment.app.FragmentActivity activity = getActivity();
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        activity.runOnUiThread(() -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            } catch (Exception e) {
                toast("打开失败: " + e.getMessage());
            }
        });
    }

    private void openFileEditor(String title, File file, boolean editable) {
        if (getContext() == null) return;
        SimpleFileDialog.show(requireContext(), title, file, editable);
    }

    private File configDir() {
        String dataDir = requireContext().getFilesDir().getAbsolutePath() + "/server";
        File dir = new File(dataDir, "config");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    private File configFile(String name) {
        File dir = configDir();
        if ("php-fpm.conf".equals(name) || "php_errors.log".equals(name) || "php.ini".equals(name)) {
            File phpDir = phpVersionDir();
            phpDir.mkdirs();
            File file = new File(phpDir, name);
            try {
                if (!file.exists()) {
                    if ("php-fpm.conf".equals(name)) {
                        writeText(file, "[global]\nerror_log=" + new File(phpDir, "php-fpm-error.log").getAbsolutePath() + "\n\n[www]\nlisten=127.0.0.1:9000\npm=dynamic\npm.max_children=6\npm.start_servers=2\npm.min_spare_servers=1\npm.max_spare_servers=3\n");
                    } else if ("php_errors.log".equals(name)) {
                        file.createNewFile();
                    }
                }
            } catch (Exception ignored) {}
            return file;
        }
        File file = new File(dir, name);
        try {
            if (!file.exists()) {
                if ("mysqld.log".equals(name) || "redis.log".equals(name)) {
                    file.createNewFile();
                }
            }
        } catch (Exception ignored) {}
        return file;
    }
private File nginxAccessLog() { return new File(configDir(), "var/log/nginx/access.log"); }
    private File nginxErrorLog() { return new File(configDir(), "var/log/nginx/error.log"); }


    private void showNginxSiteConfig() {
        // 扫描 data_dir，自动注册未在列表中的网站
        SiteScanner.scanAndRegister(requireContext().getApplicationContext(), prefs);
        Set<String> sites = prefs.getStringSet("sites", new java.util.HashSet<>());
        if (sites == null || sites.isEmpty()) {
            toast("暂无站点，请先在设置页添加网站");
            return;
        }
        String[] items = new String[sites.size()];
        final String[] keys = new String[sites.size()];
        int i = 0;
        for (String site : sites) {
            String[] p = site.split("\\|");
            String name = p.length > 0 ? p[0] : site;
            String port = p.length > 1 ? p[1] : "8080";
            items[i] = "站点:" + name + "(端口:" + port + ")";
            keys[i] = site;
            i++;
        }
        // 用 ListView 包装成主题对话框风格
        final android.widget.ListView listView = new android.widget.ListView(requireContext());
        listView.setAdapter(new android.widget.ArrayAdapter<>(requireContext(),
                android.R.layout.simple_list_item_1, items));
        android.widget.LinearLayout.LayoutParams listLp = new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                (int) (360 * requireContext().getResources().getDisplayMetrics().density));
        listView.setLayoutParams(listLp);
        final android.app.Dialog listDialog = new ThemedDialogBuilder(requireContext())
                .setTitle("选择站点")
                .setContentView(listView)
                .addButton("取消", false, d -> d.dismiss())
                .show();
        listView.setOnItemClickListener((p, v, pos, id) -> {
            listDialog.dismiss();
            showNginxSiteEditDialog(keys[pos]);
        });
    }

    private void showNginxSiteEditDialog(String site) {
        String[] p = site.split("\\|", -1);
        String name = p.length > 0 ? p[0] : "";
        String port = p.length > 1 ? p[1] : "8080";
        String siteRoot = p.length > 2 ? p[2] : "";
        String siteRewrite = p.length > 3 ? p[3].replace("\u0001", "|") : "";

        LinearLayout layout = new LinearLayout(requireContext());
        layout.setOrientation(LinearLayout.VERTICAL);

        // 运行目录编辑框（提前声明，供按钮回调使用）
        final EditText inputRoot = new EditText(requireContext());
        inputRoot.setHint("运行目录，例如 " + android.os.Environment.getExternalStorageDirectory().getAbsolutePath() + "/project/public");
        inputRoot.setText(siteRoot);
        inputRoot.setInputType(InputType.TYPE_CLASS_TEXT);

        // 选择目录按钮放在最上面
        siteKey = site;
        btnPickDir = new TextView(requireContext());
        btnPickDir.setText("选择目录");
        btnPickDir.setBackgroundResource(R.drawable.bg_action_btn);
        btnPickDir.setTextColor(0xff1a73e8);
        btnPickDir.setPadding(dp(8), dp(6), dp(8), dp(6));
        btnPickDir.setClickable(true);
        btnPickDir.setFocusable(true);
        btnPickDir.setOnClickListener(v -> {
            pendingSiteKeyForRoot = site;
            pendingRootEditText = inputRoot;
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            safLauncher.launch(intent);
        });
        layout.addView(btnPickDir);

        // 运行目录编辑框加入布局
        layout.addView(inputRoot);

        EditText inputRewrite = new EditText(requireContext());
        inputRewrite.setHint("伪静态，例如 location / { try_files $uri $uri/ /index.php?$query_string; }");
        inputRewrite.setText(siteRewrite);
        inputRewrite.setMinLines(5);
        inputRewrite.setMaxLines(6);
        inputRewrite.setGravity(android.view.Gravity.TOP | android.view.Gravity.START);
        inputRewrite.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        layout.addView(inputRewrite);

        new ThemedDialogBuilder(requireContext())
                .setTitle("配置站点: " + name)
                .setContentView(layout)
                .addButton("取消", false, d -> d.dismiss())
                .addButton("保存", true, d -> {
                    String finalRoot = inputRoot.getText().toString().trim();
                    removeSiteFromConfig(site);
                    saveSiteToConfig(name, port, finalRoot, inputRewrite.getText().toString());
                    restartNginxAndRefreshConfig();
                    toast("站点配置已保存");
                    d.dismiss();
                })
                .show();
    }

    private void removeSiteFromConfig(String site) {
        Set<String> sites = new java.util.HashSet<>(prefs.getStringSet("sites", new java.util.HashSet<>()));
        sites.remove(site);
        prefs.edit().putStringSet("sites", sites).apply();
    }

    private void saveSiteToConfig(String name, String port, String root, String rewrite) {
        Set<String> sites = new java.util.HashSet<>(prefs.getStringSet("sites", new java.util.HashSet<>()));
        String encodedRewrite = rewrite == null ? "" : rewrite.replace("|", "\u0001");
        sites.add(name + "|" + port + "|" + (root == null ? "" : root) + "|" + encodedRewrite);
        prefs.edit().putStringSet("sites", sites).apply();
    }

    private void restartNginxAndRefreshConfig() {
        if (getContext() == null) return;
        final android.content.Context appContext = requireContext().getApplicationContext();
        new Thread(() -> {
            try {
                String dataDir = appContext.getFilesDir().getAbsolutePath() + "/server";
                com.zm920.androidserver.server.WebServer ws = MainActivity.webServerRef;
                if (ws != null && ws.isRunning()) {
                    ws.stop();
                    Thread.sleep(2000);
                    ws.start();
                }
            } catch (Exception e) {
                android.util.Log.e("DownloadFragment", "nginx restart failed", e);
            }
        }).start();
    }

    private void showPhpExtensionsDialog() {
        if (getContext() == null) return;
        try { deployBundledPhpExtensions(); } catch (Exception e) { toast("扩展部署检查失败: " + e.getMessage()); }
        // 检查扩展文件是否已下载（PHP 下载后自动部署）
        if (!phpExtensionDir().exists() || phpExtensionDir().listFiles() == null || phpExtensionDir().listFiles().length == 0) {
            ToastUtil.showLong(getContext(), "请先在「组件」页下载 PHP 后自动获取扩展");
            return;
        }
        LinearLayout root = new LinearLayout(requireContext());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(8), dp(16), dp(4));

        TextView tip = new TextView(requireContext());
        tip.setText("勾选要启用的扩展,灰项不可用");
        tip.setTextSize(13f);
        tip.setTextColor(requireContext().getColor(R.color.text_secondary));
        android.widget.LinearLayout.LayoutParams tipLp = new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        tipLp.bottomMargin = (int) (10 * requireContext().getResources().getDisplayMetrics().density);
        tip.setLayoutParams(tipLp);
        root.addView(tip);

        LinearLayout listWrap = new LinearLayout(requireContext());
        listWrap.setOrientation(LinearLayout.VERTICAL);
        listWrap.setBackgroundResource(R.drawable.bg_field);
        listWrap.setPadding(dp(12), dp(12), dp(12), dp(12));

        Map<String, android.widget.CheckBox> checks = new LinkedHashMap<>();
        Set<String> enabled = getEnabledPhpExtensions();
        boolean hasSavedPhpExtConfig = prefs.contains("php_enabled_extensions_" + getPhpVersion());
        for (String ext : PHP_EXTENSIONS) {
            android.widget.CheckBox cb = new android.widget.CheckBox(requireContext());
            boolean selectable = isPhpExtensionSelectable(ext);
            cb.setText(ext + phpExtensionStatusLabel(ext));
            boolean defaultChecked = !hasSavedPhpExtConfig && isPhpBuiltinExtension(ext);
            cb.setChecked(selectable && (enabled.contains(ext) || defaultChecked));
            cb.setEnabled(selectable);
            if (!selectable) cb.setAlpha(0.65f);
            listWrap.addView(cb);
            checks.put(ext, cb);
        }
        root.addView(listWrap);

        // 把内容装进 ScrollView 防止选项多时弹窗过高
        final android.widget.ScrollView extScroll = new android.widget.ScrollView(requireContext());
        extScroll.setLayoutParams(new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                (int) (400 * requireContext().getResources().getDisplayMetrics().density)));
        extScroll.addView(root);

        new ThemedDialogBuilder(requireContext())
                .setTitle("PHP 扩展组件")
                .setContentView(extScroll)
                .addButton("关闭", false, d -> d.dismiss())
                .addButton("查看配置", false, d -> {
                    openFileEditor("php.ini", configFile("php.ini"), true);
                    d.dismiss();
                })
                .addButton("保存并验证", true, d -> {
                    LinkedHashSet<String> selected = new LinkedHashSet<>();
                    for (Map.Entry<String, android.widget.CheckBox> entry : checks.entrySet()) {
                        if (entry.getValue().isChecked() && isPhpExtensionSelectable(entry.getKey())) {
                            selected.add(entry.getKey());
                        }
                    }
                    try {
                        saveEnabledPhpExtensions(selected);
                        toast("扩展配置已保存，开始验证 PHP");
                        verifyPhpExtensionsAsync(selected);
                    } catch (Exception e) {
                        toast("保存失败: " + e.getMessage());
                    }
                    d.dismiss();
                })
                .show();
    }

    private Set<String> getEnabledPhpExtensions() {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        String raw = prefs.getString("php_enabled_extensions_" + getPhpVersion(), "");
        if (raw == null || raw.trim().isEmpty()) return result;
        try {
            org.json.JSONArray arr = new org.json.JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) result.add(arr.getString(i));
        } catch (Exception e) {
            // 兼容旧格式（逗号分隔）
            for (String part : raw.split(",")) {
                String ext = part.trim();
                if (!ext.isEmpty()) result.add(ext);
            }
        }
        return result;
    }

    private void deployBundledPhpExtensions() throws Exception {
        // 从 PHP 下载目录复制扩展 .so 文件
        String phpVer = getPhpVersion();
        File phpDir = new File(requireContext().getFilesDir(), "bin/php/" + phpVer + "/lib");
        if (!phpDir.exists()) return;
        File dir = phpExtensionDir();
        if (!dir.exists()) dir.mkdirs();
        File[] soFiles = phpDir.listFiles();
        if (soFiles == null) return;
        byte[] buf = new byte[8192];
        for (File src : soFiles) {
            if (!src.getName().endsWith(".so")) continue;
            File dst = new File(dir, src.getName());
            // 总是覆盖：避免旧版本/错误 API 的扩展残留，例如 PHP 8.5.1 的 redis.so
            try (FileInputStream is = new FileInputStream(src);
                 FileOutputStream os = new FileOutputStream(dst)) {
                int n;
                while ((n = is.read(buf)) != -1) os.write(buf, 0, n);
            }
        }
    }

    private boolean isPhpExtensionSelectable(String ext) {
        if (PHP_DISPLAY_ONLY_EXTENSIONS.contains(ext)) return false;
        if (PHP_DISABLED_EXTENSIONS.contains(ext)) return false;
        if (isPhpBuiltinExtension(ext)) return true;
        return findPhpExtensionSo(ext).exists();
    }

    private String getPhpVersion() {
        String v = prefs.getString("active_php", "8.5.1");
        return (v == null || v.isEmpty()) ? "8.5.1" : v;
    }

    private File phpVersionDir() {
        return new File(configDir(), "php/" + getPhpVersion());
    }

    /** PHP 扩展部署目录：按完整版本分文件夹，如 config/php_extensions/{version}/ */
    private File phpExtensionDir() {
        return new File(new File(configDir(), "php_extensions"), getPhpVersion());
    }

    private File findPhpExtensionSo(String ext) {
        return new File(phpExtensionDir(), ext + ".so");
    }

    private boolean isPhpBuiltinExtension(String ext) {
        if (PHP_BUILTIN_EXTENSIONS.contains(ext)) return true;
        String ver = getPhpVersion();
        // PHP 8.3.0 的 gd 已编译进 php-cgi；PHP 8.5.1 使用包内 lib/gd.so。
        if ("gd".equals(ext) && ver.startsWith("8.3.")) return true;
        return false;
    }

    private String phpExtensionStatusLabel(String ext) {
        if (PHP_DISPLAY_ONLY_EXTENSIONS.contains(ext)) return "  内置能力展示，不写入 php.ini";
        if (PHP_DISABLED_EXTENSIONS.contains(ext)) return "  已禁用";
        if (isPhpBuiltinExtension(ext)) return "  内置可启用";
        File so = findPhpExtensionSo(ext);
        if (so.exists() && so.length() > 0) return "  外部扩展已部署";
        return "  外部扩展缺失，未找到 " + ext + ".so";
    }

    private void saveEnabledPhpExtensions(Set<String> selected) throws Exception {
        deployBundledPhpExtensions();
        String json = new org.json.JSONArray(selected).toString();
        prefs.edit().putString("php_enabled_extensions_" + getPhpVersion(), json).apply();
        ensurePhpIniWithExtensions(selected);
    }

    private void ensurePhpIniWithExtensions(Set<String> selected) throws Exception {
        File ini = new File(phpVersionDir(), "php.ini");
        phpVersionDir().mkdirs();
        new File(phpVersionDir(), "tmp").mkdirs();
        new File(phpVersionDir(), "opcache").mkdirs();
        if (!ini.exists()) {
            new ConfigGenerator(configDir(), prefs).generatePhpIni(getPhpVersion());
        }
        String content = readText(ini);
        content = content.replaceAll("(?m)^\\s*extension\\s*=.*(?:\\r?\\n)?", "");
        content = content.replaceAll("(?m)^\\s*zend_extension\\s*=.*(?:\\r?\\n)?", "");
        content = content.replaceAll("(?m)^\\s*extension_dir\\s*=.*(?:\\r?\\n)?", "");
        if (!content.endsWith("\n")) content += "\n";
        content += "\n; managed by AndroidServer\n";
        content += "extension_dir=\"" + phpExtensionDir().getAbsolutePath() + "\"\n";
        content += "opcache.file_cache=\"" + new File(phpVersionDir(), "opcache").getAbsolutePath() + "\"\n";
        content += "opcache.lockfile_path=\"" + new File(phpVersionDir(), "opcache").getAbsolutePath() + "\"\n";
        content += "sys_temp_dir=\"" + new File(phpVersionDir(), "tmp").getAbsolutePath() + "\"\n";
        content += "session.save_path=\"" + new File(phpVersionDir(), "tmp").getAbsolutePath() + "\"\n";
        for (String ext : selected) {
            // 编译进 PHP 的内置扩展不需要 extension= 指令
            if (!isPhpBuiltinExtension(ext)) {
                content += "extension=" + ext + ".so\n";
            }
        }
        writeText(ini, content);
    }

    private void verifyPhpExtensionsAsync(Set<String> selected) {
        final android.content.Context appContext = requireContext().getApplicationContext();
        final androidx.fragment.app.FragmentActivity activity = getActivity();
        final String privateDataDir = appContext.getFilesDir().getAbsolutePath() + "/server";
        if (activity == null) return;
        new Thread(() -> {
            try {
                ProcessManager pm = ProcessManager.getInstance(appContext.getFilesDir());
                if (pm == null) {
                    activity.runOnUiThread(() -> toast("进程管理器未初始化"));
                    return;
                }
                deployBundledPhpExtensions();
                com.zm920.androidserver.server.BinaryDeployer bd = new com.zm920.androidserver.server.BinaryDeployer(appContext, prefs);
                bd.deployAll();
                // 强制重写 php.ini，确保扩展配置一定写入
                ensurePhpIniWithExtensions(selected);
                com.zm920.androidserver.server.PhpManager phpManager = new com.zm920.androidserver.server.PhpManager(
                        appContext,
                        pm,
                        new ConfigGenerator(new File(privateDataDir + "/config"), prefs),
                        prefs,
                        bd,
                        new StartupLogger(appContext)
                );
                phpManager.stop();
                try { Thread.sleep(1200); } catch (Exception ignored) {}
                boolean ok = phpManager.startWithExtensions(selected, PHP_BUILTIN_EXTENSIONS);
                if (ok) {
                    // 验证扩展会重启并保持 PHP 运行；同步仪表盘开关状态，避免“运行中但开关关闭”。
                    prefs.edit().putBoolean("auto_start_php", true).putBoolean("starting_php", false).apply();
                    String probeResult = verifyPhpExtensionsByHttp(appContext, selected);
                    if (probeResult.startsWith("OK:")) {
                        String msg = "PHP 验证通过，已启用扩展: " + probeResult.substring(3);
                        activity.runOnUiThread(() -> toast(msg));
                        return;
                    }
                    String report = buildPhpValidationReport(appContext) + "\n--- HTTP 探针结果 ---\n" + probeResult + "\n";
                    activity.runOnUiThread(() -> showPhpValidationFailure(report));
                    return;
                }
                String report = buildPhpValidationReport(appContext);
                activity.runOnUiThread(() -> showPhpValidationFailure(report));
            } catch (Exception e) {
                activity.runOnUiThread(() -> toast("PHP 验证异常: " + e.getMessage()));
            }
        }).start();
    }

    private String verifyPhpExtensionsByHttp(android.content.Context appContext, Set<String> selected) {
        try {
            String phpVer = getPhpVersion();
            File phpBinFile = new File(appContext.getFilesDir(), "bin/php/" + phpVer + "/bin/php-cgi");
            if (!phpBinFile.exists() || phpBinFile.length() <= 0) {
                return "本地 PHP 二进制不存在: " + phpBinFile.getAbsolutePath();
            }
            String phpBin = phpBinFile.getAbsolutePath();
            String dataDir = appContext.getFilesDir().getAbsolutePath() + "/server";
            File configDir = new File(dataDir, "config");
            File tmpScript = new File(configDir, "ext_probe_local.php");
            StringBuilder php = new StringBuilder();
            php.append("<?php\n");
            php.append("$exts = array(");
            int idx = 0;
            for (String ext : selected) {
                if (PHP_DISPLAY_ONLY_EXTENSIONS.contains(ext) || PHP_DISABLED_EXTENSIONS.contains(ext)) continue;
                if (idx++ > 0) php.append(",");
                php.append("'").append(ext.replace("'", "")).append("'");
            }
            php.append(");\n");
            php.append("$loaded = array(); $missing = array();\n");
            php.append("foreach ($exts as $e) {\n");
            php.append("  $runtimeName = ($e === 'sg11') ? 'SourceGuardian' : $e;\n");
            php.append("  if (extension_loaded($runtimeName)) $loaded[] = $e; else $missing[] = $e;\n");
            php.append("}\n");
            php.append("echo 'probe_ext_dir=' . ini_get('extension_dir') . '\n';\n");
            php.append("echo 'probe_ini_file=' . php_ini_loaded_file() . '\n';\n");
            php.append("echo 'probe_gd_exists=' . (file_exists(ini_get('extension_dir') . '/gd.so') ? 'yes' : 'no') . '\n';\n");
            php.append("echo 'probe_redis_exists=' . (file_exists(ini_get('extension_dir') . '/redis.so') ? 'yes' : 'no') . '\n';\n");
            php.append("echo 'probe_sg11_exists=' . (file_exists(ini_get('extension_dir') . '/sg11.so') ? 'yes' : 'no') . '\n';\n");
            php.append("echo 'probe_sourceguardian_loaded=' . (extension_loaded('SourceGuardian') ? 'yes' : 'no') . '\n';\n");
            php.append("if (extension_loaded('gd') && function_exists('gd_info')) { echo 'gd_info=ok\n'; } else { echo 'gd_info=missing\n'; }\n");
            php.append("echo 'loaded=' . implode(',', $loaded) . '\n';\n");
            php.append("echo 'missing=' . implode(',', $missing) . '\n';\n");
            php.append("?>");
            writeText(tmpScript, php.toString());
            java.util.List<String> cmd = new java.util.ArrayList<>();
            String arch = System.getProperty("os.arch", "");
            cmd.add(arch.contains("64") ? "/system/bin/linker64" : "/system/bin/linker");
            cmd.add(phpBin);
            cmd.add("-n");
            cmd.add("-c");
            cmd.add(new File(phpVersionDir(), "php.ini").getAbsolutePath());
            cmd.add("-d");
            cmd.add("extension_dir=" + phpExtensionDir().getAbsolutePath());
            // 不重复加 -d extension=，完全由 php.ini 管理
            cmd.add(tmpScript.getAbsolutePath());

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(configDir);
            pb.redirectErrorStream(true);
            String[] env = new BinaryDeployer(appContext, prefs).buildEnv();
            for (String e : env) {
                int pos = e.indexOf('=');
                if (pos > 0) pb.environment().put(e.substring(0, pos), e.substring(pos + 1));
            }
            File sg11Shim = new File(appContext.getFilesDir(), "bin/php/" + phpVer + "/dep/libglibc-shim.so");
            if ((phpVer.startsWith("8.3.") || phpVer.startsWith("8.5.") || phpVer.startsWith("7.4.")) && sg11Shim.isFile() && sg11Shim.length() > 0) {
                pb.environment().put("LD_PRELOAD", sg11Shim.getAbsolutePath());
            }
            Process process = pb.start();
            // 给 process 加最长 15 秒超时，避免子进程卡死时永久阻塞
            String body;
            try (java.io.InputStream is = process.getInputStream()) {
                byte[] buf = new byte[8192];
                java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                long deadline = System.currentTimeMillis() + 15000L;
                int n;
                while ((n = is.read(buf)) > 0) {
                    baos.write(buf, 0, n);
                    if (System.currentTimeMillis() > deadline) {
                        try { process.destroyForcibly(); } catch (Exception ignored) {}
                        break;
                    }
                }
                body = baos.toString("UTF-8");
            }
            process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);

            String loaded = "";
            String missing = "";
            String gdInfo = "";
            String probeExtDir = "";
            String probeIniFile = "";
            String probeGdExists = "";
            String probeRedisExists = "";
            String probeSg11Exists = "";
            String probeSourceGuardianLoaded = "";
            for (String line : body.split("\r?\n")) {
                if (line.startsWith("loaded=")) loaded = line.substring(7).trim();
                else if (line.startsWith("missing=")) missing = line.substring(8).trim();
                else if (line.startsWith("gd_info=")) gdInfo = line.substring(8).trim();
                else if (line.startsWith("probe_ext_dir=")) probeExtDir = line.substring(14).trim();
                else if (line.startsWith("probe_ini_file=")) probeIniFile = line.substring(15).trim();
                else if (line.startsWith("probe_gd_exists=")) probeGdExists = line.substring(16).trim();
                else if (line.startsWith("probe_redis_exists=")) probeRedisExists = line.substring(18).trim();
                else if (line.startsWith("probe_sg11_exists=")) probeSg11Exists = line.substring(18).trim();
                else if (line.startsWith("probe_sourceguardian_loaded=")) probeSourceGuardianLoaded = line.substring(28).trim();
            }
            if (loaded.contains("gd") && !"ok".equals(gdInfo)) {
                return "gd 扩展已声明加载，但 gd_info() 不可用"
                        + "\n本地输出:\n" + body
                        + "\nextension_dir: " + probeExtDir
                        + "\nphp_ini: " + probeIniFile
                        + "\ngd.so 存在: " + probeGdExists;
            }
            if (missing.isEmpty()) return "OK:" + (loaded.isEmpty() ? "无" : loaded);
            return "以下扩展未真正加载: " + missing
                    + "\n本地输出:\n" + body
                    + "\nextension_dir: " + probeExtDir
                    + "\nphp_ini: " + probeIniFile
                    + "\ngd.so 存在: " + probeGdExists
                    + "\nredis.so 存在: " + probeRedisExists
                    + "\nsg11.so 存在: " + probeSg11Exists
                    + "\nSourceGuardian 加载: " + probeSourceGuardianLoaded;
        } catch (Exception e) {
            return "本地 PHP 探针异常: " + e.getMessage();
        }
    }


    private static class ProbeTarget {
        final File dir;
        final int port;
        final String path;

        ProbeTarget(File dir, int port, String path) {
            this.dir = dir;
            this.port = port;
            this.path = path;
        }
    }

    private ProbeTarget resolvePhpProbeTarget(android.content.Context appContext) {
        String wwwRoot = prefs.getString("data_dir", android.os.Environment.getExternalStorageDirectory().getAbsolutePath() + "/wwwroot/www");
        File pma = new File(wwwRoot, "phpmyadmin");
        if (new File(pma, "index.php").exists()) {
            return new ProbeTarget(pma, 15237, "/ext_probe.php");
        }
        java.util.Set<String> sites = prefs.getStringSet("sites", new java.util.HashSet<>());
        if (sites != null && !sites.isEmpty()) {
            String first = sites.iterator().next();
            String[] parts = first.split("\\|");
            if (parts.length >= 2) {
                try {
                    int port = Integer.parseInt(parts[1]);
                    String siteName = parts[0];
                    return new ProbeTarget(
                            new File(wwwRoot, siteName),
                            port,
                            "/ext_probe.php"
                    );
                } catch (Exception ignored) {}
            }
        }
        return null;
    }

    private int resolvePhpProbePort() {
        java.util.Set<String> sites = prefs.getStringSet("sites", new java.util.HashSet<>());
        if (sites != null && !sites.isEmpty()) {
            String first = sites.iterator().next();
            String[] parts = first.split("\\|");
            if (parts.length >= 2) {
                try {
                    return Integer.parseInt(parts[1]);
                } catch (Exception ignored) {}
            }
        }
        return 15237;
    }

    private String resolvePhpProbePath() {
        return "/php_ext_probe/index.php";
    }

    private String requestLocalHttp(int port, String path) {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(6000);
            java.io.OutputStream os = socket.getOutputStream();
            java.io.InputStream is = socket.getInputStream();
            String req = "GET " + path + " HTTP/1.1\r\n"
                    + "Host: 127.0.0.1\r\n"
                    + "Connection: close\r\n\r\n";
            os.write(req.getBytes());
            os.flush();
            byte[] data = is.readAllBytes();
            String raw = new String(data);
            int split = raw.indexOf("\r\n\r\n");
            String head = split >= 0 ? raw.substring(0, split) : raw;
            String body = split >= 0 ? raw.substring(split + 4) : "";
            String firstLine = head.contains("\r\n") ? head.substring(0, head.indexOf("\r\n")) : head;
            if (!firstLine.contains(" 200 ")) return "HTTP_ERROR:" + firstLine + "\n" + body;
            return body;
        } catch (Exception e) {
            return "HTTP_ERROR:HTTP 探针异常: " + e.getMessage();
        }
    }

    private File resolvePhpErrorLogFile() {
        File verDir = phpVersionDir();
        File direct = new File(verDir, "php_errors.log");
        if (direct.exists() && direct.length() > 0) return direct;
        String dataDir = requireContext().getFilesDir().getAbsolutePath() + "/server";
        File alt = new File(new File(dataDir, "config/php/" + getPhpVersion()), "php_errors.log");
        if (alt.exists()) return alt;
        return direct;
    }

    private String buildPhpValidationReport(android.content.Context appContext) {
        StringBuilder sb = new StringBuilder();
        File ini = new File(phpVersionDir(), "php.ini");
        File err = resolvePhpErrorLogFile();
        sb.append("PHP 扩展验证失败\n\n");
        sb.append("--- 路径状态 ---\n");
        sb.append("php.ini: ").append(ini.getAbsolutePath()).append(" exists=").append(ini.exists()).append(" size=").append(ini.length()).append("\n");
        sb.append("php_errors.log: ").append(err.getAbsolutePath()).append(" exists=").append(err.exists()).append(" size=").append(err.length()).append("\n");
        sb.append("extension_dir: ").append(phpExtensionDir().getAbsolutePath()).append(" exists=").append(phpExtensionDir().exists()).append("\n");
        sb.append("gd.so: ").append(fileState(findPhpExtensionSo("gd"))).append("\n");
        sb.append("redis.so: ").append(fileState(findPhpExtensionSo("redis"))).append("\n");
        sb.append("\n--- php.ini 片段 ---\n");
        sb.append(readMatchingLines(ini, new String[]{"extension_dir", "extension=", "error_log", "log_errors"}));
        sb.append("\n--- php_errors.log ---\n");
        sb.append(readTailSafe(err, 120));
        sb.append("\n--- runtime_logs/php-fpm.log ---\n");
        sb.append(readTailSafe(new File(appContext.getFilesDir(), "runtime_logs/php-fpm-" + getPhpVersion() + ".log"), 200));
        sb.append("\n--- 最近启动失败报告 ---\n");
        sb.append(readLatestStartupFailure(appContext));
        return sb.toString();
    }

    private String fileState(File file) {
        if (file == null) return "null";
        return file.getAbsolutePath() + " exists=" + file.exists() + " size=" + file.length();
    }

    private String readMatchingLines(File file, String[] keys) {
        try {
            if (file == null || !file.exists()) return "(文件不存在)\n";
            List<String> lines = java.nio.file.Files.readAllLines(file.toPath());
            StringBuilder sb = new StringBuilder();
            for (String line : lines) {
                String trim = line.trim();
                for (String key : keys) {
                    if (trim.startsWith(key)) {
                        sb.append(line).append("\n");
                        break;
                    }
                }
            }
            if (sb.length() == 0) return "(未找到相关配置)\n";
            return sb.toString();
        } catch (Exception e) {
            return "读取失败: " + e.getMessage() + "\n";
        }
    }

    private void showPhpValidationFailure(String report) {
        TextView textView = new TextView(requireContext());
        textView.setText(report);
        textView.setTextIsSelectable(true);
        textView.setTextSize(13f);
        textView.setTextColor(requireContext().getColor(R.color.text_secondary));
        textView.setBackgroundResource(R.drawable.bg_field);
        textView.setPadding(dp(12), dp(12), dp(12), dp(12));
        textView.setTypeface(android.graphics.Typeface.MONOSPACE);

        ScrollView scrollView = new ScrollView(requireContext());
        scrollView.addView(textView, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        android.widget.LinearLayout.LayoutParams scLp = new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                (int) (360 * requireContext().getResources().getDisplayMetrics().density));
        scrollView.setLayoutParams(scLp);

        new ThemedDialogBuilder(requireContext())
                .setTitle("PHP 验证失败")
                .setContentView(scrollView)
                .addButton("关闭", false, d -> d.dismiss())
                .addButton("打开 php.ini", false, d -> {
                    openFileEditor("php.ini", configFile("php.ini"), true);
                    d.dismiss();
                })
                .addButton("错误日志", true, d -> {
                    openFileEditor("PHP 错误日志", resolvePhpErrorLogFile(), true);
                    d.dismiss();
                })
                .show();
    }

    private String readTailSafe(File file, int maxLines) {
        try {
            if (file == null || !file.exists() || file.length() <= 0) return "(空)\n";
            List<String> lines = java.nio.file.Files.readAllLines(file.toPath());
            int from = Math.max(0, lines.size() - maxLines);
            StringBuilder sb = new StringBuilder();
            for (int i = from; i < lines.size(); i++) sb.append(lines.get(i)).append("\n");
            return sb.toString();
        } catch (Exception e) {
            return "读取失败: " + e.getMessage() + "\n";
        }
    }

    private String readLatestStartupFailure(android.content.Context appContext) {
        try {
            File dir = new File(appContext.getFilesDir(), "startup_logs");
            File[] files = dir.listFiles((d, name) -> name.startsWith("php-fpm_") && name.endsWith(".log"));
            if (files == null || files.length == 0) return "(没有启动失败报告)\n";
            java.util.Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
            return readTailSafe(files[0], 160);
        } catch (Exception e) {
            return "读取失败: " + e.getMessage() + "\n";
        }
    }


    private void exportAllLogs() {
        if (getContext() == null) return;
        toast("开始导出日志...");
        new Thread(() -> {
            try {
                File filesDir = requireContext().getFilesDir();
                String dataDir = StoragePaths.getInfraRoot(prefs.getString("data_dir", StoragePaths.getDefaultWwwRoot()));
                File logDir = new File(dataDir, "log");
                if (!logDir.exists()) logDir.mkdirs();
                String name = "logs_" + System.currentTimeMillis() + ".zip";
                File zipFile = new File(logDir, name);

                java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(new java.io.FileOutputStream(zipFile));
                // 扫描所有组件目录，只添加 .log 文件
                addLogFilesFromDir(zos, new File(filesDir, "startup_logs"), "startup_logs");
                addLogFilesFromDir(zos, new File(filesDir, "runtime_logs"), "runtime_logs");
                addLogFilesFromDir(zos, new File(filesDir, "server/config"), "config");
                addLogFilesFromDir(zos, new File(filesDir, "logs"), "logs");
                addLogFilesFromDir(zos, new File(filesDir, "cpolar"), "cpolar");
                zos.close();
                toast("日志已导出: " + zipFile.getAbsolutePath());
            } catch (Exception e) {
                toast("导出失败: " + e.getMessage());
            }
        }).start();
    }

    /** 递归扫描目录，只添加 .log 文件到 zip */
    private void addLogFilesFromDir(java.util.zip.ZipOutputStream zos, File dir, String prefix) {
        if (dir == null || !dir.exists() || !dir.isDirectory()) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        byte[] buf = new byte[8192];
        for (File f : files) {
            if (f.isDirectory()) {
                addLogFilesFromDir(zos, f, prefix + "/" + f.getName());
            } else if (f.isFile() && f.length() > 0 && f.getName().endsWith(".log")) {
                try (java.io.FileInputStream fis = new java.io.FileInputStream(f)) {
                    zos.putNextEntry(new java.util.zip.ZipEntry(prefix + "/" + f.getName()));
                    int n;
                    while ((n = fis.read(buf)) > 0) zos.write(buf, 0, n);
                    zos.closeEntry();
                } catch (Exception ignored) {}
            }
        }
    }

    private void addZipFile(java.util.zip.ZipOutputStream zos, File f, String entryName) {
        if (f == null || !f.exists() || !f.isFile() || f.length() <= 0) return;
        byte[] buf = new byte[8192];
        try (java.io.FileInputStream fis = new java.io.FileInputStream(f)) {
            zos.putNextEntry(new java.util.zip.ZipEntry(entryName));
            int n;
            while ((n = fis.read(buf)) > 0) zos.write(buf, 0, n);
            zos.closeEntry();
        } catch (Exception ignored) {}
    }

    private void zipDirToZip(java.util.zip.ZipOutputStream zos, File dir, String prefix) throws Exception {
        if (dir == null || !dir.exists()) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File file : files) {
            String entryName = prefix + "/" + file.getName();
            if (file.isDirectory()) {
                zipDirToZip(zos, file, entryName);
                continue;
            }
            java.io.FileInputStream fis = new java.io.FileInputStream(file);
            zos.putNextEntry(new java.util.zip.ZipEntry(entryName));
            byte[] buf = new byte[8192];
            int len;
            while ((len = fis.read(buf)) > 0) zos.write(buf, 0, len);
            fis.close();
            zos.closeEntry();
        }
    }

    private void promptRestartMariaDbAfterPasswordChange() {
        new ThemedDialogBuilder(requireContext())
                .setTitle("密码已保存")
                .setMessage("是否立即重启 MariaDB 使新密码生效？")
                .addButton("稍后", false, d -> {
                    toast("MariaDB 重启后新密码才会生效");
                    d.dismiss();
                })
                .addButton("立即重启", true, d -> {
                    restartMariaDbNow();
                    d.dismiss();
                })
                .show();
    }

    private void restartMariaDbNow() {
        if (getContext() == null || getActivity() == null) return;
        final android.content.Context appContext = requireContext().getApplicationContext();
        final androidx.fragment.app.FragmentActivity activity = getActivity();
        final String privateDataDir = appContext.getFilesDir().getAbsolutePath() + "/server";
        if (activity == null) return;
        // 显示持续进度对话框，等完成后再切换为结果提示
        final android.app.Dialog[] progressDialog = new android.app.Dialog[1];
        activity.runOnUiThread(() -> {
            try {
                LinearLayout layout = new LinearLayout(requireContext());
                layout.setOrientation(LinearLayout.VERTICAL);
                layout.setGravity(android.view.Gravity.CENTER);
                layout.setPadding(dp(24), dp(20), dp(24), dp(12));
                ProgressBar progressBar = new ProgressBar(requireContext());
                progressBar.setIndeterminate(true);
                layout.addView(progressBar);
                TextView textView = new TextView(requireContext());
                textView.setTextSize(14f);
                textView.setTextColor(0xff333333);
                textView.setPadding(0, dp(14), 0, 0);
                textView.setGravity(android.view.Gravity.CENTER);
                textView.setText("MariaDB 重启中...");
                layout.addView(textView);
                progressDialog[0] = new ThemedDialogBuilder(requireContext())
                        .setTitle("修改密码")
                        .setContentView(layout)
                        .setCancelable(false)
                        .setCanceledOnTouchOutside(false)
                        .show();
            } catch (Exception ignored) {}
        });
        new Thread(() -> {
            try {
                ProcessManager pm = ProcessManager.getInstance(appContext.getFilesDir());
                if (pm == null) {
                    activity.runOnUiThread(() -> {
                        if (progressDialog[0] != null && progressDialog[0].isShowing()) progressDialog[0].dismiss();
                        ToastUtil.showShort(appContext, "进程管理器未初始化");
                    });
                    return;
                }
                DatabaseManager dbm = new DatabaseManager(
                        appContext,
                        pm,
                        new ConfigGenerator(new File(privateDataDir + "/config"), prefs),
                        prefs,
                        new BinaryDeployer(appContext, prefs),
                        new StartupLogger(appContext)
                );
                dbm.stop();
                Thread.sleep(2000);
                boolean ok = dbm.start();
                if (ok && MainActivity.webServerRef != null && MainActivity.webServerRef.isRunning()) {
                    try {
                        MainActivity.webServerRef.stop();
                        Thread.sleep(1000);
                        MainActivity.webServerRef.start();
                    } catch (Exception ignored) {}
                }
                boolean finalOk = ok;
                activity.runOnUiThread(() -> {
                    if (progressDialog[0] != null && progressDialog[0].isShowing()) progressDialog[0].dismiss();
                    ToastUtil.showShort(appContext, finalOk ? "MariaDB 已重启，新密码已生效" : "MariaDB 重启失败，请查看v1日志");
                });
            } catch (Exception e) {
                activity.runOnUiThread(() -> {
                    if (progressDialog[0] != null && progressDialog[0].isShowing()) progressDialog[0].dismiss();
                    ToastUtil.showLong(appContext, "MariaDB 重启异常: " + e.getMessage());
                });
            }
        }).start();
    }

    private void showAddDatabaseDialog() {
        if (getContext() == null) return;

        LinearLayout layout = new LinearLayout(requireContext());
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad12 = dp(12);
        layout.setPadding(pad12, pad12, pad12, 0);

        EditText inputDb = new EditText(requireContext());
        inputDb.setHint("数据库名");
        inputDb.setText(randomDbName());
        inputDb.setTextSize(14f);
        inputDb.setPadding(dp(12), dp(10), dp(12), dp(10));
        inputDb.setBackground(androidx.core.content.ContextCompat.getDrawable(requireContext(), R.drawable.bg_field));
        inputDb.setSingleLine(true);
        layout.addView(inputDb);

        TextView regenDb = new TextView(requireContext());
        regenDb.setText("刷新数据库名/用户名");
        regenDb.setTextColor(resolvePrimaryColor());
        int pad8 = dp(8);
        regenDb.setPadding(0, pad8, 0, pad8);
        layout.addView(regenDb);

        EditText inputUser = new EditText(requireContext());
        inputUser.setHint("数据库用户");
        inputUser.setText(inputDb.getText().toString());
        inputUser.setTextSize(14f);
        inputUser.setPadding(dp(12), dp(10), dp(12), dp(10));
        inputUser.setBackground(androidx.core.content.ContextCompat.getDrawable(requireContext(), R.drawable.bg_field));
        inputUser.setSingleLine(true);
        LinearLayout.LayoutParams userLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        userLp.topMargin = dp(12);
        inputUser.setLayoutParams(userLp);
        layout.addView(inputUser);

        EditText inputPwd = new EditText(requireContext());
        inputPwd.setHint("数据库密码");
        inputPwd.setText(randomDbPassword());
        inputPwd.setTextSize(14f);
        inputPwd.setPadding(dp(12), dp(10), dp(12), dp(10));
        inputPwd.setBackground(androidx.core.content.ContextCompat.getDrawable(requireContext(), R.drawable.bg_field));
        inputPwd.setSingleLine(true);
        LinearLayout.LayoutParams pwdLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        pwdLp.topMargin = dp(12);
        inputPwd.setLayoutParams(pwdLp);
        layout.addView(inputPwd);

        TextView regenPwd = new TextView(requireContext());
        regenPwd.setText("刷新密码");
        regenPwd.setTextColor(resolvePrimaryColor());
        regenPwd.setPadding(0, pad8, 0, pad8);
        layout.addView(regenPwd);

        regenDb.setOnClickListener(v -> {
            String db = randomDbName();
            inputDb.setText(db);
            inputUser.setText(db);
        });
        regenPwd.setOnClickListener(v -> inputPwd.setText(randomDbPassword()));

        final java.util.concurrent.atomic.AtomicReference<android.app.Dialog> inputDialogRef = new java.util.concurrent.atomic.AtomicReference<>();
        android.app.Dialog inputDialog = new ThemedDialogBuilder(requireContext())
                .setTitle("添加数据库")
                .setContentView(layout)
                .addButton("取消", false, d -> d.dismiss())
                .addButton("创建", true, d -> {
                    String dbName = inputDb.getText().toString().trim();
                    String userName = inputUser.getText().toString().trim();
                    if (dbName.isEmpty()) { toast("数据库名不能为空"); return; }
                    if (userName.isEmpty()) { toast("用户名不能为空"); return; }
                    if ("root".equalsIgnoreCase(dbName)) { toast("数据库名不能为 root"); return; }
                    if ("root".equalsIgnoreCase(userName)) { toast("用户名不能为 root"); return; }
                    // 不立即关闭，等创建完成后再关闭
                    createDatabase(dbName, userName, inputPwd.getText().toString().trim(), inputDialogRef.get());
                })
                .show();
        inputDialogRef.set(inputDialog);
    }

    private String randomDbName() {
        String hex = Long.toHexString(System.currentTimeMillis());
        return "db_" + hex.substring(Math.max(0, hex.length() - 6));
    }

    private String randomDbPassword() {
        return  Long.toHexString(Double.doubleToLongBits(Math.random()));
    }

    private void createDatabase(String dbName, String userName, String password, final android.app.Dialog inputDialog) {
        if (dbName.isEmpty() || userName.isEmpty() || password.isEmpty()) {
            toast("数据库名、用户名、密码不能为空");
            return;
        }
        final String safeDb = dbName.replaceAll("[^a-zA-Z0-9_]", "_");
        final String safeUser = userName.replaceAll("[^a-zA-Z0-9_]", "_");
        final String safePwd = password.replace("\\", "\\\\").replace("'", "\\'");
        final android.content.Context appContext = requireContext().getApplicationContext();
        final androidx.fragment.app.FragmentActivity activity = getActivity();
        final String privateDataDir = appContext.getFilesDir().getAbsolutePath() + "/server";
        if (activity == null) return;
        final android.app.Dialog[] progressDialog = new android.app.Dialog[1];
        activity.runOnUiThread(() -> {
            try {
                LinearLayout layout = new LinearLayout(requireContext());
                layout.setOrientation(LinearLayout.VERTICAL);
                layout.setGravity(android.view.Gravity.CENTER);
                layout.setPadding(dp(24), dp(20), dp(24), dp(12));
                ProgressBar progressBar = new ProgressBar(requireContext());
                progressBar.setIndeterminate(true);
                layout.addView(progressBar);
                TextView textView = new TextView(requireContext());
                textView.setTextSize(14f);
                textView.setTextColor(resolvePrimaryColor());
                textView.setPadding(0, dp(14), 0, 0);
                textView.setGravity(android.view.Gravity.CENTER);
                textView.setText("数据库创建中...");
                layout.addView(textView);
                progressDialog[0] = new ThemedDialogBuilder(requireContext())
                        .setTitle("添加数据库")
                        .setContentView(layout)
                        .setCancelable(false)
                        .setCanceledOnTouchOutside(false)
                        .show();
            } catch (Exception ignored) {}
        });
        new Thread(() -> {
            try {
                executeCreateDatabaseByInitFile(safeDb, safeUser, safePwd);
                activity.runOnUiThread(() -> {
                    if (progressDialog[0] != null && progressDialog[0].isShowing()) progressDialog[0].dismiss();
                    if (inputDialog != null && inputDialog.isShowing()) inputDialog.dismiss();
                    addCreatedDatabase(safeDb, safeUser, safePwd);
                    ToastUtil.showShort(appContext, "创建完成: " + safeDb + " / " + safeUser);
                });
            } catch (Exception e) {
                String errMsg = e.getMessage();
                activity.runOnUiThread(() -> {
                    if (progressDialog[0] != null && progressDialog[0].isShowing()) progressDialog[0].dismiss();
                    // 失败时保持输入对话框打开，让用户可以重试
                    ToastUtil.showShort(appContext, "创建失败: " + errMsg);
                });
            }
        }).start();
    }

        private void executeCreateDatabaseByInitFile(String dbName, String userName, String password) throws Exception {
        // 通过压缩包内的 mariadb CLI 直连运行中的 MariaDB 执行建库建用户
        android.content.Context appContext = requireContext().getApplicationContext();
        int mysqlPort = prefs.getInt("mysql_port", 3306);
        String mysqlPassword = prefs.getString("mysql_password", "123456");
        String escapedPwd = password.replace("\\", "\\\\").replace("'", "\\'");

        BinaryDeployer bd = new BinaryDeployer(appContext, prefs);
        bd.deployAll();
        String mariadbBin = bd.getBinPath("mariadb");
        if (mariadbBin == null) throw new Exception("mariadb CLI 未安装，请先在组件页下载 MariaDB");

        StringBuilder sb = new StringBuilder();
        sb.append("CREATE DATABASE IF NOT EXISTS `").append(dbName).append("`;\n");
        sb.append("CREATE USER IF NOT EXISTS '").append(userName).append("'@'localhost' IDENTIFIED BY '").append(escapedPwd).append("';\n");
        sb.append("CREATE USER IF NOT EXISTS '").append(userName).append("'@'127.0.0.1' IDENTIFIED BY '").append(escapedPwd).append("';\n");
        sb.append("CREATE USER IF NOT EXISTS '").append(userName).append("'@'%' IDENTIFIED BY '").append(escapedPwd).append("';\n");
        sb.append("GRANT ALL PRIVILEGES ON `").append(dbName).append("`.* TO '").append(userName).append("'@'localhost';\n");
        sb.append("GRANT ALL PRIVILEGES ON `").append(dbName).append("`.* TO '").append(userName).append("'@'127.0.0.1';\n");
        sb.append("GRANT ALL PRIVILEGES ON `").append(dbName).append("`.* TO '").append(userName).append("'@'%';\n");
        sb.append("FLUSH PRIVILEGES;\n");

        // CLI 用 linker64 启动，LD_LIBRARY_PATH 只用压缩包 dep + 系统路径
        String activeVer = prefs.getString("active_mariadb", "");
        File depDir = new File(appContext.getFilesDir(),
            "bin/mariadb/" + activeVer + "/dep");
        String ldPath = "/system/lib64:/vendor/lib64:/system/lib";
        if (depDir.isDirectory()) {
            ldPath = depDir.getAbsolutePath() + ":" + ldPath;
        }

        String arch = System.getProperty("os.arch", "");

        // 密码降级重试：prefs 密码 → 默认密码 123456 → 空密码（unix_socket）
        // 解决安卓12 上 init-file 改密码失败导致 prefs 与 MariaDB 密码不一致的问题
        String[] passwordsToTry = new String[]{
            mysqlPassword,  // 当前 prefs 中的密码
            "123456",        // 默认密码
            ""               // 空密码（unix_socket 认证）
        };

        Exception lastException = null;
        for (String pwd : passwordsToTry) {
            try {
                String[] cmd;
                if (pwd.isEmpty()) {
                    // 空密码时不加 -p 参数
                    cmd = new String[]{
                        arch.contains("64") ? "/system/bin/linker64" : "/system/bin/linker",
                        mariadbBin,
                        "-h", "127.0.0.1",
                        "-P", String.valueOf(mysqlPort),
                        "-u", "root",
                        "-e", sb.toString()
                    };
                } else {
                    cmd = new String[]{
                        arch.contains("64") ? "/system/bin/linker64" : "/system/bin/linker",
                        mariadbBin,
                        "-h", "127.0.0.1",
                        "-P", String.valueOf(mysqlPort),
                        "-u", "root",
                        "-p" + pwd,
                        "-e", sb.toString()
                    };
                }

                ProcessBuilder pb = new ProcessBuilder(cmd);
                pb.environment().put("LD_LIBRARY_PATH", ldPath);
                pb.environment().put("TMPDIR", appContext.getCacheDir().getAbsolutePath());
                pb.redirectErrorStream(true);
                Process p = pb.start();
                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[4096]; int n;
                java.io.InputStream pis = p.getInputStream();
                while ((n = pis.read(buf)) != -1) bos.write(buf, 0, n);
                p.waitFor(15, java.util.concurrent.TimeUnit.SECONDS);
                String output = bos.toString("UTF-8").trim();
                int exitCode = p.exitValue();

                if (exitCode == 0) {
                    Log.i(TAG, "createDatabase OK: " + dbName + " / " + userName + " (pwd=" + (pwd.isEmpty() ? "(空)" : "***") + ")");
                    // 如果实际可用的密码与 prefs 不同，更新 prefs
                    if (!pwd.equals(mysqlPassword)) {
                        Log.w(TAG, "检测到 prefs 密码与 MariaDB 实际密码不一致，已更新 prefs");
                        prefs.edit().putString("mysql_password", pwd).apply();
                    }
                    return;
                } else {
                    Log.w(TAG, "密码 " + (pwd.isEmpty() ? "(空)" : "***") + " 失败: " + output);
                    lastException = new Exception("mariadb CLI 错误 (exit=" + exitCode + "): " + output);
                }
            } catch (Exception e) {
                Log.w(TAG, "密码 " + (pwd.isEmpty() ? "(空)" : "***") + " 异常: " + e.getMessage());
                lastException = e;
            }
        }
        throw lastException != null ? lastException : new Exception("所有密码都失败");
    }

        private void showMysqlChangePwdDialog() {
        LinearLayout layout = new LinearLayout(requireContext());
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad12 = dp(12);
        layout.setPadding(pad12, pad12, pad12, 0);

        EditText input = new EditText(requireContext());
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        input.setHint("输入新的 root 密码");
        input.setTextSize(14f);
        input.setPadding(dp(12), dp(10), dp(12), dp(10));
        input.setBackground(androidx.core.content.ContextCompat.getDrawable(requireContext(), R.drawable.bg_field));
        input.setSingleLine(true);
        String current = prefs.getString("mysql_password", "123456");
        input.setText(current);
        input.setSelection(input.getText().length());
        layout.addView(input);

        TextView hint = new TextView(requireContext());
        hint.setText("请勿将 root 密码改为 123456，否则存在安全风险");
        hint.setTextSize(12f);
        hint.setTextColor(resolvePrimaryColor());
        int pad8 = dp(8);
        hint.setPadding(0, pad8, 0, 0);
        layout.addView(hint);

        final java.util.concurrent.atomic.AtomicReference<android.app.Dialog> inputDialogRef = new java.util.concurrent.atomic.AtomicReference<>();
        android.app.Dialog inputDialog = new ThemedDialogBuilder(requireContext())
                .setTitle("修改 MariaDB 密码")
                .setContentView(layout)
                .addButton("取消", false, d -> d.dismiss())
                .addButton("保存", true, d -> {
                    String newPwd = input.getText().toString();
                    if (newPwd.trim().isEmpty()) { toast("密码不能为空"); return; }
                    // 不立即关闭，等操作完成后再关闭
                    String oldPwd = prefs.getString("mysql_password", "123456");
                    prefs.edit().putString("mysql_password", newPwd).apply();
                    tcpAlterRootPassword(oldPwd, newPwd, inputDialogRef.get());
                })
                .show();
        inputDialogRef.set(inputDialog);
    }


        /** 通过 DatabaseManager.updateRootPassword() 更新 root 密码，无需外部 CLI */
    private void tcpAlterRootPassword(String oldPwd, String newPwd, final android.app.Dialog inputDialog) {
        final android.content.Context appContext = requireContext().getApplicationContext();
        final String privateDataDir = appContext.getFilesDir().getAbsolutePath() + "/server";
        final androidx.fragment.app.FragmentActivity activity = requireActivity();

        // 显示进度对话框
        final android.app.Dialog[] progressDialog = new android.app.Dialog[1];
        activity.runOnUiThread(() -> {
            try {
                LinearLayout pLayout = new LinearLayout(requireContext());
                pLayout.setOrientation(LinearLayout.VERTICAL);
                pLayout.setGravity(android.view.Gravity.CENTER);
                pLayout.setPadding(dp(24), dp(20), dp(24), dp(12));
                ProgressBar progressBar = new ProgressBar(requireContext());
                progressBar.setIndeterminate(true);
                pLayout.addView(progressBar);
                TextView textView = new TextView(requireContext());
                textView.setTextSize(14f);
                textView.setTextColor(resolvePrimaryColor());
                textView.setPadding(0, dp(14), 0, 0);
                textView.setGravity(android.view.Gravity.CENTER);
                textView.setText("更新密码中...");
                pLayout.addView(textView);
                progressDialog[0] = new ThemedDialogBuilder(requireContext())
                        .setTitle("修改密码")
                        .setContentView(pLayout)
                        .setCancelable(false)
                        .setCanceledOnTouchOutside(false)
                        .show();
            } catch (Exception ignored) {}
        });

        new Thread(() -> {
            try {
                ProcessManager pm = ProcessManager.getInstance(appContext.getFilesDir());
                if (pm == null) {
                    activity.runOnUiThread(() -> {
                        if (progressDialog[0] != null && progressDialog[0].isShowing()) progressDialog[0].dismiss();
                        if (inputDialog != null && inputDialog.isShowing()) inputDialog.dismiss();
                        toast("进程管理器未初始化");
                    });
                    return;
                }

                BinaryDeployer bd = new BinaryDeployer(appContext, prefs);
                bd.deployAll();
                DatabaseManager dbm = new DatabaseManager(
                    appContext, pm,
                    new ConfigGenerator(new File(privateDataDir, "config"), prefs),
                    prefs, bd, new StartupLogger(appContext)
                );

                boolean ok = dbm.updateRootPassword(newPwd);
                activity.runOnUiThread(() -> {
                    if (progressDialog[0] != null && progressDialog[0].isShowing()) progressDialog[0].dismiss();
                    if (inputDialog != null && inputDialog.isShowing()) inputDialog.dismiss();
                    toast(ok ? "root 密码已更新" : "密码更新失败，请查看日志");
                });
            } catch (Exception e) {
                Log.e(TAG, "tcpAlterRootPassword error", e);
                final String errMsg = e.getMessage();
                activity.runOnUiThread(() -> {
                    if (progressDialog[0] != null && progressDialog[0].isShowing()) progressDialog[0].dismiss();
                    if (inputDialog != null && inputDialog.isShowing()) inputDialog.dismiss();
                    toast("密码更新失败: " + errMsg);
                });
            }
        }).start();
    }
    private void writeMysqlInitSql(String password) throws Exception {
        File file = configFile("init.sql");
        String escaped = password.replace("\\", "\\\\").replace("'", "\\'");
        String text = "DELETE FROM mysql.global_priv WHERE User='';\n"
                + "UPDATE mysql.global_priv SET Priv=json_set(Priv, '$.plugin', 'mysql_native_password', '$.authentication_string', PASSWORD('" + escaped + "')) WHERE User='root' AND Host='localhost';\n"
                + "UPDATE mysql.global_priv SET Priv=json_set(Priv, '$.plugin', 'mysql_native_password', '$.authentication_string', PASSWORD('" + escaped + "')) WHERE User='root' AND Host='127.0.0.1';\n"
                + "UPDATE mysql.global_priv SET Priv=json_set(Priv, '$.plugin', 'mysql_native_password', '$.authentication_string', PASSWORD('" + escaped + "')) WHERE User='root' AND Host='%';\n"
                + "FLUSH PRIVILEGES;\n";
        writeText(file, text);
    }



    /** 通过 CLI 查询 mysql.db 获取所有非 root 用户的数据库和用户信息 */
    private java.util.List<DbInfo> queryDbUsersFromServer() {
        java.util.List<DbInfo> result = new java.util.ArrayList<>();
        try {
            android.content.Context appContext = requireContext().getApplicationContext();
            BinaryDeployer bd = new BinaryDeployer(appContext, prefs);
            bd.deployAll();
            String mariadbBin = bd.getBinPath("mariadb");
            if (mariadbBin == null) return result;

            int mysqlPort = prefs.getInt("mysql_port", 3306);
            String mysqlPassword = prefs.getString("mysql_password", "123456");
            String activeVer = prefs.getString("active_mariadb", "");
            File depDir = new File(appContext.getFilesDir(),
                "bin/mariadb/" + activeVer + "/dep");
            String ldPath = "/system/lib64:/vendor/lib64:/system/lib";
            if (depDir.isDirectory()) ldPath = depDir.getAbsolutePath() + ":" + ldPath;

            String arch = System.getProperty("os.arch", "");
            String[] cmd = new String[]{
                arch.contains("64") ? "/system/bin/linker64" : "/system/bin/linker",
                mariadbBin,
                "-h", "127.0.0.1",
                "-P", String.valueOf(mysqlPort),
                "-u", "root",
                "-p" + mysqlPassword,
                "--batch", "--skip-column-names",
                "-e", "SELECT DISTINCT Db,User FROM mysql.db WHERE User NOT IN('root','mariadb.sys','PUBLIC','') AND Db NOT IN('mysql','test') AND Db NOT LIKE 'test\\\\_%' ORDER BY Db,User"
            };
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.environment().put("LD_LIBRARY_PATH", ldPath);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096]; int n;
            java.io.InputStream pis = p.getInputStream();
            while ((n = pis.read(buf)) != -1) bos.write(buf, 0, n);
            p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);

            if (p.exitValue() == 0) {
                String output = bos.toString("UTF-8").trim();
                if (!output.isEmpty()) {
                    for (String line : output.split("\n")) {
                        line = line.trim();
                        if (line.isEmpty()) continue;
                        String[] parts = line.split("\t");
                        if (parts.length >= 2) {
                            String db = parts[0].trim();
                            String user = parts[1].trim();
                            if (!db.isEmpty() && !user.isEmpty()) {
                                result.add(new DbInfo(db, user, "", 0));
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "queryDbUsersFromServer error: " + e.getMessage());
        }
        return result;
    }

    private void showCreatedDatabasesDialog() {
        showCreatedDatabasesDialog(false);
    }

    private androidx.appcompat.app.AlertDialog newProgressDialog(String title, String message) {
        return new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle(title)
                .setMessage(message)
                .setCancelable(false)
                .show();
    }

    private void showCreatedDatabasesDialog(final boolean fromServerRefresh) {
        if (getContext() == null) return;
        final List<DbInfo> dbs = getCreatedDatabases();

        // 只在首次打开时异步从服务端查询补充
        if (!fromServerRefresh) {
            new Thread(() -> {
                try {
                    java.util.List<DbInfo> serverList = queryDbUsersFromServer();
                    if (serverList != null && !serverList.isEmpty()) {
                        boolean hasNew = false;
                        for (DbInfo si : serverList) {
                            boolean found = false;
                            for (DbInfo li : dbs) {
                                if (li.db.equals(si.db) && li.user.equals(si.user)) {
                                    found = true;
                                    break;
                                }
                            }
                            if (!found) {
                                dbs.add(si);
                                hasNew = true;
                            }
                        }
                        if (hasNew && isAdded() && getActivity() != null) {
                            getActivity().runOnUiThread(() -> {
                                // 复用已有弹窗，替换内容视图，避免销毁重建导致闪现
                                if (dbListDialog != null && dbListDialog.isShowing()) {
                                    LinearLayout contentBox = (LinearLayout) dbListDialog.findViewById(0x7F0A9999);
                                    if (contentBox != null) {
                                        contentBox.removeAllViews();
                                        contentBox.addView(buildDbListView(dbs));
                                    }
                                } else {
                                    showCreatedDatabasesDialog(true);
                                }
                            });
                        }
                    }
                } catch (Exception e) {
                    Log.w(TAG, "queryDbUsersFromServer error", e);
                }
            }).start();
        }

        final ScrollView scroller = buildDbListView(dbs);
        final android.app.Dialog[] dialogRef = new android.app.Dialog[1];
        dbListDialog = new ThemedDialogBuilder(requireContext())
            .setTitle("\u6570\u636e\u5e93\u5217\u8868")
            .setContentView(scroller)
            .addButton("\u5173\u95ed", false, d -> d.dismiss())
            .addButton("\u6dfb\u52a0\u6570\u636e\u5e93", true, d -> { d.dismiss(); showAddDatabaseDialog(); })
            .show();
        dbListDialog.setOnDismissListener(d -> dbListDialog = null);
        dialogRef[0] = dbListDialog;
    }

    /** 构建数据库列表内容视图（可复用，支持弹窗内容替换刷新） */
    private ScrollView buildDbListView(java.util.List<DbInfo> dbs) {
        LinearLayout root = new LinearLayout(requireContext());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(4), dp(4), dp(4), dp(4));

        if (dbs.isEmpty()) {
            TextView emptyTv = new TextView(requireContext());
            emptyTv.setText("\u6682\u65e0\u5df2\u521b\u5efa\u7684\u6570\u636e\u5e93\n\u70b9\u51fb\u300c\u6dfb\u52a0\u6570\u636e\u5e93\u300d\u521b\u5efa");
            emptyTv.setTextSize(14f);
            emptyTv.setTextColor(0xFF888888);
            emptyTv.setGravity(android.view.Gravity.CENTER);
            emptyTv.setPadding(0, dp(24), 0, dp(24));
            root.addView(emptyTv);
        } else {
            // Table header
            LinearLayout headerRow = new LinearLayout(requireContext());
            headerRow.setOrientation(LinearLayout.HORIZONTAL);
            headerRow.setBackgroundColor(0xFFF5F5F5);
            int hPad = dp(4);
            int hVPad = dp(4);
            headerRow.setPadding(hPad, hVPad, hPad, hVPad);

            String[] headers = {"\u6570\u636e\u5e93", "\u7528\u6237", "\u5bc6\u7801", "\u64cd\u4f5c"};
            int[] weights = {3, 2, 3, 2};
            for (int i = 0; i < headers.length; i++) {
                TextView tv = new TextView(requireContext());
                tv.setText(headers[i]);
                tv.setTextSize(11f);
                tv.setTextColor(0xFF202124);
                tv.setTypeface(null, android.graphics.Typeface.BOLD);
                tv.setGravity(android.view.Gravity.CENTER);
                tv.setSingleLine(true);
                tv.setMaxLines(1);
                tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
                tv.setPadding(dp(2), 0, dp(2), 0);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(36), weights[i]);
                tv.setLayoutParams(lp);
                headerRow.addView(tv);
            }
            root.addView(headerRow);

            // Divider
            View divider = new View(requireContext());
            divider.setBackgroundColor(0xFFE0E0E0);
            divider.setLayoutParams(new LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, 1));
            root.addView(divider);

            // Data rows
            for (int idx = 0; idx < dbs.size(); idx++) {
                final DbInfo info = dbs.get(idx);

                LinearLayout row = new LinearLayout(requireContext());
                row.setOrientation(LinearLayout.HORIZONTAL);
                int rPad = dp(4);
                int rVPad = dp(6);
                row.setPadding(rPad, rVPad, rPad, rVPad);
                row.setGravity(android.view.Gravity.CENTER_VERTICAL);
                if (idx % 2 == 0) row.setBackgroundColor(0xFFFAFAFA);

                // Database name (clickable to copy)
                TextView dbTv = new TextView(requireContext());
                dbTv.setText(info.db);
                dbTv.setTextSize(12f);
                dbTv.setTextColor(0xFF1A73E8);
                dbTv.setGravity(android.view.Gravity.CENTER);
                dbTv.setPadding(dp(2), 0, dp(2), 0);
                dbTv.setSingleLine(true);
                dbTv.setEllipsize(android.text.TextUtils.TruncateAt.END);
                dbTv.setClickable(true);
                dbTv.setFocusable(true);
                dbTv.setOnClickListener(v -> copyToClipboard("\u6570\u636e\u5e93\u540d", info.db));
                LinearLayout.LayoutParams dbLp = new LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 3);
                dbTv.setLayoutParams(dbLp);
                row.addView(dbTv);

                // User (clickable to copy)
                TextView userTv = new TextView(requireContext());
                userTv.setText(info.user);
                userTv.setTextSize(12f);
                userTv.setTextColor(0xFF1A73E8);
                userTv.setGravity(android.view.Gravity.CENTER);
                userTv.setPadding(dp(2), 0, dp(2), 0);
                userTv.setSingleLine(true);
                userTv.setEllipsize(android.text.TextUtils.TruncateAt.END);
                userTv.setClickable(true);
                userTv.setFocusable(true);
                userTv.setOnClickListener(v -> copyToClipboard("\u7528\u6237\u540d", info.user));
                LinearLayout.LayoutParams userLp = new LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 2);
                userTv.setLayoutParams(userLp);
                row.addView(userTv);

                // Password column
                LinearLayout pwdCol = new LinearLayout(requireContext());
                pwdCol.setOrientation(LinearLayout.VERTICAL);
                pwdCol.setGravity(android.view.Gravity.CENTER);
                LinearLayout.LayoutParams pwdColLp = new LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 3);
                pwdCol.setLayoutParams(pwdColLp);

                TextView pwdTv = new TextView(requireContext());
                pwdTv.setText("\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022");
                pwdTv.setTextSize(12f);
                pwdTv.setTextColor(0xFF5F6368);
                pwdTv.setGravity(android.view.Gravity.CENTER);
                pwdTv.setPadding(dp(2), 0, dp(2), dp(2));
                pwdTv.setMaxLines(1);
                pwdTv.setEllipsize(android.text.TextUtils.TruncateAt.END);
                pwdTv.setSingleLine(true);
                pwdTv.setClickable(true);
                pwdTv.setFocusable(true);
                pwdTv.setOnClickListener(v -> copyToClipboard("\u5bc6\u7801", info.password));
                pwdCol.addView(pwdTv);

                TextView randomBtn = new TextView(requireContext());
                randomBtn.setText("\u968f\u673a\u4fee\u6539");
                randomBtn.setTextSize(9f);
                randomBtn.setTextColor(0xFFEF6C00);
                randomBtn.setGravity(android.view.Gravity.CENTER);
                randomBtn.setPadding(dp(0), dp(1), dp(0), dp(1));
                randomBtn.setClickable(true);
                randomBtn.setFocusable(true);
                randomBtn.setOnClickListener(v -> {
                    if (dbListDialog != null && dbListDialog.isShowing()) dbListDialog.dismiss();
                    androidx.appcompat.app.AlertDialog progress = newProgressDialog("修改中", "正在修改数据库密码，请稍候...");
                    changeDbPassword(info.db, info.user, progress);
                });
                pwdCol.addView(randomBtn);

                row.addView(pwdCol);

                // Operation column: 3-row layout 备份/恢复/删除
                LinearLayout opCol = new LinearLayout(requireContext());
                opCol.setOrientation(LinearLayout.VERTICAL);
                opCol.setGravity(android.view.Gravity.CENTER);
                LinearLayout.LayoutParams opColLp = new LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 2);
                opCol.setLayoutParams(opColLp);

                TextView backupBtn = new TextView(requireContext());
                backupBtn.setText("\u5907\u4efd");
                backupBtn.setTextSize(13f);
                backupBtn.setTextColor(0xFF34A853);
                backupBtn.setMaxLines(1);
                backupBtn.setGravity(android.view.Gravity.CENTER);
                backupBtn.setPadding(dp(0), dp(3), dp(0), dp(3));
                backupBtn.setClickable(true);
                backupBtn.setFocusable(true);
                backupBtn.setOnClickListener(v -> backupDatabase(info.db));
                opCol.addView(backupBtn);

                TextView restoreBtn = new TextView(requireContext());
                restoreBtn.setText("\u8fd8\u539f");
                restoreBtn.setTextSize(13f);
                restoreBtn.setTextColor(0xFF1A73E8);
                restoreBtn.setMaxLines(1);
                restoreBtn.setGravity(android.view.Gravity.CENTER);
                restoreBtn.setPadding(dp(0), dp(3), dp(0), dp(3));
                restoreBtn.setClickable(true);
                restoreBtn.setFocusable(true);
                restoreBtn.setOnClickListener(v -> restoreDatabase(info.db));
                opCol.addView(restoreBtn);

                TextView deleteBtn = new TextView(requireContext());
                deleteBtn.setText("\u5220\u9664");
                deleteBtn.setTextSize(13f);
                deleteBtn.setTextColor(0xFFE53935);
                deleteBtn.setMaxLines(1);
                deleteBtn.setGravity(android.view.Gravity.CENTER);
                deleteBtn.setPadding(dp(0), dp(3), dp(0), dp(3));
                deleteBtn.setClickable(true);
                deleteBtn.setFocusable(true);
                deleteBtn.setOnClickListener(v -> confirmDeleteDatabase(info.db, info.user));
                opCol.addView(deleteBtn);

                row.addView(opCol);

                root.addView(row);

                // Row divider
                View rowDivider = new View(requireContext());
                rowDivider.setBackgroundColor(0xFFF0F0F0);
                rowDivider.setLayoutParams(new LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT, 1));
                root.addView(rowDivider);
            }
        }

        // Put content in ScrollView
        ScrollView scroller = new ScrollView(requireContext());
        scroller.setLayoutParams(new LinearLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            (int) (420 * requireContext().getResources().getDisplayMetrics().density)));
        scroller.addView(root);
        return scroller;
    }


    private void confirmDeleteDatabase(String dbName, String userName) {
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle("\u5220\u9664\u6570\u636e\u5e93")
            .setMessage("\u786e\u5b9a\u8981\u5220\u9664\u6570\u636e\u5e93 " + dbName + " \u5417\uff1f\n\n\u6b64\u64cd\u4f5c\u4e0d\u53ef\u6062\u590d\uff01")
            .setPositiveButton("\u786e\u8ba4\u5220\u9664", (dialog, which) -> {
                if (dbListDialog != null && dbListDialog.isShowing()) dbListDialog.dismiss();
                dropDatabase(dbName, userName);
            })
            .setNegativeButton("\u53d6\u6d88", null)
            .show();
    }

    private void dropDatabase(String dbName, String userName) {
        final android.content.Context appContext = requireContext().getApplicationContext();
        final String privateDataDir = appContext.getFilesDir().getAbsolutePath() + "/server";
        final androidx.fragment.app.FragmentActivity activity = requireActivity();
        final android.app.Dialog[] progressDialog = new android.app.Dialog[1];
        activity.runOnUiThread(() -> {
            try {
                LinearLayout layout = new LinearLayout(requireContext());
                layout.setOrientation(LinearLayout.VERTICAL);
                layout.setGravity(android.view.Gravity.CENTER);
                layout.setPadding(dp(24), dp(20), dp(24), dp(12));
                ProgressBar progressBar = new ProgressBar(requireContext());
                progressBar.setIndeterminate(true);
                layout.addView(progressBar);
                TextView tv = new TextView(requireContext());
                tv.setTextSize(14f);
                tv.setText("\u5220\u9664\u4e2d: " + dbName + "...");
                tv.setPadding(0, dp(14), 0, 0);
                tv.setGravity(android.view.Gravity.CENTER);
                layout.addView(tv);
                progressDialog[0] = new ThemedDialogBuilder(requireContext())
                    .setTitle("\u5220\u9664\u6570\u636e\u5e93")
                    .setContentView(layout)
                    .setCancelable(false)
                    .setCanceledOnTouchOutside(false)
                    .show();
            } catch (Exception ignored) {}
        });
        new Thread(() -> {
            try {
                ProcessManager pm = ProcessManager.getInstance(appContext.getFilesDir());
                if (pm == null) { activity.runOnUiThread(() -> { if (progressDialog[0] != null && progressDialog[0].isShowing()) progressDialog[0].dismiss(); toast("\u8fdb\u7a0b\u7ba1\u7406\u5668\u672a\u521d\u59cb\u5316"); }); return; }
                BinaryDeployer bd = new BinaryDeployer(appContext, prefs);
                bd.deployAll();
                DatabaseManager dbm = new DatabaseManager(appContext, pm,
                    new ConfigGenerator(new File(privateDataDir, "config"), prefs),
                    prefs, bd, new StartupLogger(appContext));
                String sql = "DROP DATABASE IF EXISTS " + dbName + ";\n"
                           + "DROP USER IF EXISTS '" + userName + "'@'localhost';\n"
                           + "DROP USER IF EXISTS '" + userName + "'@'127.0.0.1';\n"
                           + "DROP USER IF EXISTS '" + userName + "'@'%';\n"
                           + "FLUSH PRIVILEGES;\n";
                if (!dbm.executeSqlViaInitFile(sql)) throw new Exception("SQL \u6267\u884c\u5931\u8d25");
                List<DbInfo> dbs = getCreatedDatabases();
                for (int i = dbs.size() - 1; i >= 0; i--) {
                    if (dbs.get(i).db.equals(dbName)) { dbs.remove(i); }
                }
                saveCreatedDatabases(dbs);
                activity.runOnUiThread(() -> {
                    if (progressDialog[0] != null && progressDialog[0].isShowing()) progressDialog[0].dismiss();
                    toast("\u6570\u636e\u5e93 " + dbName + " \u5df2\u5220\u9664");
                    showCreatedDatabasesDialog(false);
                });
            } catch (Exception e) {
                Log.e(TAG, "dropDatabase error", e);
                activity.runOnUiThread(() -> {
                    if (progressDialog[0] != null && progressDialog[0].isShowing()) progressDialog[0].dismiss();
                    toast("\u5220\u9664\u5931\u8d25: " + e.getMessage());
                });
            }
        }).start();
    }

    private void showMysqlDataDirDialog() {
        String path = prefs.getString("mysql_data_dir", configDir().getAbsolutePath() + "/data");
        new ThemedDialogBuilder(requireContext())
                .setTitle("MariaDB \u5b58\u50a8\u4f4d\u7f6e")
                .setMessage("\u5f53\u524d\u6570\u636e\u5b58\u50a8\u76ee\u5f55\uff1a\n" + path)
                .addButton("\u5173\u95ed", false, d -> d.dismiss())
                .show();
    }

    private void showRedisKeysDialog() {
        if (getContext() == null) return;
        new Thread(() -> {
            try {
                RedisClient client = new RedisClient("127.0.0.1", prefs.getInt("redis_port", 6379));
                String dbsize = client.dbsize();
                List<String> keys = client.keys("*");
                androidx.fragment.app.FragmentActivity activity = getActivity();
                if (activity != null && !activity.isFinishing() && !activity.isDestroyed()) {
                    activity.runOnUiThread(() -> showRedisKeyResult(dbsize, keys));
                }
            } catch (Exception e) {
                androidx.fragment.app.FragmentActivity activity = getActivity();
                if (activity != null && !activity.isFinishing() && !activity.isDestroyed()) {
                    activity.runOnUiThread(() -> toast("Redis 读取失败: " + e.getMessage()));
                }
            }
        }).start();
    }

    private void showRedisKeyResult(String dbsize, List<String> keys) {
        LinearLayout root = new LinearLayout(requireContext());
        root.setOrientation(LinearLayout.VERTICAL);

        TextView head = new TextView(requireContext());
        head.setText("键数量: " + dbsize);
        head.setTextSize(14f);
        head.setTextColor(requireContext().getColor(R.color.text_primary));
        android.widget.LinearLayout.LayoutParams headLp = new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        headLp.bottomMargin = (int) (8 * requireContext().getResources().getDisplayMetrics().density);
        head.setLayoutParams(headLp);
        root.addView(head);

        TextView list = new TextView(requireContext());
        list.setBackgroundResource(R.drawable.bg_field);
        list.setPadding(dp(12), dp(12), dp(12), dp(12));
        list.setTextSize(13f);
        list.setTextColor(requireContext().getColor(R.color.text_secondary));
        list.setTextIsSelectable(true);
        list.setTypeface(android.graphics.Typeface.MONOSPACE);
        StringBuilder sb = new StringBuilder();
        if (keys.isEmpty()) sb.append("当前没有缓存键");
        else {
            int limit = Math.min(keys.size(), 200);
            for (int i = 0; i < limit; i++) sb.append(i + 1).append(". ").append(keys.get(i)).append("\n");
            if (keys.size() > limit) sb.append("\n仅显示前 ").append(limit).append(" 项");
        }
        list.setText(sb.toString());
        ScrollView sc = new ScrollView(requireContext());
        sc.addView(list);
        root.addView(sc, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(360)));

        new ThemedDialogBuilder(requireContext())
                .setTitle("Redis 缓存列表")
                .setContentView(root)
                .addButton("关闭", false, d -> d.dismiss())
                .show();
    }

    private void confirmRedisFlush() {
        new ThemedDialogBuilder(requireContext())
                .setTitle("清空 Redis 缓存")
                .setMessage("该操作会执行 FLUSHALL，不可恢复。")
                .addButton("取消", false, d -> d.dismiss())
                .addButton("确认清空", true, d -> {
                    new Thread(() -> {
                        try {
                            RedisClient client = new RedisClient("127.0.0.1", prefs.getInt("redis_port", 6379));
                            String result = client.flushAll();
                            androidx.fragment.app.FragmentActivity activity = getActivity();
                            if (activity != null && !activity.isFinishing() && !activity.isDestroyed()) {
                                activity.runOnUiThread(() -> toast("执行结果: " + result));
                            }
                        } catch (Exception e) {
                            androidx.fragment.app.FragmentActivity activity = getActivity();
                            if (activity != null && !activity.isFinishing() && !activity.isDestroyed()) {
                                activity.runOnUiThread(() -> toast("清空失败: " + e.getMessage()));
                            }
                        }
                    }).start();
                    d.dismiss();
                })
                .show();
    }

    private void copyDir(File src, File dst) throws Exception {
        dst.mkdirs();
        File[] files = src.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) copyDir(f, new File(dst, f.getName()));
            else {
                try (FileInputStream fis = new FileInputStream(f);
                     FileOutputStream fos = new FileOutputStream(new File(dst, f.getName()))) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = fis.read(buf)) != -1) fos.write(buf, 0, n);
                    fos.flush();
                }
            }
        }
    }

    private void deleteDir(File dir) {
        File[] files = dir.listFiles();
        if (files != null) for (File f : files) {
            if (f.isDirectory()) deleteDir(f);
            f.delete();
        }
    }

    private void togglePause(String comp) {
        BinaryDownloader.DownloadTask task = tasks.get(comp);
        if (task == null) return;
        if (task.isPaused()) task.resume(); else task.pause();
    }

    private void refreshAll() { for (String c : VERSIONS.keySet()) refreshOne(c); }

    private void refreshOne(String comp) {
        View view = safeView();
        if (view == null) return;
        V v = views.get(comp);
        if (v == null) return;
        TextView tv = view.findViewById(v.status);
        String active = prefs.getString("active_" + comp.toLowerCase(), "");
        if (!active.isEmpty() && downloader.isInstalled(comp, active)) tv.setText(active + " 已安装");
        else {
            String installed = downloader.getInstalledVersion(comp);
            tv.setText(installed.isEmpty() ? "未安装" : installed + " 已安装");
        }
    }

    private String fmt(long bytes) {
        if (bytes <= 0) return "0B";
        String[] u = {"B", "KB", "MB", "GB"};
        int i = 0;
        double v = bytes;
        while (v >= 1024 && i < u.length - 1) { v /= 1024; i++; }
        return String.format("%.1f%s", v, u[i]);
    }

    private String readText(File file) throws Exception {
        try (FileInputStream fis = new FileInputStream(file)) {
            return new String(fis.readAllBytes());
        }
    }

    private void writeText(File file, String text) throws Exception {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        try (FileOutputStream fos = new FileOutputStream(file, false)) {
            fos.write(text.getBytes());
            fos.flush();
        }
    }

    private void toast(String msg) {
        androidx.fragment.app.FragmentActivity activity = getActivity();
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        activity.runOnUiThread(() -> {
            if (getContext() != null) {
                ToastUtil.showShort(getContext(), msg);
            }
        });
    }

    private static final int ID_THEMED_CONTENT = 0x7F0A9999; // 内部用 id（避免依赖 R.id 生成）
    private int dp(int value) {
        return (int) (value * requireContext().getResources().getDisplayMetrics().density);
    }

    /** 解析当前主题色（与 SimpleFileDialog 一致） */
    private int resolvePrimaryColor() {
        try {
            int resId;
            switch (prefs.getString("theme_key", "slate")) {
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
            return androidx.core.content.ContextCompat.getColor(requireContext(), resId);
        } catch (Exception e) {
            return 0xFF1A73E8;
        }
    }

    /**
     * 创建统一样式的 Dialog：顶部主题色标题栏 + 内容 + 底部等宽按钮行
     * 用法：
     *   ThemedDialogBuilder b = new ThemedDialogBuilder(requireContext());
     *   b.setTitle("添加数据库").setContentView(layout)
     *    .addButton("取消", false, d -> d.dismiss())
     *    .addButton("创建", true, d -> { ... ; d.dismiss(); })
     *    .show();
     */
    private static class ThemedDialogBuilder {
        final android.app.Dialog dialog;
        final android.widget.LinearLayout root;
        final android.widget.LinearLayout btnRow;
        final int primaryColor;
        final int r, g, b;
        final float density;

        ThemedDialogBuilder(android.content.Context ctx) {
            primaryColor = resolveColor(ctx);
            r = android.graphics.Color.red(primaryColor);
            g = android.graphics.Color.green(primaryColor);
            b = android.graphics.Color.blue(primaryColor);
            density = ctx.getResources().getDisplayMetrics().density;

            dialog = new android.app.Dialog(ctx);
            dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);

            root = new android.widget.LinearLayout(ctx);
            root.setOrientation(android.widget.LinearLayout.VERTICAL);
            root.setBackgroundColor(0xFFFFFFFF);

            // 顶部主题色标题栏
            android.widget.LinearLayout titleBar = new android.widget.LinearLayout(ctx);
            titleBar.setOrientation(android.widget.LinearLayout.HORIZONTAL);
            titleBar.setBackgroundColor(primaryColor);
            int tPad = (int) (14 * density);
            titleBar.setPadding(tPad, tPad, tPad, tPad);
            android.widget.LinearLayout.LayoutParams titleBarLp =
                    new android.widget.LinearLayout.LayoutParams(
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                            android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
            titleBar.setLayoutParams(titleBarLp);
            root.addView(titleBar);

            // 内容容器
            android.widget.LinearLayout contentBox = new android.widget.LinearLayout(ctx);
            contentBox.setOrientation(android.widget.LinearLayout.VERTICAL);
            int cPad = (int) (16 * density);
            contentBox.setPadding(cPad, cPad, cPad, cPad);
            android.widget.LinearLayout.LayoutParams contentLp =
                    new android.widget.LinearLayout.LayoutParams(
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                            android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
            contentBox.setLayoutParams(contentLp);
            contentBox.setId(ID_THEMED_CONTENT); // 内部用
            root.addView(contentBox);

            // 底部按钮行
            btnRow = new android.widget.LinearLayout(ctx);
            btnRow.setOrientation(android.widget.LinearLayout.HORIZONTAL);
            int bPad = (int) (12 * density);
            android.widget.LinearLayout.LayoutParams btnRowLp =
                    new android.widget.LinearLayout.LayoutParams(
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                            android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
            btnRowLp.leftMargin = bPad;
            btnRowLp.rightMargin = bPad;
            btnRowLp.topMargin = 0;
            btnRowLp.bottomMargin = bPad;
            btnRow.setLayoutParams(btnRowLp);
            root.addView(btnRow);
        }

        ThemedDialogBuilder setCancelable(boolean cancelable) {
            dialog.setCancelable(cancelable);
            return this;
        }

        ThemedDialogBuilder setCanceledOnTouchOutside(boolean cancel) {
            dialog.setCanceledOnTouchOutside(cancel);
            return this;
        }

        ThemedDialogBuilder setTitle(String title) {
            // 标题加到 titleBar
            android.widget.LinearLayout titleBar = (android.widget.LinearLayout) root.getChildAt(0);
            android.widget.TextView titleTv = new android.widget.TextView(dialog.getContext());
            titleTv.setText(title);
            titleTv.setTextSize(15f);
            titleTv.setTextColor(0xFFFFFFFF);
            titleTv.setTypeface(null, android.graphics.Typeface.BOLD);
            titleTv.setSingleLine(true);
            titleTv.setEllipsize(android.text.TextUtils.TruncateAt.END);
            titleTv.setLayoutParams(new android.widget.LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT));
            titleBar.addView(titleTv);
            return this;
        }

        ThemedDialogBuilder setContentView(android.view.View v) {
            android.widget.LinearLayout contentBox = (android.widget.LinearLayout) root.findViewById(ID_THEMED_CONTENT);
            contentBox.removeAllViews();
            contentBox.addView(v);
            return this;
        }

        ThemedDialogBuilder setMessage(String text) {
            android.widget.TextView tv = new android.widget.TextView(dialog.getContext());
            tv.setText(text);
            tv.setTextSize(14f);
            tv.setTextColor(0xFF333333);
            tv.setLineSpacing(2f, 1f);
            return setContentView(tv);
        }

        ThemedDialogBuilder addButton(String text, boolean primary, final java.util.function.Consumer<android.app.Dialog> onClick) {
            android.widget.TextView btn = new android.widget.TextView(dialog.getContext());
            btn.setText(text);
            btn.setTextSize(14f);
            btn.setGravity(android.view.Gravity.CENTER);
            btn.setClickable(true);
            btn.setFocusable(true);
            int vPad = (int) (10 * density);
            btn.setPadding((int) (12 * density), vPad, (int) (12 * density), vPad);

            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
            bg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
            bg.setCornerRadius(8 * density);
            if (primary) {
                bg.setColor(primaryColor);
                btn.setTextColor(0xFFFFFFFF);
            } else {
                bg.setColor(android.graphics.Color.argb(18, r, g, b));
                bg.setStroke((int) (1 * density), android.graphics.Color.argb(120, r, g, b));
                btn.setTextColor(primaryColor);
            }
            btn.setBackground(bg);

            android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
                    0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.leftMargin = (int) (4 * density);
            lp.rightMargin = (int) (4 * density);
            btn.setLayoutParams(lp);
            btn.setOnClickListener(v -> {
                if (onClick != null) onClick.accept(dialog);
            });
            btnRow.addView(btn);
            return this;
        }

        android.app.Dialog show() {
            dialog.setContentView(root);
            android.view.Window window = dialog.getWindow();
            if (window != null) {
                window.setLayout(
                        (int) (dialog.getContext().getResources().getDisplayMetrics().widthPixels * 0.88f),
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
                // 圆角白色背景
                android.graphics.drawable.GradientDrawable winBg = new android.graphics.drawable.GradientDrawable();
                winBg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
                winBg.setCornerRadius(14 * density);
                winBg.setColor(0xFFFFFFFF);
                window.setBackgroundDrawable(winBg);
                window.getDecorView().setBackgroundColor(0xFFFFFFFF);
            }
            dialog.show();
            return dialog;
        }
    }

    private static int resolveColor(android.content.Context ctx) {
        try {
            android.content.SharedPreferences p = ctx.getSharedPreferences("server_settings", 0);
            int resId;
            switch (p.getString("theme_key", "slate")) {
                case "green":  resId = com.zm920.androidserver.R.color.theme_green_primary; break;
                case "purple": resId = com.zm920.androidserver.R.color.theme_purple_primary; break;
                case "orange": resId = com.zm920.androidserver.R.color.theme_orange_primary; break;
                case "blue":   resId = com.zm920.androidserver.R.color.blue_primary; break;
                case "red":    resId = com.zm920.androidserver.R.color.theme_red_primary; break;
                case "cyan":   resId = com.zm920.androidserver.R.color.theme_cyan_primary; break;
                case "pink":   resId = com.zm920.androidserver.R.color.theme_pink_primary; break;
                case "indigo": resId = com.zm920.androidserver.R.color.theme_indigo_primary; break;
                case "brown":  resId = com.zm920.androidserver.R.color.theme_brown_primary; break;
                default:       resId = com.zm920.androidserver.R.color.theme_slate_primary;
            }
            return androidx.core.content.ContextCompat.getColor(ctx, resId);
        } catch (Exception e) {
            return 0xFF1A73E8;
        }
    }

    private void setDirReadable(File dir) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            try {
                f.setReadable(true, false);
                if (f.isDirectory()) setDirReadable(f);
            } catch (Exception ignored) {}
        }
    }





    private static class DbInfo {
        String db;
        String user;
        String password;
        long created;

        DbInfo(String db, String user, String password, long created) {
            this.db = db;
            this.user = user;
            this.password = password;
            this.created = created;
        }
    }

    private List<DbInfo> getCreatedDatabases() {
        List<DbInfo> list = new ArrayList<>();
        try {
            String json = prefs.getString(KEY_CREATED_DBS, "[]");
            org.json.JSONArray arr = new org.json.JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                org.json.JSONObject obj = arr.getJSONObject(i);
                String db = obj.optString("db", "");
                String pwd = obj.optString("password", "");
                // 过滤无效记录：数据库名为空或密码为空且创建时间为0（来自服务端查询的占位记录）
                if (db.isEmpty()) continue;
                list.add(new DbInfo(
                    db,
                    obj.optString("user", ""),
                    pwd,
                    obj.optLong("created", 0)
                ));
            }
        } catch (Exception e) {
            Log.w(TAG, "getCreatedDatabases parse error", e);
        }
        return list;
    }

    private void saveCreatedDatabases(List<DbInfo> list) {
        try {
            org.json.JSONArray arr = new org.json.JSONArray();
            for (DbInfo info : list) {
                org.json.JSONObject obj = new org.json.JSONObject();
                obj.put("db", info.db);
                obj.put("user", info.user);
                obj.put("password", info.password);
                obj.put("created", info.created);
                arr.put(obj);
            }
            prefs.edit().putString(KEY_CREATED_DBS, arr.toString()).apply();
        } catch (Exception e) {
            Log.w(TAG, "saveCreatedDatabases error", e);
        }
    }

    private void addCreatedDatabase(String db, String user, String password) {
        List<DbInfo> list = getCreatedDatabases();
        for (DbInfo info : list) {
            if (info.db.equals(db)) {
                info.password = password;
                saveCreatedDatabases(list);
                return;
            }
        }
        list.add(new DbInfo(db, user, password, System.currentTimeMillis()));
        saveCreatedDatabases(list);
    }

    private void copyToClipboard(String label, String value) {
        if (getContext() == null || value == null || value.isEmpty()) return;
        android.content.ClipboardManager cm = (android.content.ClipboardManager)
            requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
        android.content.ClipData clip = android.content.ClipData.newPlainText(label, value);
        cm.setPrimaryClip(clip);
        toast("\u5df2\u590d\u5236 " + label + ": " + value);
    }

    private void changeDbPassword(String dbName, String userName, androidx.appcompat.app.AlertDialog progress) {
        if (getContext() == null || getActivity() == null) return;
        final String newPwd = Long.toHexString(Double.doubleToLongBits(Math.random()));
        final android.content.Context appContext = requireContext().getApplicationContext();
        final String privateDataDir = appContext.getFilesDir().getAbsolutePath() + "/server";
        new Thread(() -> {
            try {
                ProcessManager pm = ProcessManager.getInstance(appContext.getFilesDir());
                if (pm == null) { requireActivity().runOnUiThread(() -> toast("进程管理器未初始化")); return; }
                BinaryDeployer bd = new BinaryDeployer(appContext, prefs);
                bd.deployAll();
                DatabaseManager dbm = new DatabaseManager(appContext, pm,
                    new ConfigGenerator(new File(privateDataDir, "config"), prefs),
                    prefs, bd, new StartupLogger(appContext));
                String ep = newPwd.replace("'", "\\'");
                String sql = "CREATE USER IF NOT EXISTS '" + userName + "'@'localhost' IDENTIFIED BY '" + ep + "';\n"
                           + "CREATE USER IF NOT EXISTS '" + userName + "'@'127.0.0.1' IDENTIFIED BY '" + ep + "';\n"
                           + "CREATE USER IF NOT EXISTS '" + userName + "'@'%' IDENTIFIED BY '" + ep + "';\n"
                           + "ALTER USER '" + userName + "'@'localhost' IDENTIFIED BY '" + ep + "';\n"
                           + "ALTER USER '" + userName + "'@'127.0.0.1' IDENTIFIED BY '" + ep + "';\n"
                           + "ALTER USER '" + userName + "'@'%' IDENTIFIED BY '" + ep + "';\n"
                           + "GRANT ALL PRIVILEGES ON *.* TO '" + userName + "'@'localhost';\n"
                           + "GRANT ALL PRIVILEGES ON *.* TO '" + userName + "'@'127.0.0.1';\n"
                           + "GRANT ALL PRIVILEGES ON *.* TO '" + userName + "'@'%';\n"
                           + "FLUSH PRIVILEGES;\n";
                if (!dbm.executeSqlViaInitFile(sql)) throw new Exception("SQL 执行失败");
                List<DbInfo> dbs = getCreatedDatabases();
                for (DbInfo info : dbs) {
                    if (info.db.equals(dbName)) { info.password = newPwd; break; }
                }
                saveCreatedDatabases(dbs);
                requireActivity().runOnUiThread(() -> {
                    try { if (progress != null) progress.dismiss(); } catch (Exception ignored) {}
                    toast("密码已修改");
                    showCreatedDatabasesDialog(false);
                });
            } catch (Exception e) {
                Log.e(TAG, "changeDbPassword error", e);
                requireActivity().runOnUiThread(() -> {
                    try { if (progress != null) progress.dismiss(); } catch (Exception ignored) {}
                    toast("修改密码失败: " + e.getMessage());
                    showCreatedDatabasesDialog(false);
                });
            }
        }).start();
    }

private void backupDatabase(String dbName) {
        if (getContext() == null || getActivity() == null) return;
        final android.content.Context appContext = requireContext().getApplicationContext();
        final androidx.fragment.app.FragmentActivity activity = getActivity();
        final String privateDataDir = appContext.getFilesDir().getAbsolutePath() + "/server";

        if (!PermissionUtil.ensureStoragePermission(requireActivity())) return;

        final android.app.Dialog[] progressDialog = new android.app.Dialog[1];
        activity.runOnUiThread(() -> {
            try {
                LinearLayout layout = new LinearLayout(requireContext());
                layout.setOrientation(LinearLayout.VERTICAL);
                layout.setGravity(android.view.Gravity.CENTER);
                layout.setPadding(dp(24), dp(20), dp(24), dp(12));
                ProgressBar progressBar = new ProgressBar(requireContext());
                progressBar.setIndeterminate(true);
                layout.addView(progressBar);
                TextView textView = new TextView(requireContext());
                textView.setTextSize(14f);
                textView.setText("\u5907\u4efd\u4e2d: " + dbName + "...");
                textView.setPadding(0, dp(14), 0, 0);
                textView.setGravity(android.view.Gravity.CENTER);
                layout.addView(textView);
                progressDialog[0] = new ThemedDialogBuilder(requireContext())
                    .setTitle("\u6570\u636e\u5e93\u5907\u4efd")
                    .setContentView(layout)
                    .setCancelable(false)
                    .setCanceledOnTouchOutside(false)
                    .show();
            } catch (Exception ignored) {}
        });

        new Thread(() -> {
            try {
                int mysqlPort = prefs.getInt("mysql_port", 3306);
                String dateStr = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(new java.util.Date());
                String dataDir = StoragePaths.getInfraRoot(prefs.getString("data_dir", StoragePaths.getDefaultWwwRoot()));
                File backupsRoot = new File(dataDir, "\u5907\u4efd\u76ee\u5f55");
                backupsRoot.mkdirs();
                File configDir = new File(privateDataDir, "config");
                File mysqlDataDir = new File(configDir, "data");
                File mysqlDbDir = new File(mysqlDataDir, dbName);

                if (!mysqlDbDir.exists() || !mysqlDbDir.isDirectory()) {
                    throw new Exception("\u6570\u636e\u5e93\u76ee\u5f55\u4e0d\u5b58\u5728: " + mysqlDbDir.getAbsolutePath());
                }

                ProcessManager pm = ProcessManager.getInstance(appContext.getFilesDir());
                if (pm == null) throw new Exception("\u8fdb\u7a0b\u7ba1\u7406\u5668\u672a\u521d\u59cb\u5316");

                BinaryDeployer bd = new BinaryDeployer(appContext, prefs);
                bd.deployAll();
                String mysqldPath = bd.getBinPath("mariadbd");

                // Brief cold backup: stop mysqld, copy data files, restart
                boolean wasRunning = pm.isAlive("mysqld");
                if (wasRunning) pm.stop("mysqld");
                Thread.sleep(800);

                // Package mysql database directory as zip
                File zipFile = new File(backupsRoot, dbName + "_" + dateStr + ".zip");
                java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(new java.io.FileOutputStream(zipFile));
                zipDir(zos, mysqlDbDir, dbName + "/");
                zos.close();

                // Restart if it was running
                if (wasRunning && mysqldPath != null) {
                    String[] env = bd.buildEnv();
                    String[] cmd = bd.buildExecCmd(mysqldPath,
                        "--defaults-file=" + new File(privateDataDir, "config/my.cnf").getAbsolutePath(),
                        "--skip-grant-tables");
                    pm.start("mysqld", cmd, env, configDir, mysqlPort);
                    for (int i = 0; i < 30; i++) {
                        if (pm.isAlive("mysqld")) break;
                        Thread.sleep(500);
                    }
                }

                String finalPath = zipFile.getAbsolutePath();
                activity.runOnUiThread(() -> {
                    if (progressDialog[0] != null && progressDialog[0].isShowing()) progressDialog[0].dismiss();
                    toast("\u5907\u4efd\u5b8c\u6210: " + finalPath);
                });
            } catch (Exception e) {
                String errMsg = e.getMessage();
                Log.e(TAG, "backup error", e);
                activity.runOnUiThread(() -> {
                    if (progressDialog[0] != null && progressDialog[0].isShowing()) progressDialog[0].dismiss();
                    toast("\u5907\u4efd\u5931\u8d25: " + errMsg);
                });
            }
        }).start();
    }

    /** Recursively zip a directory */
    private void zipDir(java.util.zip.ZipOutputStream zos, File dir, String prefix) throws Exception {
        File[] files = dir.listFiles();
        if (files == null) return;
        byte[] buf = new byte[8192];
        for (File f : files) {
            String entry = prefix + f.getName() + (f.isDirectory() ? "/" : "");
            if (f.isDirectory()) {
                zos.putNextEntry(new java.util.zip.ZipEntry(entry));
                zos.closeEntry();
                zipDir(zos, f, entry);
            } else {
                zos.putNextEntry(new java.util.zip.ZipEntry(entry));
                java.io.FileInputStream fis = new java.io.FileInputStream(f);
                int n;
                while ((n = fis.read(buf)) > 0) zos.write(buf, 0, n);
                fis.close();
                zos.closeEntry();
            }
        }
    }
    /** Restore database from latest backup zip */
    private void restoreDatabase(String dbName) {
        if (getContext() == null || getActivity() == null) return;
        final android.content.Context appContext = requireContext().getApplicationContext();
        final androidx.fragment.app.FragmentActivity activity = getActivity();
        final String privateDataDir = appContext.getFilesDir().getAbsolutePath() + "/server";

        // Find latest backup
        String dataDir = StoragePaths.getInfraRoot(prefs.getString("data_dir", StoragePaths.getDefaultWwwRoot()));
        File backupDir = new File(dataDir, "\u5907\u4efd\u76ee\u5f55");
        File[] backups = backupDir.listFiles((d, n) -> n.startsWith(dbName + "_") && n.endsWith(".zip"));
        if (backups == null || backups.length == 0) {
            toast("\u6ca1\u6709\u627e\u5230 " + dbName + " \u7684\u5907\u4efd\u6587\u4ef6");
            return;
        }
        java.util.Arrays.sort(backups, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        final File latestBackup = backups[0];

        final android.app.Dialog[] progressDialog = new android.app.Dialog[1];
        activity.runOnUiThread(() -> {
            try {
                LinearLayout layout = new LinearLayout(requireContext());
                layout.setOrientation(LinearLayout.VERTICAL);
                layout.setGravity(android.view.Gravity.CENTER);
                layout.setPadding(dp(24), dp(20), dp(24), dp(12));
                ProgressBar progressBar = new ProgressBar(requireContext());
                progressBar.setIndeterminate(true);
                layout.addView(progressBar);
                TextView textView = new TextView(requireContext());
                textView.setTextSize(14f);
                textView.setText("\u8fd8\u539f\u4e2d: " + dbName + "...");
                textView.setPadding(0, dp(14), 0, 0);
                textView.setGravity(android.view.Gravity.CENTER);
                layout.addView(textView);
                progressDialog[0] = new ThemedDialogBuilder(requireContext())
                    .setTitle("\u6570\u636e\u5e93\u8fd8\u539f")
                    .setContentView(layout)
                    .setCancelable(false)
                    .setCanceledOnTouchOutside(false)
                    .show();
            } catch (Exception ignored) {}
        });

        new Thread(() -> {
            try {
                ProcessManager pm = ProcessManager.getInstance(appContext.getFilesDir());
                if (pm == null) throw new Exception("\u8fdb\u7a0b\u7ba1\u7406\u5668\u672a\u521d\u59cb\u5316");

                boolean wasRunning = pm.isAlive("mysqld");
                if (wasRunning) pm.stop("mysqld");
                Thread.sleep(800);

                File configDir = new File(privateDataDir, "config");
                File mysqlDataDir = new File(configDir, "data");
                File dbDir = new File(mysqlDataDir, dbName);

                // Remove current database dir and extract backup
                if (dbDir.exists()) deleteDir(dbDir);
                dbDir.mkdirs();

                // Extract zip
                java.util.zip.ZipInputStream zis = new java.util.zip.ZipInputStream(new java.io.FileInputStream(latestBackup));
                java.util.zip.ZipEntry entry;
                byte[] buf = new byte[8192];
                while ((entry = zis.getNextEntry()) != null) {
                    File outFile = new File(mysqlDataDir, entry.getName());
                    if (entry.isDirectory()) {
                        outFile.mkdirs();
                    } else {
                        outFile.getParentFile().mkdirs();
                        java.io.FileOutputStream fos = new java.io.FileOutputStream(outFile);
                        int n;
                        while ((n = zis.read(buf)) > 0) fos.write(buf, 0, n);
                        fos.close();
                    }
                    zis.closeEntry();
                }
                zis.close();

                // Restart mariadb if it was running
                if (wasRunning) {
                    int mysqlPort = prefs.getInt("mysql_port", 3306);
                    BinaryDeployer bd = new BinaryDeployer(appContext, prefs);
                    bd.deployAll();
                    String mysqldPath = bd.getBinPath("mariadbd");
                    if (mysqldPath != null) {
                        String[] env = bd.buildEnv();
                        String[] cmd = bd.buildExecCmd(mysqldPath,
                            "--defaults-file=" + new File(privateDataDir, "config/my.cnf").getAbsolutePath(),
                            "--skip-grant-tables");
                        pm.start("mysqld", cmd, env, configDir, mysqlPort);
                        for (int i = 0; i < 30; i++) {
                            if (pm.isAlive("mysqld")) break;
                            Thread.sleep(500);
                        }
                    }
                }

                activity.runOnUiThread(() -> {
                    if (progressDialog[0] != null && progressDialog[0].isShowing()) progressDialog[0].dismiss();
                    toast("\u8fd8\u539f\u5b8c\u6210: " + dbName);
                });
            } catch (Exception e) {
                String errMsg = e.getMessage();
                Log.e(TAG, "restore error", e);
                activity.runOnUiThread(() -> {
                    if (progressDialog[0] != null && progressDialog[0].isShowing()) progressDialog[0].dismiss();
                    toast("\u8fd8\u539f\u5931\u8d25: " + errMsg);
                });
            }
        }).start();
    }

    /** MySQL protocol: handshake + auth for backup */
    private void doBackupHandshake(java.io.InputStream in, java.io.OutputStream out) throws Exception {
        byte[] hdr = readPacketHeader(in);
        byte[] handshake = new byte[(hdr[0] & 0xFF) | ((hdr[1] & 0xFF) << 8) | ((hdr[2] & 0xFF) << 16)];
        readFully(in, handshake);
        sendPacket(out, buildBackupAuthPacket("root", handshake), 0);
        hdr = readPacketHeader(in);
        byte[] authResp = new byte[(hdr[0] & 0xFF) | ((hdr[1] & 0xFF) << 8) | ((hdr[2] & 0xFF) << 16)];
        readFully(in, authResp);
    }    /** Read MySQL OK/ERR packet after a command (no result set) */
    private void readOkPacket(java.io.InputStream in) throws Exception {
        byte[] hdr = readPacketHeader(in);
        byte[] data = new byte[(hdr[0] & 0xFF) | ((hdr[1] & 0xFF) << 8) | ((hdr[2] & 0xFF) << 16)];
        readFully(in, data);
        if (data.length > 0 && data[0] == (byte)0xFF) {
            String msg = new String(data, 5, data.length - 5, "UTF-8");
            throw new Exception("SQL\u9519\u8bef: " + msg);
        }
    }

    /** Read MySQL result set returning String[][] */
    private String[][] readResultSet2(java.io.InputStream in) throws Exception {
        byte[] hdr = readPacketHeader(in);
        byte[] data = new byte[(hdr[0] & 0xFF) | ((hdr[1] & 0xFF) << 8) | ((hdr[2] & 0xFF) << 16)];
        readFully(in, data);
        if (data.length == 0 || data[0] == (byte)0xFF) return null;
        if (data[0] == 0x00 || data[0] == 0xFE) return new String[0][];

        java.io.ByteArrayInputStream bais = new java.io.ByteArrayInputStream(data);
        long colCount = readLenEnc(bais);
        if (colCount <= 0) return new String[0][];

        // Skip column definitions
        for (int i = 0; i < colCount; i++) {
            byte[] colHdr = readPacketHeader(in);
            byte[] colData = new byte[(colHdr[0] & 0xFF) | ((colHdr[1] & 0xFF) << 8) | ((colHdr[2] & 0xFF) << 16)];
            readFully(in, colData);
        }
        // Skip EOF
        byte[] eofHdr = readPacketHeader(in);
        byte[] eofPayload = new byte[(eofHdr[0] & 0xFF) | ((eofHdr[1] & 0xFF) << 8) | ((eofHdr[2] & 0xFF) << 16)];
        readFully(in, eofPayload);

        // Read rows
        java.util.List<String[]> rows = new java.util.ArrayList<>();
        while (true) {
            byte[] rowHdr = readPacketHeader(in);
            byte[] rowData = new byte[(rowHdr[0] & 0xFF) | ((rowHdr[1] & 0xFF) << 8) | ((rowHdr[2] & 0xFF) << 16)];
            readFully(in, rowData);
            if (rowData.length == 0 || rowData[0] == (byte)0xFE || rowData[0] == 0x00) break;

            bais = new java.io.ByteArrayInputStream(rowData);
            String[] row = new String[(int)colCount];
            for (int i = 0; i < colCount; i++) {
                row[i] = readLenEncString(bais);
            }
            rows.add(row);
        }
        return rows.toArray(new String[0][]);
    }

    /** MySQL protocol: read 4-byte packet header */
    private byte[] readPacketHeader(java.io.InputStream in) throws Exception {
        byte[] hdr = new byte[4];
        readFully(in, hdr);
        return hdr;
    }

    /** MySQL protocol: read exact bytes */
    private void readFully(java.io.InputStream in, byte[] buf) throws Exception {
        int off = 0;
        while (off < buf.length) {
            int n = in.read(buf, off, buf.length - off);
            if (n < 0) throw new java.io.EOFException();
            off += n;
        }
    }

    /** MySQL protocol: send packet with header */
    private void sendPacket(java.io.OutputStream out, byte[] data, int seq) throws Exception {
        byte[] len3 = new byte[3];
        len3[0] = (byte)(data.length & 0xFF);
        len3[1] = (byte)((data.length >> 8) & 0xFF);
        len3[2] = (byte)((data.length >> 16) & 0xFF);
        out.write(len3);
        out.write(seq);
        out.write(data);
        out.flush();
    }

    // ====== mysql_native_password 认证实现 ======

    /** SHA1 哈希（返回 hex 字符串） */
    private static String sha1Hex(String input) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-1");
            byte[] digest = md.digest(input.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b & 0xFF));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /** SHA1 哈希（返回原始字节） */
    private static byte[] sha1Bytes(byte[] input) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-1");
            return md.digest(input);
        } catch (Exception e) {
            return new byte[20];
        }
    }

    /** 从 handshake 包中提取 20 字节 scramble */
    private static byte[] extractScramble(byte[] handshake) {
        try {
            // handshake 结构 (MySQL 4.1+ / MariaDB):
            // [0] protocol_version
            // [1..N] server_version (null-terminated)
            // after server_version: 4 bytes thread_id
            // then 8 bytes scramble_part1
            // then 1 byte filler (0x00)
            // then 2 bytes capability_lower
            // then 1 byte charset
            // then 2 bytes status
            // then 2 bytes capability_upper
            // then 1 byte auth_plugin_data_len
            // then 10 bytes reserved
            // then scramble_part2 (null-terminated, len = max(13, auth_plugin_data_len - 8))

            int pos = 0;
            // skip protocol_version (1)
            pos = 1;
            // skip server_version (null-terminated)
            while (pos < handshake.length && handshake[pos] != 0) pos++;
            pos++; // skip null terminator
            // skip thread_id (4)
            pos += 4;
            // read scramble_part1 (8 bytes)
            byte[] part1 = new byte[8];
            System.arraycopy(handshake, pos, part1, 0, 8);
            pos += 8;
            // skip filler (1)
            pos++;
            // skip capability_lower (2)
            pos += 2;
            // skip charset (1)
            pos++;
            // skip status (2)
            pos += 2;
            // skip capability_upper (2)
            pos += 2;
            // read auth_plugin_data_len (1)
            int plugLen = handshake[pos] & 0xFF;
            pos++;
            // skip reserved (10)
            pos += 10;
            // read scramble_part2: length = max(13, plugLen - 8), null-terminated
            int part2Len = Math.max(13, plugLen - 8);
            byte[] part2 = new byte[part2Len];
            System.arraycopy(handshake, pos, part2, 0, Math.min(part2Len, handshake.length - pos));
            // truncate at null
            int actualLen2 = 0;
            while (actualLen2 < part2.length && part2[actualLen2] != 0) actualLen2++;

            byte[] scramble = new byte[20];
            System.arraycopy(part1, 0, scramble, 0, 8);
            System.arraycopy(part2, 0, scramble, 8, Math.min(actualLen2, 12));
            return scramble;
        } catch (Exception e) {
            Log.w(TAG, "extractScramble error", e);
            return new byte[20];
        }
    }

    /** 构建 mysql_native_password 认证包 */
    private byte[] buildNativeAuthPacket(String username, String password, byte[] handshake) {
        try {
            byte[] scramble = extractScramble(handshake);
            // auth_response = SHA1(password) XOR SHA1(scramble + SHA1(SHA1(password)))
            byte[] sha1Pass = sha1Bytes(password.getBytes("UTF-8"));
            byte[] sha1Sha1Pass = sha1Bytes(sha1Pass);

            byte[] combined = new byte[scramble.length + sha1Sha1Pass.length];
            System.arraycopy(scramble, 0, combined, 0, scramble.length);
            System.arraycopy(sha1Sha1Pass, 0, combined, scramble.length, sha1Sha1Pass.length);
            byte[] sha1Combined = sha1Bytes(combined);

            byte[] authResp = new byte[20];
            for (int i = 0; i < 20; i++) {
                authResp[i] = (byte)(sha1Pass[i] ^ sha1Combined[i]);
            }

            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            // client capabilities: CLIENT_PROTOCOL_41 | CLIENT_SECURE_CONNECTION | CLIENT_PLUGIN_AUTH | CLIENT_CONNECT_WITH_DB (optional)
            int caps = 0x00A20003; // 标准
            writeInt4(baos, caps);
            writeInt4(baos, 16777215); // max packet size
            baos.write(33); // charset utf8mb4
            for (int i = 0; i < 23; i++) baos.write(0); // reserved
            baos.write(username.getBytes("UTF-8"));
            baos.write(0); // null terminator
            baos.write(authResp.length); // auth response length
            baos.write(authResp);
            // 不发送 auth plugin name（MariaDB 默认识别 mysql_native_password）

            return baos.toByteArray();
        } catch (Exception e) {
            Log.w(TAG, "buildNativeAuthPacket error", e);
            return new byte[0];
        }
    }

    /** MySQL protocol: build auth packet for --skip-grant-tables */
    private byte[] buildBackupAuthPacket(String user, byte[] handshake) {
        byte[] emptyHash = new byte[20];
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        try {
            writeInt4(baos, 0x00A20003);
            writeInt4(baos, 16777215);
            baos.write(33);
            for (int i = 0; i < 23; i++) baos.write(0);
            baos.write(user.getBytes());
            baos.write(0);
            baos.write(emptyHash.length);
            baos.write(emptyHash, 0, emptyHash.length);
        } catch (Exception ignored) {}
        return baos.toByteArray();
    }

    /** MySQL protocol: build COM_QUERY packet */
    private byte[] buildBackupQuery(String sql) {
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        try {
            baos.write(0x03);
            baos.write(sql.getBytes("UTF-8"));
        } catch (Exception ignored) {}
        return baos.toByteArray();
    }

    private long readLenEnc(java.io.InputStream in) throws Exception {
        int b = in.read();
        if (b < 0) throw new java.io.EOFException();
        if (b < 251) return b;
        if (b == 251) return -1;
        if (b == 252) return (in.read() & 0xFF) | ((in.read() & 0xFF) << 8);
        if (b == 253) return (in.read() & 0xFF) | ((in.read() & 0xFF) << 8) | ((in.read() & 0xFF) << 16);
        long v = 0;
        for (int i = 0; i < 8; i++) v |= ((long)(in.read() & 0xFF)) << (i * 8);
        return v;
    }

    private String readLenEncString(java.io.InputStream in) throws Exception {
        long len = readLenEnc(in);
        if (len < 0) return null;
        byte[] buf = new byte[(int)len];
        readFully(in, buf);
        return new String(buf, "UTF-8");
    }
    private void writeInt4(java.io.ByteArrayOutputStream baos, int v) {
        baos.write(v & 0xFF);
        baos.write((v >> 8) & 0xFF);
        baos.write((v >> 16) & 0xFF);
        baos.write((v >> 24) & 0xFF);
    }


}
