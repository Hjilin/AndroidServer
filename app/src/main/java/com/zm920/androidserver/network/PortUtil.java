package com.zm920.androidserver.network;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;

/**
 * 端口可用性探测工具
 * 用一个临时 ServerSocket 短占一下目标端口，立即关闭。
 *  - 抛出异常说明端口被占用或权限不足
 *  - 成功 bind 后立即关闭（SO_REUSEADDR 避免 TIME_WAIT 阻塞）
 *
 * 注意：探测与真正 bind 之间存在 TOCTOU 窗口，极小概率下仍可能失败，
 * 此时调用方仍需处理真正的 BindException。
 */
public final class PortUtil {

    private PortUtil() {}

    public static class Result {
        public final boolean available;
        public final String reason;   // 不可用时的原因（"端口被占用"等）
        public Result(boolean available, String reason) {
            this.available = available;
            this.reason = reason;
        }
    }

    /** 默认探测 0.0.0.0:port。Android 12+ 非特权进程禁止监听 < 1024 */
    public static Result check(int port) {
        return check("0.0.0.0", port);
    }

    public static Result check(String host, int port) {
        if (port <= 0 || port > 65535) {
            return new Result(false, "端口非法");
        }
        ServerSocket probe = null;
        try {
            probe = new ServerSocket();
            probe.setReuseAddress(true);
            probe.bind(new InetSocketAddress(host, port));
            return new Result(true, "");
        } catch (java.net.BindException e) {
            return new Result(false, "端口 " + port + " 已被占用");
        } catch (SecurityException e) {
            return new Result(false, "无权限绑定端口 " + port);
        } catch (IOException e) {
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return new Result(false, "端口 " + port + " 不可用: " + msg);
        } finally {
            if (probe != null) {
                try { probe.close(); } catch (IOException ignored) {}
            }
        }
    }
}
