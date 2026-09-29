package com.zm920.androidserver.server;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Base64;
import java.util.Date;
import java.util.Locale;

/**
 * 原生 WebDAV 文件共享服务（参考简云 WebDAV 模块范式实现）。
 * 无外部依赖，基于 ServerSocket；支持 Basic 认证与
 * OPTIONS/GET/HEAD/PUT/DELETE/MKCOL/MOVE/COPY/PROPFIND，DAV 1,2。
 */
public class WebdavServer {

    private static final String TAG = "WebdavServer";
    private static final String LOG_DIR = "webdav_logs";
    private static final String LOG_FILE = "webdav.log";

    private static volatile ServerSocket serverSocket;
    private static volatile Thread acceptThread;
    private static volatile boolean running;
    private static volatile String rootPath = "";
    private static volatile String password = "";
    private static volatile int currentPort = 0;

    private static Context appContext;

    private WebdavServer() {}

    // ===== 对外启停 =====
    public static synchronized boolean start(Context ctx, int port, String root, String pass) {
        if (running) return true;
        appContext = ctx.getApplicationContext();
        rootPath = root;
        password = pass == null ? "" : pass;
        currentPort = port;
        try {
            serverSocket = new ServerSocket(port);
            serverSocket.setSoTimeout(0);
            running = true;
            acceptThread = new Thread(WebdavServer::acceptLoop, "webdav-accept");
            acceptThread.setDaemon(true);
            acceptThread.start();
            writeLog("WebDAV 启动成功，端口 " + port + "，根目录 " + root);
            return true;
        } catch (Exception e) {
            running = false;
            Log.e(TAG, "启动失败", e);
            writeLog("WebDAV 启动失败: " + e.getMessage());
            return false;
        }
    }

    public static synchronized void stop() {
        running = false;
        try { if (serverSocket != null) serverSocket.close(); } catch (Exception ignored) {}
        serverSocket = null;
        acceptThread = null;
        writeLog("WebDAV 服务已停止");
    }

    public static boolean isRunning() {
        if (!running) return false;
        return currentPort > 0 && isPortOpen(currentPort);
    }

    public static int getCurrentPort() { return currentPort; }

    private static void acceptLoop() {
        while (running) {
            try {
                Socket s = serverSocket.accept();
                Thread t = new Thread(() -> handle(s), "webdav-conn");
                t.setDaemon(true);
                t.start();
            } catch (Exception e) {
                if (running) Log.e(TAG, "accept 错误", e);
            }
        }
    }

    // ===== 连接处理 =====
    private static void handle(Socket socket) {
        try {
            socket.setSoTimeout(20000);
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();
            Request req = readRequest(in);
            if (req == null) { socket.close(); return; }

            // Basic 认证
            if (!checkAuth(req)) {
                writeLog("401 未授权: " + req.method + " " + req.path + " (" + socket.getInetAddress() + ")");
                String body = "Unauthorized";
                String resp = "HTTP/1.1 401 Unauthorized\r\n"
                    + "WWW-Authenticate: Basic realm=\"WebDAV\"\r\n"
                    + "Content-Type: text/plain\r\n"
                    + "Content-Length: " + body.length() + "\r\n"
                    + "Connection: close\r\n\r\n" + body;
                out.write(resp.getBytes(StandardCharsets.UTF_8));
                out.flush();
                socket.close();
                return;
            }

            dispatch(req, in, out, socket);
            out.flush();
        } catch (SocketTimeoutException te) {
            writeLog("连接超时: " + socket.getInetAddress());
        } catch (Exception e) {
            Log.e(TAG, "处理连接错误", e);
        } finally {
            try { socket.close(); } catch (Exception ignored) {}
        }
    }

