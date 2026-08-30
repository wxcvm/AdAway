package org.adaway.util;

import android.content.Context;
import java.io.IOException;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import static org.adaway.model.root.ShellUtils.isBundledExecutableRunning;

/**
 * AdBlock web server 运行状态判定：进程检测 + 双栈 HTTP/HTTPS 探活。
 *
 * 说明：
 *  - 纯进程检测（pgrep/ps）在部分 ROM 会因 toybox 差异假阴性；
 *  - OkHttp 探活能真实反映服务可达性，两者结合（判断用 OR）。
 *  - 探活一律 IPv6 loopback [::1] 优先：部分 ROM 残留 DNAT 规则劫持
 *    127.0.0.1:80（实测 -> 127.0.0.1:12121），v4 直连 refused。
 */
public final class WebServerState {

    private WebServerState() {}

    /** 进程级检测（shell 多策略，见 ShellUtils）。 */
    public static boolean isWebServerRunning() {
        return isBundledExecutableRunning(WebServerUtils.WEB_SERVER_EXECUTABLE);
    }

    /**
     * 权威运行判定：HTTP + HTTPS 双栈探活（3s 超时）。
     * 进程活着但探活失败时不再直接判死——由调用方（getWebServerState）
     * 结合证书哈希做最终状态；本方法仅反映服务可达性。
     */
    public static boolean isWebServerReachable(Context context) {
        // HTTP 双栈探活（::1 优先）
        String body = WebServerNet.httpGetFirst(
                WebServerNet.newLocalClient(),
                WebServerNet.HTTP_HOSTS,
                WebServerUtils.getHttpPort(context),
                "/internal-test");
        if (body != null) return true;
        // HTTPS 双栈探活（TLS 端口无 DNAT 劫持风险，仍 ::1 优先）
        OkHttpClient client = WebServerNet.newLocalClient();
        for (String host : WebServerNet.HTTP_HOSTS) {
            try {
                int port = WebServerUtils.getHttpsPort(context);
                String url = port == 443 ? "https://" + host + "/internal-test"
                        : "https://" + host + ":" + port + "/internal-test";
                try (Response r = client.newCall(
                        new Request.Builder().url(url).build()
                ).execute()) {
                    if (r.isSuccessful()) return true;
                }
            } catch (IOException ignored) {
            }
        }
        return false;
    }
}