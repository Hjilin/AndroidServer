package com.zm920.androidserver.cpolar;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.io.RandomAccessFile;

/**
 * cpolar 全局单例。
 * <p>对外提供：get() / start() / stop() / status() / 读取日志。
 */
public class CpolarManager {

    private static final String TAG = "CpolarManager";
    private static CpolarManager instance;
    private final Context appContext;
    private final CpolarRunner runner;

    private CpolarManager(Context ctx) {
        this.appContext = ctx.getApplicationContext();
        this.runner = new CpolarRunner(this.appContext);
        // 首次安装写入默认 token
        CpolarConfig.ensureDefaultToken(this.appContext);
    }

    public static synchronized CpolarManager get(Context ctx) {
        if (instance == null) instance = new CpolarManager(ctx);
        return instance;
    }

    public static CpolarManager get() {
        if (instance == null) throw new IllegalStateException("CpolarManager not initialized");
        return instance;
    }

    public static boolean isInitialized() { return instance != null; }

    public boolean start() { return runner.start(); }
    public void stop() { runner.stop(); }
    public boolean isRunning() { return runner.isRunning(); }
    public String getPublicUrl() { return runner.getPublicUrl(); }
    public String getLastError() { return runner.getLastError(); }
    public File getLogFile() { return runner.getLogFile(); }
    public File getBinFile() { return runner.getBinFile(); }

    /** 读最近 N KB 日志 */
    public String selfCheck() { return runner.selfCheck(); }

    public String readTailLog(int maxBytes) {
        File f = getLogFile();
        if (f == null || !f.exists()) return "";
        try {
            long size = f.length();
            int read = (int) Math.min(size, maxBytes);
            byte[] buf = new byte[read];
            try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
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
