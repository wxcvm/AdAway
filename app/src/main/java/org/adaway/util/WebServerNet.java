package org.adaway.util;

import androidx.annotation.Nullable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
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

    private WebServerNet() {}

    /** OkHttp 探测宿主优先级：IPv6 loopback 优先。 */
    static final String[] HTTP_HOSTS = {"[::1]", "127.0.0.1"};

    /** 构造本地 OkHttp 客户端（NO_PROXY + 3s 超时）。 */
    static OkHttpClient newLocalClient() {
        return new OkHttpClient.Builder()
                .proxy(java.net.Proxy.NO_PROXY)
                .connectTimeout(3, TimeUnit.SECONDS)
                .readTimeout(3, TimeUnit.SECONDS)
                .build();
    }

    /** 依次尝试多个本地地址（IPv6 优先），返回首个成功响应体；全部失败返回 null。 */
    @Nullable
    static String httpGetFirst(OkHttpClient client, String[] hosts, int port, String path) {
        for (String host : hosts) {
            try (Response r = client.newCall(
                    new Request.Builder().url("http://" + host + ":" + port + path).build()
            ).execute()) {
                if (r.isSuccessful() && r.body() != null) {
                    return r.body().string();
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
        try {
            Process process = new ProcessBuilder(
                    cmd0, cmd1, "-w", "3",
                    host, String.valueOf(port))
                    .redirectErrorStream(false)
                    .start();
            Thread errDrain = new Thread(() -> {
                try (InputStream es = process.getErrorStream()) {
                    byte[] eb = new byte[1024];
                    while (es.read(eb) != -1) { /* discard */ }
                } catch (IOException ignored) { }
            });
            errDrain.setDaemon(true);
            errDrain.start();
            OutputStream out = process.getOutputStream();
            out.write(request.getBytes("UTF-8"));
            out.close();
            InputStream in = process.getInputStream();
            java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) body.write(buf, 0, n);
            in.close();
            if (!process.waitFor(4000, TimeUnit.MILLISECONDS)) {
                process.destroy();
                return null;
            }
            try { errDrain.join(500); } catch (InterruptedException ignored) { }
            return body.toString("UTF-8");
        } catch (Exception e) {
            Timber.w(e, "Failed to fetch via nc (host=%s port=%d)", host, port);
            return null;
        }
    }

    /** 从原始 HTTP 响应剥离 header，返回 body 部分；响应为 null 时返回 null。 */
    @Nullable
    static String bodyFromResponse(@Nullable String response) {
        if (response == null) return null;
        int headerEnd = response.indexOf("\r\n\r\n");
        return headerEnd >= 0 ? response.substring(headerEnd + 4) : response;
    }
}