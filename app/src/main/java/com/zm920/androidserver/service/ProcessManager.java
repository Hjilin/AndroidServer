package com.zm920.androidserver.service;

import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ProcessManager {

    private static final String TAG = "ProcessManager";
    private static final String PID_DIR = "pids";

    private static ProcessManager instance;

    private final File baseDir;
    private final File pidDir;
    private final Map<String, Process> processMap = new ConcurrentHashMap<>();
    private final Map<String, Integer> pidMemMap = new ConcurrentHashMap<>();
    private final Map<String, Integer> portMap = new ConcurrentHashMap<>();

    private static final Map<String, Integer> DEFAULT_PORTS = new ConcurrentHashMap<>();
    static {
        DEFAULT_PORTS.put("nginx", 8080);
        DEFAULT_PORTS.put("php-fpm", 9000);
        DEFAULT_PORTS.put("mysqld", 3306);
        DEFAULT_PORTS.put("redis", 6379);
        DEFAULT_PORTS.put("openlist", 5244);
    }

    private ProcessManager(File baseDir) {
        this.baseDir = baseDir;
        this.pidDir = new File(baseDir, PID_DIR);
        pidDir.mkdirs();
    }

    public static synchronized ProcessManager getInstance(File baseDir) {
        if (instance == null) instance = new ProcessManager(baseDir);
        return instance;
    }

    public static synchronized ProcessManager getInstance() {
        return instance;
    }

    public boolean start(String name, String[] cmd, String[] env, File workDir) {
        return start(name, cmd, env, workDir, 0);
    }

    public boolean start(String name, String[] cmd, String[] env, File workDir, int healthPort) {
        if (isAlive(name)) return false;

        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            if (env != null) {
                pb.environment().putAll(System.getenv());
                for (String e : env) {
                    if (e.contains("=")) {
                        String[] kv = e.split("=", 2);
                        pb.environment().put(kv[0], kv[1]);
                    }
                }
            }
            if (workDir != null) pb.directory(workDir);
            pb.redirectErrorStream(true);

            Process process = pb.start();
            int pid = getPid(process);

            processMap.put(name, process);
            if (pid > 0) pidMemMap.put(name, pid);
            if (healthPort > 0) portMap.put(name, healthPort);
            if (pid > 0) writePid(name, pid);
            if (healthPort > 0) writePort(name, healthPort);
            startLogReader(name, process);

            Log.i(TAG, name + " 已启动, pid=" + pid + ", port=" + healthPort);
            return true;
        } catch (IOException e) {
            Log.e(TAG, "启动 " + name + " 失败", e);
            // 把失败原因写入插件日志文件，便于用户导出定位
            try {
                java.io.File dir = new java.io.File(baseDir, "runtime_logs");
                dir.mkdirs();
                java.io.File f = new java.io.File(dir, name + ".log");
                java.io.StringWriter sw = new java.io.StringWriter();
                java.io.PrintWriter pw = new java.io.PrintWriter(sw);
                pw.println("=== 启动失败 @ " + new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(new java.util.Date()));
                pw.println("cmd: " + java.util.Arrays.toString(cmd));
                e.printStackTrace(pw);
                pw.println("=== End ===");
                try (java.io.FileOutputStream fos = new java.io.FileOutputStream(f, true)) {
                    fos.write(sw.toString().getBytes("UTF-8"));
                }
            } catch (Throwable ignored) {}
            return false;
        }
    }

    public void stop(String name) {
        Process process = processMap.remove(name);
        if (process != null) {
            try {
                process.destroyForcibly();
                process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS);
            } catch (Exception ignored) {}
        } else {
            int pid = readPid(name);
            if (pid > 0) {
                shellKill(pid);
            }
        }
        // 兜底：用 killall 按进程名杀（nginx/phpm 二进制通过 linker64 启动时 cmdline 包含关键词）
        killallByName(name);
        killByCmdline(name.contains("php") ? new String[]{"php-cgi", "php-fpm"} : new String[]{name});
        // 验证端口是否真正释放，否则重试
        int healthPort = portMap.getOrDefault(name, 0);
        if (healthPort <= 0) healthPort = readPort(name);
        if (healthPort <= 0) healthPort = DEFAULT_PORTS.getOrDefault(name, 0);
        if (healthPort > 0) {
            for (int i = 0; i < 10; i++) {
                if (canBind(healthPort)) break;
                try { Thread.sleep(500); } catch (Exception ignored) {}
                int pid = readPid(name);
                if (pid > 0) {
                    shellKill(pid);
                }
                killByPort(healthPort);
                killallByName(name);
                killByCmdline(name.contains("php") ? new String[]{"php-cgi", "php-fpm"} : new String[]{name});
            }
        }
        pidMemMap.remove(name);
        portMap.remove(name);
        deletePid(name);
        deletePort(name);
        Log.i(TAG, name + " 已停止");
    }

    public void stopAll() {
        // 复制 key 集合再遍历，避免 stop() 内 remove 导致并发修改异常
        java.util.ArrayList<String> names = new java.util.ArrayList<>(processMap.keySet());
        for (String name : names) stop(name);
    }

    public boolean isAlive(String name) {
        int port = portMap.getOrDefault(name, 0);
        if (port <= 0) port = readPort(name);
        if (port <= 0) port = DEFAULT_PORTS.getOrDefault(name, 0);

        if (port > 0) {
            if (isPortOpen(port)) {
                portMap.put(name, port);
                writePort(name, port);
                return true;
            }
        }

        Process process = processMap.get(name);
        if (process != null) {
            try {
                if (process.isAlive()) return true;
            } catch (Exception ignored) {}
            processMap.remove(name);
        }

        Integer pid = pidMemMap.get(name);
        if (pid == null || pid <= 0) pid = readPid(name);
        if (pid != null && pid > 0) {
            if (new File("/proc/" + pid + "/status").exists()) {
                pidMemMap.put(name, pid);
                return true;
            }
            deletePid(name);
        }

        return false;
    
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


    /** 用 killall 按进程名批量杀（兜底） */

    
    


    private void killallByName(String name) {
        try {
            String keyword = name;
            if (name.startsWith("php-fpm")) keyword = "php-cgi";
            runCmd(new String[]{"/system/bin/killall", "-9", keyword});
        } catch (Exception ignored) {}
    }

    /** 可靠地杀掉进程：先 killProcess，再 /system/bin/kill，再 killall */
    private void shellKill(int pid) {
        if (pid <= 0) return;
        // 方法1: android.os.Process.killProcess（JVM 内部发信号，无 exec 开销）
        try { android.os.Process.killProcess(pid); } catch (Exception ignored) {}
        try { Thread.sleep(50); } catch (Exception ignored) {}
        // 方法2: /system/bin/kill 绝对路径（兜底）
        runCmd(new String[]{"/system/bin/kill", "-9", String.valueOf(pid)});
        try { Thread.sleep(50); } catch (Exception ignored) {}
        // 方法3: shell kill 内置命令
        runCmd(new String[]{"/system/bin/sh", "-c", "kill -9 " + pid + " 2>/dev/null; exit 0"});
        Log.i(TAG, "shellKill: 已尝试杀死 PID " + pid);
    }

    // Kill processes by scanning /proc/PID/cmdline for keyword matches.
    // Writes a temp shell script to baseDir to avoid Java string escaping.
    public void killByCmdline(String... keywords) {
        try {
            java.io.File script = new java.io.File(baseDir, "kill_orphan.sh");
            StringBuilder sb = new StringBuilder();
            sb.append("#!/system/bin/sh\n");
            for (String kw : keywords) {
                sb.append("for p in /proc/[0-9]*; do\n");
                sb.append("  pid=$(basename $p)\n");
                sb.append("  if grep -q '").append(kw).append("' /proc/$pid/cmdline 2>/dev/null; then\n");
                sb.append("    /system/bin/kill -9 $pid 2>/dev/null\n");
                sb.append("    kill -9 $pid 2>/dev/null\n");
                sb.append("  fi\n");
                sb.append("done\n");
            }
            sb.append("exit 0\n");
            java.io.FileOutputStream fos = new java.io.FileOutputStream(script);
            fos.write(sb.toString().getBytes("UTF-8"));
            fos.close();
            script.setExecutable(true);
            runCmd(new String[]{"/system/bin/sh", script.getAbsolutePath()});
            script.delete();
        } catch (Exception e) {
            android.util.Log.w(TAG, "killByCmdline: " + e.getMessage());
        }
    }

    /** 可靠的 ProcessBuilder 执行，消费输出防止死锁 */
    private void runCmd(String[] cmd) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            byte[] buf = new byte[4096]; while (p.getInputStream().read(buf) != -1) {}
            p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception ignored) {}
    }

    /** 用 ServerSocket.bind() 实测端口是否可绑定 */
    private boolean canBind(int port) {
        try {
            java.net.ServerSocket ss = new java.net.ServerSocket();
            ss.setReuseAddress(true);
            ss.bind(new java.net.InetSocketAddress("0.0.0.0", port));
            ss.close();
            return true;
        } catch (Exception e) {
            return false;
        }
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

    public int getPid(String name) {
        Integer pid = pidMemMap.get(name);
        if (pid != null) return pid;
        return readPid(name);
    }

    public String[] getRunningProcesses() {
        return processMap.keySet().toArray(new String[0]);
    }

    public int processCount() {
        return processMap.size();
    }

    private int getPid(Process process) {
        try {
            Object result = process.getClass().getMethod("pid").invoke(process);
            if (result instanceof Long) return ((Long) result).intValue();
            if (result instanceof Integer) return (Integer) result;
        } catch (Exception ignored) {}
        try {
            Field f = process.getClass().getDeclaredField("pid");
            f.setAccessible(true);
            return f.getInt(process);
        } catch (Exception ignored) {}
        try {
            Field f = process.getClass().getSuperclass().getDeclaredField("pid");
            f.setAccessible(true);
            return f.getInt(process);
        } catch (Exception ignored) {}
        // fallback: 遍历整个类层级找 pid 字段
        Class<?> clazz = process.getClass();
        while (clazz != null) {
            for (Field f : clazz.getDeclaredFields()) {
                String fn = f.getName();
                if (fn.equals("pid") || fn.contains("pid")) {
                    Class<?> ft = f.getType();
                    if (ft == int.class || ft == long.class) {
                        try {
                            f.setAccessible(true);
                            return f.getInt(process);
                        } catch (Exception ignored) {}
                    }
                }
            }
            clazz = clazz.getSuperclass();
        }
        return -1;
    }

    private void writePid(String name, int pid) {
        try {
            File f = new File(pidDir, name + ".pid");
            try (OutputStream os = new FileOutputStream(f)) {
                os.write(String.valueOf(pid).getBytes());
            }
        } catch (IOException e) {
            Log.e(TAG, "写 PID 失败", e);
        }
    }

    private int readPid(String name) {
        File f = new File(pidDir, name + ".pid");
        if (!f.exists()) return -1;
        try (FileInputStream is = new FileInputStream(f)) {
            byte[] buf = new byte[16];
            int n = is.read(buf);
            return Integer.parseInt(new String(buf, 0, n).trim());
        } catch (Exception e) {
            return -1;
        }
    }

    private void deletePid(String name) {
        File f = new File(pidDir, name + ".pid");
        if (f.exists()) f.delete();
    }

    private void writePort(String name, int port) {
        try {
            File f = new File(pidDir, name + ".port");
            try (OutputStream os = new FileOutputStream(f)) {
                os.write(String.valueOf(port).getBytes());
            }
        } catch (IOException e) {
            Log.e(TAG, "写 PORT 失败", e);
        }
    }

    private int readPort(String name) {
        File f = new File(pidDir, name + ".port");
        if (!f.exists()) return -1;
        try (FileInputStream is = new FileInputStream(f)) {
            byte[] buf = new byte[16];
            int n = is.read(buf);
            return Integer.parseInt(new String(buf, 0, n).trim());
        } catch (Exception e) {
            return -1;
        }
    }

    private void deletePort(String name) {
        File f = new File(pidDir, name + ".port");
        if (f.exists()) f.delete();
    }

    private void startLogReader(String name, Process process) {
        File logFile = new File(baseDir, "runtime_logs/" + name + ".log");
        logFile.getParentFile().mkdirs();

        Thread reader = new Thread(() -> {
            try (InputStream is = process.getInputStream();
                 BufferedReader br = new BufferedReader(new InputStreamReader(is));
                 FileOutputStream fos = new FileOutputStream(logFile)) {
                fos.write(("=== " + name + " started ===\n").getBytes());
                String line;
                while ((line = br.readLine()) != null) {
                    fos.write((line + "\n").getBytes());
                    fos.flush();
                }
                fos.write(("=== " + name + " stdout closed ===\n").getBytes());
            } catch (IOException e) {
                Log.e(TAG, name + " 日志异常", e);
            }
            try {
                int exitCode = process.waitFor();
                try (FileOutputStream fos = new FileOutputStream(logFile, true)) {
                    fos.write(("=== " + name + " exited with code " + exitCode + " ===\n").getBytes());
                }
                Log.w(TAG, name + " 退出, code=" + exitCode);
            } catch (Exception ignored) {}
            processMap.remove(name);
            pidMemMap.remove(name);
        }, "log-reader-" + name);
        reader.setDaemon(true);
        reader.start();
    }
}
