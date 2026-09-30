package com.zm920.androidserver.download;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.GZIPInputStream;

public class BinaryDownloader {

    private static final String TAG = "BinaryDownloader";
    private static final int BUFFER = 8192;

    private final File appBinDir;

    // {组件, {版本, 二进制文件名, 下载链接}}
    private static final Map<String, String[]> DOWNLOAD_MAP = new HashMap<>();
    // 多版本下载 URL：key = component_version，value = [binName, downloadUrl]
    private static final Map<String, String[]> VERSION_URL_MAP = new HashMap<>();
    static {{
        String base = "https://gh-proxy.com/https://github.com/Hjilin/mobileserver-bin-resources/releases/download/v1.1.0/";
        VERSION_URL_MAP.put("PHP_8.3.0", new String[]{"8.3.0", "php-cgi",
            base + "php-8.3.0-combined.tar.gz"});
        VERSION_URL_MAP.put("PHP_7.4.0", new String[]{"7.4.0", "php-cgi",
            base + "php-7.4.0-combined.tar.gz"});
    }}
    static {
        String base = "https://gh-proxy.com/https://github.com/Hjilin/mobileserver-bin-resources/releases/download/v1.1.0/";
        DOWNLOAD_MAP.put("Nginx", new String[]{"1.31.2", "nginx",
            base + "nginx-1.31.2.tar.gz"});
        DOWNLOAD_MAP.put("PHP", new String[]{"8.5.1", "php-cgi",
            base + "php-8.5.1-combined.tar.gz"});
        DOWNLOAD_MAP.put("MariaDB", new String[]{"12.3.2", "mariadb",
            base + "mariadb-12.3.2.tar.gz"});
        DOWNLOAD_MAP.put("Redis", new String[]{"8.8.0", "redis-server",
            base + "redis-8.8.0.tar.gz"});

    }

    public BinaryDownloader(File appDir) {
        this.appBinDir = appDir;
        if (!appBinDir.exists()) appBinDir.mkdirs();
    }

    public interface ProgressCallback { void onProgress(int percent, long downloaded, long total); }
    public interface CompleteCallback { void onSuccess(File binFile); void onError(String msg); }

    public class DownloadTask {
        private final String component, version;
        private final ProgressCallback progress;
        private final CompleteCallback complete;
        private final File targetDir;
        private final AtomicBoolean paused = new AtomicBoolean(false);
        private final Object pauseLock = new Object();
        private Thread worker;
        private volatile boolean stopped = false;
        private String binName, downloadUrl;

        DownloadTask(String component, String version, ProgressCallback p, CompleteCallback c) {
            this.component = component;
            this.version = version;
            this.progress = p;
            this.complete = c;
            this.targetDir = new File(appBinDir, component.toLowerCase() + "/" + version);
            // 优先查找版本专用 URL，否则用组件默认 URL
            String[] info = VERSION_URL_MAP.get(component + "_" + version);
            if (info == null) info = DOWNLOAD_MAP.get(component);
            if (info != null) { binName = info[1]; downloadUrl = info[2]; }
        }

        public void start() { stopped = false; worker = new Thread(this::run); worker.start(); }
        public void pause() { paused.set(true); }
        public void resume() { paused.set(false); synchronized (pauseLock) { pauseLock.notifyAll(); } }
        public void stop() { stopped = true; synchronized (pauseLock) { pauseLock.notifyAll(); } }
        public boolean isPaused() { return paused.get(); }

