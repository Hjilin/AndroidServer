package com.zm920.androidserver.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.zm920.androidserver.MainActivity;
import com.zm920.androidserver.mefrp.MefrpConfig;
import com.zm920.androidserver.mefrp.MefrpManager;

/**
 * ME Frp 内网穿透前台服务。
 *
 * <p>Intent actions:
 * <ul>
 *   <li>{@link #ACTION_START} - 启动 mefrpc 进程</li>
 *   <li>{@link #ACTION_STOP}  - 停止 mefrpc 进程</li>
 * </ul>
 */
public class MefrpService extends Service {

    private static final String TAG = "MefrpService";
    private static final String CHANNEL_ID = "mefrp_channel";
    private static final int NOTIFICATION_ID = 1005;

    public static final String ACTION_START = "com.zm920.androidserver.MEFRP_START";
    public static final String ACTION_STOP = "com.zm920.androidserver.MEFRP_STOP";
    public static final String ACTION_STATUS = "com.zm920.androidserver.MEFRP_STATUS";
    public static final String EXTRA_URL = "url";
    public static final String EXTRA_RUNNING = "running";
    public static final String EXTRA_STATUS = "status";
    public static final String EXTRA_ERROR = "error";

    private static volatile boolean running = false;
    private static volatile String lastError = "";
    private static volatile String publicUrl = "";

    private Thread pollThread;
    private volatile String lastBroadcastUrl = "";
    private volatile String lastBroadcastStatus = "";
    private volatile boolean userStopped = false;
    private volatile boolean serviceDestroyed = false;
    private final Handler restartHandler = new Handler(Looper.getMainLooper());
    private static final long RESTART_DELAY_MS = 15000;
    private static final int POLL_INTERVAL_MS = 2000;

    public static synchronized boolean isRunning() { return running; }
    public static synchronized String getLastError() { return lastError; }
    public static synchronized String getPublicUrl() { return publicUrl; }

    @Override
    public void onCreate() {
        super.onCreate();
        publicUrl = MefrpConfig.getPublicUrl(this);
        lastError = MefrpConfig.getLastError(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForegroundInternal();

        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopForeground(true);
            doStop();
            stopSelf();
            return START_NOT_STICKY;
        }
        doStart();
        return START_STICKY;
    }

