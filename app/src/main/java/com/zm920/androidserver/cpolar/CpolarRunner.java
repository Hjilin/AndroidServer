package com.zm920.androidserver.cpolar;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CpolarRunner {

    private static final String TAG = "CpolarRunner";
    /**
     * cpolar 公网地址匹配：匹配任何包含 .cpolar.xxx 的 http/https/tcp 地址
     *  - https?://&lt;id&gt;.r&lt;N&gt;.cpolar.&lt;tld&gt;
     *  - tcp://&lt;host&gt;.tcp.cpolar.&lt;tld&gt;:&lt;port&gt;
     *  - 或任何其他 cpolar 子域格式
     */
    private static final Pattern URL_PATTERN = Pattern.compile(
            "((?:https?|tcp)://[a-z0-9\\-\\.]+\\.cpolar\\.[a-z]+(?::\\d+)?)");

    private final Context ctx;
    private final File binFile;
    private final File ymlFile;
    private final File logFile;
    private final File workDir;

    private volatile Process process;
    private volatile Thread readerThread;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicReference<String> publicUrl = new AtomicReference<>("");
    private final AtomicReference<String> lastError = new AtomicReference<>("");

    public CpolarRunner(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.workDir = new File(ctx.getFilesDir(), "cpolar");
        // 二进制放在 nativeLibraryDir，selinux 允许 exec
        this.binFile = new File(ctx.getApplicationInfo().nativeLibraryDir, "libcpolar.so");
        // cpolar 找的是 $HOME/.cpolar/cpolar.yml
        this.ymlFile = new File(workDir, ".cpolar/cpolar.yml");
        this.logFile = new File(workDir, "cpolar.log");
    }

    public boolean isRunning() { return running.get(); }
    public String getPublicUrl() { return publicUrl.get(); }
    public String getLastError() { return lastError.get(); }
    public File getLogFile() { return logFile; }
    public File getBinFile() { return binFile; }
    public File getWorkDir() { return workDir; }

    /** 环境自检 - 在 UI 里直接展示 */
    public synchronized String selfCheck() {
        StringBuilder sb = new StringBuilder();
        try {
            sb.append("=== cpolar 环境自检 ===\n");
            sb.append("filesDir: ").append(ctx.getFilesDir().getAbsolutePath()).append("\n");
            sb.append("workDir: ").append(workDir.getAbsolutePath()).append("\n");
            sb.append("workDir exists: ").append(workDir.exists()).append("\n");
            sb.append("bin: ").append(binFile.getAbsolutePath()).append("\n");
            sb.append("bin exists: ").append(binFile.exists()).append("\n");
            if (binFile.exists()) {
                sb.append("bin size: ").append(binFile.length()).append(" bytes\n");
                sb.append("canRead: ").append(binFile.canRead()).append("\n");
                sb.append("canExec: ").append(binFile.canExecute()).append("\n");
            }
            sb.append("yml: ").append(ymlFile.getAbsolutePath()).append("\n");
            sb.append("yml exists: ").append(ymlFile.exists()).append("\n");
            // uname
            try {
                sb.append("\n--- uname -a ---\n");
                sb.append(execForOutput(new String[]{"uname", "-a"}, 3));
            } catch (Exception e) { sb.append("uname failed: ").append(e.getMessage()).append("\n"); }
            // 试一下二进制能否执行
            if (binFile.exists()) {
                try {
                    sb.append("\n--- cpolar version ---\n");
                    sb.append(execForOutput(new String[]{binFile.getAbsolutePath(), "version"}, 5));
                } catch (Exception e) {
                    sb.append("cpolar version failed: ").append(e.getMessage()).append("\n");
                }
                // 用 sh -c 试一次
                try {
                    sb.append("\n--- sh -c cpolar version ---\n");
                    sb.append(execForOutput(new String[]{"sh", "-c", binFile.getAbsolutePath() + " version"}, 5));
                } catch (Exception e) {
                    sb.append("sh -c failed: ").append(e.getMessage()).append("\n");
                }
            }
        } catch (Exception e) {
            sb.append("selfCheck error: ").append(e.getMessage());
        }
        return sb.toString();
    }

    private String execForOutput(String[] cmd, int timeoutSec) {
        Process p = null;
        java.io.InputStream is = null;
        try {
            // 用 ProcessBuilder + redirectErrorStream(true)，避免 stderr 缓冲填满导致子进程死锁
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            p = pb.start();
            is = p.getInputStream();
            StringBuilder out = new StringBuilder();
            byte[] buf = new byte[2048];
            int n;
            long start = System.currentTimeMillis();
            long deadline = start + (long) timeoutSec * 1000L;
            while ((n = is.read(buf)) > 0) {
                out.append(new String(buf, 0, n, "UTF-8"));
                if (System.currentTimeMillis() > deadline) {
                    try { p.destroyForcibly(); } catch (Exception ignored) {}
                    out.append("\n[timed out]\n");
                    break;
                }
            }
            p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS);
            return out.toString();
        } catch (Exception e) { return "[error: " + e.getMessage() + "]"; }
        finally {
            closeQuietly(is);
            if (p != null && p.isAlive()) {
                try { p.destroyForcibly(); } catch (Exception ignored) {}
            }
        }
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c == null) return;
        try { c.close(); } catch (Exception ignored) {}
    }

    public synchronized boolean ensureInstalled() {
        try {
            if (!workDir.exists()) workDir.mkdirs();

            // 1) nativeLibraryDir 下的 libcpolar.so 在 APK 安装时由系统解压，
            //    已经被系统设置好可执行权限。我们不写、不 chmod。
            if (!binFile.exists()) {
                lastError.set("libcpolar.so not found in nativeLibraryDir: " + binFile.getAbsolutePath());
                Log.e(TAG, lastError.get());
                return false;
            }
            Log.i(TAG, "bin: " + binFile.getAbsolutePath() + " (" + binFile.length() + " bytes, canExec=" + binFile.canExecute() + ")");

            // 2) 创建 .cpolar 子目录
            File ymlDir = ymlFile.getParentFile();
            if (!ymlDir.exists()) ymlDir.mkdirs();

            // 3) 写 yml
            String token = CpolarConfig.getToken(ctx);
            if (token.isEmpty()) {
                lastError.set("token is empty");
                return false;
            }
            try (FileOutputStream fos = new FileOutputStream(ymlFile)) {
                String yml = "authtoken: " + token + "\n";
                fos.write(yml.getBytes("UTF-8"));
            }
            ymlFile.setReadable(true, false);
            ymlFile.setWritable(true, true);
            Log.i(TAG, "yml written: " + ymlFile.getAbsolutePath());
            return true;
        } catch (Exception e) {
            Log.e(TAG, "ensureInstalled failed", e);
            lastError.set(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            return false;
        }
    }

    public synchronized boolean start() {
        if (running.get()) return true;
        lastError.set("");
        if (!ensureInstalled()) return false;

        int port = CpolarConfig.getTargetPort(ctx);
        String region = CpolarConfig.getRegion(ctx);
        String proto = CpolarConfig.getProto(ctx);
        String level = CpolarConfig.getLogLevel(ctx);

        try {
            // 直接用绝对路径调用二进制
            ProcessBuilder pb = new ProcessBuilder(
                    binFile.getAbsolutePath(),
                    proto,
                    "-region=" + region,
                    "-log=stdout",
                    "-log-level=" + level,
                    String.valueOf(port)
            );
            pb.directory(workDir);
            pb.redirectErrorStream(true);
            java.util.Map<String, String> env = pb.environment();
            env.put("HOME", workDir.getAbsolutePath());
            env.put("USER", "root");
            env.put("LOGNAME", "root");

            writeLog("[runner] starting cpolar: cmd=" + binFile.getAbsolutePath() + " " + proto + " -region=" + region + " -log=stdout -log-level=" + level + " " + port);
            writeLog("[runner] workDir=" + workDir.getAbsolutePath() + " targetPort=" + port);
            process = pb.start();
            running.set(true);
            lastError.set("");
            publicUrl.set("");
            writeLog("[runner] cpolar process started");
            Log.i(TAG, "cpolar process started");

            readerThread = new Thread(this::readLoop, "CpolarRunner-Reader");
            readerThread.setDaemon(true);
            readerThread.start();
            return true;
        } catch (Exception e) {
            Log.e(TAG, "start failed", e);
            lastError.set(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            writeLog("[runner] start failed: " + lastError.get());
            running.set(false);
            return false;
        }
    }

    public synchronized void stop() {
        if (!running.get()) return;
        running.set(false);
        // 先关闭输入流，让 reader 线程的 readLine() 立即返回
        try {
            if (process != null) {
                java.io.InputStream is = process.getInputStream();
                if (is != null) try { is.close(); } catch (Exception ignored) {}
                if (android.os.Build.VERSION.SDK_INT >= 26) {
                    process.destroyForcibly();
                } else {
                    process.destroy();
                }
            }
        } catch (Exception ignored) {}
        try { if (readerThread != null) readerThread.join(1000); } catch (Exception ignored) {}
        process = null;
        readerThread = null;
        lastError.set("");
        writeLog("[runner] cpolar stopped");
        Log.i(TAG, "cpolar stopped");
    }

    private void readLoop() {
        // 防御性检查：start 失败时 process 可能为 null
        Process p = process;
        if (p == null) {
            running.set(false);
            return;
        }
        // start() 已用 pb.redirectErrorStream(true) 合并 stdout+stderr，
        // readLoop 读 InputStream 时 stderr 不会再填满缓冲导致 cpolar 卡死。
        try (java.io.BufferedReader br = new java.io.BufferedReader(
                new java.io.InputStreamReader(p.getInputStream(), "UTF-8"))) {
            String line;
            while ((line = br.readLine()) != null && running.get()) {
                writeLog(line);
                Matcher m = URL_PATTERN.matcher(line);
                if (m.find()) {
                    String url = m.group(1);
                    publicUrl.set(url);
                    CpolarConfig.setPublicUrl(ctx, url);
                    Log.i(TAG, "tunnel url: " + url);
                }
                if (line.contains("error") || line.contains("错误")) {
                    lastError.set(line);
                }
            }
        } catch (Exception e) {
            if (running.get()) {
                Log.w(TAG, "reader error: " + e.getMessage());
                lastError.set("reader: " + e.getMessage());
            }
        } finally {
            running.set(false);
            writeLog("[runner] reader loop exited");
        }
    }

    private void writeLog(String line) {
        try {
            if (logFile.exists() && logFile.length() > 256 * 1024) logFile.delete();
            try (FileOutputStream fos = new FileOutputStream(logFile, true)) {
                fos.write((line + "\n").getBytes("UTF-8"));
            }
        } catch (Exception ignored) {}
    }
}