        private void run() {
            try {
                if (component.equals("phpMyAdmin")) { downloadPhpMyAdmin(); return; }
                if (downloadUrl == null) { postError("未知组件"); return; }

                File tmpDir = new File(appBinDir, ".tmp");
                tmpDir.mkdirs();

                String archiveName = component.toLowerCase() + "-" + version + ".tar.gz";
                File tarGz = new File(tmpDir, archiveName);
                postProgress(0, 0, 0);
                // 优先使用本地下载好的文件（手动部署）
                File localFile = new File(android.os.Environment.getExternalStorageDirectory().getAbsolutePath() + "/Download/" + archiveName);
                if (!localFile.exists() || localFile.length() == 0) {
                    localFile = new File(android.os.Environment.getExternalStorageDirectory().getAbsolutePath() + "/Download/" + component.toLowerCase() + "-" + version + "-combined.tar.gz");
                }
                if (localFile.exists() && localFile.length() > 0) {
                    android.util.Log.i(TAG, "发现本地文件: " + localFile.getAbsolutePath());
                    try (java.io.FileInputStream fis = new java.io.FileInputStream(localFile);
                         java.io.FileOutputStream fos = new java.io.FileOutputStream(tarGz)) {
                        byte[] buf = new byte[8192]; int n;
                        long total = localFile.length();
                        long done = 0;
                        while ((n = fis.read(buf)) != -1) { fos.write(buf, 0, n); done += n; postProgress((int)(done * 100 / total), done, total); }
                    }
                    android.util.Log.i(TAG, "本地文件复制完成");
                } else {
                    downloadFile(downloadUrl, tarGz);
                }

                // 清空目标目录
                if (targetDir.exists()) deleteDir(targetDir);
                targetDir.mkdirs();

                // Java 解压 tar.gz（兼容无 tar 命令的 Android 设备）
                try {
                    extractTarGz(tarGz, targetDir);
                } catch (Exception e) {
                    tarGz.delete();
                    Log.e(TAG, "解压失败", e);
                    postError("解压失败: " + e.getMessage());
                    return;
                }
                tarGz.delete();

                // 查找二进制
                File binFile = new File(targetDir, "bin/" + binName);
                if (!binFile.exists()) {
                    binFile = findFile(targetDir, binName);
                }
                if (binFile == null || !binFile.exists()) {
                    postError("未找到二进制 " + binName);
                    return;
                }
                // 设置 bin/ 目录下所有文件执行权限（mariadb 有两个二进制）
                File binDir = new File(targetDir, "bin");
                if (binDir.isDirectory()) {
                    File[] allBins = binDir.listFiles();
                    if (allBins != null) {
                        for (File bf : allBins) {
                            if (!bf.isFile()) continue;
                            try { android.system.Os.chmod(bf.getAbsolutePath(), 0755); } catch (Exception ignored) {}
                            try { Runtime.getRuntime().exec(new String[]{"chmod", "755", bf.getAbsolutePath()}).waitFor(); } catch (Exception ignored) {}
                        }
                    }
                }
                Log.i(TAG, component + " 下载完成: " + binFile.getAbsolutePath() + " exec=" + binFile.canExecute());

                // 组件库不再散拷到公共 files/lib。
                // nginx/php/redis/mariadb 均优先使用自己的版本目录 lib/、dep/，后续版本也保持同一部署模型。

                postSuccess(binFile);
        } catch (Exception e) {
            Log.e(TAG, "错误", e);
            postError(e.getMessage());
        }
    }

        /** GZIP + tar 解压，纯 Java 实现 */
        private void extractTarGz(File tarGz, File destDir) throws Exception {
            // try-with-resources 确保 gzis 异常时也关闭
            try (GZIPInputStream gzis = new GZIPInputStream(new FileInputStream(tarGz))) {
            byte[] block = new byte[512];
            while (true) {
                int read = readFully(gzis, block);
                if (read < 512) break;
                if (isAllZeros(block)) break;

                // 文件名（100 字节）
                String fileName = new String(block, 0, 100, "ASCII").trim();
                int idx = fileName.indexOf('\0');
                if (idx >= 0) fileName = fileName.substring(0, idx);
                if (fileName.isEmpty()) break;

                // 长文件名：././@LongLink
                char type = block.length > 156 ? (char) block[156] : '0';
                long fileSize = Long.parseLong(new String(block, 124, 12, "ASCII").trim(), 8);

                if (type == 'L') {
                    // GNU long name extension
                    byte[] longNameBuf = new byte[(int) fileSize];
                    readFully(gzis, longNameBuf);
                    fileName = new String(longNameBuf, "ASCII").trim();
                    // 跳过 padding
                    skipPadding(gzis, fileSize);
                    // 读取下一个 header
                    read = readFully(gzis, block);
                    if (read < 512) break;
                    if (isAllZeros(block)) break;
                    fileName = new String(block, 0, 100, "ASCII").trim();
                    idx = fileName.indexOf('\0');
                    if (idx >= 0) fileName = fileName.substring(0, idx);
                    // 用 long name 覆盖
                    fileName = new String(longNameBuf, "ASCII").trim();
                    idx = fileName.indexOf('\0');
                    if (idx >= 0) fileName = fileName.substring(0, idx);
                    // 重新读取类型和大小
                    type = block.length > 156 ? (char) block[156] : '0';
                    fileSize = Long.parseLong(new String(block, 124, 12, "ASCII").trim(), 8);
                }

                // 去掉开头的 ./
                if (fileName.startsWith("./")) fileName = fileName.substring(2);

                File outFile = new File(destDir, fileName);

                if (type == '5') {
                    outFile.mkdirs();
                } else {
                    outFile.getParentFile().mkdirs();
                    try (FileOutputStream fos = new FileOutputStream(outFile)) {
                        long remaining = fileSize;
                        byte[] buf = new byte[BUFFER];
                        while (remaining > 0) {
                            int n = gzis.read(buf, 0, (int) Math.min(buf.length, remaining));
                            if (n == -1) break;
                            fos.write(buf, 0, n);
                            remaining -= n;
                        }
                    }
                }

                // 跳过 padding 到 512 字节边界
                skipPadding(gzis, fileSize);
            }
            }
        }