    private static void dispatch(Request req, InputStream in, OutputStream out, Socket socket) throws IOException {
        String m = req.method;
        String p = sanitizePath(req.path);
        File target = resolveFile(p);

        if ("OPTIONS".equals(m)) {
            String resp = "HTTP/1.1 200 OK\r\n"
                + "Allow: OPTIONS, GET, HEAD, PUT, DELETE, MKCOL, MOVE, COPY, PROPFIND\r\n"
                + "DAV: 1, 2\r\n"
                + "MS-Author-Via: DAV\r\n"
                + "Content-Length: 0\r\n\r\n";
            out.write(resp.getBytes(StandardCharsets.UTF_8));
            writeLog("OPTIONS " + p);
            return;
        }

        if (!authFileOk(target)) {
            sendStatus(out, 404, "Not Found");
            writeLog(m + " 404 " + p);
            return;
        }

        switch (m) {
            case "GET":
            case "HEAD":
                if (target.isDirectory()) {
                    // 目录返回简化 HTML 列表
                    sendDirHtml(out, p, target);
                } else if (target.isFile()) {
                    sendFile(out, target, "HEAD".equals(m));
                } else {
                    sendStatus(out, 404, "Not Found");
                }
                writeLog(m + " " + p + (target.isFile() ? " (" + target.length() + "B)" : ""));
                break;
            case "PUT":
                if (target.isDirectory()) { sendStatus(out, 405, "Method Not Allowed"); break; }
                target.getParentFile().mkdirs();
                try (FileOutputStream fos = new FileOutputStream(target)) {
                    byte[] buf = new byte[65536]; int n;
                    while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
                }
                sendStatus(out, 201, "Created");
                writeLog("PUT " + p);
                break;
            case "DELETE":
                if (target.isDirectory() && target.list().length > 0) {
                    deleteRecursive(target);
                } else {
                    target.delete();
                }
                sendStatus(out, 200, "OK");
                writeLog("DELETE " + p);
                break;
            case "MKCOL":
                if (target.exists()) { sendStatus(out, 405, "Method Not Allowed"); break; }
                if (target.mkdirs()) sendStatus(out, 201, "Created");
                else sendStatus(out, 409, "Conflict");
                writeLog("MKCOL " + p);
                break;
            case "MOVE": {
                String dest = sanitizePath(req.headers.get("Destination"));
                File destFile = resolveFile(dest);
                if (destFile == null) { sendStatus(out, 400, "Bad Request"); break; }
                destFile.getParentFile().mkdirs();
                if (target.isDirectory()) moveDir(target, destFile);
                else target.renameTo(destFile);
                sendStatus(out, 201, "Created");
                writeLog("MOVE " + p + " -> " + dest);
                break;
            }
            case "COPY": {
                String dest = sanitizePath(req.headers.get("Destination"));
                File destFile = resolveFile(dest);
                if (destFile == null) { sendStatus(out, 400, "Bad Request"); break; }
                destFile.getParentFile().mkdirs();
                copyRecursive(target, destFile);
                sendStatus(out, 201, "Created");
                writeLog("COPY " + p + " -> " + dest);
                break;
            }
            case "PROPFIND":
                sendPropfind(out, p, target, req.depth);
                writeLog("PROPFIND " + p + " depth=" + req.depth);
                break;
            default:
                sendStatus(out, 405, "Method Not Allowed");
        }
    }

    // ===== 工具 =====
    private static boolean checkAuth(Request req) {
        if (password == null || password.isEmpty()) return true;
        String auth = req.headers.get("Authorization");
        if (auth == null || !auth.startsWith("Basic ")) return false;
        try {
            String decoded = new String(Base64.getDecoder().decode(auth.substring(6).trim()),
                    StandardCharsets.UTF_8);
            int idx = decoded.indexOf(':');
            if (idx < 0) return false;
            String user = decoded.substring(0, idx);
            String pass = decoded.substring(idx + 1);
            return pass.equals(password);
        } catch (Exception e) {
            return false;
        }
    }

    private static String sanitizePath(String path) {
        if (path == null) return "/";
        // 去掉 Destination 里的 host 部分
        int scheme = path.indexOf("://");
        if (scheme >= 0) {
            int slash = path.indexOf('/', scheme + 3);
            path = slash >= 0 ? path.substring(slash) : "/";
        }
        // 去掉查询串
        int q = path.indexOf('?');
        if (q >= 0) path = path.substring(0, q);
        if (!path.startsWith("/")) path = "/" + path;
        return path;
    }

