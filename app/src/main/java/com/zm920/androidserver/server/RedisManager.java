package com.zm920.androidserver.server;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.zm920.androidserver.config.ConfigGenerator;
import com.zm920.androidserver.service.ProcessManager;

import java.io.File;
import java.net.InetSocketAddress;
import java.net.Socket;

public class RedisManager {

    private static final String TAG = "RedisManager";

    private final Context context;
    private final ProcessManager processManager;
    private final ConfigGenerator configGenerator;
    private final SharedPreferences prefs;
    private final BinaryDeployer deployer;
    private final StartupLogger startupLogger;

    public RedisManager(Context context, ProcessManager pm,
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
        return prefs.contains("active_redis");
    }

    public boolean start() throws Exception {
        int redisPort = prefs.getInt("redis_port", 6379);
        for (int attempt = 0; attempt < 6; attempt++) {
            killOrphan("redis-server");
            try { Thread.sleep(500); } catch (Exception ignored) {}
            if (!isPortOpen(redisPort)) break;
            Log.w(TAG, "端口 " + redisPort + " 仍被占用，第 " + (attempt+1) + " 次清理");
        }
        if (isPortOpen(redisPort)) {
            Log.i(TAG, "Redis 已在运行 (端口 " + redisPort + " 已占用)");
            return true;
        }

        try {
            deployer.deployAll();
            String redisBin = deployer.getBinPath("redis-server");
            if (redisBin == null) return false;

            String dataDir = context.getFilesDir().getAbsolutePath() + "/server";
            File configDir = new File(dataDir, "config");
            configDir.mkdirs();

            configGenerator.generateRedisConf();

            String[] env = deployer.buildEnv();
            String[] cmd = deployer.buildExecCmd(redisBin, configDir + "/redis.conf");

            boolean ok = processManager.start("redis", cmd, env, configDir, redisPort);
            if (ok) {
                for (int i = 0; i < 10; i++) {
                    Thread.sleep(300);
                    if (processManager.isAlive("redis")) return true;
                }
            }

            startupLogger.logFailure("redis", redisBin, cmd, env, configDir,
                new Exception("Redis 启动后存活检查失败"));
            return false;
        } catch (Exception e) {
            startupLogger.logFailure("redis", deployer.getBinPath("redis-server"), null, null, null, e);
            return false;
        }
    }

    public void stop() {
        killOrphan("redis-server");
        processManager.stop("redis");
    }

    public boolean isRunning() {
        if (processManager.isAlive("redis")) return true;
        int port = prefs.getInt("redis_port", 6379);
        return isPortOpen(port);
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
                        Runtime.getRuntime().exec(new String[]{"/system/bin/kill", "-9", pid}).waitFor();
                    }
                } catch (Exception ignored) {}
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
