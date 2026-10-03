package com.zm920.androidserver;

import android.app.Application;
import android.os.Build;
import android.os.Environment;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import com.umeng.commonsdk.UMConfigure;
import com.umeng.analytics.MobclickAgent;

public class App extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        // 全局崩溃捕获：把崩溃堆栈写入 filesDir/runtime_logs/crash.log，便于导出定位
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            try {
                File dir = new File(getFilesDir(), "runtime_logs");
                dir.mkdirs();
                File f = new File(dir, "crash.log");
                StringWriter sw = new StringWriter();
                PrintWriter pw = new PrintWriter(sw);
                pw.println("=== Crash @ " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date()));
                pw.println("Thread: " + thread.getName());
                pw.println("SDK: " + Build.VERSION.SDK_INT + " / " + Build.MODEL);
                throwable.printStackTrace(pw);
                pw.println("=== End ===");
                try (FileOutputStream fos = new FileOutputStream(f, true)) {
                    fos.write(sw.toString().getBytes("UTF-8"));
                }
            } catch (Throwable ignored) {
            }
            // 继续走默认流程（可被 Android 接管重启）
            android.os.Process.killProcess(android.os.Process.myPid());
        });
        // 友盟统计
        //UMConfigure.init(this, "6a338cbacbfa695951600810", "AndroidServer", UMConfigure.DEVICE_TYPE_PHONE, "");
       //MobclickAgent.setPageCollectionMode(MobclickAgent.PageMode.AUTO);
    }
}
