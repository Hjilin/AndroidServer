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
import java.util.HashSet;
import java.util.Set;

public class WebServer {

    private static final String TAG = "WebServer";
    private final Object startLock = new Object();

    private final Context context;
    private final ProcessManager processManager;
    private final ConfigGenerator configGenerator;
    private final SharedPreferences prefs;
    private final BinaryDeployer deployer;
    private final StartupLogger startupLogger;

    public WebServer(Context context, ProcessManager pm,
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
        return prefs.contains("active_nginx");
    }

    public String getBinPath() {
        return deployer.getBinPath("nginx");
    }

    /** 获取所有站点的端口集合 */
    private Set<Integer> getSitePorts() {
        Set<Integer> ports = new HashSet<>();
        Set<String> sites = prefs.getStringSet("sites", new HashSet<>());
        for (String site : sites) {
            String[] parts = site.split("\\|");
            if (parts.length >= 2) {
                try { ports.add(Integer.parseInt(parts[1])); } catch (Exception ignored) {}
            }
        }
        return ports;
    }

    private void validateSitePorts() throws Exception {
        Set<String> sites = prefs.getStringSet("sites", new HashSet<>());
        for (String site : sites) {
            String[] parts = site.split("\\|");
            if (parts.length < 2) continue;
            int port;
            try {
                port = Integer.parseInt(parts[1]);
            } catch (Exception e) {
                throw new Exception("站点端口无效: " + site);
            }
            if (port < 1024) {
                Log.w(TAG, "端口 " + port + " 低于 1024，尝试启动（可能无权限）");
            }
            if (port == 555) {
                throw new Exception("端口 555 在当前 Android 环境不可用，请改为 8080、8081、9001 等端口");
            }
        }
    }

    /** 是否有站点 */
    public boolean hasSites() {
        Set<String> sites = prefs.getStringSet("sites", new HashSet<>());
        return sites != null && !sites.isEmpty();
    }
    /** 检测 phpMyAdmin：检查私有目录下是否存在版本目录 */
    private boolean hasPhpMyAdmin() {
        java.io.File binPma = new java.io.File(context.getFilesDir(), "bin/phpmyadmin");
        if (!binPma.exists() || !binPma.isDirectory()) return false;
        java.io.File[] versions = binPma.listFiles();
        if (versions == null || versions.length == 0) return false;
        for (java.io.File v : versions) {
            if (v.isDirectory() && v.getName().matches("\\d+\\.\\d+\\.\\d+.*")) return true;
        }
        return false;
    }

    /** 获取 phpMyAdmin 已安装版本的完整路径 */
    private String getPhpMyAdminRoot() {
        java.io.File binPma = new java.io.File(context.getFilesDir(), "bin/phpmyadmin");
        if (!binPma.exists() || !binPma.isDirectory()) return null;
        java.io.File[] versions = binPma.listFiles();
        if (versions == null) return null;
        for (java.io.File v : versions) {
            if (v.isDirectory() && v.getName().matches("\\d+\\.\\d+\\.\\d+.*")) {
                return v.getAbsolutePath();
            }
        }
        return null;
    }



    /** 用 killall 按进程名批量杀（兜底） */

    
    


    private void killallNginx() {
        try {
            runCmd(new String[]{"/system/bin/killall", "-9", "nginx"});
        } catch (Exception ignored) {}
    }

