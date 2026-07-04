package com.zm920.androidserver.server;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Map;

/**
 * 进程启动失败日志记录器
 * 将详细的启动失败原因写入文件
 */
public class StartupLogger {

    private static final String TAG = "StartupLogger";
    private static final String LOG_DIR = "startup_logs";

    private final Context context;
    private final File logDir;

    public StartupLogger(Context context) {
        this.context = context;
        this.logDir = new File(context.getFilesDir(), LOG_DIR);
        if (!this.logDir.exists()) this.logDir.mkdirs();
    }

    /** 记录启动失败 */
    public void logFailure(String serviceName, String binPath,
                           String[] cmd, String[] env, File workDir,
                           Exception error) {
        String timestamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)
                .format(new Date());
        File logFile = new File(logDir, serviceName + "_" + timestamp + ".log");

        StringBuilder sb = new StringBuilder();
        sb.append("=== ").append(serviceName).append(" 启动失败报告 ===\n");
        sb.append("时间: ").append(timestamp).append("\n");
        if (binPath != null) {
            File binFile = new File(binPath);
            sb.append("二进制: ").append(binPath).append("\n");
            sb.append("存在: ").append(binFile.exists()).append("\n");
            sb.append("可执行: ").append(binFile.canExecute()).append("\n");
            sb.append("大小: ").append(binFile.length()).append("\n");
        }

        sb.append("\n--- 命令行 ---\n");
        if (cmd != null) {
            for (int i = 0; i < cmd.length; i++) sb.append("  [").append(i).append("] ").append(cmd[i]).append("\n");
        }

        sb.append("\n--- 环境变量 ---\n");
        if (env != null) {
            for (String e : env) sb.append("  ").append(e).append("\n");
        }

        // 添加系统环境变量
        sb.append("\n--- 系统环境变量 (关键) ---\n");
        Map<String, String> sysEnv = System.getenv();
        for (String key : new String[]{"LD_LIBRARY_PATH", "PATH", "TMPDIR", "HOME", "TERM"}) {
            String val = sysEnv.get(key);
            sb.append("  ").append(key).append("=").append(val != null ? val : "(未设置)").append("\n");
        }

        sb.append("\n--- 工作目录 ---\n");
        sb.append("  路径: ").append(workDir != null ? workDir.getAbsolutePath() : "null").append("\n");
        sb.append("  存在: ").append(workDir != null ? workDir.exists() : "null").append("\n");

        // 检查两个可能的位置
        String[] possibleLibPaths = {
            context.getFilesDir() + "/lib",
            context.getDir("lib", Context.MODE_PRIVATE).getAbsolutePath()
        };
        String[] possibleBinPaths = {
            context.getFilesDir() + "/bin",
            context.getDir("bin", Context.MODE_PRIVATE).getAbsolutePath()
        };

        sb.append("\n--- 二进制目录 ---\n");
        for (String p : possibleBinPaths) {
            File d = new File(p);
            sb.append("  路径: ").append(p).append("\n");
            sb.append("  存在: ").append(d.exists()).append("\n");
            sb.append("  可执行 (挂载点): ").append(isExecMount(d)).append("\n");
            if (d.exists()) {
                File[] files = d.listFiles();
                if (files != null) for (File f : files) {
                    sb.append("    ").append(f.getName())
                      .append(" (").append(f.length()).append(")")
                      .append(" 可执行=").append(f.canExecute())
                      .append("\n");
                }
            }
        }

        sb.append("\n--- 库目录 ---\n");
        for (String p : possibleLibPaths) {
            File d = new File(p);
            sb.append("  路径: ").append(p).append("\n");
            sb.append("  存在: ").append(d.exists()).append("\n");
            if (d.exists()) {
                File[] files = d.listFiles();
                sb.append("  文件数: ").append(files != null ? files.length : "null").append("\n");
                if (files != null) for (File f : files) {
                    sb.append("    ").append(f.getName())
                      .append(" (").append(f.length()).append(")")
                      .append("\n");
                }
            }
        }

        sb.append("\n--- 异常 ---\n");
        if (error != null) {
            sb.append("  类型: ").append(error.getClass().getName()).append("\n");
            sb.append("  消息: ").append(error.getMessage()).append("\n");
            for (StackTraceElement e : error.getStackTrace()) {
                sb.append("    at ").append(e.toString()).append("\n");
            }
        }

