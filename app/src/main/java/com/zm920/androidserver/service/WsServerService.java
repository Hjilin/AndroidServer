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

import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import java.net.InetSocketAddress;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Deque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.zm920.androidserver.network.PortUtil;

/**
 * WebSocket 服务端 - 纯 Java 实现（org.java-websocket）
 *
 * <p>Intent actions:
 * <ul>
 *   <li>{@link #ACTION_START} - 启动 WebSocket 服务（Extra: port）</li>
 *   <li>{@link #ACTION_STOP}  - 停止</li>
 *   <li>{@link #ACTION_SEND}  - 广播消息（Extra: message）</li>
 *   <li>{@link #ACTION_BAN}   - 封禁 IP（Extra: ip）</li>
 *   <li>{@link #ACTION_UNBAN} - 解封 IP（Extra: ip）</li>
 * </ul>
 *
 * <p>状态通过 {@link #ACTION_LOG} Broadcast 通知 UI，
 * 携带 {@link #EXTRA_LOG}（文本）、{@link #EXTRA_CLIENT_COUNT}（当前连接数）。
 */
public class WsServerService extends Service {

    private static final String TAG = "WsServerService";
    private static final String CHANNEL_ID = "ws_server_channel";
    private static final int NOTIFICATION_ID = 1003;

    private static final String PREFS = "server_settings";
    private static final String KEY_WS_PORT = "ws_port";
    private static final String KEY_WS_TOKEN = "ws_token";
    private static final String KEY_WS_BIND_WAN = "ws_bind_wan";
    private static final String KEY_WS_BAN_IPS = "ws_ban_ips";
    private static final int DEFAULT_PORT = 9999;

    // 限速/限连接
    private static final int MAX_FRAME_SIZE = 4096;     // 单帧 <= 4 KB
    private static final int MAX_MESSAGE_SIZE = 8192;   // 单消息 <= 8 KB
    private static final int MAX_CLIENTS = 20;          // 同时最多 20 个客户端
    private static final int RATE_LIMIT_PER_SEC = 20;   // 每客户端每秒最多 20 条

    // IP 频率限制：60s 内失败 5 次 -> 临时封禁 15 分钟；可被管理员手动永久封禁
    private static final int IP_FAIL_LIMIT = 5;
    private static final long IP_FAIL_WINDOW_MS = 60_000L;
    private static final long IP_BAN_DURATION_MS = 15 * 60_000L;

    // 最近消息环形缓冲（最近 200 条）
    private static final int MAX_RECENT_MESSAGES = 200;
    private static final int MAX_LOG_CONTENT_CHARS = 200;   // 写文件/UI 时截断
    public static final int BIND_LOOPBACK = 0;
    public static final int BIND_ALL = 1;

    public static final String ACTION_START = "com.zm920.androidserver.WS_START";
    public static final String ACTION_STOP = "com.zm920.androidserver.WS_STOP";
    public static final String ACTION_SEND = "com.zm920.androidserver.WS_SEND";
    public static final String ACTION_BAN = "com.zm920.androidserver.WS_BAN";
    public static final String ACTION_UNBAN = "com.zm920.androidserver.WS_UNBAN";
    public static final String ACTION_LOG = "com.zm920.androidserver.WS_LOG";
    public static final String EXTRA_PORT = "port";
    public static final String EXTRA_MESSAGE = "message";
    public static final String EXTRA_IP = "ip";
    public static final String EXTRA_LOG = "log";
    public static final String EXTRA_CLIENT_COUNT = "client_count";
    public static final String EXTRA_RUNNING = "running";
    /** 启动失败时给 UI 的建议（如"请到设置页修改 WebSocket 端口"） */
    public static final String EXTRA_SUGGESTION = "suggestion";
    /** 启动失败时的错误码（1=端口占用 2=权限 3=其他） */
    public static final String EXTRA_ERROR_CODE = "error_code";

    private static final String LOG_DIR = "ws_logs";
    private static final String LOG_FILE = "ws.log";
    private static final int MAX_LOG_SIZE = 512 * 1024;

    private static volatile boolean running = false;
    private static volatile int currentPort = DEFAULT_PORT;
    private static volatile int clientCount = 0;
    private static volatile String lastError = "";
    private WsServerImpl server;

