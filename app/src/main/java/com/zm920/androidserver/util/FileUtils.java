package com.zm920.androidserver.util;

import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;

/**
 * 安全的文件操作工具类。
 * 统一处理目录创建、文件写入等场景，避免 mkdirs() 在某些设备上静默失败导致的问题。
 */
public class FileUtils {

    private static final String TAG = "FileUtils";

    /** 安全创建目录，返回 true 表示目录已存在或创建成功 */
    public static boolean ensureDir(File dir) {
        if (dir == null) return false;
        if (dir.exists()) return true;
        boolean ok = dir.mkdirs();
        if (!ok) {
            Log.w(TAG, "mkdirs failed: " + dir.getAbsolutePath());
        }
        return dir.exists();
    }

    /** 安全创建文件（父目录不存在时自动创建） */
    public static boolean ensureFile(File file) {
        if (file == null) return false;
        if (file.exists()) return true;
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            if (!ensureDir(parent)) return false;
        }
        try {
            return file.createNewFile();
        } catch (IOException e) {
            Log.e(TAG, "createNewFile failed: " + file.getAbsolutePath(), e);
            return false;
        }
    }

    /** 安全写入 FileOutputStream（父目录不存在时自动创建） */
    public static FileOutputStream openOutputStream(File file) throws IOException {
        if (!ensureFile(file)) {
            throw new IOException("Cannot create file: " + file.getAbsolutePath());
        }
        return new FileOutputStream(file);
    }

    /** 安全写入 FileOutputStream（追加模式） */
    public static FileOutputStream openOutputStream(File file, boolean append) throws IOException {
        if (!ensureFile(file)) {
            throw new IOException("Cannot create file: " + file.getAbsolutePath());
        }
        return new FileOutputStream(file, append);
    }

    /** 安全写入 FileWriter */
    public static FileWriter openWriter(File file) throws IOException {
        if (!ensureFile(file)) {
            throw new IOException("Cannot create file: " + file.getAbsolutePath());
        }
        return new FileWriter(file);
    }
}
