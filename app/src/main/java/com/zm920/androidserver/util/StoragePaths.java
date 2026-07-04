package com.zm920.androidserver.util;

import android.os.Environment;

public class StoragePaths {
    public static String getExternalRoot() {
        return Environment.getExternalStorageDirectory().getAbsolutePath();
    }

    public static String getDefaultWwwRoot() {
        return getExternalRoot() + "/wwwroot/www";
    }

    public static String getSampleProjectPath() {
        return getExternalRoot() + "/project/public";
    }

    /**
     * 从 data_dir 反推基础设施根目录（父目录的 wwwroot）。
     * data_dir 形如 "/sdcard/xxx/wwwroot/www"，返回 "/sdcard/xxx/wwwroot"。
     * 若 data_dir 不符合预期格式，回退到默认基础设施根目录。
     */
    public static String getInfraRoot(String dataDir) {
        if (dataDir == null) dataDir = getDefaultWwwRoot();
        String suffix = "/wwwroot/www";
        if (dataDir.endsWith(suffix)) {
            return dataDir.substring(0, dataDir.length() - suffix.length()) + "/wwwroot";
        }
        // 兼容旧值或异常值：使用默认基础设施根目录
        return getExternalRoot() + "/wwwroot";
    }
}
