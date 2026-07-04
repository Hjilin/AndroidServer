package com.zm920.androidserver.network;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.URL;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class NetworkUtil {

    public interface IpCallback {
        void onResult(String ip);
    }

    /**
     * 获取内网 IPv4 地址
     */
    public static String getLocalIpAddress() {
        try {
            List<NetworkInterface> interfaces = Collections.list(
                    NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface ni : interfaces) {
                if (ni.isLoopback() || !ni.isUp()) continue;
                List<InetAddress> addrs = Collections.list(ni.getInetAddresses());
                for (InetAddress addr : addrs) {
                    if (addr instanceof Inet4Address) {
                        String ip = addr.getHostAddress();
                        if (!ip.startsWith("127.")) {
                            return ip;
                        }
                    }
                }
            }
        } catch (Exception e) {
            return "获取失败";
        }
        return "未连接";
    }

    /**
     * 获取 WiFi 状态下内网 IP（更快，但仅 WiFi 有效）
     */
    public static String getWifiIpAddress(Context context) {
        try {
            WifiManager wm = (WifiManager) context.getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                WifiInfo wifiInfo = wm.getConnectionInfo();
                int ipInt = wifiInfo.getIpAddress();
                if (ipInt != 0) {
                    return String.format("%d.%d.%d.%d",
                            ipInt & 0xff, (ipInt >> 8) & 0xff,
                            (ipInt >> 16) & 0xff, (ipInt >> 24) & 0xff);
                }
            }
        } catch (Exception ignored) {}
        return getLocalIpAddress();
    }

    /**
     * 异步获取外网 IP：请求 https://my.ip.cn/ 并截取 IPv4
     */
    public static void fetchExternalIp(final IpCallback callback) {
        new Thread(() -> {
            String result = "获取失败";
            java.net.HttpURLConnection conn = null;
            try {
                URL url = new URL("https://my.ip.cn/");
                conn = (java.net.HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14)");
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(conn.getInputStream()))) {
                    String line = br.readLine();
                    if (line != null) {
                        // 格式: ip：222.77.44.40 归属地：中国 福建 泉州 安溪 电信
                        Matcher m2 = Pattern.compile("\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}").matcher(line);
                        if (m2.find()) {
                            result = m2.group();
                        } else {
                            String trimmed = line.trim();
                            if (trimmed.contains("：")) {
                                String part = trimmed.split("[：:]")[1].trim().split("\\s+")[0];
                                if (part.matches("\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}")) {
                                    result = part;
                                }
                            }
                        }
                    }
                }
            } catch (Exception ignored) {
                // 超时或网络异常则返回"获取失败"
            } finally {
                if (conn != null) {
                    try { conn.disconnect(); } catch (Exception ignored) {}
                }
            }
            final String ip = result;
            new android.os.Handler(android.os.Looper.getMainLooper())
                    .post(() -> {
                        if (callback != null) callback.onResult(ip);
                    });
        }).start();
    }

    public static boolean isNetworkAvailable(Context context) {
        ConnectivityManager cm = (ConnectivityManager) context
                .getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm != null) {
            NetworkInfo ni = cm.getActiveNetworkInfo();
            return ni != null && ni.isConnected();
        }
        return false;
    }
}