        private void skipPadding(InputStream is, long dataSize) throws Exception {
            long padding = (512 - dataSize % 512) % 512;
            long skipped = 0;
            while (skipped < padding) {
                long s = is.skip(padding - skipped);
                if (s <= 0) break;
                skipped += s;
            }
        }

        private int readFully(InputStream is, byte[] buf) throws Exception {
            int total = 0;
            while (total < buf.length) {
                int n = is.read(buf, total, buf.length - total);
                if (n == -1) break;
                total += n;
            }
            return total;
        }

        private boolean isAllZeros(byte[] b) {
            for (byte v : b) if (v != 0) return false;
            return true;
        }

        private void downloadPhpMyAdmin() throws Exception {
            // 检查是否已部署
            File idx = new File(targetDir, "index.php");
            if (idx.exists()) { postSuccess(idx); return; }
            File mainIdx = new File(targetDir, "main.php");
            if (mainIdx.exists()) { postSuccess(mainIdx); return; }

            // 检查本地 Download 目录是否有已下载的压缩包
            File downloadDir = new File(android.os.Environment.getExternalStorageDirectory().getAbsolutePath() + "/Download");
            File localTarGzAll = new File(downloadDir, "phpMyAdmin-" + version + "-all-languages.tar.gz");
            File localTarGz = new File(downloadDir, "phpMyAdmin-" + version + ".tar.gz");
            File localZip = new File(downloadDir, "phpMyAdmin-5.2.1-all-languages.zip");
            File selectedLocalFile = null;
            if (localTarGzAll.exists() && localTarGzAll.length() > 0) {
                selectedLocalFile = localTarGzAll;
            } else if (localTarGz.exists() && localTarGz.length() > 0) {
                selectedLocalFile = localTarGz;
            } else if (localZip.exists() && localZip.length() > 0) {
                selectedLocalFile = localZip;
            }
            if (selectedLocalFile != null) {
                android.util.Log.i(TAG, "发现本地 phpMyAdmin 文件，跳过下载");
                File tmpDir = new File(appBinDir, ".tmp"); tmpDir.mkdirs();
                File tarGz = new File(tmpDir, "phpMyAdmin-" + version + ".tar.gz");
                // 复制到临时目录
                byte[] buf = new byte[8192];
                try (java.io.FileInputStream fis = new java.io.FileInputStream(selectedLocalFile);
                     java.io.FileOutputStream fos = new java.io.FileOutputStream(tarGz)) {
                    int n; long total = selectedLocalFile.length(); long done = 0;
                    while ((n = fis.read(buf)) != -1) { fos.write(buf, 0, n); done += n; postProgress((int)(done * 100 / total), done, total); }
                }
                // 沿用后续解压逻辑
                targetDir.mkdirs();
                try {
                    ProcessBuilder pb = new ProcessBuilder("tar", "-xzf", tarGz.getAbsolutePath(),
                            "-C", targetDir.getAbsolutePath(), "--strip-components=1");
                    pb.redirectErrorStream(true);
                    Process p = pb.start();
                    if (p.waitFor(30, java.util.concurrent.TimeUnit.SECONDS) && p.exitValue() == 0) {
                        tarGz.delete();
                        postSuccess(new File(targetDir, "index.php"));
                        return;
                    }
                    Log.w(TAG, "phpMyAdmin tar 命令失败，尝试 Java 解压");
                } catch (Exception ignored) {}
                // Java fallback
                try {
                    if (targetDir.exists()) deleteDir(targetDir);
                    targetDir.mkdirs();
                    File flatDir = new File(targetDir, "_phpmyadmin");
                    flatDir.mkdirs();
                    extractTarGz(tarGz, flatDir);
                    File[] subs = flatDir.listFiles();
                    if (subs != null) {
                        for (File sub : subs) {
                            if (sub.isDirectory() && sub.getName().startsWith("phpMyAdmin-")) {
                                moveDirContents(sub, targetDir);
                                break;
                            }
                        }
                    }
                    deleteDir(flatDir);
                    tarGz.delete();
                    if (new File(targetDir, "index.php").exists()) {
                        postSuccess(new File(targetDir, "index.php"));
                        return;
                    }
                } catch (Exception ignored) {}
                tarGz.delete();
                postError("解压失败");
                return;
            }

            String url = "https://gh-proxy.com/https://github.com/Hjilin/mobileserver-bin-resources/releases/download/v1.1.0/phpMyAdmin-5.2.1-all-languages.tar.gz";
            File tmpDir = new File(appBinDir, ".tmp"); tmpDir.mkdirs();
            File tarGz = new File(tmpDir, "phpMyAdmin-" + version + ".tar.gz");
            postProgress(0, 0, 0);
            downloadFile(url, tarGz);
            targetDir.mkdirs();
            try {
                // tar 命令解压（phpMyAdmin 包结构固定）
                ProcessBuilder pb = new ProcessBuilder("tar", "-xzf", tarGz.getAbsolutePath(),
                        "-C", targetDir.getAbsolutePath(), "--strip-components=1");
                pb.redirectErrorStream(true);
                Process p = pb.start();
                if (p.waitFor(30, java.util.concurrent.TimeUnit.SECONDS) && p.exitValue() == 0) {
                    tarGz.delete();
                    postSuccess(new File(targetDir, "index.php"));
                    return;
                }
                // tar 失败时 fallback 到 Java 解压
                Log.w(TAG, "phpMyAdmin tar 命令失败，尝试 Java 解压");
            } catch (Exception ignored) {}

            // Java fallback: 解压到临时目录再移动
            try {
                if (targetDir.exists()) deleteDir(targetDir);
                targetDir.mkdirs();
                File flatDir = new File(targetDir, "_phpmyadmin");
                flatDir.mkdirs();
                extractTarGz(tarGz, flatDir);
                // 查找 phpMyAdmin-* 目录并移动到 targetDir
                File[] subs = flatDir.listFiles();
                if (subs != null) {
                    for (File sub : subs) {
                        if (sub.isDirectory() && sub.getName().startsWith("phpMyAdmin-")) {
                            moveDirContents(sub, targetDir);
                            break;
                        }
                    }
                }
                deleteDir(flatDir);
                tarGz.delete();
                if (new File(targetDir, "index.php").exists()) {
                    postSuccess(new File(targetDir, "index.php"));
                    return;
                }
            } catch (Exception ignored) {}
            tarGz.delete();
            postError("解压失败");
        }

