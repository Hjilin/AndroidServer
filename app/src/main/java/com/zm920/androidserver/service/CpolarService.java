package com.zm920.androidserver.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.zm920.androidserver.MainActivity;
import com.zm920.androidserver.cpolar.CpolarConfig;
import com.zm920.androidserver.cpolar.CpolarManager;
import com.zm920.androidserver.cpolar.SimpleHttpServer;

import java.io.File;

/**
 * cpolar 内网穿透前台服务。
 *
 * <p>Intent actions:
 * <ul>
 *   <li>{@link #ACTION_START} - 启动 cpolar 进程</li>
 *   <li>{@link #ACTION_STOP}  - 停止 cpolar 进程</li>
 * </ul>
 *
 * <p>状态通过 SharedPreferences "cpolar_public_url" / "cpolar_last_error" 暴露。
 */
public class CpolarService extends Service {

    private static final String TAG = "CpolarService";
    private static final String CHANNEL_ID = "cpolar_channel";
    private static final int NOTIFICATION_ID = 1004;

    public static final String ACTION_START = "com.zm920.androidserver.CPOLAR_START";
    public static final String ACTION_STOP = "com.zm920.androidserver.CPOLAR_STOP";

    private static volatile boolean running = false;
    private static volatile String lastError = "";
    private static volatile String publicUrl = "";

    public static final String ACTION_STATUS = "com.zm920.androidserver.CPOLAR_STATUS";
    public static final String EXTRA_URL = "url";
    public static final String EXTRA_RUNNING = "running";
    public static final String EXTRA_STATUS = "status";  // starting / running / stopped / error
    public static final String EXTRA_ERROR = "error";

    private Thread pollThread;
    private volatile String lastBroadcastUrl = "";
    private volatile String lastBroadcastStatus = "";

    public static synchronized boolean isRunning() { return running; }
    public static synchronized String getLastError() { return lastError; }
    public static synchronized String getPublicUrl() { return publicUrl; }

    @Override
    public void onCreate() {
        super.onCreate();
        // 同步一次静态状态（进程被杀后从 prefs 恢复）
        publicUrl = CpolarConfig.getPublicUrl(this);
        lastError = CpolarConfig.getLastError(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // 必须先调 startForeground 避免 ANR
        startForegroundInternal();

        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopForeground(true);
            doStop();
            stopSelf();
            return START_NOT_STICKY;
        }
        // 默认 / ACTION_START
        doStart();
        return START_STICKY;
    }

    private void doStart() {
        if (running) {
            Log.i(TAG, "already running");
            return;
        }
        try {
            int targetPort = CpolarConfig.getTargetPort(this);

            // 0) 端口合法性
            if (targetPort <= 0 || targetPort > 65535) {
                String msg = "目标端口非法: " + targetPort;
                Log.e(TAG, msg);
                lastError = msg;
                CpolarConfig.setLastError(this, msg);
                updateNotification("启动失败: " + msg);
                lastBroadcastUrl = "";
                lastBroadcastStatus = "";
                broadcastStatus("error", "");
                return;
            }
            // 直接启动 cpolar（不管端口状态如何）

            // 启 cpolar
            CpolarManager mgr = CpolarManager.get(this);
            if (mgr.start()) {
                running = true;
                lastError = "";
                Log.i(TAG, "cpolar service start issued");
                updateNotification("启动中...");
                // 关键状态变化立即广播，不等 600ms 轮询（解决"启动慢、需切窗口才更新"）
                lastBroadcastUrl = ""; // 强制下次 broadcast 必发
                lastBroadcastStatus = "";
                broadcastStatus("starting", "");
            } else {
                lastError = mgr.getLastError();
                CpolarConfig.setLastError(this, lastError);
                running = false; // 启动失败立刻置 false
                Log.e(TAG, "cpolar start failed: " + lastError);
                updateNotification("启动失败: " + lastError);
                // 失败状态也立即广播
                lastBroadcastUrl = "";
                lastBroadcastStatus = "";
                broadcastStatus("error", "");
            }
            startPoll();
        } catch (Exception e) {
            Log.e(TAG, "doStart error", e);
            lastError = e.getMessage();
            updateNotification("启动异常: " + e.getMessage());
        }
    }

