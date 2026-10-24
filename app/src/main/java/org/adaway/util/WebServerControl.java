package org.adaway.util;

import androidx.annotation.Nullable;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import timber.log.Timber;

/**
 * AdBlock web server 控制命令（/control endpoint）。
 * 与统计同栈：toybox nc + IPv6 loopback（::1）优先（规避 v4 DNAT 劫持）。
 */
public final class WebServerControl {
    private static final String GENERATION_HEADER = "X-AdAway-Config-Generation";
    private static final String CONFIG_ACK = "OK: block config reloaded";

    private WebServerControl() {}

    /**
     * 发送控制命令（reload_images / flush_stats / shutdown）。
     *
     * @param cmd 命令名
     * @return 响应 body 字符串，或失败返回 null
     */
    @Nullable
    public static String sendControlCommand(String cmd) {
        Reply reply = send(cmd, WebServerUtils.getStatsHttpPort());
        return reply == null ? null : reply.body;
    }

    public static boolean reloadConfig(String generation) {
        return reloadConfig(generation, WebServerUtils.getStatsHttpPort());
    }

    /** Local management endpoint, also usable on an isolated ephemeral port. */
    public static boolean reloadConfig(String generation, int port) {
        if (!ContentResponseSettings.validGeneration(generation)) return false;
        Reply reply = send("reload_config", port);
        return reply != null && CONFIG_ACK.equals(reply.body) && generation.equals(reply.generation);
    }

    private static final class Reply {
        final String body;
        final String generation;
        Reply(String body, String generation) { this.body = body; this.generation = generation; }
    }

    @Nullable
    private static Reply send(String cmd, int port) {
        if (port < 1 || port > 65535 || !cmd.matches("[a-z_]{1,31}")) return null;
        String body = "cmd=" + cmd;
        String request = "POST /control HTTP/1.1\r\n" +
                "Host: adaway\r\n" +
                "Content-Type: application/x-www-form-urlencoded\r\n" +
                "Content-Length: " + body.length() + "\r\n" +
                "Connection: close\r\n\r\n" +
                body;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        for (String host : new String[]{"::1", "127.0.0.1"}) {
            if (Thread.currentThread().isInterrupted()) return null;
            String response = WebServerNet.ncRequest("/system/bin/toybox", "nc", host, port, request,
                    Math.min(1000, remainingMillis(deadline)));
            String parsed = WebServerNet.bodyFromResponse(response);
            if (parsed != null) return new Reply(parsed, generationFromResponse(response));
        }
        // Some ROM nc implementations truncate responses. Use a direct, proxy-free
        // HTTP client within the same overall budget; IPv4 always gets a share.
        return sendHttp(body, port, deadline);
    }

    private static long remainingMillis(long deadline) {
        return Math.max(0, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()));
    }

    @Nullable
    private static String generationFromResponse(String response) {
        int end = response.indexOf("\r\n\r\n");
        String generation = null;
        for (String line : response.substring(0, end).split("\r\n")) {
            int colon = line.indexOf(':');
            if (colon > 0 && line.substring(0, colon).equalsIgnoreCase(GENERATION_HEADER)) {
                if (generation != null) return null;
                generation = line.substring(colon + 1).trim();
            }
        }
        return generation;
    }

    @Nullable
    private static Reply sendHttp(String body, int port, long deadline) {
        for (int index = 0; index < WebServerNet.HTTP_HOSTS.length; index++) {
            long remaining = remainingMillis(deadline);
            if (remaining <= 0 || Thread.currentThread().isInterrupted()) return null;
            OkHttpClient client = WebServerNet.newLocalClient().newBuilder()
                    .callTimeout(Math.max(1, remaining / (WebServerNet.HTTP_HOSTS.length - index)), TimeUnit.MILLISECONDS).build();
            // This is a local-only management transport; TLS trust is unchanged.
            Request request = new Request.Builder().url("http" + "://" + WebServerNet.HTTP_HOSTS[index] + ":" + port + "/control")
                    .post(RequestBody.create(body, MediaType.get("application/x-www-form-urlencoded"))).build();
            try (Response response = client.newCall(request).execute()) {
                if (response.isSuccessful() && response.body() != null) {
                    String result = BoundedProcessResponse.read(response.body().byteStream(), 8192);
                    String generation = response.headers(GENERATION_HEADER).size() == 1 ? response.header(GENERATION_HEADER) : null;
                    return new Reply(result, generation);
                }
            } catch (IOException error) { Timber.d(error, "Local control transport unavailable"); }
        }
        return null;
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