    /* ====== 静态共享状态（service 重启时清空，bannedIps 中永久项会从 Prefs 恢复） ====== */

    /** 在线客户端：WebSocket -> 客户端元信息 */
    private static final ConcurrentHashMap<WebSocket, ClientInfo> activeClients = new ConcurrentHashMap<>();
    /** 封禁列表：ip -> 解禁时刻 (epoch ms)，0 表示永久 */
    private static final ConcurrentHashMap<String, Long> bannedIps = new ConcurrentHashMap<>();
    /** IP 失败计数：ip -> [窗口起始ms, 窗口内失败次数] */
    private static final ConcurrentHashMap<String, long[]> connectAttempts = new ConcurrentHashMap<>();
    /** 最近收到的消息（环形） */
    private static final Deque<RecentMessage> recentMessages = new java.util.concurrent.LinkedBlockingDeque<>();

    /* ====== 数据类 ====== */

    /** 活跃客户端信息（用于 UI 展示） */
    public static class ClientInfo {
        public final String ip;            // "192.168.1.5"
        public final int remotePort;       // 远端端口
        public final long connectTime;     // 连接时刻
        public volatile long messageCount; // 累计消息数
        public volatile long lastSeen;     // 最后一次收到消息的时刻
        public volatile String lastMessage;// 最近一条消息（截断）

        public ClientInfo(String ip, int remotePort) {
            this.ip = ip;
            this.remotePort = remotePort;
            this.connectTime = System.currentTimeMillis();
            this.lastSeen = this.connectTime;
            this.messageCount = 0;
            this.lastMessage = "";
        }
    }

    /** 封禁条目 */
    public static class BannedIp {
        public final String ip;
        public final long banUntil;   // 0 = 永久
        public final String reason;

        public BannedIp(String ip, long banUntil, String reason) {
            this.ip = ip;
            this.banUntil = banUntil;
            this.reason = reason;
        }

        public boolean isPermanent() { return banUntil <= 0L; }
    }

    /** 最近收到的一条消息 */
    public static class RecentMessage {
        public final long time;
        public final String ip;
        public final String content;  // 截断

        public RecentMessage(long time, String ip, String content) {
            this.time = time;
            this.ip = ip;
            this.content = content;
        }
    }

    /* ====== 公开 API ====== */

    public static synchronized boolean isRunning() { return running; }
    public static synchronized int getCurrentPort() { return currentPort; }
    public static synchronized int getClientCount() { return clientCount; }
    public static synchronized String getLastError() { return lastError; }

    /** 当前在线客户端快照（线程安全 copy） */
    public static List<ClientInfo> getActiveClients() {
        List<ClientInfo> out = new ArrayList<>();
        out.addAll(activeClients.values());
        return out;
    }

    /** 当前封禁列表（自动清理过期项） */
    public static List<BannedIp> getBannedIps() {
        long now = System.currentTimeMillis();
        List<BannedIp> out = new ArrayList<>();
        for (java.util.Map.Entry<String, Long> e : bannedIps.entrySet()) {
            long until = e.getValue();
            // 0 = 永久；大于 0 且已过期 -> 跳过
            if (until > 0 && until <= now) continue;
            out.add(new BannedIp(e.getKey(), until, "manual"));
        }
        return out;
    }

    /** 最近收到的消息（最多 200 条） */
    public static List<RecentMessage> getRecentMessages() {
        List<RecentMessage> out = new ArrayList<>();
        synchronized (recentMessages) {
            out.addAll(recentMessages);
        }
        return out;
    }

    /** 清空最近消息缓存（不影响磁盘日志） */
    public static void clearRecentMessages() {
        synchronized (recentMessages) {
            recentMessages.clear();
        }
    }

    /**
     * 封禁 IP
     * @param durationMs 封禁时长（ms），0 或负数表示永久
     */
    public static void banIp(String ip, long durationMs) {
        if (ip == null || ip.isEmpty()) return;
        long until = durationMs <= 0 ? 0L : System.currentTimeMillis() + durationMs;
        bannedIps.put(ip, until);
        // 踢掉该 IP 已建立的连接
        for (java.util.Map.Entry<WebSocket, ClientInfo> e : activeClients.entrySet()) {
            if (ip.equals(e.getValue().ip)) {
                try { e.getKey().close(4003, "banned"); } catch (Exception ignored) {}
            }
        }
        // 同步持久化永久封禁
        persistBannedIps();
    }

