package com.zm920.androidserver.update;

import android.content.Context;
import android.content.pm.PackageManager;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

public class UpdateChecker {

    private static final String TAG = "UpdateChecker";
    private static final String UPDATE_URL =
            "https://gitee.com/huang_songyuan/protocol-download/releases/download/%E5%8D%8F%E8%AE%AE2/update.json";
    private static final int CONNECT_TIMEOUT = 8000;
    private static final int READ_TIMEOUT = 8000;

    public static class UpdateInfo {
        public int versionCode;
        public String versionName;
        public String releaseDate;
        public String downloadUrl;
        public String fileSize;
        public String md5;
        public boolean forceUpdate;
        public List<String> changelog;
    }

    public interface Callback {
        void onResult(UpdateInfo info);
        void onError(String msg);
    }

    public static void check(final Context ctx, final Callback cb) {
        new Thread(() -> {
            try {
                URL url = new URL(UPDATE_URL);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(CONNECT_TIMEOUT);
                conn.setReadTimeout(READ_TIMEOUT);
                conn.setRequestProperty("Accept", "application/json");

                int code = conn.getResponseCode();
                if (code != 200) {
                    postError(cb, "服务器返回: " + code);
                    return;
                }

                BufferedReader br = new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), "UTF-8"));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
                br.close();
                conn.disconnect();

                JSONObject json = new JSONObject(sb.toString());
                UpdateInfo info = new UpdateInfo();
                info.versionCode = json.optInt("versionCode", 0);
                info.versionName = json.optString("versionName", "");
                info.releaseDate = json.optString("releaseDate", "");
                info.downloadUrl = json.optString("downloadUrl", "");
                info.fileSize = json.optString("fileSize", "");
                info.md5 = json.optString("md5", "");
                info.forceUpdate = json.optBoolean("forceUpdate", false);

                JSONArray ja = json.optJSONArray("changelog");
                if (ja != null) {
                    info.changelog = new ArrayList<>();
                    for (int i = 0; i < ja.length(); i++) {
                        info.changelog.add(ja.optString(i, ""));
                    }
                }

                postResult(cb, info);
            } catch (Exception e) {
                Log.w(TAG, "check update error", e);
                postError(cb, e.getMessage() == null ? "网络错误" : e.getMessage());
            }
        }).start();
    }

    public static int getLocalVersionCode(Context ctx) {
        try {
            return ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionCode;
        } catch (PackageManager.NameNotFoundException e) {
            return 0;
        }
    }

    public static String getLocalVersionName(Context ctx) {
        try {
            return ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "unknown";
        }
    }

    private static void postResult(final Callback cb, final UpdateInfo info) {
        if (cb != null) {
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> cb.onResult(info));
        }
    }

    private static void postError(final Callback cb, final String msg) {
        if (cb != null) {
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> cb.onError(msg));
        }
    }
}
