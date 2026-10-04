package com.zm920.androidserver.config;

import android.content.SharedPreferences;

import java.io.File;
import java.io.FileWriter;
import java.util.HashSet;
import java.util.Set;

public class ConfigGenerator {

    private final File configDir;
    private final SharedPreferences prefs;

    public ConfigGenerator(File configDir, SharedPreferences prefs) {
        this.configDir = configDir;
        this.prefs = prefs;
        if (!configDir.exists()) configDir.mkdirs();
    }

    public File generateNginxConf(String binDir, String wwwRoot) throws Exception {
        return generateNginxConf(binDir, wwwRoot, false, "");
    }

    public File generateNginxConf(String binDir, String wwwRoot, boolean hasPhpMyAdmin) throws Exception {
        return generateNginxConf(binDir, wwwRoot, hasPhpMyAdmin, "");
    }

    public File generateNginxConf(String binDir, String wwwRoot, boolean hasPhpMyAdmin, String pmaRoot) throws Exception {
        String tmpDir = configDir + "/var/lib/nginx";
        int phpPort = prefs.getInt("php_port", 9000);

        String conf = "worker_processes auto;\n"
                + "daemon off;\n"
                + "error_log " + configDir + "/var/log/nginx/error.log;\n"
                + "pid " + configDir + "/var/run/nginx.pid;\n"
                + "events { worker_connections 1024; }\n"
                + "http {\n"
                + "    access_log " + configDir + "/var/log/nginx/access.log;\n"
                + "    client_body_temp_path " + tmpDir + "/client-body;\n"
                + "    proxy_temp_path " + tmpDir + "/proxy;\n"
                + "    fastcgi_temp_path " + tmpDir + "/fastcgi;\n"
                + "    uwsgi_temp_path " + tmpDir + "/uwsgi;\n"
                + "    scgi_temp_path " + tmpDir + "/scgi;\n"
                + "    include " + configDir + "/mime.types;\n"
                + "    default_type application/octet-stream;\n"
                + "    keepalive_timeout 5;\n"
                + "    index index.html index.htm index.php;\n"
                // === Gzip 压缩 ===
                + "    gzip on;\n"
                + "    gzip_vary on;\n"
                + "    gzip_min_length 256;\n"
                + "    gzip_comp_level 4;\n"
                + "    gzip_types text/plain text/css application/json application/javascript text/xml application/xml;\n"
                + "    gzip_disable \"msie6\";\n"
                // === 安全基础设置 ===
                + "    fastcgi_hide_header X-Powered-By;\n"
                + "    proxy_hide_header X-Powered-By;\n";

        Set<String> sites = prefs.getStringSet("sites", new HashSet<>());

        for (String site : sites) {
            String[] parts = site.split("\\|");
            if (parts.length >= 2) {
                String name = parts[0];
                int port = Integer.parseInt(parts[1]);
                String siteRoot = parts.length >= 3 && parts[2] != null && !parts[2].trim().isEmpty()
                        ? parts[2].trim()
                        : wwwRoot + "/" + name;
                String siteRewrite = parts.length >= 4 ? parts[3].replace("\u0001", "|") : "";

                conf += "    server {\n"
                        + "        listen " + port + ";\n"
                        + "        server_name " + name + ";\n"
                        + "        root \"" + siteRoot + "\";\n"
                        + (!siteRewrite.trim().isEmpty()
                            ? "        " + siteRewrite.trim().replace("\n", "\n        ") + (siteRewrite.endsWith("\n") ? "" : "\n")
                            : "        location / {\n            try_files $uri $uri/ =404;\n        }\n")
                        + "        location ~ \\.php$ {\n"
                        + "            fastcgi_pass 127.0.0.1:" + phpPort + ";\n"
                        + "            include " + configDir + "/fastcgi_params;\n"
                        + "            fastcgi_intercept_errors on;\n"
                        + "            error_page 502 503 504 = @fpm_down;\n"
                        + "        }\n"
                        + "        location @fpm_down {\n"
                        + "            default_type text/html;\n"
                        + "            return 503 \"<!DOCTYPE html><html><head><meta charset=utf-8><meta name=viewport content='width=device-width,initial-scale=1'><title>PHP-FPM 未启动</title><style>body{font-family:sans-serif;display:flex;justify-content:center;align-items:center;min-height:100vh;margin:0;background:#f5f5f5}.card{text-align:center;padding:40px;background:#fff;border-radius:12px;box-shadow:0 2px 8px rgba(0,0,0,.1)}h2{color:#333;margin-bottom:8px}p{color:#666}</style></head><body><div class=card><h2>PHP-FPM 未启动</h2><p>请先在仪表盘启动 PHP 服务</p></div></body></html>\";\n"
                        + "        }\n"
                        + "    }\n";
            }
        }

        // 自动检测 phpMyAdmin（需调用方传入双重校验结果）
        if (hasPhpMyAdmin) {
            conf += "    server {\n"
                    + "        listen 15237;\n"
                    + "        server_name phpmyadmin;\n"
                    + "        root \"" + pmaRoot + "\";\n"
                    + "        location / {\n"
                    + "            try_files $uri $uri/ =404;\n"
                    + "            index index.php index.html;\n"
                    + "        }\n"
                    + "        location ~ \\.php$ {\n"
                    + "            fastcgi_pass 127.0.0.1:" + phpPort + ";\n"
                    + "            include " + configDir + "/fastcgi_params;\n"
                    + "            fastcgi_intercept_errors on;\n"
                    + "            error_page 502 503 504 = @fpm_down;\n"
                    + "        }\n"
                    + "        location @fpm_down {\n"
                    + "            default_type text/html;\n"
                    + "            return 503 \"<!DOCTYPE html><html><head><meta charset=utf-8><meta name=viewport content='width=device-width,initial-scale=1'><title>PHP-FPM 未启动</title><style>body{font-family:sans-serif;display:flex;justify-content:center;align-items:center;min-height:100vh;margin:0;background:#f5f5f5}.card{text-align:center;padding:40px;background:#fff;border-radius:12px;box-shadow:0 2px 8px rgba(0,0,0,.1)}h2{color:#333;margin-bottom:8px}p{color:#666}</style></head><body><div class=card><h2>PHP-FPM 未启动</h2><p>请先在仪表盘启动 PHP 服务</p></div></body></html>\";\n"
                    + "        }\n"
                    + "    }\n";
        }

        // A2 统一入口：若 copyparty 插件已启用，提供一体化反代入口（独立 8088 端口，不干扰站点端口）
        // 访问 http://ip:8088/ 反代到 copyparty 5301；保留插件独立端口直达
        try {
            boolean cpEnabled = prefs.getBoolean("plugin_enabled_copyparty", false);
            if (cpEnabled) {
                conf += "    server {\n"
                        + "        listen 8088;\n"
                        + "        server_name cp_proxy;\n"
                        + "        location / {\n"
                        + "            proxy_pass http://127.0.0.1:5301;\n"
                        + "            proxy_set_header Host $host;\n"
                        + "            proxy_set_header X-Real-IP $remote_addr;\n"
                        + "            proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;\n"
                        + "            proxy_set_header X-Forwarded-Proto $scheme;\n"
                        + "            proxy_read_timeout 300s;\n"
                        + "            proxy_send_timeout 300s;\n"
                        + "            client_max_body_size 0;\n"
                        + "            proxy_buffering off;\n"
                        + "        }\n"
                        + "    }\n";
            }
        } catch (Exception ignored) {}

        conf += "}\n";

        writeFile(new File(configDir, "nginx.conf"), conf);
        generateMimeTypes();
        generateFastcgiParams();
        return new File(configDir, "nginx.conf");
    }

