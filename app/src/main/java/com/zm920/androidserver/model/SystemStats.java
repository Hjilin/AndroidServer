package com.zm920.androidserver.model;

import android.app.Activity;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;

public class SystemStats {

    private long totalMem;
    private long availableMem;
    private long totalStorage;
    private long availableStorage;

    // CPU 负载
    private double load1m;
    private double load5m;
    private double load15m;
    private int cpuCores;

    // 网络流量（byte）
    private long rxBytes;   // 总接收
    private long txBytes;   // 总发送

    // 缓存：避免频繁采集（系统状态变化慢，2秒缓存足够）
    private long lastCollectTime = 0;
    private static final long COLLECT_INTERVAL_MS = 2000;

    public SystemStats() {
        collect();
    }

    /**
     * 采集内存和存储数据。2秒内重复调用直接返回缓存值。
     */
    public void collect() {
        long now = System.currentTimeMillis();
        if (now - lastCollectTime < COLLECT_INTERVAL_MS) return;
        lastCollectTime = now;
        // 内存信息（从 /proc/meminfo 读取）
        totalMem = 0;
        availableMem = 0;
        try (BufferedReader br = new BufferedReader(new FileReader("/proc/meminfo"))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.startsWith("MemTotal:")) {
                    totalMem = parseMemLine(line);
                } else if (line.startsWith("MemAvailable:")) {
                    availableMem = parseMemLine(line);
                }
            }
        } catch (Exception ignored) {}

        // 存储信息（内部存储）
        try {
            File path = Environment.getDataDirectory();
            StatFs stat = new StatFs(path.getPath());
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
                totalStorage = stat.getTotalBytes();
                availableStorage = stat.getAvailableBytes();
            } else {
                totalStorage = (long) stat.getBlockCount() * stat.getBlockSize();
                availableStorage = (long) stat.getAvailableBlocks() * stat.getBlockSize();
            }
        } catch (Exception ignored) {}

        // CPU 负载：优先读 /proc/loadavg（无进程开销），失败时回退到 uptime 命令
        load1m = 0; load5m = 0; load15m = 0; cpuCores = 1;
        boolean loadOk = false;
        try (BufferedReader br = new BufferedReader(new FileReader("/proc/loadavg"))) {
            String line = br.readLine();
            if (line != null) {
                // 格式: "5.26 5.53 5.76 1/567 12345"
                String[] parts = line.split(" ");
                if (parts.length >= 3) {
                    load1m = Double.parseDouble(parts[0].trim());
                    load5m = Double.parseDouble(parts[1].trim());
                    load15m = Double.parseDouble(parts[2].trim());
                    loadOk = true;
                }
            }
        } catch (Exception ignored) {}
        if (!loadOk) {
            try {
                Process p = Runtime.getRuntime().exec("uptime");
                try (BufferedReader br = new BufferedReader(new java.io.InputStreamReader(p.getInputStream(), "UTF-8"))) {
                    String line = br.readLine();
                    if (line != null && line.contains("load average:")) {
                        String loadPart = line.substring(line.indexOf("load average:") + 14).trim();
                        String[] parts = loadPart.split(",");
                        if (parts.length >= 3) {
                            load1m = Double.parseDouble(parts[0].trim());
                            load5m = Double.parseDouble(parts[1].trim());
                            load15m = Double.parseDouble(parts[2].trim());
                        }
                    }
                }
                p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS);
            } catch (Exception ignored) {}
        }
        try { cpuCores = Runtime.getRuntime().availableProcessors(); if (cpuCores < 1) cpuCores = 1; } catch (Exception ignored) {}

        // 网络流量（Android TrafficStats API，累计值）
        rxBytes = android.net.TrafficStats.getTotalRxBytes();
        txBytes = android.net.TrafficStats.getTotalTxBytes();
        // TrafficStats 可能返回 -1（权限不足时）
        if (rxBytes < 0) rxBytes = 0;
        if (txBytes < 0) txBytes = 0;
    }

    private long parseMemLine(String line) {
        String[] parts = line.split("\\s+");
        if (parts.length >= 2) {
            try {
                long value = Long.parseLong(parts[1]);
                // 单位是 kB，转为字节
                return value * 1024;
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        return 0;
    }

    // ---- 内存 ----

    public long getTotalMem() {
        return totalMem;
    }

    public long getAvailableMem() {
        return availableMem;
    }

    public long getUsedMem() {
        return totalMem - availableMem;
    }

    public int getMemPercent() {
        if (totalMem <= 0) return 0;
        return (int) ((totalMem - availableMem) * 100 / totalMem);
    }

    public String getTotalMemStr() {
        return formatBytes(totalMem);
    }

    public String getAvailableMemStr() {
        return formatBytes(availableMem);
    }

    public String getUsedMemStr() {
        return formatBytes(getUsedMem());
    }

    // ---- 存储 ----

    public long getTotalStorage() {
        return totalStorage;
    }

    public long getAvailableStorage() {
        return availableStorage;
    }

    public long getUsedStorage() {
        return totalStorage - availableStorage;
    }

    public int getStoragePercent() {
        if (totalStorage <= 0) return 0;
        return (int) ((totalStorage - availableStorage) * 100 / totalStorage);
    }

    public String getTotalStorageStr() {
        return formatBytes(totalStorage);
    }

    public String getAvailableStorageStr() {
        return formatBytes(availableStorage);
    }

    public String getUsedStorageStr() {
        return formatBytes(getUsedStorage());
    }

    // ---- CPU 负载 ----

    public double getLoad1m() { return load1m; }
    public double getLoad5m() { return load5m; }
    public double getLoad15m() { return load15m; }
    public int getCpuCores() { return cpuCores; }
    public String getLoadStr() {
        return String.format("%.2f / %.2f / %.2f", load1m, load5m, load15m);
    }
    public int getLoadPercent() {
        if (cpuCores <= 0) return 0;
        return (int) Math.min(load1m * 100 / cpuCores, 100);
    }

    // ---- 网络流量 ----

    public long getRxBytes() { return rxBytes; }
    public long getTxBytes() { return txBytes; }
    public String getRxStr() { return formatBytes(rxBytes); }
    public String getTxStr() { return formatBytes(txBytes); }

    // ---- 工具 ----

    private String formatBytes(long bytes) {
        if (bytes <= 0) return "0B";
        String[] units = {"B", "KB", "MB", "GB", "TB"};
        int unitIndex = 0;
        double value = bytes;
        while (value >= 1024 && unitIndex < units.length - 1) {
            value /= 1024;
            unitIndex++;
        }
        return String.format("%.1f%s", value, units[unitIndex]);
    }
}