        sb.append("\n--- 进程退出后捕获输出 ---\n");
        sb.append(captureProcessOutput(cmd, env, workDir));

        try (FileOutputStream fos = new FileOutputStream(logFile)) {
            fos.write(sb.toString().getBytes("UTF-8"));
            fos.flush();
            Log.i(TAG, "启动失败日志已写入: " + logFile.getAbsolutePath());
        } catch (Exception e) {
            Log.e(TAG, "写入日志失败", e);
        }
    }


    private void appendIfExists(StringBuilder sb, File workDir, String name) {
        try {
            if (workDir == null) return;
            File file = new File(workDir, name);
            sb.append("  文件: ").append(file.getAbsolutePath()).append("\n");
            if (!file.exists()) {
                sb.append("  (不存在)\n");
                return;
            }
            int count = 0;
            try (java.io.BufferedReader br = new java.io.BufferedReader(
                    new java.io.InputStreamReader(new java.io.FileInputStream(file), "UTF-8"))) {
                String line;
                while ((line = br.readLine()) != null && count < 400) {
                    sb.append("    ").append(line).append("\n");
                    count++;
                }
            }
            if (count >= 400) sb.append("    ...(已截断)\n");
        } catch (Exception e) {
            sb.append("  读取失败: ").append(e.getMessage()).append("\n");
        }
    }

    /** 检查目录所在挂载点是否可执行 */
    private boolean isExecMount(File dir) {
        BufferedReader br = null;
        Process proc = null;
        try {
            // 用 ProcessBuilder + redirectErrorStream(true) 避免 stderr 缓冲填满死锁
            ProcessBuilder pb = new ProcessBuilder("mount");
            pb.redirectErrorStream(true);
            proc = pb.start();
            br = new BufferedReader(new InputStreamReader(proc.getInputStream()));
            String line;
            String dirPath = dir.getAbsolutePath();
            int slashIdx = dirPath.indexOf("/", 5);
            String prefix = slashIdx > 0 ? dirPath.substring(0, slashIdx) : dirPath;
            while ((line = br.readLine()) != null) {
                if (line.contains(prefix)) {
                    return !line.contains("noexec");
                }
            }
            return true; // 未知，假设可执行
        } catch (Exception e) {
            return true;
        } finally {
            closeQuietly(br);
            if (proc != null && proc.isAlive()) {
                try { proc.destroyForcibly(); } catch (Exception ignored) {}
            }
        }
    }


    private static void closeQuietly(java.io.Closeable c) {
        if (c == null) return;
        try { c.close(); } catch (Exception ignored) {}
    }

    /** 尝试捕获进程的标准输出/错误 */
    private String captureProcessOutput(String[] cmd, String[] env, File workDir) {
        if (cmd == null || cmd.length == 0) return "  (无命令)\n";
        try {
            String[] logCmd = cmd;  // 所有组件命令已由调用方组装为 linker + 版本目录二进制
            ProcessBuilder pb = new ProcessBuilder(logCmd);
            if (env != null) {
                for (String e : env) {
                    if (e.contains("=")) {
                        String[] parts = e.split("=", 2);
                        pb.environment().put(parts[0], parts[1]);
                    }
                }
            }
            if (workDir != null) pb.directory(workDir);
            pb.redirectErrorStream(true);
            Process p = pb.start();

            StringBuilder out = new StringBuilder();
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            int lineCount = 0;
            while ((line = br.readLine()) != null && lineCount < 100) {
                out.append("  ").append(line).append("\n");
                lineCount++;
            }
            br.close();

            boolean exited = p.waitFor(8, java.util.concurrent.TimeUnit.SECONDS);
            int exitCode = -1;
            if (exited) {
                try { exitCode = p.exitValue(); } catch (Exception ignored) {}
            } else {
                try { p.destroyForcibly(); } catch (Exception ignored) {}
            }
            out.append("  退出码: ").append(exited ? String.valueOf(exitCode) : "超时").append("\n");

            if (exitCode == 0) {
                out.append("  ⚠️ 进程实际启动了，但管理器认为失败\n");
            }

            return out.toString();
        } catch (Exception e) {
            return "  捕获输出失败: " + e.getMessage() + "\n";
        }
    }

    /** 获取所有日志文件 */
    public File[] getLogFiles() {
        File[] files = logDir.listFiles();
        return files != null ? files : new File[0];
    }

    /** 同 ProcessManager 的 linker 包装逻辑 */

}
