package com.zm920.androidserver.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import org.apache.ftpserver.FtpServer;
import org.apache.ftpserver.FtpServerFactory;
import org.apache.ftpserver.ftplet.Authority;
import org.apache.ftpserver.ftplet.FtpException;
import org.apache.ftpserver.listener.ListenerFactory;
import org.apache.ftpserver.usermanager.impl.BaseUser;
import org.apache.ftpserver.usermanager.impl.WritePermission;
import org.apache.ftpserver.usermanager.PropertiesUserManagerFactory;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.ServerSocket;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * FTP 服务 - Apache FtpServer 纯 Java 实现
 * 配置从 SharedPreferences 读取（端口 + 用户列表 JSON），默认监听 0.0.0.0（局域网 + 外网均可访问）
 *
 * 为避免通知栏常驻显示，运行后立即通过 stopForeground(STOP_FOREGROUND_DETACH) 移除通知，
 * 仅保留前台服务属性（避免被系统杀死），用户感知不到任何通知。
 */
public class FtpServerService extends Service {

    private static final String TAG = "FtpServerService";
    private static final String CHANNEL_ID = "ftp_server_channel";
    private static final int NOTIFICATION_ID = 1002;

    private static final String PREFS = "server_settings";
    private static final String KEY_PORT = "ftp_port";
    private static final String KEY_USERS = "ftp_users";
    private static final String KEY_BIND_WAN = "ftp_bind_wan";

    private static final String LOG_DIR = "ftp_logs";
    private static final String LOG_FILE = "ftp.log";
    private static final int MAX_LOG_SIZE = 512 * 1024;

    private static FtpServer ftpServer;
    private static boolean running = false;
    private static int currentPort = 2121;
    private static String lastError = "";

    public static synchronized boolean isRunning() { return running; }
    public static synchronized String getLastError() { return lastError; }
    public static synchronized int getCurrentPort() { return currentPort; }

    @Override
    public void onCreate() {
        super.onCreate();
        ensureLogDir();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (running) {
            writeLog("服务已经在运行（端口 " + currentPort + "）");
            sendStatusBroadcast();
            return START_STICKY;
        }

        // 必须先调用 startForeground（Android 8+ 要求 startForegroundService 启动后 5s 内调用）
        startForegroundInternal();

        SharedPreferences prefs = getSharedPreferences(PREFS, 0);
        int port = prefs.getInt(KEY_PORT, 2121);
        String usersJson = prefs.getString(KEY_USERS, "");
        boolean wan = prefs.getBoolean(KEY_BIND_WAN, false);

        if (port <= 0 || port > 65535) {
            port = 2121;
        }

        if (!isPortAvailable(port)) {
            String msg = "端口 " + port + " 已被占用";
            lastError = msg;
            writeLog("启动失败: " + msg);
            running = false;
            sendStatusBroadcast();
            stopSelf();
            return START_NOT_STICKY;
        }

        final int fPort = port;
        final String fUsers = usersJson;
        final boolean fWan = wan;
        currentPort = port;
        new Thread(() -> startFtpServer(fPort, fWan, fUsers)).start();

        return START_STICKY;
    }

    /** 启动前台服务并显示运行通知 */
    private void startForegroundInternal() {
        try {
            createNotificationChannel();
            // Android 14+ 自动传入 foregroundServiceType，低版本行为不变
            // 每 5h50min 自动重启前台状态，避免触发 Android 14 dataSync 6 小时限制
            ForegroundServiceHelper.startForegroundWithAutoRestart(
                    this, NOTIFICATION_ID, () -> buildNotification());
        } catch (Exception e) {
            Log.e(TAG, "startForegroundInternal error", e);
        }
    }

    /** 构建前台服务通知（每次重启时调用，获取最新端口） */
    private Notification buildNotification() {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("FTP 服务")
                .setContentText("运行中 - 端口 " + currentPort)
                .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .build();
    }

    private void updateNotification(String text) {
        try {
            createNotificationChannel();
            Notification n = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("FTP 服务")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .build();
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.notify(NOTIFICATION_ID, n);
        } catch (Exception e) {
            Log.e(TAG, "updateNotification error", e);
        }
    }

