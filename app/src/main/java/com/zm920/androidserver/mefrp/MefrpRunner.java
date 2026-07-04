package com.zm920.androidserver.mefrp;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ME Frp 进程管理。
 *
 * <p>启动命令：./mefrpc -t &lt;key&gt; -p &lt;tunnel_port&gt;
 * <p>二进制路径：默认 /storage/emulated/0/mefrpc，可配置
 */
public class MefrpRunner {

    public interface OnExitCallback {
        void onProcessExited();
    }

    private static final String TAG = "MefrpRunner";

    /**
     * ME Frp 公网地址匹配。
     * 域名是用户自己绑定的，不固定，所以用日志中 URL 两边的参照提取：
     * "您可以使用 [http://xxx] 访问您的服务" / "可以使用 http://xxx 访问"
     */
    private static final Pattern URL_PATTERN = Pattern.compile(
            "(?:您可以使用|可以使用|使用)\\s*\\[?\\s*((?:https?|tcp)://[^\\s\\]\\[，,]+)");
    private final Context ctx;
    private final File workDir;
    private final File logFile;

    private volatile Process process;
    private volatile Thread readerThread;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicReference<String> publicUrl = new AtomicReference<>("");
    private final AtomicReference<String> lastError = new AtomicReference<>("");
    private volatile OnExitCallback exitCallback;

    public MefrpRunner(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.workDir = new File(ctx.getFilesDir(), "mefrp");
        this.logFile = new File(workDir, "mefrpc.log");
    }

    public boolean isRunning() { return running.get(); }
    public String getPublicUrl() { return publicUrl.get(); }
    public String getLastError() { return lastError.get(); }
    public void setExitCallback(OnExitCallback cb) { this.exitCallback = cb; }
    public File getLogFile() { return logFile; }
    public File getWorkDir() { return workDir; }

    /** 获取 mefrpc 二进制文件：私有部署目录 */
    public File getBinFile() {
        String ver = MefrpConfig.getVersion(ctx);
        return new File(ctx.getFilesDir(), "bin/mefrp/" + ver + "/bin/mefrpc");
    }

    /** 获取 mefrpc 部署目录 */
    public File getDeployDir() {
        String ver = MefrpConfig.getVersion(ctx);
        return new File(ctx.getFilesDir(), "bin/mefrp/" + ver);
    }

    /** 检查二进制是否已部署 */
    public boolean isBinReady() {
        File bin = getBinFile();
        return bin.exists() && bin.canExecute();
    }

