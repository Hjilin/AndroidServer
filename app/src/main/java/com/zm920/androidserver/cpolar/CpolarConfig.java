package com.zm920.androidserver.cpolar;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * cpolar 配置管理。SharedPreferences: server_settings
 * <ul>
 *   <li>cpolar_token         必填，用户从 cpolar.com 拿；首次安装默认为空，需在设置页填写</li>
 *   <li>cpolar_region        cn / cn_top / cn_vip / us，默认 cn</li>
 *   <li>cpolar_target_port   目标本地端口，默认 8080（nginx）</li>
 *   <li>cpolar_proto         http / tcp，默认 http</li>
 *   <li>cpolar_log_level     DEBUG / INFO / WARNING / ERROR，默认 INFO</li>
 *   <li>cpolar_enabled       是否启用（用户 UI 勾选）</li>
 *   <li>cpolar_public_url    最近一次成功分配的公网 URL</li>
 *   <li>cpolar_local_path    cpolar 二进制在 filesDir 下的路径</li>
 * </ul>
 */
public class CpolarConfig {

    private static final String PREFS = "server_settings";
    public static final String KEY_TOKEN = "cpolar_token";
    public static final String KEY_REGION = "cpolar_region";
    public static final String KEY_PORT = "cpolar_target_port";
    public static final String KEY_PROTO = "cpolar_proto";
    public static final String KEY_LOG_LEVEL = "cpolar_log_level";
    public static final String KEY_ENABLED = "cpolar_enabled";
    public static final String KEY_PUBLIC_URL = "cpolar_public_url";
    public static final String KEY_LAST_ERROR = "cpolar_last_error";

    /** 默认 token 留空：由用户自行在「设置」页填写。
     *  之前曾内嵌一个测通 token，但 token 不应进源码。
     */
    private static final String DEFAULT_TOKEN = "";

    public static String getToken(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, 0);
        return p.getString(KEY_TOKEN, "");
    }

    public static void setToken(Context ctx, String token) {
        ctx.getSharedPreferences(PREFS, 0).edit().putString(KEY_TOKEN, token).apply();
    }

    public static String getRegion(Context ctx) {
        return ctx.getSharedPreferences(PREFS, 0).getString(KEY_REGION, "cn");
    }

    public static void setRegion(Context ctx, String region) {
        ctx.getSharedPreferences(PREFS, 0).edit().putString(KEY_REGION, region).apply();
    }

    public static int getTargetPort(Context ctx) {
        return ctx.getSharedPreferences(PREFS, 0).getInt(KEY_PORT, 8080);
    }

    public static void setTargetPort(Context ctx, int port) {
        ctx.getSharedPreferences(PREFS, 0).edit().putInt(KEY_PORT, port).apply();
    }

    public static String getProto(Context ctx) {
        return ctx.getSharedPreferences(PREFS, 0).getString(KEY_PROTO, "http");
    }

    public static void setProto(Context ctx, String proto) {
        ctx.getSharedPreferences(PREFS, 0).edit().putString(KEY_PROTO, proto).apply();
    }

    public static String getLogLevel(Context ctx) {
        return ctx.getSharedPreferences(PREFS, 0).getString(KEY_LOG_LEVEL, "INFO");
    }

    public static void setLogLevel(Context ctx, String level) {
        ctx.getSharedPreferences(PREFS, 0).edit().putString(KEY_LOG_LEVEL, level).apply();
    }

    public static boolean isEnabled(Context ctx) {
        return ctx.getSharedPreferences(PREFS, 0).getBoolean(KEY_ENABLED, false);
    }

    public static void setEnabled(Context ctx, boolean v) {
        ctx.getSharedPreferences(PREFS, 0).edit().putBoolean(KEY_ENABLED, v).apply();
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

    /**
     * 首次安装兼容入口：DEFAULT_TOKEN 现已留空，新装时不会写入任何值；
     * 老用户已存的 token 也不会被覆盖。
     * 保留此方法仅用于以后若需要再内置默认 token 时能快速恢复。
     */
    public static void ensureDefaultToken(Context ctx) {
        if (!DEFAULT_TOKEN.isEmpty() && getToken(ctx).isEmpty()) {
            setToken(ctx, DEFAULT_TOKEN);
        }
    }
}
