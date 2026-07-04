package com.zm920.androidserver.service;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.zm920.androidserver.MainActivity;

/**
 * 网络变化广播接收器。
 *
 * <p>监听网络切换（WiFi ↔ 4G、AP 切换、飞行模式等），
 * 触发后延迟 3 秒重启 nginx，确保监听 socket 重新绑定到新网络接口。
 *
 * <p>注册方式：AndroidManifest.xml 中静态注册。
 */
public class NetworkChangeReceiver extends BroadcastReceiver {

    private static final String TAG = "NetworkChangeReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (!ConnectivityManager.CONNECTIVITY_ACTION.equals(action)) return;

        Log.i(TAG, "网络变化: " + action);

        // 延迟 3 秒重启 nginx，等待网络稳定
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try {
                if (MainActivity.webServerRef != null && MainActivity.webServerRef.isRunning()) {
                    Log.i(TAG, "重启 nginx 以适配新网络");
                    MainActivity.webServerRef.restart();
                }
            } catch (Exception e) {
                Log.e(TAG, "重启 nginx 失败", e);
            }
        }, 3000);
    }
}
