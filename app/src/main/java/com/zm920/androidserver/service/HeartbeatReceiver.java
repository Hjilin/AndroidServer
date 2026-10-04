package com.zm920.androidserver.service;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * 心跳广播接收器。
 *
 * <p>由 ServerService 通过 AlarmManager 每 2 分钟触发一次。
 * 触发后：
 * <ol>
 *   <li>确保 ServerService 仍在运行（被杀则拉起）</li>
 *   <li>插件保活：把标记为"应运行"但已死的插件自动重启</li>
 *   <li>健康检查：auto_start 开启的原生组件掉线留日志</li>
 * </ol>
 *
 * <p>目的：避免 Doze 模式下 nginx/php-fpm/mysqld 等 native 进程被挂起，
 * 并自动恢复被杀的服务进程。
 */
public class HeartbeatReceiver extends BroadcastReceiver {

    private static final String TAG = "HeartbeatReceiver";
    public static final String ACTION_HEARTBEAT = "com.zm920.androidserver.action.HEARTBEAT";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION_HEARTBEAT.equals(intent.getAction())) return;
        Log.i(TAG, "心跳触发");

        // 1. 确保 ServerService 仍在运行
        try {
            Intent serviceIntent = new Intent(context, ServerService.class);
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent);
            } else {
                context.startService(serviceIntent);
            }
        } catch (Exception e) {
            Log.e(TAG, "拉起 ServerService 失败", e);
        }

        // 2. 插件保活：把标记为"应运行"但已死的插件自动重启
        try {
            com.zm920.androidserver.plugin.PluginManager pm =
                    com.zm920.androidserver.plugin.PluginManager.getInstance(context);
            int revived = pm.restartEnabled();
            if (revived > 0) {
                Log.i(TAG, "插件保活：已自动重启 " + revived + " 个插件");
            }
        } catch (Exception e) {
            Log.e(TAG, "插件保活失败", e);
        }

        // 3. 健康检查：auto_start 开启的原生组件是否掉线（nginx/mariadb 为 App 子进程，
        //    由 ServerService 前台服务+心跳保 App 进程；此处仅留日志便于诊断）
        try {
            android.content.SharedPreferences sp =
                    context.getSharedPreferences("server_settings", android.content.Context.MODE_PRIVATE);
            boolean autoNginx = sp.getBoolean("auto_start_nginx", false) && sp.contains("active_nginx");
            boolean autoMariadb = sp.getBoolean("auto_start_mariadb", false) && sp.contains("active_mariadb");
            if (autoNginx && !portOpen(8080)) {
                Log.w(TAG, "健康检查：nginx(8080) 应运行但掉线，等待下次界面刷新由 auto_start 恢复");
            }
            if (autoMariadb && !portOpen(3306)) {
                Log.w(TAG, "健康检查：mariadb(3306) 应运行但掉线，等待下次界面刷新由 auto_start 恢复");
            }
        } catch (Exception ignored) {}
    }

    private boolean portOpen(int port) {
        try (java.net.Socket s = new java.net.Socket()) {
            s.connect(new java.net.InetSocketAddress("127.0.0.1", port), 500);
            return true;
        } catch (Exception e) { return false; }
    }
}