    private boolean isPortAvailable(int port) {
        try (ServerSocket socket = new ServerSocket(port)) {
            socket.setReuseAddress(true);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void startFtpServer(int port, boolean wan, String usersJson) {
        try {
            FtpServerFactory serverFactory = new FtpServerFactory();

            ListenerFactory listenerFactory = new ListenerFactory();
            listenerFactory.setPort(port);
            listenerFactory.setServerAddress(wan ? "0.0.0.0" : "127.0.0.1");
            listenerFactory.setIdleTimeout(300);
            serverFactory.addListener("default", listenerFactory.createListener());

            PropertiesUserManagerFactory userManagerFactory = new PropertiesUserManagerFactory();
            File userPropFile = new File(getFilesDir(), "ftp-users.properties");
            if (!userPropFile.exists()) {
                try { userPropFile.createNewFile(); } catch (Exception ignored) {}
            }
            userManagerFactory.setFile(userPropFile);
            serverFactory.setUserManager(userManagerFactory.createUserManager());

            int userCount = 0;
            if (usersJson != null && !usersJson.isEmpty()) {
                try {
                    org.json.JSONArray arr = new org.json.JSONArray(usersJson);
                    for (int i = 0; i < arr.length(); i++) {
                        org.json.JSONObject obj = arr.getJSONObject(i);
                        String name = obj.optString("username", "");
                        String pass = obj.optString("password", "");
                        String home = obj.optString("homeDir", "");
                        if (name.isEmpty()) continue;
                        if (home == null || home.isEmpty()) {
                            home = android.os.Environment.getExternalStorageDirectory().getAbsolutePath();
                        }

                        BaseUser user = new BaseUser();
                        user.setName(name);
                        if (!pass.isEmpty()) user.setPassword(pass);
                        user.setHomeDirectory(home);
                        List<Authority> authorities = new ArrayList<>();
                        authorities.add(new WritePermission());
                        user.setAuthorities(authorities);
                        try {
                            serverFactory.getUserManager().save(user);
                            userCount++;
                        } catch (FtpException e) {
                            writeLog("保存用户 " + name + " 失败: " + e.getMessage());
                            Log.e(TAG, "save user " + name + " failed", e);
                        }
                    }
                } catch (Exception e) {
                    writeLog("解析用户配置失败: " + e.getMessage());
                    Log.e(TAG, "parse users json error", e);
                }
            }

            ftpServer = serverFactory.createServer();
            ftpServer.start();

            running = true;
            lastError = "";
            currentPort = port;
            writeLog("FTP 服务启动成功，端口 " + port + "，监听地址 " + (wan ? "0.0.0.0（内网+外网）" : "127.0.0.1（仅本机）") + "，已加载 " + userCount + " 个用户");
            updateNotification("运行中 - 端口 " + port);
            sendStatusBroadcast();

        } catch (Exception e) {
            Log.e(TAG, "start error", e);
            running = false;
            lastError = e.getMessage() != null ? e.getMessage() : "未知错误";
            writeLog("启动异常: " + stackToString(e));
            sendStatusBroadcast();
            stopSelf();
        }
    }

    @Override
    public void onDestroy() {
        // 取消定时重启，防止内存泄漏
        ForegroundServiceHelper.cancelAutoRestart(this);
        stopFtpServer();
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
            Intent restartIntent = new Intent(getApplicationContext(), FtpServerService.class);
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

    private void stopFtpServer() {
        if (ftpServer != null) {
            try { ftpServer.stop(); } catch (Exception e) { Log.e(TAG, "stop error", e); }
            ftpServer = null;
        }
        if (running) {
            writeLog("FTP 服务已停止");
            updateNotification("已停止");
        }
        running = false;
        sendStatusBroadcast();
    }

    public static void stopFtp(Context ctx) {
        if (ctx != null) {
            try { ctx.stopService(new Intent(ctx, FtpServerService.class)); } catch (Exception ignored) {}
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return null; }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "FTP 服务", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("FTP 服务器运行状态");
            channel.setShowBadge(false);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    private void sendStatusBroadcast() {
        sendBroadcast(new Intent("com.zm920.androidserver.FTP_STATUS"));
    }

    // ----- 日志 -----

    private void ensureLogDir() {
        File d = new File(getFilesDir(), LOG_DIR);
        if (!d.exists()) d.mkdirs();
    }

    private File getLogFile() {
        return new File(getFilesDir(), LOG_DIR + "/" + LOG_FILE);
    }

    private void writeLog(String msg) {
        try {
            ensureLogDir();
            File f = getLogFile();
            if (f.exists() && f.length() > MAX_LOG_SIZE) {
                f.delete();
            }
            String time = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(new Date());
            FileWriter fw = new FileWriter(f, true);
            fw.write("[" + time + "] " + msg + "\n");
            fw.close();
        } catch (Exception e) {
            Log.e(TAG, "write log error", e);
        }
    }

    public static String readLog(Context ctx) {
        try {
            File f = new File(ctx.getFilesDir(), LOG_DIR + "/" + LOG_FILE);
            if (!f.exists()) return "";
            long size = f.length();
            int readSize = (int) Math.min(size, 200 * 1024);
            byte[] buf = new byte[readSize];
            try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(f, "r")) {
                raf.seek(size - readSize);
                int read = raf.read(buf);
                if (read < readSize) {
                    byte[] nb = new byte[read];
                    System.arraycopy(buf, 0, nb, 0, read);
                    buf = nb;
                }
            }
            String content = new String(buf, "UTF-8");
            int nl = content.indexOf('\n');
            if (nl > 0 && nl < 60) content = content.substring(nl + 1);
            return content;
        } catch (Exception e) {
            return "读取日志失败: " + e.getMessage();
        }
    }

    public static void clearLog(Context ctx) {
        try {
            File f = new File(ctx.getFilesDir(), LOG_DIR + "/" + LOG_FILE);
            if (f.exists()) f.delete();
        } catch (Exception ignored) {}
    }

    private String stackToString(Exception e) {
        StringWriter sw = new StringWriter();
        e.printStackTrace(new PrintWriter(sw));
        String s = sw.toString();
        return s.length() > 600 ? s.substring(0, 600) + "..." : s;
    }
}
