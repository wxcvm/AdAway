package org.adaway.util;

import androidx.annotation.Nullable;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
        String body = buildControlBody(cmd, readControlToken());
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        String request = "POST /control HTTP/1.1\r\n" +
                "Host: adaway\r\n" +
                "Content-Type: application/x-www-form-urlencoded\r\n" +
                "Content-Length: " + payload.length + "\r\n" +
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
     * Builds the urlencoded {@code /control} body.
     *
     * <p>Package-visible so it can be unit tested: the server refuses the call
     * with 403 unless the per-run token it wrote to {@code control_token.txt}
     * is present, and this is the only place that token is attached.</p>
     */
    static String buildControlBody(String cmd, @Nullable String token) {
        if (token == null || token.isEmpty()) {
            return "cmd=" + cmd;
        }
        return "cmd=" + cmd + "&token=" + token;
    }

    /**
     * Parses the contents of {@code control_token.txt}.
     *
     * @return the 32 hex characters the server writes, or {@code null} when the
     *         content is missing or is not such a token
     */
    @Nullable
    static String parseToken(@Nullable String fileContents) {
        if (fileContents == null) {
            return null;
        }
        String token = fileContents.trim();
        if (token.length() != 32) {
            return null;
        }
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            boolean hex = (c >= '0' && c <= '9')
                    || (c >= 'a' && c <= 'f')
                    || (c >= 'A' && c <= 'F');
            if (!hex) {
                return null;
            }
        }
        return token;
    }

    /**
     * Reads the per-run control token the native server dropped into the
     * resource directory.
     *
     * @return the token, or {@code null} when it cannot be read (the request is
     *         then sent without one and the server answers 403)
     */
    @Nullable
    private static String readControlToken() {
        String dir = WebServerUtils.getResourcePathCached();
        if (dir == null || dir.isEmpty()) {
            return null;
        }
        File tokenFile = new File(dir, "control_token.txt");
        if (!tokenFile.isFile()) {
            return null;
        }
        try {
            byte[] raw = Files.readAllBytes(tokenFile.toPath());
            return parseToken(new String(raw, StandardCharsets.UTF_8));
        } catch (Exception e) {
            Timber.w(e, "Failed to read the web server control token");
            return null;
        }
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