package com.zm920.androidserver.mefrp;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * ME Frp 配置管理。SharedPreferences: server_settings
 * <ul>
 *   <li>mefrp_key           必填，用户从 mefrp.com 拿</li>
 *   <li>mefrp_tunnel_port   隧道端口号</li>
 *   <li>mefrp_local_port    目标本地端口，默认 8080</li>
 *   <li>mefrp_bin_path      mefrpc 二进制路径，默认 /storage/emulated/0/mefrpc</li>
 *   <li>mefrp_download_url  mefrpc 二进制下载 URL（初始化时使用）</li>
 *   <li>mefrp_enabled       是否启用</li>
 *   <li>mefrp_auto_start    软件启动 30 秒后自动启动</li>
 *   <li>mefrp_public_url    最近一次成功分配的公网 URL</li>
 *   <li>mefrp_last_error    最近一次错误</li>
 * </ul>
 */
public class MefrpConfig {

    private static final String PREFS = "server_settings";
    public static final String KEY_KEY = "mefrp_key";
    public static final String KEY_TUNNEL_PORT = "mefrp_tunnel_port";
    public static final String KEY_DOWNLOAD_URL = "mefrp_download_url";
    public static final String KEY_VERSION = "mefrp_version";
    public static final String KEY_LOCAL_PORT = "mefrp_local_port";
    public static final String KEY_ENABLED = "mefrp_enabled";
    public static final String KEY_AUTO_START = "mefrp_auto_start";
    public static final String KEY_PUBLIC_URL = "mefrp_public_url";
    public static final String KEY_LAST_ERROR = "mefrp_last_error";

    /** 默认二进制路径：用户指定的 /storage/emulated/0/mefrpc */
    public static final String DEFAULT_BIN_PATH = "/storage/emulated/0/mefrpc";

    /** 默认 mefrpc 下载地址（内置，初始化时优先本地，本地没有再走网络） */
    public static final String DEFAULT_DOWNLOAD_URL = "https://mefrp.com/download/mefrpc.tar.gz";

    public static String getKey(Context ctx) {
        return ctx.getSharedPreferences(PREFS, 0).getString(KEY_KEY, "");
    }

    public static void setKey(Context ctx, String key) {
        ctx.getSharedPreferences(PREFS, 0).edit().putString(KEY_KEY, key).apply();
    }

    public static String getTunnelPort(Context ctx) {
        try {
            return ctx.getSharedPreferences(PREFS, 0).getString(KEY_TUNNEL_PORT, "");
        } catch (ClassCastException e) {
            // 兼容旧数据：之前用 putInt 存的 Integer 类型
            try {
                int oldVal = ctx.getSharedPreferences(PREFS, 0).getInt(KEY_TUNNEL_PORT, 0);
                String s = oldVal == 0 ? "" : String.valueOf(oldVal);
                // 顺便迁移为 String 类型
                ctx.getSharedPreferences(PREFS, 0).edit().putString(KEY_TUNNEL_PORT, s).apply();
                return s;
            } catch (Exception ex) {
                return "";
            }
        }
    }

    public static String getDownloadUrl(Context ctx) {
        return ctx.getSharedPreferences(PREFS, 0).getString(KEY_DOWNLOAD_URL, "");
    }

    public static void setDownloadUrl(Context ctx, String url) {
        ctx.getSharedPreferences(PREFS, 0).edit().putString(KEY_DOWNLOAD_URL, url).apply();
    }

    public static String getVersion(Context ctx) {
        return ctx.getSharedPreferences(PREFS, 0).getString(KEY_VERSION, "1.0");
    }

    public static void setVersion(Context ctx, String ver) {
        ctx.getSharedPreferences(PREFS, 0).edit().putString(KEY_VERSION, ver).apply();
    }

    public static void setTunnelPort(Context ctx, String port) {
        ctx.getSharedPreferences(PREFS, 0).edit().putString(KEY_TUNNEL_PORT, port == null ? "" : port).apply();
    }

    public static int getLocalPort(Context ctx) {
        return ctx.getSharedPreferences(PREFS, 0).getInt(KEY_LOCAL_PORT, 8080);
    }

    public static void setLocalPort(Context ctx, int port) {
        ctx.getSharedPreferences(PREFS, 0).edit().putInt(KEY_LOCAL_PORT, port).apply();
    }

    public static boolean isEnabled(Context ctx) {
        return ctx.getSharedPreferences(PREFS, 0).getBoolean(KEY_ENABLED, false);
    }

    public static void setEnabled(Context ctx, boolean v) {
        ctx.getSharedPreferences(PREFS, 0).edit().putBoolean(KEY_ENABLED, v).apply();
    }

    public static boolean isAutoStart(Context ctx) {
        return ctx.getSharedPreferences(PREFS, 0).getBoolean(KEY_AUTO_START, false);
    }

    public static void setAutoStart(Context ctx, boolean v) {
        ctx.getSharedPreferences(PREFS, 0).edit().putBoolean(KEY_AUTO_START, v).apply();
    }

    public static boolean hasRequiredConfig(Context ctx) {
        return !getKey(ctx).trim().isEmpty() && !getTunnelPort(ctx).trim().isEmpty();
    }

    public static String getPublicUrl(Context ctx) {
        return ctx.getSharedPreferences(PREFS, 0).getString(KEY_PUBLIC_URL, "");
    }

    public static void setPublicUrl(Context ctx, String url) {
        ctx.getSharedPreferences(PREFS, 0).edit().putString(KEY_PUBLIC_URL, url).apply();
    }

    public static String getLastError(Context ctx) {
        return ctx.getSharedPreferences(PREFS, 0).getString(KEY_LAST_ERROR, "");
    }

    public static void setLastError(Context ctx, String err) {
        ctx.getSharedPreferences(PREFS, 0).edit().putString(KEY_LAST_ERROR, err == null ? "" : err).apply();
    }
}