    private void doStart() {
        if (running) {
            Log.i(TAG, "already running");
            return;
        }
        userStopped = false;
        try {
            MefrpManager mgr = MefrpManager.get(this);
            mgr.setExitCallback(this::onMefrpcExited);
            if (mgr.start()) {
                running = true;
                lastError = "";
                Log.i(TAG, "mefrp service start issued");
                updateNotification("启动中...");
                lastBroadcastUrl = "";
                lastBroadcastStatus = "";
                broadcastStatus("starting", "");
            } else {
                lastError = mgr.getLastError();
                MefrpConfig.setLastError(this, lastError);
                running = false;
                Log.e(TAG, "mefrp start failed: " + lastError);
                updateNotification("启动失败: " + lastError);
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

    private void doStop() {
        userStopped = true;
        stopPoll();
        try {
            if (MefrpManager.isInitialized()) MefrpManager.get().stop();
        } catch (Exception e) {
            Log.e(TAG, "doStop error", e);
        }
        running = false;
        publicUrl = "";
        MefrpConfig.setPublicUrl(this, "");
        MefrpConfig.setLastError(this, "");
        lastError = "";
        updateNotification("已停止");
        lastBroadcastUrl = "FORCE_STOP_BROADCAST";
        lastBroadcastStatus = "";
        broadcastStatus("stopped", "");
    }

    @Override
    public void onDestroy() {
        serviceDestroyed = true;
        ForegroundServiceHelper.cancelAutoRestart(this);
        restartHandler.removeCallbacksAndMessages(null);
        try { stopForeground(true); } catch (Exception ignored) {}
        // 系统销毁 Service 时不主动杀 mefrpc，避免长连接被误断。
        // 只有用户点「停止」时 doStop() 才会真正停止进程。
        if (userStopped) {
            doStop();
        }
        super.onDestroy();
    }

    @Override
    public void onTimeout(int startId) {
        Log.w(TAG, "前台服务超时，重新启动");
        try {
            Intent restartIntent = new Intent(getApplicationContext(), MefrpService.class);
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
        pollThread = new Thread(this::pollLoop, "MefrpService-Poll");
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
        while (running && !Thread.currentThread().isInterrupted()) {
            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException e) { return; }
            try {
                String curUrl = "";
                String curStatus = "starting";
                if (MefrpManager.isInitialized()) {
                    MefrpManager mgr = MefrpManager.get();
                    if (mgr.isRunning()) {
                        curUrl = mgr.getPublicUrl();
                        if (curUrl.isEmpty()) curStatus = "starting";
                        else curStatus = "running";
                    } else {
                        // 进程不在运行时才检查 error
                        curStatus = "error";
                    }
                }
                if (curUrl.isEmpty()) {
                    curUrl = MefrpConfig.getPublicUrl(this);
                }
                publicUrl = curUrl;
                if (!curUrl.equals(lastBroadcastUrl) || !curStatus.equals(lastBroadcastStatus)) {
                    lastBroadcastUrl = curUrl;
                    lastBroadcastStatus = curStatus;
                    String notifText;
                    if ("running".equals(curStatus)) notifText = curUrl;
                    else if ("starting".equals(curStatus)) notifText = "启动中...";
                    else notifText = "错误: " + (MefrpManager.isInitialized() ? MefrpManager.get().getLastError() : "");
                    updateNotification(notifText);
                    broadcastStatus(curStatus, curUrl);
                }
            } catch (Exception e) {
                Log.w(TAG, "poll: " + e.getMessage());
            }
        }
    }

    private void onMefrpcExited() {
        // mefrpc 进程异常退出（非用户主动停止）
        running = false;
        Log.w(TAG, "mefrpc exited unexpectedly, scheduling restart");
        broadcastStatus("error", "");
        if (userStopped) return;
        restartHandler.removeCallbacksAndMessages(null);
        restartHandler.postDelayed(() -> {
            if (userStopped || serviceDestroyed) return;
            if (!MefrpConfig.isAutoStart(this)) return;
            if (!MefrpConfig.hasRequiredConfig(this)) return;
            if (!com.zm920.androidserver.network.NetworkUtil.isNetworkAvailable(this)) {
                Log.i(TAG, "restart: network not available, retry");
                restartHandler.postDelayed(this::tryRestart, RESTART_DELAY_MS);
                return;
            }
            tryRestart();
        }, RESTART_DELAY_MS);
    }

    private void tryRestart() {
        if (userStopped || running) return;
        if (!MefrpConfig.isAutoStart(this)) return;
        if (!MefrpConfig.hasRequiredConfig(this)) return;
        if (!com.zm920.androidserver.network.NetworkUtil.isNetworkAvailable(this)) {
            restartHandler.removeCallbacksAndMessages(null);
            restartHandler.postDelayed(this::tryRestart, RESTART_DELAY_MS);
            return;
        }
        Log.i(TAG, "restarting mefrpc");
        doStart();
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

    private void startForegroundInternal() {
        try {
            createNotificationChannel();
            ForegroundServiceHelper.startForegroundWithAutoRestart(
                    this, NOTIFICATION_ID, () -> buildNotification());
        } catch (Exception e) {
            Log.e(TAG, "startForeground failed", e);
        }
    }

    private Notification buildNotification() {
        Intent tap = new Intent(this, MainActivity.class);
        tap.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int piFlags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                ? PendingIntent.FLAG_IMMUTABLE : 0;
        PendingIntent pi = PendingIntent.getActivity(this, 0, tap, piFlags);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("ME Frp 内网穿透")
                .setContentText(publicUrl.isEmpty() ? (running ? "启动中..." : "已停止") : publicUrl)
                .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setContentIntent(pi)
                .build();
    }

    private void updateNotification(String text) {
        try {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.notify(NOTIFICATION_ID, buildNotification());
        } catch (Exception ignored) {}
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "ME Frp 内网穿透",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("ME Frp 后台运行通知");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }
}
