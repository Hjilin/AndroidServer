package com.zm920.androidserver.server;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.zm920.androidserver.config.ConfigGenerator;
import com.zm920.androidserver.service.ProcessManager;

import java.io.File;
import java.io.FileWriter;
import java.net.InetSocketAddress;
import java.net.Socket;


public class DatabaseManager {

    private static final String TAG = "DatabaseManager";
    private static final String KEY_TABLES_COMPLETE = "mysql_tables_complete";
    private static final String KEY_DB_SAFE_MODE = "mysql_safe_mode";
    private static final String KEY_MARIADB_VERSION = "mysql_maria_version";

    private final Context context;
    private final ProcessManager processManager;
    private final ConfigGenerator configGenerator;
    private final SharedPreferences prefs;
    private final BinaryDeployer deployer;
    private final StartupLogger startupLogger;

    public DatabaseManager(Context ctx, ProcessManager pm,
                           ConfigGenerator cg, SharedPreferences sp,
                           BinaryDeployer deployer,
                           StartupLogger startupLogger) {
        this.context = ctx;
        this.processManager = pm;
        this.configGenerator = cg;
        this.prefs = sp;
        this.deployer = deployer;
        this.startupLogger = startupLogger;
    }

    public boolean isInstalled() {
        return prefs.contains("active_mariadb");
    }