    /**
     * 读取 ELF 头判断二进制架构。
     * @return true=64 位 ELF，false=32 位 ELF，null=无法识别
     */
    private Boolean detectBinaryArch(File bin) {
        try (FileInputStream fis = new FileInputStream(bin)) {
            byte[] header = new byte[5];
            int n = fis.read(header);
            if (n != 5) return null;
            // ELF 魔数: 7f 45 4c 46
            if (header[0] != 0x7f || header[1] != 'E' || header[2] != 'L' || header[3] != 'F') {
                return null;
            }
            // EI_CLASS: 1=ELFCLASS32, 2=ELFCLASS64
            return header[4] == 2;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 选择可用的 linker。
     * 优先匹配二进制架构，其次回退到另一个 linker。
     */
    private String pickLinker(boolean binIs64) {
        // 1) 优先匹配二进制架构
        String preferred = binIs64 ? "/system/bin/linker64" : "/system/bin/linker";
        if (new File(preferred).exists()) return preferred;
        // 2) 回退到另一个
        String fallback = binIs64 ? "/system/bin/linker" : "/system/bin/linker64";
        if (new File(fallback).exists()) return fallback;
        // 3) 都没有，返回 null 让上层报错
        return null;
    }

    /** 环境自检 */
    public synchronized String selfCheck() {
        StringBuilder sb = new StringBuilder();
        try {
            sb.append("=== ME Frp 环境自检 ===\n");
            sb.append("filesDir: ").append(ctx.getFilesDir().getAbsolutePath()).append("\n");
            sb.append("workDir: ").append(workDir.getAbsolutePath()).append("\n");
            sb.append("workDir exists: ").append(workDir.exists()).append("\n");
            File bin = getBinFile();
            sb.append("bin path: ").append(bin.getAbsolutePath()).append("\n");
            sb.append("deploy dir: ").append(getDeployDir().getAbsolutePath()).append("\n");
            sb.append("bin exists: ").append(bin.exists()).append("\n");
            if (bin.exists()) {
                sb.append("bin size: ").append(bin.length()).append(" bytes\n");
                sb.append("canRead: ").append(bin.canRead()).append("\n");
                sb.append("canExec: ").append(bin.canExecute()).append("\n");
                Boolean is64 = detectBinaryArch(bin);
                sb.append("bin arch: ").append(is64 == null ? "未知" : (is64 ? "64-bit" : "32-bit")).append("\n");
                sb.append("linker64: ").append(new File("/system/bin/linker64").exists() ? "存在" : "不存在").append("\n");
                sb.append("linker:   ").append(new File("/system/bin/linker").exists() ? "存在" : "不存在").append("\n");
            }
            sb.append("key: ").append(MefrpConfig.getKey(ctx).isEmpty() ? "(未填写)" : "***").append("\n");
            sb.append("tunnel no: ").append(MefrpConfig.getTunnelPort(ctx)).append("\n");
            sb.append("local port: ").append(MefrpConfig.getLocalPort(ctx)).append("\n");
        } catch (Exception e) {
            sb.append("自检异常: ").append(e.getMessage()).append("\n");
        }
        return sb.toString();
    }

    public synchronized boolean start() {
        if (running.get()) return true;
        userStopped = false;
        lastError.set("");

        // 1) 检查二进制
        File bin = getBinFile();
        if (!bin.exists()) {
            lastError.set("mefrpc 二进制不存在: " + bin.getAbsolutePath() + "，请先点击「初始化」下载");
            Log.e(TAG, lastError.get());
            return false;
        }
        if (!bin.canRead()) {
            lastError.set("mefrpc 二进制不可读: " + bin.getAbsolutePath());
            Log.e(TAG, lastError.get());
            return false;
        }

        // 2) 检查 key 和隧道号
        String key = MefrpConfig.getKey(ctx);
        if (key.isEmpty()) {
            lastError.set("Key 未填写");
            return false;
        }
        String tunnelPort = MefrpConfig.getTunnelPort(ctx);
        if (tunnelPort.isEmpty()) {
            lastError.set("隧道号未填写");
            return false;
        }

        // 3) 确保工作目录存在
        if (!workDir.exists()) workDir.mkdirs();

        try {
            // 启动命令：./mefrpc -t <key> -p <tunnel_port>
            // Android 私有目录 SELinux 禁止直接 exec，必须通过 linker/linker64 启动
            // 必须根据二进制本身的架构选择 linker，否则 32 位二进制用 linker64 加载会报
            // "is 32-bit instead of 64-bit" 错误
            Boolean binIs64Box = detectBinaryArch(bin);
            boolean binIs64 = binIs64Box != null && binIs64Box;
            String linker = pickLinker(binIs64);
            if (linker == null) {
                lastError.set("系统缺少 linker/linker64，无法启动 mefrpc");
                Log.e(TAG, lastError.get());
                writeLog("[runner] " + lastError.get());
                return false;
            }

            java.util.List<String> cmdList = new java.util.ArrayList<>();
            cmdList.add(linker);
            cmdList.add(bin.getAbsolutePath());
            cmdList.add("-t");
            cmdList.add(key);
            cmdList.add("-p");
            cmdList.add(tunnelPort);
            ProcessBuilder pb = new ProcessBuilder(cmdList);
            pb.directory(workDir);
            pb.redirectErrorStream(true);
            java.util.Map<String, String> env = pb.environment();
            // 清除 LD_PRELOAD：Termux 默认指向 64 位 libtermux-exec-ld-preload.so，
            // 会导致 32 位 mefrpc 报 "is 32-bit instead of 64-bit" 错误
            env.remove("LD_PRELOAD");
            env.put("HOME", workDir.getAbsolutePath());

            writeLog("[runner] starting mefrpc: " + bin.getAbsolutePath() + " -t *** -p " + tunnelPort);
            writeLog("[runner] workDir=" + workDir.getAbsolutePath());
            writeLog("[runner] bin arch=" + (binIs64Box == null ? "未知" : (binIs64 ? "64-bit" : "32-bit")) + ", linker=" + linker);

            process = pb.start();
            running.set(true);
            publicUrl.set("");
            writeLog("[runner] mefrpc process started");
            Log.i(TAG, "mefrpc process started");

            readerThread = new Thread(this::readLoop, "MefrpRunner-Reader");
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

    private volatile boolean userStopped = false;

    public synchronized void stop() {
        if (!running.get()) return;
        userStopped = true;
        running.set(false);
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
        writeLog("[runner] mefrpc stopped");
        Log.i(TAG, "mefrpc stopped");
    }

    private void readLoop() {
        Process p = process;
        if (p == null) {
            running.set(false);
            return;
        }
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(p.getInputStream(), "UTF-8"))) {
            String line;
            while ((line = br.readLine()) != null && running.get()) {
                writeLog(line);
                Log.i(TAG, "mefrpc: " + line);
                // 匹配公网 URL
                Matcher m = URL_PATTERN.matcher(line);
                if (m.find()) {
                    String url = m.group(1);
                    publicUrl.set(url);
                    MefrpConfig.setPublicUrl(ctx, url);
                    writeLog("[runner] public url: " + url);
                }
                // 检测错误关键字：进程仍在运行时只记录日志，不设置 lastError。
                // 避免连接节点过程中的中间日志触发 UI 误显"启动失败"。
                // 只有进程退出后才设置 lastError。
                String lower = line.toLowerCase();
                if (lower.contains("error") || lower.contains("failed") || lower.contains("invalid")) {
                    if (!lower.contains("debug")) {
                        writeLog("[runner] warning: " + line);
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "readLoop: " + e.getMessage());
        }
        // 进程退出
        running.set(false);
        writeLog("[runner] mefrpc exited");
        if (!userStopped && exitCallback != null) {
            exitCallback.onProcessExited();
        }
        userStopped = false;
    }

    private void writeLog(String line) {
        try {
            if (!workDir.exists()) workDir.mkdirs();
            try (FileOutputStream fos = new FileOutputStream(logFile, true)) {
                fos.write((line + "\n").getBytes("UTF-8"));
            }
        } catch (Exception ignored) {}
    }

    /** 读最近 N KB 日志 */
    public String readTailLog(int maxBytes) {
        File f = logFile;
        if (f == null || !f.exists()) return "";
        try {
            long size = f.length();
            int read = (int) Math.min(size, maxBytes);
            byte[] buf = new byte[read];
            try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(f, "r")) {
                raf.seek(Math.max(0, size - read));
                int n = raf.read(buf);
                if (n < read) {
                    byte[] nb = new byte[n];
                    System.arraycopy(buf, 0, nb, 0, n);
                    buf = nb;
                }
            }
            return new String(buf, "UTF-8");
        } catch (Exception e) { return "读取失败: " + e.getMessage(); }
    }
}