    private void generateMimeTypes() throws Exception {
        writeFile(new File(configDir, "mime.types"),
                "types {\n    text/html html htm;\n    text/css css;\n"
                + "    application/javascript js;\n    image/png png;\n"
                + "    image/jpeg jpg jpeg;\n    image/gif gif;\n"
                + "    image/svg+xml svg;\n    application/json json;\n}\n");
    }

    private void generateFastcgiParams() throws Exception {
        writeFile(new File(configDir, "fastcgi_params"),
                "fastcgi_param SCRIPT_FILENAME $document_root$fastcgi_script_name;\n"
                + "fastcgi_param QUERY_STRING $query_string;\n"
                + "fastcgi_param REQUEST_METHOD $request_method;\n"
                + "fastcgi_param CONTENT_TYPE $content_type;\n"
                + "fastcgi_param CONTENT_LENGTH $content_length;\n"
                + "fastcgi_param SCRIPT_NAME $fastcgi_script_name;\n"
                + "fastcgi_param REQUEST_URI $request_uri;\n"
                + "fastcgi_param DOCUMENT_URI $document_uri;\n"
                + "fastcgi_param DOCUMENT_ROOT $document_root;\n"
                + "fastcgi_param SERVER_PROTOCOL $server_protocol;\n"
                + "fastcgi_param REMOTE_ADDR $remote_addr;\n"
                + "fastcgi_param REMOTE_PORT $remote_port;\n"
                + "fastcgi_param SERVER_ADDR $server_addr;\n"
                + "fastcgi_param SERVER_PORT $server_port;\n"
                + "fastcgi_param SERVER_NAME $server_name;\n"
                + "fastcgi_param REDIRECT_STATUS 200;\n");
    }

    public File generatePhpIni() throws Exception {
        String ver = prefs.getString("active_php", "8.5.1");
        return generatePhpIni(ver);
    }