    /** App 内置的 SimpleHttpServer 单例（兜底用） */
    private static SimpleHttpServer simpleHttp;

    /**
     * 在 app 沙箱内启动一个纯 Java HTTP 服务，让 cpolar 有目标可转发。
     * 如果目标端口是 18080（或 8080），自动启 SimpleHttpServer 监听该端口。
     * 其它端口由用户自行保证有服务。
     */
    private void ensureTargetServiceRunning() {
        try {
            int port = CpolarConfig.getTargetPort(this);
            // 兜底：用 SimpleHttpServer 监听目标端口（只对 18080 / 8080）
            if (port == 18080 || port == 8080) {
                if (simpleHttp == null || simpleHttp.getPort() != port || !simpleHttp.isRunning()) {
                    if (simpleHttp != null) simpleHttp.stop();
                    simpleHttp = new SimpleHttpServer(this, port, "127.0.0.1");
                    boolean ok = simpleHttp.start();
                    Log.i(TAG, "SimpleHttpServer start: " + ok + " on port " + port);
                }
            } else {
                Log.i(TAG, "port " + port + ": user must ensure target service");
            }
        } catch (Exception e) {
            Log.w(TAG, "ensureTargetServiceRunning: " + e.getMessage());
        }
    }

    public static SimpleHttpServer getSimpleHttp() { return simpleHttp; }

    private com.zm920.androidserver.server.WebServer buildWebServer() {
        try {
            android.content.SharedPreferences prefs = getSharedPreferences("server_settings", 0);
            com.zm920.androidserver.service.ProcessManager pm = com.zm920.androidserver.service.ProcessManager.getInstance(getFilesDir());
            com.zm920.androidserver.server.BinaryDeployer deployer = new com.zm920.androidserver.server.BinaryDeployer(this, prefs);
            com.zm920.androidserver.server.StartupLogger sl = new com.zm920.androidserver.server.StartupLogger(this);
            String dataDir = getFilesDir().getAbsolutePath() + "/server";
            com.zm920.androidserver.config.ConfigGenerator cg = new com.zm920.androidserver.config.ConfigGenerator(new java.io.File(dataDir + "/config"), prefs);
            return new com.zm920.androidserver.server.WebServer(this, pm, cg, prefs, deployer, sl);
        } catch (Exception e) {
            Log.e(TAG, "buildWebServer failed: " + e.getMessage());
            return null;
        }
    }

    /**
     * 推断某端口被本应用的哪个组件占用（仅用于日志/UI 提示，不影响启动决策）
     *  - 优先查运行中的服务（WsServer / FtpServer / MariaDB / Redis）
     *  - 再查 sites 列表（即便未启动也提示用户这是预留端口）
     *  - 最后查 SharedPreferences 里的常见服务端口
     * @return 描述字符串，没匹配返回 null
     */
    private String describePort(int port) {
        try {
            // 1) 运行中的本 app 服务
            if (com.zm920.androidserver.service.WsServerService.isRunning()
                    && com.zm920.androidserver.service.WsServerService.getCurrentPort() == port) {
                return "WebSocket 服务";
            }
            if (com.zm920.androidserver.service.FtpServerService.isRunning()) {
                android.content.SharedPreferences ftpSp = getSharedPreferences("server_settings", 0);
                if (ftpSp.getInt("ftp_port", 2121) == port) return "FTP 服务";
            }
            // 2) sites 列表（即便 site 未启动也提示）
            android.content.SharedPreferences sp = getSharedPreferences("server_settings", 0);
            java.util.Set<String> sites = sp.getStringSet("sites", new java.util.HashSet<>());
            for (String s : sites) {
                String[] parts = s.split("\\|");
                if (parts.length >= 2) {
                    try {
                        if (Integer.parseInt(parts[1]) == port) {
                            return "网站「" + parts[0] + "」";
                        }
                    } catch (NumberFormatException ignored) {}
                }
            }
            // 3) Prefs 里的常见服务端口
            if (sp.getInt("ws_port", 8080) == port && !com.zm920.androidserver.service.WsServerService.isRunning()) {
                return "WebSocket（已配置未启动）";
            }
            if (sp.getInt("ftp_port", 2121) == port && !com.zm920.androidserver.service.FtpServerService.isRunning()) {
                return "FTP（已配置未启动）";
            }
            if (sp.getInt("mysql_port", 3306) == port) return "MariaDB";
            if (sp.getInt("redis_port", 6379) == port) return "Redis";
        } catch (Exception ignored) {}
        return null;
    }

