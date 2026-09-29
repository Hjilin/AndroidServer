package com.zm920.androidserver.server;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.zm920.androidserver.service.ProcessManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.*;

/**
 * OpenList（AList 分支）网盘服务管理。
 * Go 静态二进制，无需 LD_LIBRARY_PATH；首次启动从资源仓库下载 zip 并解压。
 * 默认端口 5244，工作目录 files/openlist_data，数据目录在其下 data/。
 */
public class OpenListManager {

    private static final String TAG = "OpenListManager";
    public static final int PORT = 5244;

    // 日志落盘目录
    private static final String LOG_DIR = "openlist_logs";
    private static final String LOG_FILE = "openlist.log";

    // 资源包（zip 内结构 bin/openlist）
    private static final String ZIP_URL =
        "https://gh-proxy.com/https://github.com/Hjilin/mobileserver-bin-resources/releases/download/v1.0.0/openlist-4.2.6-arm64.zip";

    private final Context context;
    private final ProcessManager processManager;
    private final SharedPreferences prefs;
    private final File installDir;   // files/openlist
    private final File dataDir;      // files/openlist_data
    private final File binFile;

    public OpenListManager(Context context, ProcessManager pm, SharedPreferences prefs) {
        this.context = context;
        this.processManager = pm;
        this.prefs = prefs;
        this.installDir = new File(context.getFilesDir(), "openlist");
        this.dataDir = new File(context.getFilesDir(), "openlist_data");
        this.binFile = new File(installDir, "bin/openlist");
    }

    public boolean isInstalled() {
        return binFile.exists() && binFile.length() > 0;
    }

    public interface InstallCallback {
        void onProgress(int percent);
        void onReady();
        void onError(String msg);
    }