    /** 解除封禁 */
    public static void unbanIp(String ip) {
        if (ip == null) return;
        bannedIps.remove(ip);
        persistBannedIps();
    }

    private static void persistBannedIps() {
        // 仅持久化永久项（until<=0），临时封禁重启后失效
        Set<String> perm = new HashSet<>();
        for (java.util.Map.Entry<String, Long> e : bannedIps.entrySet()) {
            if (e.getValue() <= 0L) perm.add(e.getKey());
        }
        try {
            android.app.Application app = currentApplication();
            if (app != null) {
                app.getSharedPreferences(PREFS, 0).edit().putStringSet(KEY_WS_BAN_IPS, perm).apply();
            }
        } catch (Exception ignored) {}
    }

    private static android.app.Application currentApplication() {
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Object at = activityThread.getMethod("currentApplication").invoke(null);
            return (android.app.Application) at;
        } catch (Exception e) {
            return null;
        }
    }

    private static void loadBannedIps() {
        try {
            android.app.Application app = currentApplication();
            if (app == null) return;
            Set<String> perm = app.getSharedPreferences(PREFS, 0).getStringSet(KEY_WS_BAN_IPS, null);
            if (perm != null) {
                for (String ip : perm) bannedIps.put(ip, 0L);
            }
        } catch (Exception ignored) {}
    }

    /* ====== Service 生命周期 ====== */

    @Override
    public void onCreate() {
        super.onCreate();
        ensureLogDir();
        loadBannedIps();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        String action = intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopServer();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_SEND.equals(action)) {
            String msg = intent.getStringExtra(EXTRA_MESSAGE);
            if (server != null && msg != null) {
                server.broadcastMessage(msg);
                appendLog("广播: " + msg);
            }
            return START_STICKY;
        }
        if (ACTION_BAN.equals(action)) {
            String ip = intent.getStringExtra(EXTRA_IP);
            if (ip != null) {
                banIp(ip, 0L); // 永久封禁
                appendLog("已封禁 IP: " + ip);
                sendLog("已封禁 IP " + ip, clientCount);
            }
            // 若服务本身没在跑（仅为响应封禁 intent 而短暂启动），立即退出
            if (!running) stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_UNBAN.equals(action)) {
            String ip = intent.getStringExtra(EXTRA_IP);
            if (ip != null) {
                unbanIp(ip);
                appendLog("已解封 IP: " + ip);
                sendLog("已解封 IP " + ip, clientCount);
            }
            if (!running) stopSelf();
            return START_NOT_STICKY;
        }
        // 默认 / ACTION_START
        int port = intent.getIntExtra(EXTRA_PORT, 0);
        if (port <= 0 || port > 65535) {
            port = prefs(getApplicationContext()).getInt(KEY_WS_PORT, DEFAULT_PORT);
        }
        startServer(port);
        return START_STICKY;
    }

    private void startServer(int port) {
        // 必须先 startForeground（Android 8+ 要求 5s 内调用）
        startForegroundInternal(port);

        if (running) {
            // 已经在跑：换端口就重启
            stopServer();
        }

        currentPort = port;
        // 读取公网开关：默认只监听 127.0.0.1（防未授权访问），开启公网才监听 0.0.0.0
        boolean bindWan = prefs(getApplicationContext()).getBoolean(KEY_WS_BIND_WAN, false);
        String bindAddr = bindWan ? "0.0.0.0" : "127.0.0.1";

        // ---- 同步预检端口：避免 Java-WebSocket.start() 异步 bind 失败导致 running 错乱 ----
        // 用与正式 bind 相同的目标地址探测一次
        PortUtil.Result r = PortUtil.check(bindAddr, port);
        if (!r.available) {
            running = false;
            lastError = r.reason;
            String hint;
            int code;
            if (r.reason != null && r.reason.contains("占用")) {
                hint = "端口 " + port + " 被其他程序占用，请到「设置 → WebSocket 端口」修改后重试";
                code = 1;
            } else if (r.reason != null && r.reason.contains("权限")) {
                hint = r.reason + "，请到「设置 → 允许外网连接」切换或选择其他端口";
                code = 2;
            } else {
                hint = r.reason + "，请检查后重试";
                code = 3;
            }
            Log.e(TAG, "port check failed: " + r.reason);
            appendLog("启动失败: " + lastError);
            appendLog("建议: " + hint);
            sendLog("启动失败: " + lastError, 0, hint, code);
            stopSelf();
            return;
        }

        try {
            server = new WsServerImpl(port, bindAddr);
            server.start();
            running = true;
            lastError = "";
            appendLog("WebSocket 服务启动成功，端口 " + port);
            appendLog("连接地址: ws://手机IP:" + port);
            sendLog("服务已启动，端口 " + port, 0);
        } catch (Exception e) {
            running = false;
            lastError = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            Log.e(TAG, "start failed", e);
            String hint = "启动失败: " + lastError;
            appendLog(hint);
            sendLog(hint, 0, hint, 3);
            stopSelf();
        }
    }

    private void stopServer() {
        try {
            if (server != null) {
                server.stop(1000);
                server = null;
            }
        } catch (Exception e) {
            Log.e(TAG, "stop error", e);
        }
        running = false;
        // 清空运行时状态（封禁列表中临时项也清除，永久项保留）
        activeClients.clear();
        connectAttempts.clear();
        recentMessages.clear();
        // 临时封禁也清掉（避免服务空闲时大量占内存）
        Iterator<java.util.Map.Entry<String, Long>> it = bannedIps.entrySet().iterator();
        while (it.hasNext()) {
            java.util.Map.Entry<String, Long> e = it.next();
            if (e.getValue() > 0L) it.remove();
        }
        clientCount = 0;
        appendLog("服务已停止");
        sendLog("服务已停止", 0);
        updateNotification("已停止");
    }

    @Override
    public void onDestroy() {
        // 取消定时重启，防止内存泄漏
        ForegroundServiceHelper.cancelAutoRestart(this);
        stopServer();
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
            Intent restartIntent = new Intent(getApplicationContext(), WsServerService.class);
            restartIntent.setAction(ACTION_START);
            restartIntent.putExtra(EXTRA_PORT, currentPort);
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

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return null; }

    /* ----- 通知 ----- */

    private void startForegroundInternal(int port) {
        try {
            createNotificationChannel();
            // Android 14+ 自动传入 foregroundServiceType，低版本行为不变
            // 每 5h50min 自动重启前台状态，避免触发 Android 14 dataSync 6 小时限制
            ForegroundServiceHelper.startForegroundWithAutoRestart(
                    this, NOTIFICATION_ID, () -> buildNotification("运行中 - 端口 " + currentPort));
        } catch (Exception e) {
            Log.e(TAG, "startForeground failed", e);
        }
    }

    private void updateNotification(String text) {
        try {
            Notification n = buildNotification(text);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.notify(NOTIFICATION_ID, n);
        } catch (Exception ignored) {}
    }

    private Notification buildNotification(String text) {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("WebSocket 服务")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "WebSocket 服务", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("WebSocket 服务器运行状态");
            channel.setShowBadge(false);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    /* ----- 日志 ----- */

    private void sendLog(String text, int count) { sendLog(text, count, "", 0); }

    private void sendLog(String text, int count, String suggestion, int errorCode) {
        try {
            Intent i = new Intent(ACTION_LOG);
            i.setPackage(getPackageName());
            i.putExtra(EXTRA_LOG, text);
            i.putExtra(EXTRA_CLIENT_COUNT, count);
            i.putExtra(EXTRA_RUNNING, running);
            if (suggestion != null && !suggestion.isEmpty()) {
                i.putExtra(EXTRA_SUGGESTION, suggestion);
                i.putExtra(EXTRA_ERROR_CODE, errorCode);
            }
            sendBroadcast(i);
        } catch (Exception ignored) {}
    }

    private void ensureLogDir() {
        try {
            java.io.File d = new java.io.File(getFilesDir(), LOG_DIR);
            if (!d.exists()) d.mkdirs();
        } catch (Exception ignored) {}
    }

    private void appendLog(String msg) {
        try {
            ensureLogDir();
            java.io.File f = new java.io.File(getFilesDir(), LOG_DIR + "/" + LOG_FILE);
            if (f.exists() && f.length() > MAX_LOG_SIZE) f.delete();
            String time = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(new Date());
            java.io.FileWriter fw = new java.io.FileWriter(f, true);
            fw.write("[" + time + "] " + msg + "\n");
            fw.close();
        } catch (Exception ignored) {}
    }

    public static String readLog(Context ctx) {
        try {
            java.io.File f = new java.io.File(ctx.getFilesDir(), LOG_DIR + "/" + LOG_FILE);
            if (!f.exists()) return "";
            long size = f.length();
            int readSize = (int) Math.min(size, 200 * 1024);
            byte[] buf = new byte[readSize];
            try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(f, "r")) {
                raf.seek(Math.max(0, size - readSize));
                int read = raf.read(buf);
                if (read < readSize) {
                    byte[] nb = new byte[read];
                    System.arraycopy(buf, 0, nb, 0, read);
                    buf = nb;
                }
            }
            return new String(buf, "UTF-8");
        } catch (Exception e) {
            return "读取日志失败: " + e.getMessage();
        }
    }

    public static void clearLog(Context ctx) {
        try {
            java.io.File f = new java.io.File(ctx.getFilesDir(), LOG_DIR + "/" + LOG_FILE);
            if (f.exists()) f.delete();
        } catch (Exception ignored) {}
    }

    public static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, 0);
    }

    public static int getConfiguredPort(Context ctx) {
        return prefs(ctx).getInt(KEY_WS_PORT, DEFAULT_PORT);
    }

    public static void setConfiguredPort(Context ctx, int port) {
        prefs(ctx).edit().putInt(KEY_WS_PORT, port).apply();
    }

    /** 获取当前 token（首次访问时若未设置则自动生成一个 32 字节随机 hex） */
    public static synchronized String getToken(Context ctx) {
        SharedPreferences sp = prefs(ctx);
        String t = sp.getString(KEY_WS_TOKEN, "");
        if (t.isEmpty()) {
            t = generateToken();
            sp.edit().putString(KEY_WS_TOKEN, t).apply();
        }
        return t;
    }

    /** 重新生成 token（重启服务后生效） */
    public static synchronized String regenerateToken(Context ctx) {
        String t = generateToken();
        prefs(ctx).edit().putString(KEY_WS_TOKEN, t).apply();
        return t;
    }

    private static String generateToken() {
        byte[] buf = new byte[32];
        new java.security.SecureRandom().nextBytes(buf);
        StringBuilder sb = new StringBuilder(buf.length * 2);
        for (byte b : buf) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    public static boolean getBindWan(Context ctx) {
        return prefs(ctx).getBoolean(KEY_WS_BIND_WAN, false);
    }

    public static void setBindWan(Context ctx, boolean wan) {
        prefs(ctx).edit().putBoolean(KEY_WS_BIND_WAN, wan).apply();
    }

    /** 拼出带 token 的完整 URL（用于 UI 复制） */
    public static String buildConnectUrl(Context ctx) {
        int port = getCurrentPort();
        if (port <= 0) port = getConfiguredPort(ctx);
        String ip = getBindWan(ctx)
                ? com.zm920.androidserver.network.NetworkUtil.getLocalIpAddress()
                : "127.0.0.1";
        return "ws://" + ip + ":" + port + "/?token=" + getToken(ctx);
    }

    /* =========================================================
     * WebSocket 实现
     * - 0.0.0.0 / 127.0.0.1 监听（按 ws_bind_wan 开关决定）
     * - onOpen: 检查封禁 -> 检查 IP 失败计数 -> 校验 token
     * - 限速：每客户端每秒最多 20 条
     * - 限连接：同时最多 20 个
     * - 限消息：单条 <= 8 KB
     * - onMessage: 记录消息内容（截断）+ 更新客户端元信息
     * - echo 行为保留（内网调试用）
     * ========================================================= */
    private class WsServerImpl extends WebSocketServer {
        private final Set<WebSocket> clients = Collections.synchronizedSet(new HashSet<>());
        // 每客户端限速：WebSocket -> (窗口起始时间ms, 窗口内消息计数)
        private final java.util.Map<WebSocket, long[]> rateMap = java.util.Collections.synchronizedMap(new java.util.HashMap<>());

        WsServerImpl(int port, String bindAddr) {
            super(new InetSocketAddress(bindAddr, port));
            setReuseAddr(true);
            setConnectionLostTimeout(60);
        }

        @Override
        public void onOpen(WebSocket conn, ClientHandshake handshake) {
            // 取出远端 IP
            String ip = "unknown";
            int rport = 0;
            if (conn.getRemoteSocketAddress() instanceof InetSocketAddress) {
                InetSocketAddress isa = (InetSocketAddress) conn.getRemoteSocketAddress();
                String host = isa.getAddress() == null ? "?" : isa.getAddress().getHostAddress();
                if (host != null) ip = host;
                rport = isa.getPort();
            }
            final String remote = ip + ":" + rport;

            // 1) 封禁检查（含过期清理）
            long now = System.currentTimeMillis();
            Long until = bannedIps.get(ip);
            if (until != null) {
                if (until <= 0L) {
                    appendLog("拒绝封禁IP: " + remote + " (永久)");
                    try { conn.close(4003, "banned"); } catch (Exception ignored) {}
                    return;
                }
                if (until > now) {
                    long left = (until - now) / 1000;
                    appendLog("拒绝封禁IP: " + remote + " (剩余 " + left + "s)");
                    try { conn.close(4003, "banned"); } catch (Exception ignored) {}
                    return;
                }
                // 已过期 -> 清理并放行
                bannedIps.remove(ip, until);
            }

            // 2) 限连接数
            if (clients.size() >= MAX_CLIENTS) {
                appendLog("拒绝连接(已达上限 " + MAX_CLIENTS + "): " + remote);
                try { conn.close(1013, "server full"); } catch (Exception ignored) {}
                return;
            }

            // 3) 校验 token
            String expectedToken = getToken(getApplicationContext());
            String providedToken = extractToken(handshake.getResourceDescriptor());
            if (expectedToken == null || expectedToken.isEmpty()
                    || providedToken == null || !providedToken.equals(expectedToken)) {
                // 认证失败：累计该 IP 失败次数
                int newCount = registerAuthFailure(ip);
                appendLog("认证失败: " + remote + " (累计 " + newCount + "/" + IP_FAIL_LIMIT + ")");
                // 超过阈值则自动封禁
                if (newCount >= IP_FAIL_LIMIT) {
                    bannedIps.put(ip, System.currentTimeMillis() + IP_BAN_DURATION_MS);
                    appendLog("IP " + ip + " 因多次认证失败被临时封禁 " + (IP_BAN_DURATION_MS / 60000) + " 分钟");
                    try { conn.close(4003, "too many failed attempts"); } catch (Exception ignored) {}
                } else {
                    try { conn.close(4001, "invalid token"); } catch (Exception ignored) {}
                }
                return;
            }
            // 认证成功：清空该 IP 的失败计数
            connectAttempts.remove(ip);

            // 4) 注册客户端
            clients.add(conn);
            ClientInfo info = new ClientInfo(ip, rport);
            activeClients.put(conn, info);
            clientCount = clients.size();
            appendLog("客户端连接: " + remote + " (在线 " + clientCount + ")");
            try { conn.send("已连接 Android WebSocket 服务端"); } catch (Exception ignored) {}
            sendLog("客户端连接 (" + clientCount + ")", clientCount);
            updateNotification("运行中 · " + clientCount + " 个客户端");
        }

        @Override
        public void onClose(WebSocket conn, int code, String reason, boolean remote) {
            clients.remove(conn);
            rateMap.remove(conn);
            activeClients.remove(conn);
            clientCount = clients.size();
            // 不记 reason 原文（可能含 token 等敏感数据），只记 code
            appendLog("客户端断开: code=" + code);
            sendLog("客户端断开 (" + clientCount + ")", clientCount);
            updateNotification("运行中 · " + clientCount + " 个客户端");
        }

        @Override
        public void onMessage(WebSocket conn, String message) {
            // 1) 限消息大小
            if (message == null || message.length() > MAX_MESSAGE_SIZE) {
                appendLog("消息过大踢出 (" + (message == null ? 0 : message.length()) + " 字节)");
                try { conn.close(4009, "message too large"); } catch (Exception ignored) {}
                return;
            }
            // 2) 限速
            long now = System.currentTimeMillis();
            long[] stat = rateMap.computeIfAbsent(conn, k -> new long[]{now, 0});
            synchronized (stat) {
                if (now - stat[0] >= 1000) {
                    stat[0] = now;
                    stat[1] = 0;
                }
                stat[1]++;
                if (stat[1] > RATE_LIMIT_PER_SEC) {
                    appendLog("限速踢出: " + conn.getRemoteSocketAddress());
                    try { conn.close(4008, "rate limit"); } catch (Exception ignored) {}
                    return;
                }
            }
            // 3) 记录消息内容（截断写入日志/UI）
            String display = message.length() > MAX_LOG_CONTENT_CHARS
                    ? message.substring(0, MAX_LOG_CONTENT_CHARS) + "..."
                    : message;
            // 替换换行为占位，避免日志乱
            String safe = display.replace('\n', ' ').replace('\r', ' ');
            appendLog("收到 [" + conn.getRemoteSocketAddress() + "]: " + safe);
            // 4) 更新客户端元信息
            ClientInfo info = activeClients.get(conn);
            if (info != null) {
                info.messageCount++;
                info.lastSeen = now;
                info.lastMessage = safe;
            }
            // 5) 写入最近消息环形缓冲
            String ip = info == null ? "?" : info.ip;
            synchronized (recentMessages) {
                recentMessages.addLast(new RecentMessage(now, ip, safe));
                while (recentMessages.size() > MAX_RECENT_MESSAGES) {
                    recentMessages.pollFirst();
                }
            }
            // 6) echo（调试用）
            try { conn.send("echo: " + message); } catch (Exception ignored) {}
        }

        @Override
        public void onError(WebSocket conn, Exception ex) {
            String msg = ex == null ? "未知错误" :
                    (ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
            // conn==null 表示服务级错误（如 bind 失败），需要把 running 置 false 并通知 UI
            boolean serverLevel = (conn == null);
            appendLog((serverLevel ? "服务级错误" : "连接错误") + ": " + msg);
            Log.e(TAG, "ws error" + (serverLevel ? " (server-level)" : ""), ex);
            if (serverLevel && running) {
                running = false;
                lastError = "运行中异常终止: " + msg;
                String hint = "服务异常终止，请到「设置 → WebSocket 端口」检查端口是否被释放后重试";
                sendLog("服务已停止: " + msg, clientCount, hint, 1);
                updateNotification("已停止");
                stopSelf();
            } else {
                sendLog("错误: " + msg, clientCount);
            }
        }

        @Override
        public void onStart() {
            Log.i(TAG, "ws server started: " + getAddress());
        }

        void broadcastMessage(String msg) {
            synchronized (clients) {
                for (WebSocket c : clients) {
                    if (c != null && c.isOpen()) {
                        try { c.send(msg); } catch (Exception ignored) {}
                    }
                }
            }
        }

        /** 从 URL resource descriptor 里提取 token 参数 */
        private String extractToken(String resource) {
            if (resource == null) return null;
            int q = resource.indexOf('?');
            if (q < 0) return null;
            String qs = resource.substring(q + 1);
            for (String kv : qs.split("&")) {
                int eq = kv.indexOf('=');
                if (eq <= 0) continue;
                String k = kv.substring(0, eq);
                String v = kv.substring(eq + 1);
                if ("token".equalsIgnoreCase(k)) return v;
            }
            return null;
        }
    }

    /** 记录 IP 认证失败，返回累计次数（自动清理窗口外的旧记录） */
    private static int registerAuthFailure(String ip) {
        long now = System.currentTimeMillis();
        long[] stat = connectAttempts.computeIfAbsent(ip, k -> new long[]{now, 0});
        synchronized (stat) {
            if (now - stat[0] >= IP_FAIL_WINDOW_MS) {
                stat[0] = now;
                stat[1] = 0;
            }
            stat[1]++;
            return (int) stat[1];
        }
    }
}