    /**
     * 查 Prefs 中是否配了该端口：只要配了（即使服务没启动）就视为冲突，
     * 不允许 cpolar 再用这个端口做穿透。
     * 优先级：sites 列表 → 常见服务端口（ws / ftp / nginx / mysql / redis / cpolar）
     */
    private String findConfiguredServiceOnPort(int port) {
        try {
            android.content.SharedPreferences sp = getSharedPreferences("server_settings", 0);
            // 1) sites 列表（添加网站时填的端口）
            java.util.Set<String> sites = sp.getStringSet("sites", new java.util.HashSet<>());
            for (String s : sites) {
                String[] parts = s.split("\\|");
                if (parts.length >= 2) {
                    try {
                        if (Integer.parseInt(parts[1]) == port) {
                            return "网站「" + parts[0] + "」";
                        }
                    } catch (NumberFormatException ignored) {}
                }
            }
            // 2) 常见服务端口（不管有没有启动，只要配置了就不准 cpolar 用）
            if (sp.getInt("ws_port", 8080) == port) return "WebSocket";
            if (sp.getInt("ftp_port", 2121) == port) return "FTP";
            if (sp.getInt("ngx_port", 8080) == port) return "Nginx/网站";
            if (sp.getInt("mysql_port", 3306) == port) return "MariaDB";
            if (sp.getInt("redis_port", 6379) == port) return "Redis";
            if (sp.getInt("cpolar_webui_port", 9200) == port) return "cpolar WebUI";
        } catch (Exception ignored) {}
        return null;
    }

    private void doStop() {
        stopPoll();
        try {
            if (CpolarManager.isInitialized()) CpolarManager.get().stop();
            // 顺便停 SimpleHttpServer
            if (simpleHttp != null && simpleHttp.isRunning()) {
                simpleHttp.stop();
                Log.i(TAG, "SimpleHttpServer stopped");
            }
        } catch (Exception e) {
            Log.e(TAG, "doStop error", e);
        }
        running = false;
        publicUrl = "";
        CpolarConfig.setPublicUrl(this, "");
        CpolarConfig.setLastError(this, "");
        lastError = "";
        updateNotification("已停止");
        // 关键状态变化立即广播（不等轮询），让 UI 立刻显示"未启动"
        lastBroadcastUrl = "FORCE_STOP_BROADCAST";
        lastBroadcastStatus = "";
        broadcastStatus("stopped", "");
    }

    @Override
    public void onDestroy() {
        // 取消定时重启，防止内存泄漏
        ForegroundServiceHelper.cancelAutoRestart(this);
        try { stopForeground(true); } catch (Exception ignored) {}
        doStop();
        super.onDestroy();
    }

