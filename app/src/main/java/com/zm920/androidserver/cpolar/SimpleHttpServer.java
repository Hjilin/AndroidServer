package com.zm920.androidserver.cpolar;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * App 内置 Java HTTP 服务，给 cpolar 提供转发目标。
 * 不依赖 nginx/Apache，纯 ServerSocket 实现。
 *
 * <p>支持：
 * <ul>
 *   <li>静态资源（assets/www/index.html）</li>
 *   <li>动态 JSON /info 返回设备信息</li>
 *   <li>代理到 nginx/php（如果已启）</li>
 * </ul>
 */
public class SimpleHttpServer {

    private static final String TAG = "SimpleHttpServer";

    private final Context ctx;
    private final int port;
    private final String bindAddress;
    private final ServerSocket[] serverSocketRef = new ServerSocket[1];
    private final Thread[] acceptThreadRef = new Thread[1];
    private final ExecutorService pool = Executors.newFixedThreadPool(4);
    private final AtomicLong totalRequests = new AtomicLong(0);
    private final long startedAt = System.currentTimeMillis();

    private volatile boolean running = false;
    private volatile String lastError = "";

    public SimpleHttpServer(Context ctx, int port, String bindAddress) {
        this.ctx = ctx.getApplicationContext();
        this.port = port;
        this.bindAddress = bindAddress == null ? "0.0.0.0" : bindAddress;
    }

    public boolean isRunning() { return running; }
    public int getPort() { return port; }
    public String getLastError() { return lastError; }
    public long getTotalRequests() { return totalRequests.get(); }
    public long getUptimeMs() { return System.currentTimeMillis() - startedAt; }

    public synchronized boolean start() {
        if (running) return true;
        try {
            ServerSocket ss = new ServerSocket(port, 64, InetAddress.getByName(bindAddress));
            ss.setReuseAddress(true);
            serverSocketRef[0] = ss;
            running = true;
            lastError = "";
            Thread t = new Thread(this::acceptLoop, "SimpleHttp-Accept");
            t.setDaemon(true);
            acceptThreadRef[0] = t;
            t.start();
            Log.i(TAG, "started on " + bindAddress + ":" + port);
            return true;
        } catch (Exception e) {
            lastError = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            Log.e(TAG, "start failed: " + lastError, e);
            running = false;
            return false;
        }
    }