    public boolean start() throws Exception {
        int mysqlPort = prefs.getInt("mysql_port", 3306);
        killByPort(mysqlPort);
        for (int i = 0; i < 5; i++) {
            if (!isPortOpen(mysqlPort)) break;
            killByPort(mysqlPort);
            try { Thread.sleep(300); } catch (Exception ignored) {}
        }
        if (isPortOpen(mysqlPort)) {
            Log.i(TAG, "MariaDB 已在运行");
            return true;
        }

        try {
            deployer.deployAll();
            String mysqld = deployer.getBinPath("mariadbd");
            if (mysqld == null) return false;

            String dataDir = context.getFilesDir().getAbsolutePath() + "/server";
            File configDir = new File(dataDir, "config");
            String customMysqlDataDir = prefs.getString("mysql_data_dir", null);
            File mysqlData = customMysqlDataDir == null || customMysqlDataDir.trim().isEmpty()
                    ? new File(configDir, "data")
                    : new File(customMysqlDataDir);
            configDir.mkdirs();
            mysqlData.mkdirs();

            deployErrMsg(configDir, "english");
            deployErrMsg(configDir, "chinese");
            new File(configDir, "tmp").mkdirs();

            // 版本升级检测：MariaDB版本变化时强制重建系统表
            String currentMariaVer = prefs.getString("active_mariadb", "");
            String lastMariaVer = prefs.getString(KEY_MARIADB_VERSION, "");
            // 首次安装或版本变化时重建数据目录（避免旧模板与二进制不兼容）
            boolean versionChanged = !currentMariaVer.isEmpty() && (lastMariaVer.isEmpty() || !currentMariaVer.equals(lastMariaVer));
            boolean mysqlComplete = isSystemTableComplete(mysqlData);
            if (!mysqlComplete || versionChanged) {
                if (versionChanged) {
                    Log.w(TAG, "MariaDB 版本变化: " + lastMariaVer + " -> " + currentMariaVer + "，重建系统表");
                } else {
                    Log.w(TAG, "检测到系统表不完整，从 MariaDB 下载包恢复模板");
                }
                if (mysqlData.exists()) deleteDir(mysqlData);
                mysqlData.mkdirs();
                restoreTemplateData(mysqlData);
            }

            // my.cnf: 首次创建、bind 地址变化、或缺少新配置项时重写
            File myCnfFile = new File(configDir, "my.cnf");
            boolean bindWan = prefs.getBoolean("mysql_bind_wan", false);
            String expectedBind = bindWan ? "0.0.0.0" : "127.0.0.1";
            boolean needRewrite = !myCnfFile.exists() || myCnfFile.length() == 0;
            if (!needRewrite && myCnfFile.exists()) {
                try {
                    byte[] cnfBytes = new byte[(int) myCnfFile.length()];
                    java.io.FileInputStream fis = new java.io.FileInputStream(myCnfFile);
                    fis.read(cnfBytes);
                    fis.close();
                    String cnfContent = new String(cnfBytes);
                    // 检查 bind-address
                    if (!cnfContent.contains("bind-address = " + expectedBind)) {
                        needRewrite = true;
                        Log.i(TAG, "bind-address 设置变更，重写 my.cnf");
                    }
                    // 检查是否包含新配置项（性能优化）
                    else if (!cnfContent.contains("innodb_buffer_pool_size = 32M")
                          || !cnfContent.contains("character-set-server = utf8mb4")
                          || !cnfContent.contains("max_allowed_packet = 16M")) {
                        needRewrite = true;
                        Log.i(TAG, "my.cnf 缺少新配置项，重写以应用性能优化");
                    }
                } catch (Exception e) {
                    needRewrite = true;
                }
            }
            if (needRewrite) {
                writeMyCnf(configDir, mysqlData, mysqlPort);
            }

            // 正常启动 MariaDB（无 --init-file、无 --skip-grant-tables）。
            // 密码由 showMysqlChangePwdDialog 通过 TCP Socket + mysql_native_password 维护，
            // 启动时无需额外同步。
            String mysqlPassword = prefs.getString("mysql_password", "123456");

            String[] env = deployer.buildEnv();
            String[] cmd = deployer.buildExecCmd(mysqld,
                "--defaults-file=" + new File(configDir, "my.cnf").getAbsolutePath()
            );
            Log.i(TAG, "启动 MariaDB: " + String.join(" ", cmd));
            boolean ok = processManager.start("mysqld", cmd, env, configDir, mysqlPort);
            if (!ok) {
                startupLogger.logFailure("mariadbd", mysqld, cmd, env, configDir,
                        new Exception("MariaDB 启动失败"));
                return false;
            }

            for (int i = 0; i < 30; i++) {
                Thread.sleep(500);
                if (processManager.isAlive("mysqld")) {
                    String currentVer = prefs.getString("active_mariadb", "");
                    prefs.edit()
                            .putBoolean(KEY_TABLES_COMPLETE, true)
                            .putBoolean(KEY_DB_SAFE_MODE, false)
                            .putString(KEY_MARIADB_VERSION, currentVer)
                            .apply();
                    Log.i(TAG, "MariaDB 就绪 (密码由 UI 侧 TCP 协议管理)");
                    return true;
                }
            }

            startupLogger.logFailure("mariadbd", mysqld, cmd, env, configDir,
                    new Exception("MariaDB 启动超时"));
            return false;
        } catch (Exception e) {
            startupLogger.logFailure("mariadbd", deployer.getBinPath("mariadbd"), null, null, null, e);
            return false;
        }
    }

    public void stop() {
        int mysqlPort = prefs.getInt("mysql_port", 3306);
        processManager.stop("mysqld");
        killByPort(mysqlPort);
        // 确保端口真正释放
        for (int i = 0; i < 5; i++) {
            if (!isPortOpen(mysqlPort)) break;
            killByPort(mysqlPort);
            try { Thread.sleep(300); } catch (Exception ignored) {}
        }
    }


    /**
     * 用 --skip-grant-tables 重启 MariaDB，TCP 连接后 UPDATE 密码哈希，再正常重启。
     * 兼容所有认证插件（ed25519 / unix_socket / mysql_native_password），
     * 由 tcpAlterRootPassword 调用。
     */
    public boolean updateRootPassword(String newPassword) {
        try {
            String ep = newPassword.replace("'", "\\'");
            // 用 --skip-grant-tables 模式改密码，避免 init-file 在崩溃恢复阶段卡住
            String sql = "FLUSH PRIVILEGES;\n"
                       + "ALTER USER 'root'@'localhost' IDENTIFIED BY '" + ep + "';\n"
                       + "ALTER USER 'root'@'127.0.0.1' IDENTIFIED BY '" + ep + "';\n"
                       + "ALTER USER 'root'@'%' IDENTIFIED BY '" + ep + "';\n"
                       + "FLUSH PRIVILEGES;\n";
            return executeSqlViaSkipGrantTables(sql);
        } catch (Exception e) { Log.e(TAG, "updRootPwd err", e); return false; }
    }

