package com.zm920.androidserver.server;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.File;

public class BinaryDeployer {

    private final Context context;
    private final SharedPreferences prefs;
    private final File nativeLibDir;

    // {查找名, jniLibs文件名, 二进制文件名, 下载目录名}
    private static final String[][] BINS = {
        {"nginx",        "libnginx.so",        "nginx",        "nginx"},
        {"mariadbd",     "libmariadb_launcher.so", "mariadbd", "mariadb"},
        {"mariadb",      "mariadb",            "mariadb",      "mariadb"},
        {"redis-server", "libredisserver.so",  "redis-server", "redis"},
        {"php-cgi",      "libphpcgi.so",       "php-cgi",      "php"},
    };

    public BinaryDeployer(Context context, SharedPreferences prefs) {
        this.context = context;
        this.prefs = prefs;
        this.nativeLibDir = new File(context.getApplicationInfo().nativeLibraryDir);
    }

    public void deployAll() throws Exception { }

    /** 获取二进制路径：mariadbd/mariadb 优先下载目录，其他优先 nativeLibDir */
    public String getBinPath(String name) {
        boolean preferDownload = name.equals("mariadbd") || name.equals("mariadb");
        for (String[] bin : BINS) {
            if (bin[0].equals(name)) {
                if (preferDownload) {
                    // 优先下载目录（压缩包内有完整 mariadbd + mariadb + 依赖）
                    String activeVer = prefs.getString("active_" + bin[3], "");
                    if (!activeVer.isEmpty()) {
                        File f = new File(context.getFilesDir(),
                            "bin/" + bin[3] + "/" + activeVer + "/bin/" + bin[2]);
                        if (f.exists() && f.length() > 0) return f.getAbsolutePath();
                    }
                    // fallback 到 nativeLibDir
                    File f = new File(nativeLibDir, bin[1]);
                    if (f.exists() && f.length() > 0) return f.getAbsolutePath();
                } else {
                    // 1. nativeLibraryDir（jniLibs 提取目录）
                    File f = new File(nativeLibDir, bin[1]);
                    if (f.exists() && f.length() > 0) return f.getAbsolutePath();
                    // 2. 下载目录
                    String activeVer = prefs.getString("active_" + bin[3], "");
                    if (!activeVer.isEmpty()) {
                        f = new File(context.getFilesDir(),
                            "bin/" + bin[3] + "/" + activeVer + "/bin/" + bin[2]);
                        if (f.exists() && f.length() > 0) return f.getAbsolutePath();
                    }
                }
                return null;
            }
        }
        return null;
    }

    /** 构建 LD_LIBRARY_PATH */
    public String[] buildEnv() {
        String ldPath = nativeLibDir.getAbsolutePath();
        for (String[] bin : BINS) {
            String activeVer = prefs.getString("active_" + bin[3], "");
            if (!activeVer.isEmpty()) {
                File verDir = new File(context.getFilesDir(),
                    "bin/" + bin[3] + "/" + activeVer);
                File libDir = new File(verDir, "lib");
                if (libDir.isDirectory()) {
                    ldPath += ":" + libDir.getAbsolutePath();
                }
                File depDir = new File(verDir, "dep");
                if (depDir.isDirectory()) {
                    ldPath += ":" + depDir.getAbsolutePath();
                }
            }
        }
        ldPath += ":/system/lib64:/vendor/lib64:/system/lib";
        return new String[]{
            "LD_LIBRARY_PATH=" + ldPath,
            "TMPDIR=" + context.getCacheDir().getAbsolutePath()
        };
    }

    /** mariadbd 通过 linker64 启动；其他组件通过原生 linker64 启动。 */
    public String[] buildExecCmd(String binPath, String... args) {
        java.util.ArrayList<String> cmd = new java.util.ArrayList<>();
        java.util.ArrayList<String> finalArgs = new java.util.ArrayList<>();
        if (args != null) {
            for (String a : args) finalArgs.add(a);
        }

        // mariadbd 通过 linker64 启动
        if (binPath.endsWith("mariadbd")) {
            String arch = System.getProperty("os.arch", "");
            cmd.add(arch.contains("64") ? "/system/bin/linker64" : "/system/bin/linker");
            cmd.add(binPath);
            cmd.addAll(finalArgs);
            return cmd.toArray(new String[0]);
        }

        // 默认：来自 nativeLibraryDir 直行
        if (binPath.startsWith(nativeLibDir.getAbsolutePath())) {
            cmd.add(binPath);
            cmd.addAll(finalArgs);
            return cmd.toArray(new String[0]);
        }

        // 其他路径通过 linker64
        String arch = System.getProperty("os.arch", "");
        cmd.add(arch.contains("64") ? "/system/bin/linker64" : "/system/bin/linker");
        cmd.add(binPath);
        cmd.addAll(finalArgs);
        return cmd.toArray(new String[0]);
    }

    public boolean isDeployed() {
        for (String[] bin : BINS) {
            if (getBinPath(bin[0]) == null) return false;
        }
        return true;
    }
}
