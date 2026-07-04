package com.zm920.androidserver;

import android.app.Application;
import com.umeng.commonsdk.UMConfigure;
import com.umeng.analytics.MobclickAgent;

public class App extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        // 友盟统计
        //UMConfigure.init(this, "6a338cbacbfa695951600810", "AndroidServer", UMConfigure.DEVICE_TYPE_PHONE, "");
       //MobclickAgent.setPageCollectionMode(MobclickAgent.PageMode.AUTO);
    }
}