    public File generatePhpIni(String version) throws Exception {
        File phpDir = new File(configDir, "php/" + version);
        phpDir.mkdirs();
        new File(phpDir, "tmp").mkdirs();
        new File(phpDir, "opcache").mkdirs();
        File f = new File(phpDir, "php.ini");
        if (f.exists() && f.length() > 0) {
            // 升级旧版 ini：补充 opcache/sys_temp_dir（如有缺失）
            String oldContent = readFile(f);
            StringBuilder upgrade = new StringBuilder(oldContent);
            if (!oldContent.contains("opcache.lockfile_path")) {
                upgrade.append("opcache.lockfile_path = ").append(new File(phpDir, "opcache").getAbsolutePath()).append("\n");
            }
            if (!oldContent.contains("opcache.file_cache")) {
                upgrade.append("opcache.file_cache = ").append(new File(phpDir, "opcache").getAbsolutePath()).append("\n");
            }
            if (!oldContent.contains("sys_temp_dir")) {
                upgrade.append("sys_temp_dir = ").append(new File(phpDir, "tmp").getAbsolutePath()).append("\n");
            }
            if (!oldContent.contains("session.save_path")) {
                upgrade.append("session.save_path = ").append(new File(phpDir, "tmp").getAbsolutePath()).append("\n");
            }
            if (upgrade.length() > oldContent.length()) {
                writeFile(f, upgrade.toString());
            }
            return f;
        }
        String sessionPath = new File(phpDir, "sessions").getAbsolutePath();
        new File(sessionPath).mkdirs();
        String ini = "[PHP]\n"
                + "display_errors = Off\n"
                + "error_reporting = E_ALL & ~E_DEPRECATED\n"
                + "log_errors = On\n"
                + "error_log = " + new File(phpDir, "php_errors.log").getAbsolutePath() + "\n"
                + "short_open_tag = On\n"
                + "date.timezone = Asia/Shanghai\n"
                // === OPcache 性能优化 ===
                + "opcache.enable = 1\n"
                + "opcache.enable_cli = 1\n"
                + "opcache.memory_consumption = 64\n"
                + "opcache.file_cache = " + new File(phpDir, "opcache").getAbsolutePath() + "\n"
                + "opcache.lockfile_path = " + new File(phpDir, "opcache").getAbsolutePath() + "\n"
                + "realpath_cache_size = 4096k\n"
                + "realpath_cache_ttl = 120\n"
                + "sys_temp_dir = " + new File(phpDir, "tmp").getAbsolutePath() + "\n"
                // === Session 优化 ===
                + "session.save_handler = files\n"
                + "session.save_path = " + sessionPath + "\n"
                + "session.gc_probability = 1\n"
                + "session.gc_divisor = 100\n"
                + "session.gc_maxlifetime = 1440\n"
                + "extension_dir = \"\"\n"
                + "sqlite3.extension_dir = \"\"\n"
                + "output_handler = \n"
                + "implicit_flush = Off\n"
                + "max_execution_time = 300\n"
                + "max_input_time = 60\n"
                + "memory_limit = 256M\n"
                + "cgi.fix_pathinfo = 1\n"
                + "cgi.force_redirect = 0\n"
                + "upload_max_filesize = 100M\n"
                + "post_max_size = 100M\n";
        writeFile(f, ini);
        return f;
    }


    public File generateMyCnf() throws Exception {
        int port = prefs.getInt("mysql_port", 3306);
        String cnf = "[mysqld]\nport = " + port + "\n"
                + "datadir = " + configDir + "/data\n"
                + "socket = " + configDir + "/mysql.sock\n"
                + "tmpdir = " + configDir + "/tmp\n"
                + "skip-external-locking\n"
                + "innodb_flush_method=fsync\n"
                + "innodb_use_native_aio=0\n"
                // === 性能优化（手机端低内存环境）===
                + "innodb_buffer_pool_size = 32M\n"
                // === 字符集 ===
                + "character-set-server = utf8mb4\n"
                + "collation-server = utf8mb4_unicode_ci\n"
                + "init-connect = \'SET NAMES utf8mb4\'\n"
                // === 线程优化 ===
                + "thread_stack = 256K\n"
                + "thread_cache_size = 4\n"
                + "max_allowed_packet = 16M\n";
        File f = new File(configDir, "my.cnf");
        writeFile(f, cnf);
        new File(configDir, "data").mkdirs();
        new File(configDir, "tmp").mkdirs();
        return f;
    }

    public File generateRedisConf() throws Exception {
        int port = prefs.getInt("redis_port", 6379);
        String conf = "port " + port + "\ndaemonize no\n"
                + "bind 127.0.0.1\n"
                + "pidfile " + configDir + "/redis.pid\n"
                + "logfile " + configDir + "/redis.log\n"
                + "dir " + configDir + "/data\n";
        writeFile(new File(configDir, "redis.conf"), conf);
        new File(configDir, "data").mkdirs();
        return new File(configDir, "redis.conf");
    }

    
    private String readFile(File file) throws Exception {
        // 用 InputStream.read(byte[],int,int) 循环读，确保读完整个文件
        try (java.io.FileInputStream fis = new java.io.FileInputStream(file)) {
            byte[] data = new byte[(int) file.length()];
            int offset = 0;
            while (offset < data.length) {
                int read = fis.read(data, offset, data.length - offset);
                if (read <= 0) break;
                offset += read;
            }
            return new String(data, 0, offset, "UTF-8");
        }
    }
    private void writeFile(File file, String content) throws Exception {
        try (FileWriter fw = new FileWriter(file)) {
            fw.write(content);
        }
    }
}
