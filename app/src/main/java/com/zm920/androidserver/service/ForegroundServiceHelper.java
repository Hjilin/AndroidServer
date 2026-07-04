package com.zm920.androidserver.service;

import android.app.Notification;
import android.app.Service;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 前台服务启动兼容工具。
 *
 * <p>Android 14 (API 34) 强制要求 {@link Service#startForeground(int, Notification)}
 * 必须传入 {@code foregroundServiceType}，否则抛出 {@code MissingForegroundServiceTypeException}。
 *
 * <p>Android 14 同时限制 {@code FOREGROUND_SERVICE_TYPE_DATA_SYNC} 类型
 * 每 24 小时累计最多运行 6 小时。本工具提供定时重启机制：
 * 每 5 小时 50 分钟主动 stopForeground + startForeground，重置系统计时器。
 *
 * <p>使用方式：
 * <pre>
 * public class MyService extends Service {
 *     private static final int NOTIFICATION_ID = 1001;
 *
 *     private void startForegroundInternal() {
 *         ForegroundServiceHelper.startForegroundWithAutoRestart(
 *             this, NOTIFICATION_ID, () -> buildNotification());
 *     }
 *
 *     private Notification buildNotification() {
 *         return new NotificationCompat.Builder(this, CHANNEL_ID)
 *             .setContentTitle("My Service")
 *             .setContentText("Running")
 *             .setSmallIcon(...)
 *             .build();
 *     }
 *
 *     &#64;Override
 *     public void onDestroy() {
 *         ForegroundServiceHelper.cancelAutoRestart(this);
 *         super.onDestroy();
 *     }
 * }
 * </pre>
 */
public final class ForegroundServiceHelper {

    private static final String TAG = "ForegroundServiceHelper";

    /**
     * 重启间隔：5 小时 50 分钟（比 Android 14 的 6 小时限制少 10 分钟）。
     * 计算：5 * 60 * 60 * 1000 + 50 * 60 * 1000 = 21,000,000 ms = 350 分钟。
     */
    private static final long RESTART_INTERVAL_MS = 5L * 60L * 60L * 1000L + 50L * 60L * 1000L;

    /** stopForeground 后重新 startForeground 的延迟（毫秒） */
    private static final long RESTART_DELAY_MS = 1500L;

    /** 静态 Handler，所有 Service 共用，运行在主线程 */
    private static final Handler HANDLER = new Handler(Looper.getMainLooper());

    /** Service -> Runnable 映射，用于取消定时器，防止内存泄漏 */
    private static final Map<Service, Runnable> RESTART_TASKS = new ConcurrentHashMap<>();

    private ForegroundServiceHelper() {}

    /**
     * 通知提供者接口。每次重启前台状态时会调用 {@link #getNotification()}
     * 获取最新通知，避免通知内容过期（如端口、客户端数、公网 URL 等）。
     */
    public interface NotificationProvider {
        Notification getNotification();
    }

    /**
     * 启动前台服务并设置定时重启。
     *
     * @param service   Service 实例
     * @param id        通知 ID
     * @param provider  通知提供者（每次重启时调用获取最新通知）
     */
    public static void startForegroundWithAutoRestart(Service service, int id, NotificationProvider provider) {
        if (service == null || provider == null) return;
        startForeground(service, id, provider.getNotification());
        scheduleRestart(service, id, provider);
    }

    /**
     * 启动前台服务，自动适配 Android 14+ 的类型参数要求。
     *
     * <p>Android 7.0-13：调用两参数版本，行为不变。
     * <br>Android 14+：调用三参数版本，传入 {@code FOREGROUND_SERVICE_TYPE_DATA_SYNC}。
     *
     * @param service     Service 实例
     * @param id          通知 ID
     * @param notification 通知对象
     */
    public static void startForeground(Service service, int id, Notification notification) {
        if (service == null || notification == null) return;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // Android 14+ 必须传 foregroundServiceType
                service.startForeground(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                // Android 7.0-13 使用两参数版本
                service.startForeground(id, notification);
            }
        } catch (Exception e) {
            // Android 13+ 未授权通知权限时 startForeground 可能抛异常
            // 降级为普通服务（系统仍会保持进程存活）
            Log.e(TAG, "startForeground failed, fallback to stopForeground", e);
            try {
                service.stopForeground(Service.STOP_FOREGROUND_REMOVE);
            } catch (Exception ignored) {}
        }
    }

    /**
     * 设置定时重启任务。
     *
     * <p>定时器触发时：
     * <ol>
     *   <li>调用 {@code stopForeground(STOP_FOREGROUND_DETACH)} 降级为普通服务</li>
     *   <li>延迟 1.5 秒后重新调用 {@code startForeground} 升级为前台服务</li>
     *   <li>重置定时器，准备下一轮重启</li>
     * </ol>
     *
     * <p>注意：Handler 在 Doze 模式下可能延迟触发，但 Android 14 的
     * {@code onTimeout} 回调会兜底（Service 中需重写该方法）。
     */
    private static void scheduleRestart(Service service, int id, NotificationProvider provider) {
        if (service == null || provider == null) return;
        cancelAutoRestart(service);

        Runnable task = new Runnable() {
            @Override
            public void run() {
                Log.i(TAG, "定时重启前台状态: " + service.getClass().getSimpleName());
                try {
                    // 1. 降级为普通服务（通知消失，但服务继续运行）
                    service.stopForeground(Service.STOP_FOREGROUND_DETACH);
                } catch (Exception e) {
                    Log.e(TAG, "stopForeground failed", e);
                }

                // 2. 短暂延迟后重新升级为前台服务（重置 Android 14 计时器）
                HANDLER.postDelayed(() -> {
                    try {
                        startForeground(service, id, provider.getNotification());
                        // 3. 重新设置定时器，准备下一轮
                        scheduleRestart(service, id, provider);
                    } catch (Exception e) {
                        Log.e(TAG, "restartForeground failed", e);
                    }
                }, RESTART_DELAY_MS);
            }
        };

        RESTART_TASKS.put(service, task);
        HANDLER.postDelayed(task, RESTART_INTERVAL_MS);
        Log.i(TAG, "已设置定时重启: " + service.getClass().getSimpleName()
                + "，间隔 " + (RESTART_INTERVAL_MS / 60000) + " 分钟");
    }

    /**
     * 取消定时重启任务。Service 销毁时必须调用，防止内存泄漏。
     *
     * @param service Service 实例
     */
    public static void cancelAutoRestart(Service service) {
        if (service == null) return;
        Runnable task = RESTART_TASKS.remove(service);
        if (task != null) {
            HANDLER.removeCallbacks(task);
            Log.i(TAG, "已取消定时重启: " + service.getClass().getSimpleName());
        }
    }
}
