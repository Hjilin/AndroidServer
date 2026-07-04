package com.zm920.androidserver.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 扫描 data_dir 下的网站目录，自动注册到 SharedPreferences。
 * 用于卸载重装后恢复网站列表。
 */
public class SiteScanner {
    private static final String TAG = "SiteScanner";
    private static final String KEY_SITES = "sites";
    private static final String KEY_DATA_DIR = "data_dir";
    private static final AtomicBoolean SCAN_RUNNING = new AtomicBoolean(false);
    private static final String META_FILE = ".site_meta";
    private static final int DEFAULT_PORT_START = 8080;
    private static final String DATA_DIR_MARKER_REL = "/wwwroot/www/.androidserver_data_dir";

    /** 基础设施目录名（不视为网站） */
    private static final String[] INFRA_DIRS = {"备份目录", "log"};

    /**
     * 扫描 data_dir 并注册新网站到 SharedPreferences。
     * 幂等操作：已在 sites 中的网站不会被覆盖。
     */
    public static void scanAndRegister(Context context, SharedPreferences prefs) {
        if (!SCAN_RUNNING.compareAndSet(false, true)) {
            Log.i(TAG, "跳过重复扫描");
            return;
        }
        try {
            String dataDir = resolveDataDir(context, prefs);
            Log.i(TAG, "扫描网站目录: " + dataDir);
            File root = new File(dataDir);
            if (!root.exists() || !root.isDirectory()) {
                return;
            }

            File[] children = root.listFiles();
            if (children == null) return;

            Set<String> rebuilt = new HashSet<>();
            Set<Integer> usedPorts = new HashSet<>();
            for (File child : children) {
                if (!child.isDirectory()) continue;
                String name = child.getName();
                if (name.startsWith(".")) continue;
                if (isInfraDir(name)) continue;

                int port = resolvePort(child, usedPorts);
                usedPorts.add(port);
                String rewrite = readRewrite(child);
                String entry = name + "|" + port + "|" + "" + "|" + rewrite.replace("|", "");
                rebuilt.add(entry);
                Log.i(TAG, "恢复站点: " + name + " 端口=" + port + " meta=" + new File(child, META_FILE).getAbsolutePath());
            }

            Set<String> current = new HashSet<>(prefs.getStringSet(KEY_SITES, new HashSet<>()));
            // 方案 A：合并而非覆盖，保留 current 中已有的运行目录（root）
            // - current 中已有的站点：保留其 root，只用 rebuilt 刷新 port/rewrite
            // - current 中没有的站点（新创建/重装恢复）：直接用 rebuilt
            // - rebuilt 中没有但 current 中有的站点：保留（防止误删）
            Set<String> merged = new HashSet<>();
            for (String newEntry : rebuilt) {
                String[] newParts = newEntry.split("\\|", -1);
                if (newParts.length < 1) continue;
                String name = newParts[0];
                // 在 current 中查找同名站点
                String existingRoot = null;
                for (String cur : current) {
                    String[] curParts = cur.split("\\|", -1);
                    if (curParts.length > 0 && curParts[0].equals(name)) {
                        existingRoot = curParts.length >= 3 ? curParts[2] : "";
                        break;
                    }
                }
                if (existingRoot != null) {
                    // 保留 current 中的运行目录
                    String mergedEntry = name + "|" + newParts[1] + "|" + existingRoot + "|" + newParts[3];
                    merged.add(mergedEntry);
                    Log.i(TAG, "保留运行目录: " + name + " root=" + existingRoot);
                } else {
                    // 新站点（current 中没有），直接添加 rebuilt
                    merged.add(newEntry);
                }
            }
            // 保留 current 中不在 rebuilt 里的站点（防止误删）
            for (String cur : current) {
                String[] curParts = cur.split("\\|", -1);
                if (curParts.length < 1) continue;
                String name = curParts[0];
                boolean found = false;
                for (String newEntry : rebuilt) {
                    String[] newParts = newEntry.split("\\|", -1);
                    if (newParts.length > 0 && newParts[0].equals(name)) {
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    merged.add(cur);
                    Log.i(TAG, "保留 current 中独有站点: " + name);
                }
            }
            if (!current.equals(merged)) {
                prefs.edit().putStringSet(KEY_SITES, merged).apply();
                Log.i(TAG, "sites 已合并, 数量=" + merged.size());
            }
        } catch (Exception e) {
            Log.e(TAG, "扫描失败", e);
        } finally {
            SCAN_RUNNING.set(false);
        }
    }

    /** 写入网站元数据文件 */
    public static void writeMeta(File siteDir, int port, String rewrite) {
        try {
            File meta = new File(siteDir, META_FILE);
            JSONObject obj = new JSONObject();
            obj.put("port", port);
            obj.put("rewrite", rewrite == null ? "" : rewrite);
            java.io.FileWriter w = new java.io.FileWriter(meta);
            w.write(obj.toString());
            w.close();
        } catch (Exception e) {
            Log.e(TAG, "写入 .site_meta 失败", e);
        }
    }

    /** 删除网站元数据文件 */
    public static void deleteMeta(File siteDir) {
        try {
            File meta = new File(siteDir, META_FILE);
            if (meta.exists()) meta.delete();
        } catch (Exception ignored) {}
    }

    /**
     * 解析网站目录：优先 SharedPreferences；若重装后丢失，则从外部存储 marker 恢复。
     */
    public static String resolveDataDir(Context context, SharedPreferences prefs) {
        String prefDir = prefs.getString(KEY_DATA_DIR, null);
        if (prefDir != null && !prefDir.trim().isEmpty()) {
            ensureDataDirMarker(prefDir);
            return prefDir;
        }

        String restored = findPersistedDataDir();
        if (restored != null && !restored.isEmpty()) {
            prefs.edit().putString(KEY_DATA_DIR, restored).apply();
            return restored;
        }

        String fallback = StoragePaths.getDefaultWwwRoot();
        ensureDataDirMarker(fallback);
        prefs.edit().putString(KEY_DATA_DIR, fallback).apply();
        return fallback;
    }

    /** 在网站目录写入 marker，供卸载重装后恢复 data_dir */
    public static void ensureDataDirMarker(String dataDir) {
        try {
            File root = new File(dataDir);
            if (!root.exists()) root.mkdirs();
            File marker = new File(root, ".androidserver_data_dir");
            java.io.FileWriter w = new java.io.FileWriter(marker, false);
            w.write(dataDir);
            w.close();
        } catch (Exception e) {
            Log.w(TAG, "写入 data_dir marker 失败", e);
        }
    }

    /** 从外部存储查找已持久化的 data_dir marker */
    private static String findPersistedDataDir() {
        try {
            File externalRoot = new File(StoragePaths.getExternalRoot());
            File[] candidates = externalRoot.listFiles();
            if (candidates == null) return null;

            for (File child : candidates) {
                File marker = new File(child, DATA_DIR_MARKER_REL);
                String restored = readMarker(marker);
                if (restored != null) return restored;
            }

            File defaultMarker = new File(StoragePaths.getDefaultWwwRoot(), ".androidserver_data_dir");
            return readMarker(defaultMarker);
        } catch (Exception e) {
            Log.w(TAG, "查找 data_dir marker 失败", e);
            return null;
        }
    }

    private static String readMarker(File marker) {
        try {
            if (!marker.exists() || !marker.isFile()) return null;
            String content = new String(java.nio.file.Files.readAllBytes(marker.toPath())).trim();
            if (content.isEmpty()) return null;
            File dir = new File(content);
            if (dir.exists() && dir.isDirectory()) return content;
        } catch (Exception ignored) {}
        return null;
    }

    private static boolean isInfraDir(String name) {
        for (String infra : INFRA_DIRS) {
            if (infra.equals(name)) return true;
        }
        return false;
    }

    /** 解析端口：优先读 .site_meta，否则从 DEFAULT_PORT_START 递增 */
    private static int resolvePort(File siteDir, Set<Integer> usedPorts) {
        int port = readPortFromMeta(siteDir);
        if (port > 0 && !usedPorts.contains(port)) return port;
        int p = DEFAULT_PORT_START;
        while (usedPorts.contains(p)) p++;
        return p;
    }

    private static int readPortFromMeta(File siteDir) {
        try {
            File meta = new File(siteDir, META_FILE);
            if (!meta.exists()) return -1;
            String content = new String(java.nio.file.Files.readAllBytes(meta.toPath()));
            JSONObject obj = new JSONObject(content);
            return obj.optInt("port", -1);
        } catch (Exception e) {
            return -1;
        }
    }

    private static String readRewrite(File siteDir) {
        try {
            File meta = new File(siteDir, META_FILE);
            if (!meta.exists()) return "";
            String content = new String(java.nio.file.Files.readAllBytes(meta.toPath()));
            JSONObject obj = new JSONObject(content);
            return obj.optString("rewrite", "");
        } catch (Exception e) {
            return "";
        }
    }
}