        private void moveDirContents(File src, File dst) throws Exception {
            File[] files = src.listFiles();
            if (files == null) return;
            for (File f : files) {
                File dest = new File(dst, f.getName());
                if (f.isDirectory()) {
                    moveDirContents(f, dest);
                    dest.mkdirs();
                } else {
                    f.renameTo(dest);
                }
            }
        }

        private void downloadFile(String urlStr, File dest) throws Exception {
            System.setProperty("http.agent", "");
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(60000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14)");
            conn.setRequestProperty("Accept", "*/*");
            // 兼容 Android 8 等旧设备的 TLS 1.2 支持
            if (urlStr.startsWith("https") && conn instanceof javax.net.ssl.HttpsURLConnection) {
                try {
                    javax.net.ssl.SSLContext ssl = javax.net.ssl.SSLContext.getInstance("TLSv1.2");
                    ssl.init(null, null, null);
                    ((javax.net.ssl.HttpsURLConnection) conn).setSSLSocketFactory(ssl.getSocketFactory());
                } catch (Exception ignored) {}
            }

            int code = conn.getResponseCode();
            if (code != 200 && code != 201) {
                conn.disconnect();
                throw new Exception("HTTP " + code);
            }

            int total = conn.getContentLength();
            // try-with-resources 保证 is/os/conn 在异常路径上也正确关闭
            try (InputStream is = new BufferedInputStream(conn.getInputStream());
                 FileOutputStream os = new FileOutputStream(dest)) {
                byte[] buf = new byte[BUFFER];
                int n, done = 0;
                while ((n = is.read(buf)) != -1 && !stopped) {
                    os.write(buf, 0, n);
                    done += n;
                    final int d = done, t = total;
                    postProgress(t > 0 ? (int) ((long) d * 100 / t) : 0, d, t);
                    while (paused.get() && !stopped) {
                        synchronized (pauseLock) {
                            try { pauseLock.wait(500); }
                            catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
                        }
                    }
                }
            } finally {
                try { conn.disconnect(); } catch (Exception ignored) {}
            }
        }

