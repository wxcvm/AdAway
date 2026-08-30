package org.adaway.util;

import androidx.annotation.Nullable;
import timber.log.Timber;

/**
 * AdBlock web server 控制命令（/control endpoint）。
 * 与统计同栈：toybox nc + IPv6 loopback（::1）优先（规避 v4 DNAT 劫持）。
 */
public final class WebServerControl {

    private WebServerControl() {}

    /**
     * 发送控制命令（reload_images / flush_stats / shutdown）。
     *
     * @param cmd 命令名
     * @return 响应 body 字符串，或失败返回 null
     */
    @Nullable
    public static String sendControlCommand(String cmd) {
        String body = "cmd=" + cmd;
        String request = "POST /control HTTP/1.1\r\n" +
                "Host: adaway\r\n" +
                "Content-Type: application/x-www-form-urlencoded\r\n" +
                "Content-Length: " + body.length() + "\r\n" +
                "Connection: close\r\n\r\n" +
                body;
        String response = WebServerNet.ncRequest(
                "/system/bin/toybox", "nc", "::1",
                WebServerUtils.getStatsHttpPort(), request);
        if (response == null) {
            Timber.w("Failed to send control command: %s", cmd);
            return null;
        }
        return WebServerNet.bodyFromResponse(response);
    }

    /**
     * Reload block-placeholder images from the resource directory.
     *
     * @return true if server acknowledged the reload
     */
    public static boolean reloadImages() {
        String resp = sendControlCommand("reload_images");
        return resp != null && resp.startsWith("OK:");
    }

    /**
     * Force-flush all statistics to disk.
     *
     * @return true if server acknowledged the flush
     */
    public static boolean flushStats() {
        String resp = sendControlCommand("flush_stats");
        return resp != null && resp.startsWith("OK:");
    }

    /**
     * Gracefully shut down the web server process.
     *
     * @return true if server acknowledged the shutdown
     */
    public static boolean shutdown() {
        String resp = sendControlCommand("shutdown");
        return resp != null && resp.startsWith("OK:");
    }
}