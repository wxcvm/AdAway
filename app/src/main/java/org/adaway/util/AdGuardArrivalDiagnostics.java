package org.adaway.util;

import android.content.Context;
import com.topjohnwu.superuser.Shell;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** User-triggered observations; installation, running process, NAT and receipt are distinct signals. */
public final class AdGuardArrivalDiagnostics {
    private AdGuardArrivalDiagnostics() { }

    public static String check(Context context, String adblockDomain, String hostsDomain) {
        if (!adblockDomain.trim().isEmpty()) ListenerArrivalCheck.normalizeDomain(adblockDomain);
        if (!hostsDomain.trim().isEmpty()) ListenerArrivalCheck.normalizeDomain(hostsDomain);
        ListenerSettings settings = WebServerUtils.getListenerSettings(context);
        List<String> lines = new ArrayList<>();
        lines.add("检测目标：已保存的监听配置；回执仅确认本次测试的地址与端口，不证明全部应用的流量已到达。");
        if (settings.http != 80 || settings.https != 443)
            lines.add("当前使用非默认端口：HTTP " + settings.http + "／HTTPS " + settings.https
                    + "。DNS 记录不携带端口；本检测不证明普通域名访问的 80／443 路径有效。");
        boolean installed = AdGuardPresence.INSTANCE.isInstalled(context);
        lines.add(installed ? "已安装 AdGuard；安装状态本身不代表正在过滤。" : "未发现已知 AdGuard 包；不排除其他发行版或系统过滤。");
        rootSignals(lines);
        lines.add("VPN 状态：" + org.adaway.model.traffic.AndroidVpnObserver.read(context, false).name() + "；不据此推断 VPN 属于 AdGuard。");
        RootListenerAddresses local = new RootListenerAddresses();
        lines.add(hasManagementReceipt() ? "管理端口回执已确认：当前 AdAway 支持请求到达检测。" : "未确认管理端口回执；服务未就绪、旧版本或路径受影响，不能判定请求被谁处理。");
        for (String address : settings.diagnosticAddresses()) {
            lines.add(directResult(address, settings.http, local));
            lines.add("HTTPS [" + address + "]:" + settings.https + "（localhost）："
                    + ListenerTlsCheck.check(address, settings.https, "localhost", local::exists).describe());
        }
        addDomain(lines, "广告规则测试：", adblockDomain, settings, local);
        addDomain(lines, "Hosts 规则测试：", hostsDomain, settings, local);
        lines.add("QUIC / HTTP/3：本版本未实现 UDP 服务，TCP/443 和 HTTPS 成功不能代表 QUIC 成功。");
        lines.add("DNS 使用本应用的系统解析路径，规则类别以 AdGuard 日志为准。HTTPS 使用本应用的默认信任与名称校验；其他应用可能采用不同信任库或证书固定。未修改 AdGuard、网络或证书。");
        return String.join("\n", lines);
    }

    private static void rootSignals(List<String> lines) {
        if (Boolean.TRUE.equals(Shell.isAppGrantedRoot())) {
            Shell.Result process = Shell.cmd("pidof com.adguard.android com.adguard.android.lite").exec();
            lines.add(process.getOut().stream().anyMatch(line -> line.matches("[0-9 ]+")) ? "检测到 AdGuard 进程运行。" : "未确认 AdGuard 主进程运行；后台服务或其他版本状态未知。");
            Shell.Result rules = Shell.cmd("iptables -t nat -S OUTPUT; iptables -t nat -S ADGUARD_OUTPUT").exec();
            String text = String.join("\n", rules.getOut());
            lines.add(text.contains("-j ADGUARD_OUTPUT") && text.contains("-j REDIRECT")
                    ? "检测到 AdGuard OUTPUT 重定向规则；规则存在不等于本次请求已命中。" : "未确认 AdGuard OUTPUT 重定向；VPN / 其他链的处理仍可能存在。");
        } else lines.add("未读取 Root 进程/规则；不会因打开诊断自动申请 Root。");
    }

    private static boolean hasManagementReceipt() {
        for (String address : new String[]{"::1", "127.0.0.1"}) {
            try { if (ListenerArrivalCheck.received(address, WebServerUtils.STATS_PORT)) return true; }
            catch (IOException ignored) { /* Try the other management family. */ }
        }
        return false;
    }

    private static String directResult(String address, int port, RootListenerAddresses local) {
        try {
            if (!local.exists(address)) return address + "：当前地址不属于本机，未连接。";
            return address + (ListenerArrivalCheck.received(address, port) ? "：HTTP 请求已到达 AdAway" : "：没有 AdAway 回执；端口连通也不能视为已收到");
        } catch (IOException exception) { return address + "：没有 AdAway 回执（超时、拒绝或被其他路径处理）"; }
    }

    private static void addDomain(List<String> lines, String label, String domain, ListenerSettings settings, RootListenerAddresses local) {
        if (domain.trim().isEmpty()) return;
        List<String> tls = new ArrayList<>();
        String normalized = ListenerArrivalCheck.normalizeDomain(domain);
        lines.add(label + ListenerArrivalCheck.domainResult(settings, normalized, ListenerArrivalCheck::resolveSystem,
                (ip, port) -> {
                    boolean received = false;
                    try { received = ListenerArrivalCheck.received(ip, port, normalized); }
                    catch (IOException ignored) { /* HTTPS is independent of the HTTP result. */ }
                    tls.add("HTTPS " + normalized + " → [" + ip + "]:" + settings.https + "："
                            + ListenerTlsCheck.check(ip, settings.https, normalized, local::exists).describe());
                    return received;
                }, local::exists));
        lines.addAll(tls);
        if (tls.isEmpty()) lines.add("HTTPS " + normalized + "：未测试；系统 DNS 未提供匹配且属于本机的地址，未建立连接。");
    }
}