    private static File resolveFile(String p) {
        if (p == null || rootPath == null || rootPath.isEmpty()) return null;
        File root = new File(rootPath);
        if (!root.exists()) root.mkdirs();
        try {
            File f = new File(root, p);
            String canonical = f.getCanonicalPath();
            String rootCanonical = root.getCanonicalPath();
            if (!canonical.startsWith(rootCanonical)) return null;
            return f;
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean authFileOk(File f) {
        return f != null;
    }

    private static void sendStatus(OutputStream out, int code, String text) throws IOException {
        String resp = "HTTP/1.1 " + code + " " + text + "\r\n"
            + "Content-Length: 0\r\nConnection: close\r\n\r\n";
        out.write(resp.getBytes(StandardCharsets.UTF_8));
    }

    private static void sendFile(OutputStream out, File f, boolean head) throws IOException {
        String mime = mimeType(f.getName());
        String headStr = "HTTP/1.1 200 OK\r\n"
            + "Content-Type: " + mime + "\r\n"
            + "Content-Length: " + f.length() + "\r\n"
            + "Accept-Ranges: bytes\r\n"
            + "Connection: close\r\n\r\n";
        out.write(headStr.getBytes(StandardCharsets.UTF_8));
        if (head) return;
        try (FileInputStream fis = new FileInputStream(f)) {
            byte[] buf = new byte[65536]; int n;
            while ((n = fis.read(buf)) > 0) out.write(buf, 0, n);
        }
    }

    private static void sendDirHtml(OutputStream out, String p, File dir) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nConnection: close\r\n\r\n");
        sb.append("<html><head><title>").append(esc(p)).append("</title></head><body><h3>")
          .append(esc(p)).append("</h3><ul>");
        File[] list = dir.listFiles();
        if (list != null) {
            java.util.Arrays.sort(list);
            if (!"/".equals(p)) sb.append("<li><a href=\"..\">..</a></li>");
            for (File c : list) {
                String name = c.getName();
                String href = p.endsWith("/") ? p + name : p + "/" + name;
                sb.append("<li><a href=\"").append(esc(href)).append("\">")
                  .append(esc(name)).append(c.isDirectory() ? "/</a>" : "</a>")
                  .append(c.isFile() ? " (" + c.length() + "B)" : "").append("</li>");
            }
        }
        sb.append("</ul></body></html>");
        byte[] data = sb.toString().getBytes(StandardCharsets.UTF_8);
        out.write(data);
    }

    private static void sendPropfind(OutputStream out, String p, File target, int depth) throws IOException {
        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<D:multistatus xmlns:D=\"DAV:\">\n");
        appendPropItem(xml, p, target);
        if (depth == 1 && target.isDirectory()) {
            File[] list = target.listFiles();
            if (list != null) {
                for (File c : list) {
                    String childPath = p.endsWith("/") ? p + c.getName() : p + "/" + c.getName();
                    appendPropItem(xml, childPath, c);
                }
            }
        }
        xml.append("</D:multistatus>");
        byte[] data = xml.toString().getBytes(StandardCharsets.UTF_8);
        String headStr = "HTTP/1.1 207 Multi-Status\r\n"
            + "Content-Type: application/xml; charset=utf-8\r\n"
            + "Content-Length: " + data.length + "\r\n"
            + "DAV: 1, 2\r\n"
            + "Connection: close\r\n\r\n";
        out.write(headStr.getBytes(StandardCharsets.UTF_8));
        out.write(data);
    }

    private static void appendPropItem(StringBuilder xml, String path, File f) {
        boolean isDir = f.isDirectory();
        xml.append("  <D:response>\n    <D:href>").append(esc(path)).append("</D:href>\n    <D:propstat>\n      <D:prop>\n");
        xml.append("        <D:resourcetype>").append(isDir ? "<D:collection/>" : "").append("</D:resourcetype>\n");
        if (!isDir) {
            xml.append("        <D:getcontentlength>").append(f.length()).append("</D:getcontentlength>\n")
               .append("        <D:getcontenttype>").append(esc(mimeType(f.getName()))).append("</D:getcontenttype>\n");
        }
        xml.append("        <D:getlastmodified>").append(esc(SimpleDateFormat
            .getDateTimeInstance().format(new Date(f.lastModified())))).append("</D:getlastmodified>\n");
        xml.append("        <D:displayname>").append(esc(f.getName())).append("</D:displayname>\n");
        xml.append("      </D:prop>\n      <D:status>HTTP/1.1 200 OK</D:status>\n    </D:propstat>\n  </D:response>\n");
    }

