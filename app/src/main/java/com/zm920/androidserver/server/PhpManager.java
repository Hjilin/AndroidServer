package com.zm920.androidserver.server;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.zm920.androidserver.config.ConfigGenerator;
import com.zm920.androidserver.service.ProcessManager;

import java.io.File;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;

public class PhpManager {

    private static final String TAG = "PhpManager";
    private final Object startLock = new Object();

    private final Context context;
    private final ProcessManager processManager;
    private final ConfigGenerator configGenerator;
    private final SharedPreferences prefs;
    private final BinaryDeployer deployer;
    private final StartupLogger startupLogger;

    public PhpManager(Context context, ProcessManager pm,
                      ConfigGenerator cg, SharedPreferences sp,
                      BinaryDeployer deployer,
                      StartupLogger startupLogger) {
        this.context = context;
        this.processManager = pm;
        this.configGenerator = cg;
        this.prefs = sp;
        this.deployer = deployer;
        this.startupLogger = startupLogger;
    }

    public boolean isInstalled() {
        return prefs.contains("active_php");
    }

    public boolean start() throws Exception {
        synchronized (startLock) {
        if (isRunning()) { Log.i(TAG, "PHP already running"); return true; }
        int phpPort = prefs.getInt("php_port", 9000);
        // 循环清理并等待端口释放
        killByPort(phpPort);
        killallPhp();
        processManager.killByCmdline("php-cgi", "php-fpm");
        for (int attempt = 0; attempt < 6; attempt++) {
            killOrphan("php-cgi");
            killOrphan("php-fpm");
            killByPort(phpPort);
            killallPhp();
        processManager.killByCmdline("php-cgi", "php-fpm");
            try { Thread.sleep(500); } catch (Exception ignored) {}
            if (canBind(phpPort)) break;
            Log.w(TAG, "端口 " + phpPort + " 仍被占用，第 " + (attempt+1) + " 次清理");
        }
        if (!canBind(phpPort)) {
            Log.i(TAG, "PHP 已在运行 (端口 " + phpPort + " 已占用)");
            return true;
        }
        try {
            deployer.deployAll();
            String phpVer = getPhpVersion();
            String phpBin = getPhpBinPath();
            if (phpBin == null) {
                Log.e(TAG, "PHP " + phpVer + " 未部署，期望路径: "
                        + new File(context.getFilesDir(), "bin/php/" + phpVer + "/bin/php-cgi").getAbsolutePath());
                return false;
            }
            String dataDir = context.getFilesDir().getAbsolutePath() + "/server";
            File configDir = new File(dataDir, "config");
            configDir.mkdirs();

            configGenerator.generatePhpIni(phpVer);

            File phpIni = new File(new File(configDir, "php/" + phpVer), "php.ini");
            String[] baseEnv = deployer.buildEnv();
            String sg11Preload = buildSourceGuardianPreloadEnv(phpVer);
            int extraEnvCount = sg11Preload == null ? 3 : 4;
            String[] env = new String[baseEnv.length + extraEnvCount];
            System.arraycopy(baseEnv, 0, env, 0, baseEnv.length);
            int envIndex = baseEnv.length;
            if (sg11Preload != null) {
                env[envIndex++] = sg11Preload;
            }
            // php-cgi FastCGI 模式显式设置，避免部分环境下 stdout/stdin 关闭后直接正常退出(code 0)
            env[envIndex++] = "PHP_FCGI_CHILDREN=2";
            env[envIndex++] = "PHP_FCGI_MAX_REQUESTS=500";
            env[envIndex] = "PHP_FCGI_BACKLOG=128";
            // 所有 PHP 版本都通过 linker64 启动（绕过 SELinux app_data_file 执行限制）
            String arch = System.getProperty("os.arch", "");
            String linker = arch.contains("64") ? "/system/bin/linker64" : "/system/bin/linker";
            String[] cmd = new String[]{linker, phpBin, "-b", "127.0.0.1:" + phpPort, "-c", phpIni.getAbsolutePath()};
            Log.i(TAG, "PHP 启动命令: " + String.join(" ", cmd));

            Log.i(TAG, "启动 PHP " + phpVer + ": " + String.join(" ", cmd));

            String procName = "php-fpm-" + phpVer;
            boolean ok = processManager.start(procName, cmd, env, configDir, phpPort);
            if (ok) {
                int stableCount = 0;
                for (int i = 0; i < 20; i++) {
                    Thread.sleep(300);
                    if (processManager.isAlive(procName)) {
                        stableCount++;
                        if (stableCount >= 3) {
                            Log.i(TAG, "PHP 启动稳定，持续运行 " + (stableCount * 300) + "ms");
                            return true;
                        }
                    } else {
                        if (stableCount > 0) {
                            Log.w(TAG, "PHP 进程在启动后 " + (stableCount * 300) + "ms 时退出");
                        }
                        stableCount = 0;
                    }
                }
            }

            // 如果端口被占用，再尝试一次清理并重试
            if (isPortOpen(phpPort)) {
                Log.w(TAG, "端口 " + phpPort + " 仍被占用，深度清理后重试");
                // 更激进地清理：扫描所有包含 php 的进程
                killOrphan("php");
                killallPhp();
        processManager.killByCmdline("php-cgi", "php-fpm");
                clearTimeWait(phpPort);
                try { Thread.sleep(500); } catch (Exception ignored) {}
                // 再次尝试健康检查（同样需要稳定期）
                int stableCount = 0;
                for (int i = 0; i < 10; i++) {
                    Thread.sleep(300);
                    if (processManager.isAlive(procName)) {
                        stableCount++;
                        if (stableCount >= 3) {
                            Log.i(TAG, "PHP 重试后启动稳定，持续运行 " + (stableCount * 300) + "ms");
                            return true;
                        }
                    } else {
                        stableCount = 0;
                    }
                }
            }
            startupLogger.logFailure(procName, phpBin, cmd, env, configDir,
                new Exception("PHP 启动后存活检查失败"));
            return false;
        } catch (Exception e) {
            startupLogger.logFailure("php-fpm-" + getPhpVersion(), getPhpBinPath(), null, null, null, e);
            return false;
        }
    }
}
public void stop() {
        processManager.stop("php-fpm-" + getPhpVersion());
        int phpPort = prefs.getInt("php_port", 9000);
        for (int i = 0; i < 15; i++) {
            if (canBind(phpPort)) break;
            try { Thread.sleep(500); } catch (Exception ignored) {}
            killByPort(phpPort);
            killallPhp();
    }
    }

