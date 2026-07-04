package com.zm920.androidserver.mefrp;

import android.content.Context;

import java.io.File;

/**
 * ME Frp 全局单例。
 */
public class MefrpManager {

    private static final String TAG = "MefrpManager";
    private static MefrpManager instance;
    private final Context appContext;
    private final MefrpRunner runner;

    private MefrpManager(Context ctx) {
        this.appContext = ctx.getApplicationContext();
        this.runner = new MefrpRunner(this.appContext);
    }

    public static synchronized MefrpManager get(Context ctx) {
        if (instance == null) instance = new MefrpManager(ctx);
        return instance;
    }

    public static MefrpManager get() {
        if (instance == null) throw new IllegalStateException("MefrpManager not initialized");
        return instance;
    }

    public static boolean isInitialized() { return instance != null; }

    public boolean start() { return runner.start(); }
    public void stop() { runner.stop(); }
    public void setExitCallback(MefrpRunner.OnExitCallback cb) { runner.setExitCallback(cb); }
    public boolean isRunning() { return runner.isRunning(); }
    public String getPublicUrl() { return runner.getPublicUrl(); }
    public String getLastError() { return runner.getLastError(); }
    public File getLogFile() { return runner.getLogFile(); }
    public File getBinFile() { return runner.getBinFile(); }
    public boolean isBinReady() { return runner.isBinReady(); }
    public java.io.File getDeployDir() { return runner.getDeployDir(); }
    public String selfCheck() { return runner.selfCheck(); }
    public String readTailLog(int maxBytes) { return runner.readTailLog(maxBytes); }
}
