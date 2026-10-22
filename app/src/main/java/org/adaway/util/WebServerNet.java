package org.adaway.util;

import androidx.annotation.Nullable;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import timber.log.Timber;

/**
 * 本地 HTTP 网络原语（探活 / 统计 / 控制命令共用）。
 *
 * 历史踩坑（务必保留）：
 *  1. OkHttp 探活必须代理 NO_PROXY、3s 超时，否则系统代理会拦截 loopback 请求。
 *  2. 部分 ROM 会残留 DNAT 规则劫持 127.0.0.1:80（本机曾遇到
 *     127.0.0.1:80 -> 127.0.0.1:12121 且 12121 无监听，v4 直连直接
 *     Connection refused）。IPv6 loopback [::1] 不受该规则影响，
 *     因此所有宿主列表一律 [::1] 优先、127.0.0.1 兜底。
 *  3. toybox nc 子进程的 stderr pipe 必须被排空，否则 ~64KB 管道
 *     写满后子进程阻塞在 write()，waitFor() 会超时（即使请求已成功）。
 */
public final class WebServerNet {

    // Supports bounded 1,000-row history pages and the native 96 KiB statistics document.
    static final int MAX_RESPONSE_BYTES = 1024 * 1024;

    private WebServerNet() {}

    /** OkHttp 探测宿主优先级：IPv6 loopback 优先。 */
    static final String[] HTTP_HOSTS = {"[::1]", "127.0.0.1"};

    /** 构造本地 OkHttp 客户端（NO_PROXY + 3s 超时）。 */
    static OkHttpClient newLocalClient() {
        return new OkHttpClient.Builder()
                .proxy(java.net.Proxy.NO_PROXY)
                .connectTimeout(3, TimeUnit.SECONDS)
                .readTimeout(3, TimeUnit.SECONDS)
                .callTimeout(3, TimeUnit.SECONDS)
                .build();
    }

    /** 依次尝试多个本地地址（IPv6 优先），返回首个成功响应体；全部失败返回 null。 */
    @Nullable
    static String httpGetFirst(OkHttpClient client, String[] hosts, int port, String path) {
        return httpGetFirst(client, hosts, port, path, 6000);
    }

    @Nullable
    static String httpGetFirst(OkHttpClient client, String[] hosts, int port, String path, long timeoutMs) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        for (int index = 0; index < hosts.length; index++) {
            String host = hosts[index];
            long remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
            if (remaining <= 0 || Thread.currentThread().isInterrupted()) return null;
            // Leave each later address a share of the remaining budget.
            long hostBudget = Math.max(1, remaining / (hosts.length - index));
            OkHttpClient bounded = client.newBuilder().callTimeout(Math.min(3000, hostBudget), TimeUnit.MILLISECONDS).build();
            try (Response r = bounded.newCall(
                    new Request.Builder().url("http://" + host + ":" + port + path).build()
            ).execute()) {
                if (r.isSuccessful() && r.body() != null) {
                    return BoundedProcessResponse.read(r.body().byteStream(), MAX_RESPONSE_BYTES);
                }
            } catch (IOException ignored) {
            }
        }
        return null;
    }

    /**
     * 用 toybox/系统 nc 发送裸 HTTP 请求，返回完整原始响应（含 header）。
     * 统一处理：stderr 排空 daemon 线程、4s 超时；失败返回 null。
     */
    @Nullable
    static String ncRequest(String cmd0, String cmd1, String host, int port, String request) {
        return ncRequest(cmd0, cmd1, host, port, request, 4000);
    }

    @Nullable
    static String ncRequest(String cmd0, String cmd1, String host, int port, String request, long timeoutMs) {
        try {
            if (Thread.currentThread().isInterrupted() || timeoutMs <= 0) return null;
            Process process = new ProcessBuilder(BoundedProcessResponse.ncCommand(cmd0, cmd1, host, port))
                    .redirectErrorStream(false)
                    .start();
            return BoundedProcessResponse.exchange(process, request, timeoutMs, MAX_RESPONSE_BYTES);
        } catch (Exception e) {
            /* 外层 catch 也会接到 InterruptedException（in.read / process.waitFor 都可能抛），
               吞掉它会让上层的取消/退出逻辑失效（Sonar java:S2142）。这里只恢复中断标志，
               其余行为不变：仍然记日志并返回 null。 */
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            Timber.w(e, "Failed to fetch via nc (host=%s port=%d)", host, port);
            return null;
        }
    }

    /** 从原始 HTTP 响应剥离 header，返回 body 部分；响应为 null 时返回 null。 */
    @Nullable
    static String bodyFromResponse(@Nullable String response) {
        if (response == null) return null;
        int headerEnd = response.indexOf("\r\n\r\n");
        int statusEnd = response.indexOf("\r\n");
        if (headerEnd < 0 || statusEnd < 0) return null;
        String status = response.substring(0, statusEnd);
        if (!status.matches("HTTP/1\\.[01] 2[0-9]{2}( .*)?")) return null;
        return response.substring(headerEnd + 4);
    }
}