    private String buildSourceGuardianPreloadEnv(String phpVer) {
        if (phpVer == null || (!phpVer.startsWith("8.3.") && !phpVer.startsWith("8.5.") && !phpVer.startsWith("7.4."))) return null;
        File shim = new File(context.getFilesDir(), "bin/php/" + phpVer + "/dep/libglibc-shim.so");
        if (!shim.isFile() || shim.length() <= 0) return null;
        return "LD_PRELOAD=" + shim.getAbsolutePath();
    }

    private String getPhpVersion() {
        String v = prefs.getString("active_php", "8.5.1");
        return v.isEmpty() ? "8.5.1" : v;
    }

    /**
     * PHP 不再从 jniLibs 取 libphpcgi.so，统一从版本目录取：
     * {filesDir}/bin/php/{active_php}/bin/php-cgi
     */
    private String getPhpBinPath() {
        String phpVer = getPhpVersion();
        File f = new File(context.getFilesDir(), "bin/php/" + phpVer + "/bin/php-cgi");
        if (f.exists() && f.length() > 0) return f.getAbsolutePath();
        return null;
    }

    public boolean startWithExtensions(java.util.Set<String> extensions, java.util.Set<String> builtinExts) throws Exception {
        return start();
    }

    public boolean isRunning() {
        // 优先 processManager 三重检测（端口 → Process → PID）
        if (processManager.isAlive("php-fpm-" + getPhpVersion())) return true;
        // 兜底：直接端口检测
        int port = prefs.getInt("php_port", 9000);
        return isPortOpen(port);
    }

    /** 用 ServerSocket.bind() 实测端口是否可绑定 */
    private boolean canBind(int port) {
        try {
            ServerSocket ss = new ServerSocket();
            ss.setReuseAddress(true);
            ss.bind(new InetSocketAddress("127.0.0.1", port));
            ss.close();
            return true;
        } catch (Exception e) {
            return false;
        }
    }


    /** 用 killall 按进程名批量杀（兜底） */

    
    


    private void killallPhp() {
        try {
            runCmd(new String[]{"/system/bin/killall", "-9", "php-cgi"});
        } catch (Exception ignored) {}
        try {
            runCmd(new String[]{"/system/bin/killall", "-9", "php"});
        } catch (Exception ignored) {}
    }