    private static String mimeType(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.endsWith(".html")||n.endsWith(".htm")) return "text/html";
        if (n.endsWith(".txt")) return "text/plain";
        if (n.endsWith(".css")) return "text/css";
        if (n.endsWith(".js")) return "application/javascript";
        if (n.endsWith(".json")) return "application/json";
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".jpg")||n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".gif")) return "image/gif";
        if (n.endsWith(".webp")) return "image/webp";
        if (n.endsWith(".svg")) return "image/svg+xml";
        if (n.endsWith(".mp4")) return "video/mp4";
        if (n.endsWith(".webm")) return "video/webm";
        if (n.endsWith(".mp3")) return "audio/mpeg";
        if (n.endsWith(".pdf")) return "application/pdf";
        if (n.endsWith(".zip")) return "application/zip";
        if (n.endsWith(".gz")) return "application/gzip";
        if (n.endsWith(".tar")) return "application/x-tar";
        if (n.endsWith(".apk")) return "application/vnd.android.package-archive";
        if (n.endsWith(".php")) return "application/x-php";
        return "application/octet-stream";
    }

    private static void copyRecursive(File src, File dst) throws IOException {
        if (src.isDirectory()) {
            dst.mkdirs();
            File[] list = src.listFiles();
            if (list != null) for (File c : list) copyRecursive(c, new File(dst, c.getName()));
        } else {
            try (InputStream in = new FileInputStream(src); OutputStream o = new FileOutputStream(dst)) {
                byte[] buf = new byte[65536]; int n;
                while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
            }
        }
    }

    private static void moveDir(File src, File dst) {
        if (dst.exists()) return;
        if (src.renameTo(dst)) return;
        try { copyRecursive(src, dst); deleteRecursive(src); } catch (Exception ignored) {}
    }

    private static void deleteRecursive(File f) {
        if (f.isDirectory()) {
            File[] list = f.listFiles();
            if (list != null) for (File c : list) deleteRecursive(c);
        }
        f.delete();
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    // ===== 请求解析 =====
    private static class Request {
        String method, path;
        int depth = 1;
        java.util.Map<String, String> headers = new java.util.HashMap<>();
    }

    private static Request readRequest(InputStream in) throws IOException {
        // 读请求行 + headers
        java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(in, StandardCharsets.UTF_8));
        String line = br.readLine();
        if (line == null || line.isEmpty()) return null;
        String[] parts = line.split(" ");
        if (parts.length < 3) return null;
        Request req = new Request();
        req.method = parts[0];
        req.path = parts[1];
        String h;
        while ((h = br.readLine()) != null && !h.isEmpty()) {
            int c = h.indexOf(':');
            if (c > 0) req.headers.put(h.substring(0, c).trim(), h.substring(c + 1).trim());
        }
        String depth = req.headers.get("Depth");
        if ("0".equals(depth)) req.depth = 0;
        return req;
    }

    private static boolean isPortOpen(int port) {
        try (Socket s = new Socket()) {
            s.connect(new java.net.InetSocketAddress("127.0.0.1", port), 80);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ===== 日志 =====
    private static void writeLog(String msg) {
        try {
            if (appContext == null) return;
            File f = new File(new File(appContext.getFilesDir(), LOG_DIR), LOG_FILE);
            f.getParentFile().mkdirs();
            try (FileOutputStream fo = new FileOutputStream(f, true)) {
                String l = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.ROOT).format(new Date()) + "  " + msg + "\n";
                fo.write(l.getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {}
    }

    public static String readLog(Context ctx) {
        try {
            File f = new File(new File(ctx.getFilesDir(), LOG_DIR), LOG_FILE);
            if (!f.exists()) return "";
            long size = f.length();
            int readSize = (int) Math.min(size, 200 * 1024);
            byte[] buf = new byte[readSize];
            try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
                raf.seek(size - readSize);
                int read = raf.read(buf);
                if (read < readSize) {
                    byte[] nb = new byte[read];
                    System.arraycopy(buf, 0, nb, 0, read);
                    buf = nb;
                }
            }
            String content = new String(buf, StandardCharsets.UTF_8);
            int nl = content.indexOf('\n');
            if (nl > 0 && nl < 60) content = content.substring(nl + 1);
            return content;
        } catch (Exception e) {
            return "读取日志失败: " + e.getMessage();
        }
    }

    public static void clearLog(Context ctx) {
        try {
            File f = new File(new File(ctx.getFilesDir(), LOG_DIR), LOG_FILE);
            if (f.exists()) f.delete();
        } catch (Exception ignored) {}
    }
}