    /**
     * Android 14+ 兜底：前台服务即将被系统强制停止时回调。
     * 重新启动服务，保证服务持续运行。
     */
    @Override
    public void onTimeout(int startId) {
        Log.w(TAG, "前台服务超时，重新启动");
        try {
            Intent restartIntent = new Intent(getApplicationContext(), CpolarService.class);
            restartIntent.setAction(ACTION_START);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(restartIntent);
            } else {
                startService(restartIntent);
            }
        } catch (Exception e) {
            Log.e(TAG, "重启服务失败", e);
        }
        super.onTimeout(startId);
    }


    private void startPoll() {
        stopPoll();
        pollThread = new Thread(this::pollLoop, "CpolarService-Poll");
        pollThread.setDaemon(true);
        pollThread.start();
    }

    private void stopPoll() {
        if (pollThread != null) {
            pollThread.interrupt();
            pollThread = null;
        }
    }

    private void pollLoop() {
        // 快速轮询，URL 出现后立即广播（不超过 200ms 延迟）
        while (running && !Thread.currentThread().isInterrupted()) {
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) { return; }
            try {
                String curUrl = "";
                String curStatus = "starting";
                if (CpolarManager.isInitialized()) {
                    CpolarManager mgr = CpolarManager.get();
                    if (mgr.isRunning()) {
                        curUrl = mgr.getPublicUrl();
                        if (curUrl.isEmpty()) curStatus = "starting";
                        else curStatus = "running";
                    } else {
                        curStatus = "error";
                    }
                }
                // 同步 publicUrl static
                if (curUrl.isEmpty()) {
                    curUrl = CpolarConfig.getPublicUrl(this);
                }
                publicUrl = curUrl;
                // 状态或 URL 变化时再更新通知和广播
                if (!curUrl.equals(lastBroadcastUrl) || !curStatus.equals(lastBroadcastStatus)) {
                    lastBroadcastUrl = curUrl;
                    lastBroadcastStatus = curStatus;
                    String notifText;
                    if ("running".equals(curStatus)) notifText = curUrl;
                    else if ("starting".equals(curStatus)) notifText = "启动中...";
                    else notifText = "错误: " + (CpolarManager.isInitialized() ? CpolarManager.get().getLastError() : "");
                    updateNotification(notifText);
                    broadcastStatus(curStatus, curUrl);
                }
            } catch (Exception e) {
                Log.w(TAG, "poll: " + e.getMessage());
            }
        }
    }

    private void broadcastStatus(String status, String url) {
        try {
            Intent i = new Intent(ACTION_STATUS);
            i.setPackage(getPackageName());
            i.putExtra(EXTRA_STATUS, status);
            i.putExtra(EXTRA_URL, url == null ? "" : url);
            i.putExtra(EXTRA_RUNNING, "running".equals(status));
            i.putExtra(EXTRA_ERROR, lastError == null ? "" : lastError);
            sendBroadcast(i);
        } catch (Exception e) {
            Log.w(TAG, "broadcast: " + e.getMessage());
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return null; }

    /* ----- 通知 ----- */

    private void startForegroundInternal() {
        try {
            createNotificationChannel();
            // Android 14+ 自动传入 foregroundServiceType，低版本行为不变
            // 每 5h50min 自动重启前台状态，避免触发 Android 14 dataSync 6 小时限制
            ForegroundServiceHelper.startForegroundWithAutoRestart(
                    this, NOTIFICATION_ID, () -> buildNotification());
        } catch (Exception e) {
            Log.e(TAG, "startForeground failed", e);
        }
    }

    /** 构建前台服务通知（每次重启时调用，获取最新公网 URL） */
    private Notification buildNotification() {
        Intent tap = new Intent(this, MainActivity.class);
        tap.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int piFlags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                ? PendingIntent.FLAG_IMMUTABLE : 0;
        PendingIntent pi = PendingIntent.getActivity(this, 0, tap, piFlags);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("cpolar 内网穿透")
                .setContentText(publicUrl.isEmpty() ? (running ? "启动中..." : "已停止") : publicUrl)
                .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setContentIntent(pi)
                .build();
    }

    private void updateNotification(String text) {
        try {
            Notification n = new NotificationCompat.Builder(this, CHANNEL_ID)
                    .setContentTitle("cpolar 内网穿透")
                    .setContentText(text)
                    .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
                    .setOngoing(true)
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .build();
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.notify(NOTIFICATION_ID, n);
        } catch (Exception e) {
            Log.e(TAG, "updateNotification failed", e);
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "cpolar 内网穿透", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("cpolar 客户端运行通知");
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }
}