    /** 可靠地杀掉进程：先 killProcess，再 /system/bin/kill，再 shell kill */
    private void shellKill(int pid) {
        if (pid <= 0) return;
        try { android.os.Process.killProcess(pid); } catch (Exception ignored) {}
        try { Thread.sleep(50); } catch (Exception ignored) {}
        runCmd(new String[]{"/system/bin/kill", "-9", String.valueOf(pid)});
        try { Thread.sleep(50); } catch (Exception ignored) {}
        runCmd(new String[]{"/system/bin/sh", "-c", "kill -9 " + pid + " 2>/dev/null; exit 0"});
        Log.i(TAG, "shellKill: 已尝试杀死 PID " + pid);
    }

    /** 可靠的 ProcessBuilder 执行，消费输出防止死锁 */
    private void runCmd(String[] cmd) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            byte[] buf = new byte[4096]; while (p.getInputStream().read(buf) != -1) {}
            p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception ignored) {}
    }

    /** 使用 SO_REUSEADDR 临时 bind 再 close 强制清除 TIME_WAIT */
    private void clearTimeWait(int port) {
        try {
            ServerSocket ss = new ServerSocket();
            ss.setReuseAddress(true);
            ss.bind(new InetSocketAddress("127.0.0.1", port));
            ss.close();
        } catch (Exception ignored) {}
    }

    private void killOrphan(String name) {
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
                    if (n > 0 && new String(b, 0, n).replace("\0", " ").contains(name)) {
                        android.util.Log.i(TAG, "清理残留进程 " + pid + " (" + name + ")");
                        shellKill(Integer.parseInt(pid));
                    }
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
    }

    /** 通过 /proc/net/tcp 查找并杀死占用指定端口的进程 */
    private void killByPort(int port) {
        try {
            java.io.File f = new java.io.File("/proc/net/tcp");
            if (!f.canRead()) return;
            byte[] buf = new byte[32768];
            int n;
            try (java.io.FileInputStream fis = new java.io.FileInputStream(f)) {
                n = fis.read(buf);
            }
            if (n <= 0) return;
            String hexPort = String.format("%04X", port);
            for (String line : new String(buf, 0, n).split("\n")) {
                line = line.trim();
                String[] parts = line.split("\s+");
                if (parts.length < 10) continue;
                String localAddr = parts[1];
                if (!localAddr.endsWith(":" + hexPort)) continue;
                String state = parts[3];
                if (!"0A".equals(state)) continue;
                String inodeStr = parts[9];
                if (inodeStr == null || inodeStr.isEmpty()) continue;
                long targetInode;
                try { targetInode = Long.parseLong(inodeStr); } catch (Exception e) { continue; }
                java.io.File[] dirs = new java.io.File("/proc").listFiles();
                if (dirs == null) continue;
                for (java.io.File d : dirs) {
                    if (!d.isDirectory()) continue;
                    String pid = d.getName();
                    if (!pid.matches("\\d+")) continue;
                    java.io.File fdDir = new File(d, "fd");
                    java.io.File[] fds = fdDir.listFiles();
                    if (fds == null) continue;
                    for (java.io.File fd : fds) {
                        try {
                            String link = fd.getCanonicalPath();
                            if (link.equals("socket:[" + targetInode + "]")) {
                                android.util.Log.i(TAG, "killByPort: 端口 " + port + " 被进程 " + pid + " 占用，正在杀死");
                                shellKill(Integer.parseInt(pid));
                            }
                        } catch (Exception ignored) {}
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    private boolean isPortOpen(int port) {
        try {
            Socket s = new Socket(); s.connect(new InetSocketAddress("127.0.0.1", port), 50);
            s.close();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 读取 PHP 进程日志尾部，用于启动失败时诊断 */
    private String readRuntimeLogTail(String procName, int maxBytes) {
        File logFile = new File(context.getFilesDir(), "runtime_logs/" + procName + ".log");
        if (!logFile.exists()) return "(日志文件不存在)";
        try {
            long size = logFile.length();
            int read = (int) Math.min(size, maxBytes);
            byte[] buf = new byte[read];
            try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(logFile, "r")) {
                raf.seek(Math.max(0, size - read));
                int n = raf.read(buf);
                if (n < read) {
                    byte[] nb = new byte[n];
                    System.arraycopy(buf, 0, nb, 0, n);
                    buf = nb;
                }
            }
            return new String(buf, "UTF-8").trim();
        } catch (Exception e) {
            return "(读取日志失败: " + e.getMessage() + ")";
        }
    }
}