    /** 优雅停止 nginx：先读 pidfile 发送 SIGQUIT，再 SIGKILL 兜底 */
    private void stopNginxGracefully(String configDir) {
        // 方法A: 读 pidfile 优雅退出
        File pidFile = new File(configDir, "var/run/nginx.pid");
        if (pidFile.exists() && pidFile.canRead()) {
            try (java.io.FileInputStream fis = new java.io.FileInputStream(pidFile)) {
                byte[] b = new byte[32];
                int n = fis.read(b);
                if (n > 0) {
                    int masterPid = Integer.parseInt(new String(b, 0, n).trim());
                    if (masterPid > 0) {
                        Log.i(TAG, "向 nginx master PID " + masterPid + " 发送 SIGQUIT");
                        // SIGQUIT = 3，nginx 收到后优雅关闭 worker 再退出
                        runCmd(new String[]{"/system/bin/kill", "-s", "QUIT", String.valueOf(masterPid)});
                        try { Thread.sleep(500); } catch (Exception ignored) {}
                        runCmd(new String[]{"/system/bin/kill", "-3", String.valueOf(masterPid)});
                        try { Thread.sleep(500); } catch (Exception ignored) {}
                        // 检查是否还活着，活着就 SIGTERM
                        if (new File("/proc/" + masterPid).exists()) {
                            runCmd(new String[]{"/system/bin/kill", "-15", String.valueOf(masterPid)});
                            try { Thread.sleep(1000); } catch (Exception ignored) {}
                        }
                    }
                }
            } catch (Exception ignored) {}
        }
        // 方法B: 用 nginx 二进制发送 quit 信号
        try {
            String binPath = getBinPath();
            if (binPath != null) {
                runCmd(new String[]{binPath, "-s", "quit", "-p", configDir, "-c", new File(configDir, "nginx.conf").getAbsolutePath()});
                try { Thread.sleep(1000); } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
        // 方法C: 强杀所有 nginx 相关进程
        try { android.os.Process.killProcess(readPidFile(new File(configDir, "var/run/nginx.pid"))); } catch (Exception ignored) {}
        killallNginx();
        processManager.killByCmdline("nginx");
        killOrphan("nginx");
        killOrphan("nginx", "bin/nginx");
        killOrphan("nginx", "bin/nginx");
        // 如果 pidfile 存在，shell kill
        shellKill(readPidFile(pidFile));
    }

    private int readPidFile(File f) {
        try (java.io.FileInputStream fis = new java.io.FileInputStream(f)) {
            byte[] b = new byte[32];
            int n = fis.read(b);
            if (n > 0) return Integer.parseInt(new String(b, 0, n).trim());
        } catch (Exception ignored) {}
        return -1;
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

    /** 用 ServerSocket.bind() 实测端口是否可绑定（与 nginx 保持一致） */
    private boolean canBind(int port) {
        try {
            ServerSocket ss = new ServerSocket();
            ss.setReuseAddress(true);
            ss.bind(new InetSocketAddress("0.0.0.0", port));
            ss.close();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 循环 SO_REUSEADDR bind/close 清除 TIME_WAIT，直到端口释放或超时 */
    private void clearTimeWait(int port) {
        for (int i = 0; i < 20; i++) {
            try {
                ServerSocket ss = new ServerSocket();
                ss.setReuseAddress(true);
                ss.bind(new InetSocketAddress("0.0.0.0", port));
                ss.close();
                return; // bind 成功 = TIME_WAIT 已清除
            } catch (Exception e) {
                try { Thread.sleep(150); } catch (Exception ignored) {}
            }
        }
    }
    public boolean start() throws Exception {
        synchronized (startLock) {
        // Prevent concurrent startup
        if (isRunning()) {
            Log.i(TAG, "nginx already running, skip duplicate start");
            return true;
        }
        // 没有站点但有 phpMyAdmin 也启动
        if (!hasSites()) {
            String wwwRoot = prefs.getString("data_dir", android.os.Environment.getExternalStorageDirectory().getAbsolutePath() + "/wwwroot/www");
            boolean hasPma = hasPhpMyAdmin();
            if (!hasPma) {
                Log.i(TAG, "没有站点且无 phpMyAdmin，跳过 nginx 启动");
                return false;
            }
            Log.i(TAG, "无站点但有 phpMyAdmin，启动 nginx");
        }

        try {
            validateSitePorts();
            deployer.deployAll();
            String binPath = getBinPath();
            if (binPath == null) return false;

            String privateDataDir = context.getFilesDir().getAbsolutePath() + "/server";
            File runDir = new File(privateDataDir);
            File configDir = new File(runDir, "config");
            configDir.mkdirs();
            new File(runDir, "html").mkdirs();

            new File(configDir, "logs").mkdirs();
            new File(configDir, "var/log/nginx").mkdirs();
            new File(configDir, "var/run").mkdirs();
            new File(configDir, "var/lib/nginx/client-body").mkdirs();
            new File(configDir, "var/lib/nginx/proxy").mkdirs();
            new File(configDir, "var/lib/nginx/fastcgi").mkdirs();

            String wwwRoot = prefs.getString("data_dir", android.os.Environment.getExternalStorageDirectory().getAbsolutePath() + "/wwwroot/www");
            new File(wwwRoot).mkdirs();
            // 先生成配置（传入 phpMyAdmin 双重校验结果）
            boolean hasPma = hasPhpMyAdmin();
            String pmaRoot = getPhpMyAdminRoot();
            configGenerator.generateNginxConf(configDir.getAbsolutePath(), wwwRoot, hasPma, pmaRoot != null ? pmaRoot : "");
            String cfgPath = new File(configDir, "nginx.conf").getAbsolutePath();

            // 收集所有需要检查的端口
            java.util.Set<Integer> ports = getSitePorts();
            if (hasPhpMyAdmin()) {
                ports.add(15237);
            }
            int healthPort = 15237;
            for (int p : getSitePorts()) { healthPort = p; break; }

            // 步骤1: 优雅停止旧 nginx（SIGQUIT → 强杀 → TIME_WAIT 清理）
            stopNginxGracefully(configDir.getAbsolutePath());
            processManager.stop("nginx");

            // 步骤2: 循环等待端口真正可 bind（canBind 自带 SO_REUSEADDR 清除 TIME_WAIT）
            long deadline = System.currentTimeMillis() + 15000;
            boolean portsFreed = false;
            while (System.currentTimeMillis() < deadline) {
                portsFreed = true;
                for (int p : ports) {
                    if (!canBind(p)) { portsFreed = false; break; }
                }
                if (!canBind(15237)) portsFreed = false;
                if (portsFreed) break;
                // 继续强杀
                killallNginx();
                processManager.killByCmdline("nginx");
                killOrphan("nginx");
                killOrphan("nginx", "bin/nginx");
                try { Thread.sleep(500); } catch (Exception ignored) {}
            }
            if (!portsFreed) {
                Log.e(TAG, "nginx 端口在 15s 内未释放，强制继续启动");
            }

            // 步骤2b: 端口已释放但在服务？
            if (isRunning()) {
                Log.i(TAG, "nginx 已在运行（配置已刷新）");
                return true;
            }

            String[] env = deployer.buildEnv();
            String[] cmd = deployer.buildExecCmd(binPath, "-c", cfgPath, "-p", configDir.getAbsolutePath());

            // 步骤4: 启动并存活检查（最多 3s，200ms 间隔）
            boolean ok = processManager.start("nginx", cmd, env, configDir, healthPort);
            if (ok) {
                for (int i = 0; i < 15; i++) {
                    Thread.sleep(200);
                    if (processManager.isAlive("nginx")) return true;
                }
            }

            startupLogger.logFailure("nginx", binPath, cmd, env, configDir,
                new Exception("nginx 启动后存活检查失败"));
            return false;
        } catch (Exception e) {
            startupLogger.logFailure("nginx", getBinPath(), null, null, null, e);
            return false;
        }
    }
    }

    /**
     * 重启 nginx：先 stop 再 start。
     * 用于网络切换后重新绑定 socket。
     */
    public void restart() {
        synchronized (startLock) {
            try {
                Log.i(TAG, "重启 nginx");
                stop();
                Thread.sleep(1000);
                start();
            } catch (Exception e) {
                Log.e(TAG, "重启 nginx 失败", e);
            }
        }
    }

    public void stop() {
        synchronized (startLock) {
        String privateDataDir = context.getFilesDir().getAbsolutePath() + "/server";
        File configDir = new File(privateDataDir, "config");
        stopNginxGracefully(configDir.getAbsolutePath());
        processManager.stop("nginx");
        Set<Integer> ports = getSitePorts();
        if (hasPhpMyAdmin()) {
            ports.add(15237);
        }
        // 循环等待端口真正释放（canBind 自带 SO_REUSEADDR 清除 TIME_WAIT）
        for (int i = 0; i < 15; i++) {
            boolean allFree = true;
            for (int port : ports) {
                if (!canBind(port)) {
                    allFree = false;
                    killByPort(port);
                }
            }
            if (!canBind(15237)) allFree = false;
            if (allFree) break;
            killallNginx();
            killOrphan("nginx");
            processManager.killByCmdline("nginx");
            killOrphan("nginx", "bin/nginx");
            try { Thread.sleep(500); } catch (Exception ignored) {}
        }
    }
    }

    public boolean isRunning() {
        // 1. 先检查进程（快速），进程存在即认为运行中（端口未就绪只是暂时的）
        if (processManager.isAlive("nginx")) {
            return true;
        }
        // 2. 兜底：遍历 /proc/*/cmdline 查找 nginx 进程（处理 Process 对象丢失/PID 文件缺失的情况）
        if (isNginxProcessAlive()) {
            return true;
        }
        // 3. 最后回退到端口检测（检测是否有外部 nginx 在跑）
        Set<Integer> ports = getSitePorts();
        if (ports.isEmpty()) {
            return isPortOpen(15237);
        }
        return isPortOpen(ports.iterator().next());
    }

    // 遍历 /proc 下的 cmdline 文件查找包含 nginx 的进程
    private boolean isNginxProcessAlive() {
        try {
            File[] dirs = new File("/proc").listFiles();
            if (dirs == null) return false;
            for (File d : dirs) {
                if (!d.isDirectory()) continue;
                String pid = d.getName();
                if (!pid.matches("\\d+")) continue;
                File cl = new File(d, "cmdline");
                if (!cl.canRead()) continue;
                byte[] b = new byte[4096];
                try (java.io.FileInputStream fs = new java.io.FileInputStream(cl)) {
                    int n = fs.read(b);
                    if (n > 0 && new String(b, 0, n).replace("\0", " ").contains("nginx")) {
                        return true;
                    }
                }
            }
        } catch (Exception ignored) {}
        return false;
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

    /** 根据自定义 cmdline 关键字杀进程（如 linker64 启动的 nginx） */
    private void killOrphan(String name, String cmdlineKeyword) {
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
                    if (n > 0 && new String(b, 0, n).replace("\0", " ").contains(cmdlineKeyword)) {
                        android.util.Log.i(TAG, "清理残留进程 " + pid + " (" + cmdlineKeyword + ")");
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
                // 格式: sl  local_address  rem_address  state  ...
                // local_address 列格式: 0100007F:3B85  -> 127.0.0.1:15237
                String[] parts = line.split("\s+");
                if (parts.length < 10) continue;
                String localAddr = parts[1]; // e.g. 0100007F:3B85
                if (!localAddr.endsWith(":" + hexPort)) continue;
                // state 01=ESTABLISHED, 0A=LISTEN
                String state = parts[3];
                if (!"0A".equals(state)) continue;
                // 找到 PID: inode 是第 9 列，然后去 /proc/*/fd/ 找对应的 socket
                // 更简单：直接用 /proc/*/fd/* 匹配 inode，但这太麻烦。
                // 直接遍历 /proc/*/cmdline 杀掉所有包含 "nginx" 的进程
                // 然后如果有更多信息，也可以从 /proc/net/tcp 的 inode 反查
                // 实际上最稳妥：遍历所有 /proc/*/fd/*，查找 socket:[inode] 匹配
                String inodeStr = parts[9];
                if (inodeStr == null || inodeStr.isEmpty()) continue;
                long targetInode;
                try { targetInode = Long.parseLong(inodeStr); } catch (Exception e) { continue; }
                // 遍历 /proc 找 fd 链接到该 socket 的进程
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
}
