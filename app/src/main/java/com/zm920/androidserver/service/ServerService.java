package com.zm920.androidserver.service;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

public class ServerService extends Service {

    private static final String CHANNEL_ID = "server_service_channel";
    private static final int NOTIFICATION_ID = 1001;

    /** 心跳间隔：5 分钟。AlarmManager 定时唤醒 CPU，避免 Doze 挂起 native 进程 */
    private static final long HEARTBEAT_INTERVAL_MS = 5L * 60L * 1000L;
    /** WakeLock 超时：4 分钟（小于心跳间隔），防止忘记释放导致耗电 */
    private static final long HEARTBEAT_WAKELOCK_TIMEOUT_MS = 4L * 60L * 1000L;

    private PowerManager.WakeLock heartbeatWakeLock;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Android 14+ 自动传入 foregroundServiceType，低版本行为不变
        // 每 5h50min 自动重启前台状态，避免触发 Android 14 dataSync 6 小时限制
        ForegroundServiceHelper.startForegroundWithAutoRestart(
                this, NOTIFICATION_ID, () -> buildNotification());
        // 启动心跳：定时唤醒 CPU，避免 Doze 模式下 nginx/php-fpm 被挂起
        scheduleHeartbeat();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        // 取消定时重启，防止内存泄漏
        ForegroundServiceHelper.cancelAutoRestart(this);
        // 取消心跳
        cancelHeartbeat();
        // 释放 WakeLock
        releaseHeartbeatWakeLock();
        super.onDestroy();
    }

    /**
     * 启动心跳 Alarm：每 5 分钟唤醒一次 CPU。
     * 唤醒后通过 BroadcastReceiver 持有短时 WakeLock，让 nginx/php-fpm 处理积压请求。
     */
    private void scheduleHeartbeat() {
        try {
            AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            Intent intent = new Intent(this, HeartbeatReceiver.class);
            intent.setAction(HeartbeatReceiver.ACTION_HEARTBEAT);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                flags |= PendingIntent.FLAG_IMMUTABLE;
            }
            PendingIntent pi = PendingIntent.getBroadcast(this, 1002, intent, flags);
            long triggerAt = SystemClock.elapsedRealtime() + HEARTBEAT_INTERVAL_MS;
            // 使用 setExactAndAllowWhileIdle 确保 Doze 下也能唤醒
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi);
            } else {
                am.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi);
            }
            android.util.Log.i("ServerService", "心跳已调度，间隔 " + (HEARTBEAT_INTERVAL_MS / 60000) + " 分钟");
        } catch (Exception e) {
            android.util.Log.e("ServerService", "调度心跳失败", e);
        }
    }

    private void cancelHeartbeat() {
        try {
            AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            Intent intent = new Intent(this, HeartbeatReceiver.class);
            intent.setAction(HeartbeatReceiver.ACTION_HEARTBEAT);
            int flags = PendingIntent.FLAG_NO_CREATE;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                flags |= PendingIntent.FLAG_IMMUTABLE;
            }
            PendingIntent pi = PendingIntent.getBroadcast(this, 1002, intent, flags);
            if (pi != null) {
                am.cancel(pi);
                pi.cancel();
            }
        } catch (Exception e) {
            android.util.Log.w("ServerService", "取消心跳失败", e);
        }
    }

    /** 持有短时 WakeLock（由 HeartbeatReceiver 调用） */
    void acquireHeartbeatWakeLock() {
        try {
            if (heartbeatWakeLock == null) {
                PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                if (pm == null) return;
                heartbeatWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ServerService:Heartbeat");
                heartbeatWakeLock.setReferenceCounted(false);
            }
            if (!heartbeatWakeLock.isHeld()) {
                heartbeatWakeLock.acquire(HEARTBEAT_WAKELOCK_TIMEOUT_MS);
                android.util.Log.i("ServerService", "心跳 WakeLock 已获取，超时 " + (HEARTBEAT_WAKELOCK_TIMEOUT_MS / 60000) + " 分钟");
            }
        } catch (Exception e) {
            android.util.Log.e("ServerService", "获取 WakeLock 失败", e);
        }
    }

    private void releaseHeartbeatWakeLock() {
        try {
            if (heartbeatWakeLock != null && heartbeatWakeLock.isHeld()) {
                heartbeatWakeLock.release();
                android.util.Log.i("ServerService", "心跳 WakeLock 已释放");
            }
        } catch (Exception e) {
            android.util.Log.w("ServerService", "释放 WakeLock 失败", e);
        }
    }

    /**
     * Android 14+ 兜底：前台服务即将被系统强制停止时回调。
     * 重新启动服务，保证服务持续运行。
     */
    @Override
    public void onTimeout(int startId) {
        android.util.Log.w("ServerService", "前台服务超时，重新启动");
        try {
            Intent restartIntent = new Intent(getApplicationContext(), ServerService.class);
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                startForegroundService(restartIntent);
            } else {
                startService(restartIntent);
            }
        } catch (Exception e) {
            android.util.Log.e("ServerService", "重启服务失败", e);
        }
        super.onTimeout(startId);
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "服务器服务",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("服务器后台运行通知");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private Notification buildNotification() {
        // 点击通知打开主界面
        Intent tapIntent = new Intent(this, com.zm920.androidserver.MainActivity.class);
        tapIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        android.app.PendingIntent pendingIntent = android.app.PendingIntent.getActivity(
                this, 0, tapIntent,
                android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S
                        ? android.app.PendingIntent.FLAG_IMMUTABLE
                        : 0
        );

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("简云plus服务器管理")
                .setContentText("服务运行中")
                .setSmallIcon(android.R.drawable.ic_menu_manage)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setContentIntent(pendingIntent)
                .build();
    }
}