    /**
     * 用 --skip-grant-tables 启动 mariadbd，执行 SQL 后立即正常重启。
     * 避免 init-file 启动在崩溃恢复阶段卡住的问题（安卓12 小米常见）。
     * 安全性：--skip-grant-tables 模式下任何客户端可无密码连接，
     *          因此 SQL 执行完后立即 stop 并正常重启，恢复密码验证。
     */
    public boolean executeSqlViaSkipGrantTables(String sql) {
        int port = prefs.getInt("mysql_port", 3306);
        try {
            String mysqld = deployer.getBinPath("mariadbd");
            if (mysqld == null) return false;
            File cfgDir = new File(context.getFilesDir().getAbsolutePath() + "/server", "config");
            File myCnf = new File(cfgDir, "my.cnf");
            File sqlFile = new File(cfgDir, "mysql_skip_grant.sql");

            // 写入 SQL 文件
            java.io.FileWriter fw = new java.io.FileWriter(sqlFile);
            fw.write(sql + "\n");
            fw.close();

            // 停止当前 mariadbd
            stop();
            killByPort(port);
            Thread.sleep(2000);  // 等 aria_log_control 锁释放

            // 用 --skip-grant-tables 启动
            String[] env = deployer.buildEnv();
            String[] cmd1 = deployer.buildExecCmd(mysqld,
                "--defaults-file=" + myCnf.getAbsolutePath(),
                "--skip-grant-tables"
            );
            Log.i(TAG, "skip-grant-tables: " + String.join(" ", cmd1));
            if (!processManager.start("mysqld", cmd1, env, cfgDir, port)) {
                Log.w(TAG, "skip-grant-tables start fail");
                return false;
            }

            // 等待端口打开（--skip-grant-tables 启动通常很快，不受崩溃恢复影响）
            boolean portReady = false;
            for (int i = 0; i < 40; i++) {
                Thread.sleep(300);
                if (processManager.isAlive("mysqld") && isPortOpen(port)) {
                    portReady = true;
                    break;
                }
            }
            if (!portReady) {
                Log.w(TAG, "skip-grant-tables port not ready");
                processManager.stop("mysqld");
                killByPort(port);
                return false;
            }
            Thread.sleep(1000);

            // 用 mariadb CLI 无密码连接执行 SQL
            boolean sqlOk = runMariadbCliNoPwd(sqlFile, port);

            // 立即停止 --skip-grant-tables 模式的 mariadbd
            processManager.stop("mysqld");
            killByPort(port);
            Thread.sleep(2000);

            if (!sqlOk) {
                Log.e(TAG, "skip-grant-tables SQL 执行失败，仍尝试正常启动");
            }

            // 正常启动 mariadbd（不带 --skip-grant-tables，恢复密码验证）
            String[] cmd2 = deployer.buildExecCmd(mysqld,
                "--defaults-file=" + myCnf.getAbsolutePath());
            processManager.start("mysqld", cmd2, env, cfgDir, port);
            for (int i = 0; i < 30; i++) {
                Thread.sleep(500);
                if (processManager.isAlive("mysqld")) {
                    Log.i(TAG, "skip-grant-tables done, sqlOk=" + sqlOk);
                    return sqlOk;
                }
            }
            return false;
        } catch (Exception e) { Log.e(TAG, "execSkipGrantTables err", e); return false; }
    }