    /** 下载并解压（已安装则直接回调 onReady）。在子线程调用。 */
    private volatile boolean installing = false;
    public void ensureInstalled(final InstallCallback cb) {
        if (isInstalled()) { cb.onReady(); return; }
        synchronized (this) {
            if (installing) {
                cb.onError("组件正在下载中，请勿重复操作");
                return;
            }
            installing = true;
        }
        new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                installDir.mkdirs();
                File tmp = new File(context.getCacheDir(), "openlist.zip");
                long existing = tmp.exists() ? tmp.length() : 0;
                long got = existing; int last = -1;
                if (existing > 0) cb.onProgress(5);
                // 断点续传：请求 Range
                conn = (HttpURLConnection) new URL(ZIP_URL).openConnection();
                conn.setConnectTimeout(20000);
                conn.setReadTimeout(60000);
                conn.setInstanceFollowRedirects(true);
                conn.setRequestProperty("Range", "bytes=" + existing + "-");
                int code = conn.getResponseCode();
                int total = conn.getContentLength();
                boolean append = (code == 206);
                // 服务端不支持续传则整包重下
                if (!append) {
                    existing = 0; got = 0; append = false;
                    conn.disconnect();
                    conn = (HttpURLConnection) new URL(ZIP_URL).openConnection();
                    conn.setConnectTimeout(20000);
                    conn.setReadTimeout(60000);
                    conn.setInstanceFollowRedirects(true);
                    code = conn.getResponseCode();
                    total = conn.getContentLength();
                }
                if (code != 200 && code != 206) {
                    cb.onError("HTTP " + code);
                    return;
                }
                long targetTotal = (append && total > 0) ? existing + total : (total > 0 ? total : -1);
                try (InputStream is = conn.getInputStream();
                     FileOutputStream fos = new FileOutputStream(tmp, append)) {
                    byte[] buf = new byte[65536]; int n;
                    while ((n = is.read(buf)) > 0) {
                        fos.write(buf, 0, n);
                        got += n;
                        if (targetTotal > 0) {
                            int pct = (int) (got * 100 / targetTotal);
                            if (pct != last) { last = pct; cb.onProgress(pct); }
                        }
                    }
                }
                // 下载完成但未校验长度，强制整包重下兜底
                if (targetTotal > 0 && got < targetTotal) {
                    cb.onError("下载不完整(" + got + "/" + targetTotal + ")，已保留续传点");
                    return;
                }
                // 解压 bin/openlist
                try (ZipInputStream zis = new ZipInputStream(
                        new java.io.FileInputStream(tmp))) {
                    ZipEntry e;
                    while ((e = zis.getNextEntry()) != null) {
                        if (e.isDirectory()) continue;
                        String base = e.getName().substring(e.getName().lastIndexOf('/') + 1);
                        if (!"openlist".equals(base)) continue;
                        File out = binFile;
                        out.getParentFile().mkdirs();
                        try (FileOutputStream o = new FileOutputStream(out)) {
                            byte[] buf = new byte[65536]; int n;
                            while ((n = zis.read(buf)) > 0) o.write(buf, 0, n);
                        }
                    }
                }
                if (isInstalled()) {
                    tmp.delete();   // 解压成功，清理临时包，避免下次续传损坏包
                    cb.onReady();
                } else {
                    cb.onError("解压后未找到 openlist");
                }
            } catch (Exception e) {
                Log.e(TAG, "安装失败", e);
                cb.onError(e.getMessage() == null ? "下载失败" : e.getMessage());
            } finally {
                installing = false;
                if (conn != null) conn.disconnect();
            }
        }).start();
    }

    private Process process;

    public boolean start() throws Exception {
        if (isRunning()) return true;
        if (!isInstalled()) return false;
        killOrphan();
        dataDir.mkdirs();
        logDir().mkdirs();
        File logFile = new File(logDir(), LOG_FILE);

        String[] cmd = new String[]{
            binFile.getAbsolutePath(), "server",
            "--port", String.valueOf(PORT),
            "--data", dataDir.getAbsolutePath()
        };
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile));
        pb.directory(dataDir);
        process = pb.start();
        for (int i = 0; i < 20; i++) {
            Thread.sleep(400);
            if (isPortOpen(PORT)) {
                writeLog("OpenList 启动成功，端口 " + PORT);
                return true;
            }
            if (!process.isAlive()) {
                writeLog("OpenList 进程已退出，退出码=" + process.exitValue());
                return false;
            }
        }
        writeLog("OpenList 启动超时（端口未监听）");
        return false;
    }

    public void stop() {
        if (process != null && process.isAlive()) {
            process.destroy();
            try { process.waitFor(); } catch (Exception ignored) {}
        }
        process = null;
        killOrphan();
        writeLog("OpenList 服务已停止");
    }

    public boolean isRunning() {
        return isPortOpen(PORT);
    }

    private void killOrphan() {
        try {
            File[] dirs = new File("/proc").listFiles();
            if (dirs == null) return;
            for (File d : dirs) {
                if (!d.isDirectory()) continue;
                String pid = d.getName();
                if (!pid.matches("\\d+")) continue;
                File cl = new File(d, "cmdline");
                if (!cl.canRead()) continue;
                byte[] b = new byte[4096];
                try (java.io.FileInputStream fs = new java.io.FileInputStream(cl)) {
                    int n = fs.read(b);
                    if (n > 0 && new String(b, 0, n).replace("\0", " ").contains("openlist")) {
                        Runtime.getRuntime().exec(new String[]{"/system/bin/kill", "-9", pid}).waitFor();
                    }
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
    }

    private boolean isPortOpen(int port) {
        try {
            Socket s = new Socket();
            s.connect(new InetSocketAddress("127.0.0.1", port), 60);
            s.close();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ===== 日志 =====
    private File logDir() {
        return new File(context.getFilesDir(), LOG_DIR);
    }
    private void writeLog(String msg) {
        try {
            File f = new File(logDir(), LOG_FILE);
            f.getParentFile().mkdirs();
            try (FileOutputStream fo = new FileOutputStream(f, true)) {
                String line = new java.text.SimpleDateFormat("MM-dd HH:mm:ss").format(new java.util.Date())
                    + "  " + msg + "\n";
                fo.write(line.getBytes("UTF-8"));
            }
        } catch (Exception ignored) {}
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
}