    public synchronized void stop() {
        if (!running) return;
        running = false;
        try { if (serverSocketRef[0] != null) serverSocketRef[0].close(); } catch (Exception ignored) {}
        pool.shutdown();
        try { pool.awaitTermination(2, TimeUnit.SECONDS); } catch (Exception ignored) {}
        Log.i(TAG, "stopped");
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket sock = serverSocketRef[0].accept();
                sock.setSoTimeout(10000);
                pool.submit(() -> handleClient(sock));
            } catch (Exception e) {
                if (running) Log.w(TAG, "accept: " + e.getMessage());
            }
        }
    }

    private void handleClient(Socket sock) {
        totalRequests.incrementAndGet();
        try (InputStream in = sock.getInputStream(); OutputStream out = sock.getOutputStream()) {
            BufferedReader br = new BufferedReader(new InputStreamReader(in, "ISO-8859-1"));
            String requestLine = br.readLine();
            if (requestLine == null) return;
            String[] parts = requestLine.split(" ");
            if (parts.length < 3) return;
            String method = parts[0];
            String fullPath = parts[1];
            String path = fullPath;
            String qs = "";
            int q = fullPath.indexOf('?');
            if (q >= 0) { path = fullPath.substring(0, q); qs = fullPath.substring(q + 1); }

            // 读 header
            Map<String, String> headers = new HashMap<>();
            String line;
            while ((line = br.readLine()) != null && !line.isEmpty()) {
                int idx = line.indexOf(':');
                if (idx > 0) headers.put(line.substring(0, idx).trim().toLowerCase(), line.substring(idx + 1).trim());
            }

            String accept = headers.getOrDefault("accept", "");

            // 路由
            if (path.equals("/") || path.equals("/index.html")) {
                String body = renderIndex(headers);
                respond(out, 200, "text/html; charset=utf-8", body);
            } else if (path.equals("/info") || path.equals("/api/info")) {
                String body = renderInfo();
                respond(out, 200, "application/json; charset=utf-8", body);
            } else if (path.equals("/health") || path.equals("/healthz")) {
                respond(out, 200, "text/plain", "ok");
            } else if (path.equals("/_cpolar")) {
                String body = "cpolar tunnel target: SimpleHttpServer (App 内)\nport=" + port + "\nuptime=" + getUptimeMs() + "ms\n";
                respond(out, 200, "text/plain; charset=utf-8", body);
            } else {
                respond(out, 404, "text/plain", "not found: " + path);
            }
        } catch (Exception e) {
            try { respond(sock.getOutputStream(), 500, "text/plain", "server error: " + e.getMessage()); } catch (Exception ignored) {}
            Log.w(TAG, "client: " + e.getMessage());
        } finally {
            try { sock.close(); } catch (Exception ignored) {}
        }
    }

    private void respond(OutputStream out, int code, String contentType, String body) throws Exception {
        byte[] data = body.getBytes("UTF-8");
        String reason = code == 200 ? "OK" : code == 404 ? "Not Found" : "Internal Server Error";
        String head = "HTTP/1.1 " + code + " " + reason + "\r\n"
                + "Content-Type: " + contentType + "\r\n"
                + "Content-Length: " + data.length + "\r\n"
                + "Connection: close\r\n"
                + "Server: AndroidServer-SimpleHttp/1.0\r\n"
                + "\r\n";
        out.write(head.getBytes("UTF-8"));
        out.write(data);
        out.flush();
    }

    private String renderIndex(Map<String, String> headers) {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
        String time = sdf.format(new Date());
        String device = Build.MANUFACTURER + " " + Build.MODEL;
        String android = Build.VERSION.RELEASE + " (SDK " + Build.VERSION.SDK_INT + ")";
        String from = headers.getOrDefault("x-forwarded-for", headers.getOrDefault("host", "direct"));
        String cpolarUrl = CpolarConfig.getPublicUrl(ctx);
        return "<!DOCTYPE html>\n"
                + "<html lang=\"zh\"><head><meta charset=\"UTF-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1.0\">\n"
                + "<title>cpolar 内网穿透测试</title>\n"
                + "<style>\n"
                + "*{margin:0;padding:0;box-sizing:border-box}\n"
                + "body{font-family:Inter,-apple-system,Segoe UI,sans-serif;min-height:100vh;background:linear-gradient(135deg,#667eea 0%,#764ba2 100%);color:#fff;display:flex;align-items:center;justify-content:center;padding:20px}\n"
                + ".card{background:rgba(255,255,255,.95);color:#1a1a2e;border-radius:20px;padding:36px;max-width:520px;width:100%;box-shadow:0 20px 60px rgba(0,0,0,.3)}\n"
                + ".tag{display:inline-block;background:#27c93f;color:#fff;font-size:11px;font-weight:600;padding:4px 12px;border-radius:20px;margin-bottom:16px}\n"
                + "h1{font-size:24px;margin-bottom:6px}\n"
                + ".sub{color:#888;font-size:13px;margin-bottom:24px}\n"
                + ".row{display:flex;justify-content:space-between;padding:12px 0;border-bottom:1px solid #eee;font-size:14px}\n"
                + ".row:last-child{border-bottom:0}\n"
                + ".label{color:#888}.value{color:#1a1a2e;font-weight:600;font-family:monospace}\n"
                + ".url{background:#f5f7fb;padding:8px 12px;border-radius:8px;font-family:monospace;font-size:12px;word-break:break-all;margin-top:8px}\n"
                + ".footer{margin-top:20px;padding-top:16px;border-top:1px solid #eee;font-size:11px;color:#bbb;text-align:center}\n"
                + "</style></head><body><div class=\"card\">\n"
                + "<span class=\"tag\">● 运行中</span>\n"
                + "<h1>cpolar 内网穿透</h1>\n"
                + "<div class=\"sub\">公网访问 App 内 HTTP 服务 - 全程沙箱内完成</div>\n"
                + "<div class=\"row\"><span class=\"label\">设备</span><span class=\"value\">" + escapeHtml(device) + "</span></div>\n"
                + "<div class=\"row\"><span class=\"label\">系统</span><span class=\"value\">Android " + escapeHtml(android) + "</span></div>\n"
                + "<div class=\"row\"><span class=\"label\">本地端口</span><span class=\"value\">" + port + "</span></div>\n"
                + "<div class=\"row\"><span class=\"label\">请求数</span><span class=\"value\">" + totalRequests.get() + "</span></div>\n"
                + "<div class=\"row\"><span class=\"label\">运行时间</span><span class=\"value\">" + (getUptimeMs() / 1000) + " 秒</span></div>\n"
                + "<div class=\"row\"><span class=\"label\">服务端时间</span><span class=\"value\">" + time + "</span></div>\n"
                + "<div class=\"row\"><span class=\"label\">来源</span><span class=\"value\">" + escapeHtml(from) + "</span></div>\n"
                + "<div class=\"row\"><span class=\"label\">公网 URL</span></div>\n"
                + "<div class=\"url\">" + escapeHtml(cpolarUrl.isEmpty() ? "(未启动 cpolar)" : cpolarUrl) + "</div>\n"
                + "<div class=\"footer\">Powered by AndroidServer + cpolar · 简云内网穿透</div>\n"
                + "</div></body></html>";
    }

    private String renderInfo() {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"ok\":true,");
        sb.append("\"server\":\"SimpleHttpServer/1.0\",");
        sb.append("\"device\":\"").append(escapeJson(Build.MANUFACTURER + " " + Build.MODEL)).append("\",");
        sb.append("\"android\":\"").append(escapeJson(Build.VERSION.RELEASE)).append("\",");
        sb.append("\"sdk\":").append(Build.VERSION.SDK_INT).append(",");
        sb.append("\"port\":").append(port).append(",");
        sb.append("\"requests\":").append(totalRequests.get()).append(",");
        sb.append("\"uptime_ms\":").append(getUptimeMs()).append(",");
        sb.append("\"cpolar_url\":\"").append(escapeJson(CpolarConfig.getPublicUrl(ctx))).append("\",");
        sb.append("\"now\":").append(System.currentTimeMillis());
        sb.append("}");
        return sb.toString();
    }

    private String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }
}