    /**
     * 用 mariadb CLI 无密码连接执行 SQL 文件（用于 --skip-grant-tables 模式）。
     */
    private boolean runMariadbCliNoPwd(File sqlFile, int port) {
        try {
            String mariadbBin = deployer.getBinPath("mariadb");
            if (mariadbBin == null) return false;

            String activeVer = prefs.getString("active_mariadb", "");
            File depDir = new File(context.getFilesDir(),
                "bin/mariadb/" + activeVer + "/dep");
            String ldPath = "/system/lib64:/vendor/lib64:/system/lib";
            if (depDir.isDirectory()) {
                ldPath = depDir.getAbsolutePath() + ":" + ldPath;
            }

            String arch = System.getProperty("os.arch", "");
            String[] cmd = new String[]{
                arch.contains("64") ? "/system/bin/linker64" : "/system/bin/linker",
                mariadbBin,
                "-h", "127.0.0.1",
                "-P", String.valueOf(port),
                "-u", "root",
                "-e", "source " + sqlFile.getAbsolutePath()
            };

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.environment().put("LD_LIBRARY_PATH", ldPath);
            pb.environment().put("TMPDIR", context.getCacheDir().getAbsolutePath());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096]; int n;
            java.io.InputStream pis = p.getInputStream();
            while ((n = pis.read(buf)) != -1) bos.write(buf, 0, n);
            p.waitFor(15, java.util.concurrent.TimeUnit.SECONDS);
            String output = bos.toString("UTF-8").trim();
            int exitCode = p.exitValue();
            Log.i(TAG, "mariadb CLI (no pwd) exit=" + exitCode + " output=" + output);
            return exitCode == 0;
        } catch (Exception e) {
            Log.e(TAG, "runMariadbCliNoPwd err", e);
            return false;
        }
    }

    /** 停 MariaDB → --init-file 启动执行 SQL → 正常重启。
     *  Termux 验证: --init-file 单独使用(不带 --skip-grant-tables)在任意次数启动都能执行。 */
    public boolean executeSqlViaInitFile(String sql) {
        int port = prefs.getInt("mysql_port", 3306);
        try {
            String mysqld = deployer.getBinPath("mariadbd");
            if (mysqld == null) return false;
            File cfgDir = new File(context.getFilesDir().getAbsolutePath() + "/server", "config");
            File initFile = new File(cfgDir, "mysql_init.sql");
            java.io.FileWriter fw = new java.io.FileWriter(initFile);
            fw.write(sql + "\n");
            fw.close();

            stop();
            killByPort(port);
            Thread.sleep(1000);

            String[] env = deployer.buildEnv();
            String[] cmd1 = deployer.buildExecCmd(mysqld,
                "--defaults-file=" + new File(cfgDir, "my.cnf").getAbsolutePath(),
                "--init-file=" + initFile.getAbsolutePath()
            );
            Log.i(TAG, "init-file: " + String.join(" ", cmd1));
            if (!processManager.start("mysqld", cmd1, env, cfgDir, port)) { Log.w(TAG, "init-file start fail"); return false; }
            for (int i = 0; i < 40; i++) { Thread.sleep(300); if (processManager.isAlive("mysqld") && isPortOpen(port)) break; }
            Thread.sleep(2000);

            processManager.stop("mysqld");
            killByPort(port);
            Thread.sleep(1000);

            String[] cmd2 = deployer.buildExecCmd(mysqld,
                "--defaults-file=" + new File(cfgDir, "my.cnf").getAbsolutePath());
            processManager.start("mysqld", cmd2, env, cfgDir, port);
            for (int i = 0; i < 30; i++) { Thread.sleep(500); if (processManager.isAlive("mysqld")) { Log.i(TAG, "init-file done"); return true; } }
            return false;
        } catch (Exception e) { Log.e(TAG, "execInitFile err", e); return false; }
    }


    public boolean isRunning() {
        if (processManager.isAlive("mysqld")) return true;
        int port = prefs.getInt("mysql_port", 3306);
        return isPortOpen(port);
    }

    public boolean isSafeModeBlocked() {
        return prefs.getBoolean(KEY_DB_SAFE_MODE, false);
    }

    private void writeMyCnf(File configDir, File mysqlData, int mysqlPort) throws Exception {
        StringBuilder cnf = new StringBuilder();
        cnf.append("[mysqld]\n");
        cnf.append("port = ").append(mysqlPort).append("\n");
        boolean bindWan = prefs.getBoolean("mysql_bind_wan", false);
        cnf.append("bind-address = ").append(bindWan ? "0.0.0.0" : "127.0.0.1").append("\n");
        cnf.append("datadir = ").append(mysqlData.getAbsolutePath()).append("\n");
        cnf.append("socket = ").append(context.getFilesDir().getAbsolutePath() + "/server/config/mysql.sock").append("\n");
        cnf.append("skip-external-locking\n");
        cnf.append("innodb_flush_method=fsync\n");
        cnf.append("innodb_use_native_aio=0\n");
        cnf.append("innodb_log_file_size=4M\n");
        // === 性能优化（手机端低内存环境）===
        cnf.append("innodb_buffer_pool_size = 32M\n");
        cnf.append("innodb_flush_log_at_trx_commit=0\n");
        cnf.append("innodb_data_file_path=ibdata1:12M:autoextend\n");
        cnf.append("innodb_checksum_algorithm=crc32\n");
        cnf.append("skip_log_bin\n");
        cnf.append("sync_binlog=0\n");
        cnf.append("skip_slave_start\n");
        cnf.append("tmpdir = ").append(new File(configDir, "tmp").getAbsolutePath()).append("\n");
        cnf.append("log_error = ").append(new File(configDir, "mysqld.log").getAbsolutePath()).append("\n");
        cnf.append("lc_messages_dir = ").append(new File(configDir, "share/mariadb").getAbsolutePath()).append("\n");
        cnf.append("lc_messages = en_US\n");
        // === 字符集 ===
        cnf.append("character-set-server = utf8mb4\n");
        cnf.append("collation-server = utf8mb4_unicode_ci\n");
        cnf.append("init-connect = \'SET NAMES utf8mb4\'\n");
        // === 线程优化 ===
        cnf.append("thread_stack = 256K\n");
        cnf.append("thread_cache_size = 4\n");
        cnf.append("max_allowed_packet = 16M\n");
        FileWriter fw = new FileWriter(new File(configDir, "my.cnf"));
        fw.write(cnf.toString());
        fw.close();
    }

    private boolean isSystemTableComplete(File mysqlData) {
        File mysqlDir = new File(mysqlData, "mysql");
        if (!mysqlDir.isDirectory()) return false;
        File[] mysqlFiles = mysqlDir.listFiles();
        if (mysqlFiles == null || mysqlFiles.length < 10) return false;

        String[] required = {"ibdata1", "ib_logfile0", "undo001", "undo002", "undo003"};
        for (String name : required) {
            File f = new File(mysqlData, name);
            if (!f.exists() || !f.isFile() || f.length() <= 0) {
                Log.w(TAG, "MariaDB 数据目录缺少关键文件: " + name + ", path=" + f.getAbsolutePath());
                return false;
            }
        }
        return true;
    }

    private String escapeSql(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("'", "\\'");
    }

    /** 通过 /proc/net/tcp 查找并杀死占用指定端口的进程 */
    private void readFullyMysql(java.io.InputStream in, byte[] buf) throws Exception {
        int offset = 0;
        while (offset < buf.length) {
            int n = in.read(buf, offset, buf.length - offset);
            if (n < 0) throw new java.io.EOFException("unexpected EOF");
            offset += n;
        }
    }

    /** 从 Process 对象获取 PID */
    private int getProcessPid(Process p) {
        try {
            Object result = p.getClass().getMethod("pid").invoke(p);
            if (result instanceof Long) return ((Long) result).intValue();
            if (result instanceof Integer) return (Integer) result;
        } catch (Exception ignored) {}
        try {
            java.lang.reflect.Field f = p.getClass().getDeclaredField("pid");
            f.setAccessible(true);
            return f.getInt(p);
        } catch (Exception ignored) {}
        return -1;
    }

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
                                Runtime.getRuntime().exec(new String[]{"/system/bin/kill", "-9", pid}).waitFor();
                            }
                        } catch (Exception ignored) {}
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    /** 通过 /proc/net/tcp 检查端口是否处于 LISTEN 状态 */
    private boolean isPortListening(int port) {
        try {
            java.io.File f = new java.io.File("/proc/net/tcp");
            if (!f.canRead()) return isPortOpen(port);
            byte[] buf = new byte[32768];
            int n;
            try (java.io.FileInputStream fis = new java.io.FileInputStream(f)) {
                n = fis.read(buf);
            }
            if (n <= 0) return false;
            String hexPort = String.format("%04X", port);
            for (String line : new String(buf, 0, n).split("\n")) {
                line = line.trim();
                if (line.startsWith("sl")) continue;
                String[] parts = line.split("\s+");
                if (parts.length < 4) continue;
                String localAddr = parts[1];
                if (!localAddr.endsWith(":" + hexPort)) continue;
                // 0A = LISTEN
                if ("0A".equals(parts[3])) return true;
            }
            return false;
        } catch (Exception e) {
            return isPortOpen(port);
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

    private boolean restoreTemplateData(File mysqlData) {
        try {
            // 1. 从下载包版本目录
            String mysqlVer = prefs.getString("active_mariadb", "");
            if (!mysqlVer.isEmpty()) {
                File srcData = new File(context.getFilesDir(),
                    "bin/mariadb/" + mysqlVer + "/share/mariadb-template/data");
                if (srcData.isDirectory()) {
                    copyDir(srcData, mysqlData);
                    return true;
                }
            }
            Log.e(TAG, "MariaDB 模板资源缺失");
            return false;
        } catch (Exception e) {
            Log.e(TAG, "恢复 MariaDB 模板失败", e);
            return false;
        }
    }



    private void copyDir(File srcDir, File destDir) throws Exception {
        if (!destDir.exists()) destDir.mkdirs();
        File[] files = srcDir.listFiles();
        if (files == null) return;
        for (File f : files) {
            File dest = new File(destDir, f.getName());
            if (f.isDirectory()) {
                copyDir(f, dest);
            } else {
                copyFile(f, dest);
            }
        }
    }

    private void copyFile(File src, File dest) throws Exception {
        File parent = dest.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        java.io.FileInputStream is = new java.io.FileInputStream(src);
        java.io.FileOutputStream os = new java.io.FileOutputStream(dest);
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) != -1) os.write(buf, 0, n);
        is.close();
        os.close();
    }

    private void deleteDir(File dir) {
        File[] files = dir.listFiles();
        if (files != null) for (File f : files) {
            if (f.isDirectory()) deleteDir(f);
            f.delete();
        }
        dir.delete();
    }

    private static String mysqlNativePasswordHash(String password) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-1");
            byte[] stage1 = md.digest(password.getBytes("UTF-8"));
            md.reset();
            byte[] stage2 = md.digest(stage1);
            StringBuilder sb = new StringBuilder("*");
            for (byte b : stage2) sb.append(String.format("%02X", b));
            return sb.toString();
        } catch (Exception e) {
            Log.e(TAG, "mysqlNativePasswordHash error", e);
            return "";
        }
    }

    private void deployErrMsg(File configDir, String lang) {
        try {
            File shareDir = new File(configDir, "share/mariadb/" + lang);
            if (!shareDir.exists()) shareDir.mkdirs();
            File errFile = new File(shareDir, "errmsg.sys");
            if (!errFile.exists()) {
                // 1. 从下载包版本目录
                String mysqlVer = prefs.getString("active_mariadb", "");
                if (!mysqlVer.isEmpty()) {
                    File srcFile = new File(context.getFilesDir(),
                        "bin/mariadb/" + mysqlVer + "/share/mariadb/" + lang + "/errmsg.sys");
                    if (srcFile.exists() && srcFile.length() > 0) {
                        copyFile(srcFile, errFile);
                        Log.i(TAG, lang + "/errmsg.sys 已部署 (来自下载包)");
                        return;
                    }
                }
                Log.w(TAG, lang + "/errmsg.sys 未找到");
            }
        } catch (Exception e) {
            Log.w(TAG, "部署 " + lang + "/errmsg.sys 失败: " + e.getMessage());
        }
    }
}