        /** 限时执行外部命令，避免子进程卡死时永久阻塞 */
        private void execWithTimeout(String[] cmd, int timeoutSec) {
            Process p = null;
            try {
                p = Runtime.getRuntime().exec(cmd);
                // 子进程结束或超时
                if (!p.waitFor((long) timeoutSec, java.util.concurrent.TimeUnit.SECONDS)) {
                    try { p.destroyForcibly(); } catch (Exception ignored) {}
                }
                // 排空 stdout/stderr 防止缓冲填满
                try { p.getInputStream().close(); } catch (Exception ignored) {}
                try { p.getErrorStream().close(); } catch (Exception ignored) {}
            } catch (Exception ignored) {
            } finally {
                if (p != null && p.isAlive()) {
                    try { p.destroyForcibly(); } catch (Exception ignored) {}
                }
            }
        }

        private File findFile(File dir, String name) {
            if (!dir.isDirectory()) return null;
            File[] files = dir.listFiles();
            if (files == null) return null;
            for (File f : files) {
                if (f.isDirectory()) { File r = findFile(f, name); if (r != null) return r; }
                else if (f.getName().equals(name)) return f;
            }
            return null;
        }

        private void deleteDir(File dir) {
            if (dir.isDirectory()) {
                File[] files = dir.listFiles();
                if (files != null) for (File c : files) deleteDir(c);
            }
            dir.delete();
        }

        private void postProgress(int p, long dl, long tl) { if (progress != null) new Handler(Looper.getMainLooper()).post(() -> progress.onProgress(p, dl, tl)); }
        private void postError(String m) { ACTIVE_TASKS.remove(component); if (complete != null) new Handler(Looper.getMainLooper()).post(() -> complete.onError(m)); }
        private void postSuccess(File f) { ACTIVE_TASKS.remove(component); if (complete != null) new Handler(Looper.getMainLooper()).post(() -> complete.onSuccess(f)); }
    }

    // 活动下载任务防重表（组件名 -> 任务），静态跨 Fragment 共享
    private static final java.util.Map<String, DownloadTask> ACTIVE_TASKS = new java.util.concurrent.ConcurrentHashMap<>();

    public DownloadTask createTask(String component, String version, ProgressCallback p, CompleteCallback c) {
        // 防重：同组件已有活动任务则不新建（避免重复下载、进度互相覆盖）
        DownloadTask existing = ACTIVE_TASKS.get(component);
        if (existing != null) {
            return existing;
        }
        DownloadTask task = new DownloadTask(component, version, p, c);
        ACTIVE_TASKS.put(component, task);
        return task;
    }

    public void unregisterTask(String component) {
        ACTIVE_TASKS.remove(component);
    }

    public boolean isInstalled(String component, String version) {
        File dir = new File(appBinDir, component.toLowerCase() + "/" + version);
        if (!dir.isDirectory()) return false;
        File binDir = new File(dir, "bin");
        if (binDir.isDirectory()) {
            File[] files = binDir.listFiles();
            return files != null && files.length > 0;
        }
        File[] files = dir.listFiles();
        return files != null && files.length > 0;
    }

    public String getInstalledVersion(String component) {
        File dir = new File(appBinDir, component.toLowerCase());
        if (!dir.exists()) return "";
        File[] versions = dir.listFiles();
        if (versions == null) return "";
        for (File f : versions) {
            if (f.isDirectory()) {
                File binDir = new File(f, "bin");
                if (binDir.isDirectory()) {
                    File[] contents = binDir.listFiles();
                    if (contents != null && contents.length > 0) return f.getName();
                }
                File[] contents = f.listFiles();
                if (contents != null && contents.length > 0) return f.getName();
            }
        }
        return "";
    }
}
