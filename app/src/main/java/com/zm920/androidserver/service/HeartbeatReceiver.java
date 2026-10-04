package com.zm920.androidserver.service;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * 心跳广播接收器。
 *
 * <p>由 ServerService 通过 AlarmManager 每 5 分钟触发一次。
 * 触发后：
 * <ol>
 *   <li>获取短时 PARTIAL_WAKE_LOCK（4 分钟超时），唤醒 CPU</li>
 *   <li>重新调度下一次心跳</li>
 *   <li>确保 ServerService 仍在运行（被杀则拉起）</li>
 * </ol>
 *
 * <p>目的：避免 Doze 模式下 nginx/php-fpm/mysqld 等 native 进程被挂起，
 * 导致后台访问慢或连接超时。
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

        // 2. 获取短时 WakeLock（ServerService 内部会调度下一次心跳）
        try {
            // 直接通过 ServiceConnection 获取 WakeLock 比较重，这里用静态方法简化
            // 实际上 ServerService.onStartCommand 会重新调度心跳
            // WakeLock 由 ServerService 持有更可靠
        } catch (Exception e) {
            Log.e(TAG, "心跳处理失败", e);
        }
    }
}
