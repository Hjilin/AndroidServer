package com.zm920.androidserver.plugin;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.zm920.androidserver.service.ProcessManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 插件引擎：管理可插拔服务的生命周期。
 * 插件 = 一个目录（bin/plugins/<id>/），内含 plugin.json 元数据 + 运行所需文件。
 * 提供：扫描已装插件、解析元数据、导入(zip)、卸载、启停、日志路径、端口管理。
 */
public class PluginManager {
    private static final String TAG = "PluginManager";
    private static final String PLUGINS_DIR = "plugins";
    private static final String META_FILE = "plugin.json";
    private static final String LOG_DIR = "runtime_logs";
    private static final String PREF_NAME = "plugin_state";
    private static final String PREF_ENABLED = "enabled_plugins";

    private static volatile PluginManager instance;
    private final Context appContext;
    private final File baseDir;      // 基础设施根目录（含 bin/）
    private final File pluginsDir;   // bin/plugins/
    private final ConcurrentMap<String, JSONObject> cache = new ConcurrentHashMap<>();

    public static synchronized PluginManager getInstance(Context ctx) {
        if (instance == null) {
            instance = new PluginManager(ctx.getApplicationContext());
        }
        return instance;
    }

    private PluginManager(Context appContext) {
        this.appContext = appContext;
        this.baseDir = new File(appContext.getFilesDir(), "bin");
        this.pluginsDir = new File(baseDir, PLUGINS_DIR);
        this.pluginsDir.mkdirs();
    }

    // ============ 扫描已安装插件 ============
    public List<String> listInstalledIds() {
        List<String> ids = new ArrayList<>();
        File[] dirs = pluginsDir.listFiles();
        if (dirs != null) {
            for (File d : dirs) {
                if (d.isDirectory() && new File(d, META_FILE).exists()) {
                    ids.add(d.getName());
                }
            }
        }
        Collections.sort(ids);
        return ids;
    }

    /** 返回已安装插件元数据列表（含内置内置插件，不含未安装） */
    public List<JSONObject> listInstalled() {
        List<JSONObject> out = new ArrayList<>();
        for (String id : listInstalledIds()) {
            JSONObject meta = loadMeta(id);
            if (meta != null) out.add(meta);
        }
        return out;
    }

    /** 加载插件目录下的 plugin.json */
    public JSONObject loadMeta(String id) {
        JSONObject cached = cache.get(id);
        if (cached != null) return cached;
        File metaFile = new File(pluginsDir, id + "/" + META_FILE);
        if (!metaFile.exists()) return null;
        try {
            byte[] b = new byte[(int) metaFile.length()];
            try (FileInputStream in = new FileInputStream(metaFile)) {
                int off = 0;
                while (off < b.length) {
                    int r = in.read(b, off, b.length - off);
                    if (r <= 0) break;
                    off += r;
                }
            }
            JSONObject obj = new JSONObject(new String(b, StandardCharsets.UTF_8));
            cache.put(id, obj);
            return obj;
        } catch (Exception e) {
            Log.e(TAG, "loadMeta " + id, e);
            return null;
        }
    }

    public File getPluginDir(String id) {
        return new File(pluginsDir, id);
    }

    public boolean isInstalled(String id) {
        return new File(pluginsDir, id + "/" + META_FILE).exists();
    }

    // ============ 导入/卸载 ============
    /**
     * 从 zip 文件导入插件。zip 需在根目录包含 plugin.json，其余文件随插件解压。
     * 安全：拒绝 zip-slip（路径穿越）。
     */
    public boolean importFromZip(File zipFile, StringBuilder err) {
        String id = null;
        File target = null;
        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(zipFile))) {
            ZipEntry entry;
            // 第一遍先找 plugin.json 确认 id
            while ((entry = zis.getNextEntry()) != null) {
                String name = entry.getName();
                if (name.equals(META_FILE) || name.endsWith("/" + META_FILE)) {
                    byte[] b = readEntry(zis);
                    JSONObject meta = new JSONObject(new String(b, StandardCharsets.UTF_8));
                    id = meta.optString("id", null);
                    break;
                }
            }
            if (id == null || id.isEmpty()) {
                err.append("无效插件包：缺少 plugin.json 或未声明 id");
                return false;
            }
            if (!id.matches("[a-zA-Z0-9_\\-]{1,64}")) {
                err.append("非法插件 id");
                return false;
            }
            target = new File(pluginsDir, id);
            if (target.exists()) {
                err.append("插件已存在: ").append(id);
                return false;
            }
            target.mkdirs();

            // 第二遍解压全部
            try (ZipInputStream zis2 = new ZipInputStream(new FileInputStream(zipFile))) {
                ZipEntry e2;
                while ((e2 = zis2.getNextEntry()) != null) {
                    String name = e2.getName();
                    // 只取根目录文件，忽略多余的目录前缀
                    int slash = name.indexOf('/');
                    String rel = slash >= 0 ? name.substring(slash + 1) : name;
                    if (rel.isEmpty()) continue;
                    File outFile = new File(target, rel);
                    // 防 zip-slip
                    if (!outFile.getCanonicalPath().startsWith(target.getCanonicalPath() + File.separator)) {
                        deleteRecursive(target);
                        err.append("非法路径: ").append(name);
                        return false;
                    }
                    if (e2.isDirectory()) {
                        outFile.mkdirs();
                    } else {
                        outFile.getParentFile().mkdirs();
                        try (FileOutputStream fos = new FileOutputStream(outFile)) {
                            byte[] buf = new byte[8192];
                            int n;
                            while ((n = zis2.read(buf)) > 0) fos.write(buf, 0, n);
                        }
                    }
                    zis2.closeEntry();
                }
            }
            // 校验最终 plugin.json
            JSONObject finalMeta = loadMeta(id);
            if (finalMeta == null) {
                deleteRecursive(target);
                err.append("解压后 plugin.json 读取失败");
                return false;
            }
            cache.put(id, finalMeta);
            // 对可执行文件加权限（cmd 里带 bin/ 前缀的）
            applyExecPermission(target, finalMeta);
            return true;
        } catch (Exception e) {
            if (target != null) deleteRecursive(target);
            Log.e(TAG, "importFromZip", e);
            err.append("导入失败: ").append(e.getMessage());
            return false;
        }
    }

    private byte[] readEntry(InputStream in) throws Exception {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }

    /** 给 cmd 中声明的 bin/ 路径加执行权限 */
    private void applyExecPermission(File pluginDir, JSONObject meta) {
        JSONArray cmd = meta.optJSONArray("cmd");
        if (cmd == null) return;
        for (int i = 0; i < cmd.length(); i++) {
            String c = cmd.optString(i, "");
            if (c.startsWith("bin/")) {
                File exe = new File(pluginDir, c);
                if (exe.exists()) exe.setExecutable(true, false);
            }
        }
        // 对 bin 目录所有文件加执行权限（Go 静态包等场景）
        File binDir = new File(pluginDir, "bin");
        if (binDir.isDirectory()) {
            File[] files = binDir.listFiles();
            if (files != null) {
                for (File f : files) {
                    if (f.isFile()) f.setExecutable(true, false);
                }
            }
        }
    }

    public void uninstall(String id) {
        ProcessManager pm = ProcessManager.getInstance(baseDir);
        if (pm != null && pm.isAlive("plugin_" + id)) pm.stop("plugin_" + id);
        deleteRecursive(new File(pluginsDir, id));
        cache.remove(id);
    }

    private void deleteRecursive(File f) {
        if (f == null) return;
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) {
                for (File c : children) deleteRecursive(c);
            }
        }
        f.delete();
    }

    // ============ 启停 ============
    /**
     * 启动插件。按 plugin.json 的 cmd 构造命令。
     * 进程名统一为 plugin_<id>，日志写到 baseDir/runtime_logs/plugin_<id>.log
     */
    public boolean start(String id) {
        JSONObject meta = loadMeta(id);
        if (meta == null) return false;
        JSONArray cmdArr = meta.optJSONArray("cmd");
        if (cmdArr == null || cmdArr.length() == 0) return false;

        File pluginDir = getPluginDir(id);
        java.util.List<String> cmdList = new java.util.ArrayList<>();
        boolean binIsElf = false;
        boolean binIs64 = false;
        for (int i = 0; i < cmdArr.length(); i++) {
            String c = cmdArr.optString(i, "");
            String full = c.startsWith("bin/") ? new File(pluginDir, c).getAbsolutePath() : c;
            cmdList.add(full);
            if (i == 0) {
                // 判断首个命令是否为 ELF 二进制（Android 私有目录 SELinux 禁止直接 exec，
                // 必须通过 /system/bin/linker64|linker 启动，简云原生 mefrpc 已验证此方式）
                File f = new File(full);
                if (f.exists()) {
                    Boolean is64 = detectElfArch(f);
                    if (is64 != null) {
                        binIsElf = true;
                        binIs64 = is64;
                    }
                }
            }
        }
        // 若是 ELF，改用 linker 启动
        String[] cmd;
        if (binIsElf) {
            String linker = pickLinker(binIs64);
            if (linker == null) {
                Log.e(TAG, "系统缺少 linker/linker64，无法启动插件 " + id);
                return false;
            }
            cmdList.add(0, linker);
            cmd = cmdList.toArray(new String[0]);
        } else {
            cmd = cmdList.toArray(new String[0]);
        }

        int port = meta.optInt("port", 0);
        String procName = "plugin_" + id;

        // 构造运行环境：把插件 lib/ 加入 LD_LIBRARY_PATH，PYTHONHOME 指向插件根
        //（termux 的 python 编译前缀为 /data/data/com.termux/files/usr，用 PYTHONHOME 覆盖）
        java.util.List<String> envList = new java.util.ArrayList<>();
        String ld = System.getenv("LD_LIBRARY_PATH");
        File libDir = new File(pluginDir, "lib");
        String libPath = libDir.getAbsolutePath();
        if (ld != null && !ld.isEmpty()) libPath = libPath + ":" + ld;
        envList.add("LD_LIBRARY_PATH=" + libPath);
        envList.add("PYTHONHOME=" + pluginDir.getAbsolutePath());
        envList.add("PYTHONUTF8=1");
        // linker 模式需要 HOME（部分运行时依赖）
        envList.add("HOME=" + pluginDir.getAbsolutePath());
        // 插件自定义 env
        JSONArray metaEnv = meta.optJSONArray("env");
        if (metaEnv != null) {
            for (int i = 0; i < metaEnv.length(); i++) envList.add(metaEnv.optString(i, ""));
        }
        String[] env = envList.toArray(new String[0]);

        ProcessManager pm = ProcessManager.getInstance(baseDir);
        if (pm == null) return false;
        if (pm.isAlive(procName)) return true;
        boolean ok = pm.start(procName, cmd, env, pluginDir, port);
        if (ok) setEnabled(id, true);
        return ok;
    }

    /** 检测 ELF 架构：返回 null 表示非 ELF，true=64位, false=32位 */
    private Boolean detectElfArch(File bin) {
        try (FileInputStream fis = new FileInputStream(bin)) {
            byte[] header = new byte[5];
            int n = fis.read(header);
            if (n != 5) return null;
            if (header[0] != 0x7f || header[1] != 'E' || header[2] != 'L' || header[3] != 'F') return null;
            return header[4] == 2;
        } catch (Exception e) {
            return null;
        }
    }

    /** 选择可用的 linker：优先匹配二进制架构 */
    private String pickLinker(boolean binIs64) {
        String preferred = binIs64 ? "/system/bin/linker64" : "/system/bin/linker";
        if (new File(preferred).exists()) return preferred;
        String fallback = binIs64 ? "/system/bin/linker" : "/system/bin/linker64";
        if (new File(fallback).exists()) return fallback;
        return null;
    }

    public void stop(String id) {
        ProcessManager pm = ProcessManager.getInstance(baseDir);
        if (pm != null) pm.stop("plugin_" + id);
        setEnabled(id, false);
    }

    // ============ 启停状态持久化 & 保活 ============
    private SharedPreferences prefs() {
        return appContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    /** 记录插件是否"应保持运行"（用于保活自动重启） */
    public void setEnabled(String id, boolean enabled) {
        try {
            java.util.Set<String> cur = new java.util.HashSet<>(prefs().getStringSet(PREF_ENABLED, new java.util.HashSet<>()));
            if (enabled) cur.add(id); else cur.remove(id);
            prefs().edit().putStringSet(PREF_ENABLED, cur).apply();
        } catch (Exception ignored) {}
    }

    public boolean isEnabled(String id) {
        try {
            return prefs().getStringSet(PREF_ENABLED, new java.util.HashSet<>()).contains(id);
        } catch (Exception e) { return false; }
    }

    /** 保活：把所有标记为"应运行"但当前已死的插件重新拉起。返回本次拉起的数量 */
    public int restartEnabled() {
        int revived = 0;
        try {
            java.util.Set<String> enabled = prefs().getStringSet(PREF_ENABLED, new java.util.HashSet<>());
            for (String id : enabled) {
                if (!isInstalled(id)) { setEnabled(id, false); continue; }
                if (!isRunning(id)) {
                    if (start(id)) {
                        revived++;
                        Log.i(TAG, "保活自动重启插件: " + id);
                    } else {
                        Log.w(TAG, "保活重启失败: " + id);
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "restartEnabled", e);
        }
        return revived;
    }

    public boolean isRunning(String id) {
        ProcessManager pm = ProcessManager.getInstance(baseDir);
        if (pm == null) return false;
        return pm.isAlive("plugin_" + id);
    }

    public String getLogPath(String id) {
        return new File(baseDir, LOG_DIR + "/plugin_" + id + ".log").getAbsolutePath();
    }

    public File getBaseDir() {
        return baseDir;
    }
}
