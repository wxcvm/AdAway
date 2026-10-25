package org.adaway.util;

import android.content.Context;
import com.topjohnwu.superuser.Shell;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/** User-triggered observations; installation, running process, NAT and receipt are distinct signals. */
public final class AdGuardArrivalDiagnostics {
    private static final AtomicBoolean ROOT_PENDING = new AtomicBoolean();
    private static final String ROOT_RULES_MARKER = "__ADAWAY_DIAGNOSTIC_RULES__";
    private AdGuardArrivalDiagnostics() { }

    public static String check(Context context, String adblockDomain, String hostsDomain) {
        try { return check(context, adblockDomain, hostsDomain, new ListenerDiagnosticSession()); }
        catch (IOException exception) { return "检测未完成：" + exception.getMessage(); }
    }

    public static String check(Context context, String adblockDomain, String hostsDomain,
            ListenerDiagnosticSession session) throws IOException {
        session.begin();
        try { return checkStarted(context, adblockDomain, hostsDomain, session); }
        finally { session.finish(); }
    }

    private static String checkStarted(Context context, String adblockDomain, String hostsDomain,
            ListenerDiagnosticSession session) throws IOException {
        session.progress("读取已保存监听配置");
        if (!adblockDomain.trim().isEmpty()) ListenerArrivalCheck.normalizeDomain(adblockDomain);
        if (!hostsDomain.trim().isEmpty()) ListenerArrivalCheck.normalizeDomain(hostsDomain);
        ListenerSettings settings = WebServerUtils.getListenerSettings(context);
        String target = settings.arguments();
        session.requireCurrentConfiguration(() -> target.equals(WebServerUtils.getListenerSettings(context).arguments()));
        List<String> lines = new ArrayList<>();
        lines.add("检测开始时间（UTC）：" + java.time.Instant.now());
        lines.add("检测目标：已保存的监听配置；回执仅确认本次测试的地址与端口，不证明全部应用的流量已到达。");
        lines.add("保存目标：IPv4 " + (settings.ipv4.isEmpty() ? "关闭" : settings.ipv4)
                + " / IPv6 " + (settings.ipv6.isEmpty() ? "关闭" : settings.ipv6)
                + "；HTTP " + settings.http + " / HTTPS " + settings.https
                + "。保存值不代表运行实例已应用，以下回执为独立观察。");
        if (settings.http != 80 || settings.https != 443)
            lines.add("当前使用非默认端口：HTTP " + settings.http + "／HTTPS " + settings.https
                    + "。DNS 记录不携带端口；本检测不证明普通域名访问的 80／443 路径有效。");
        boolean installed = AdGuardPresence.INSTANCE.isInstalled(context);
        lines.add(installed ? "已安装 AdGuard；安装状态本身不代表正在过滤。" : "未发现已知 AdGuard 包；不排除其他发行版或系统过滤。");
        session.progress("检查 AdGuard 进程与 Root 重定向观察信号");
        rootSignals(lines, session);
        session.progress("读取 VPN 状态");
        lines.add("VPN 状态：" + org.adaway.model.traffic.AndroidVpnObserver.read(context, false).name() + "；不据此推断 VPN 属于 AdGuard。");
        RootListenerAddresses local = new RootListenerAddresses();
        session.progress("检查管理端口回执");
        lines.add(hasManagementReceipt(session) ? "管理端口回执已确认：当前 AdAway 支持请求到达检测。" : "未确认管理端口回执；服务未就绪、旧版本或路径受影响，不能判定请求被谁处理。");
        for (String address : settings.diagnosticAddresses()) {
            session.progress("检查 HTTP " + address + ":" + settings.http);
            lines.add(directResult(address, settings.http, local, session));
            session.progress("检查 HTTPS " + address + ":" + settings.https);
            lines.add("HTTPS [" + address + "]:" + settings.https + "（localhost）："
                    + ListenerTlsCheck.check(address, settings.https, "localhost", local::exists, session).describe());
        }
        addDomain(lines, "广告规则测试：", adblockDomain, settings, local, session);
        addDomain(lines, "Hosts 规则测试：", hostsDomain, settings, local, session);
        lines.add("QUIC / HTTP/3：本版本未实现 UDP 服务，TCP/443 和 HTTPS 成功不能代表 QUIC 成功。");
        lines.add("DNS 使用本应用的系统解析路径，规则类别以 AdGuard 日志为准。HTTPS 使用本应用的默认信任与名称校验；其他应用可能采用不同信任库或证书固定。未修改 AdGuard、网络或证书。");
        session.progress("诊断完成");
        return String.join("\n", lines);
    }

    private static void rootSignals(List<String> lines, ListenerDiagnosticSession session) throws IOException {
        session.checkCancelled();
        if (Boolean.TRUE.equals(Shell.isAppGrantedRoot())) {
            observeRoot(lines, session);
        } else lines.add("未读取 Root 进程/规则；不会因打开诊断自动申请 Root。");
    }

    private static void observeRoot(List<String> lines, ListenerDiagnosticSession session) throws IOException {
        if (!ROOT_PENDING.compareAndSet(false, true)) {
            lines.add("上次 Root 观察仍未结束；本轮未重复排队，不据此判断 AdGuard 是否运行。");
            return;
        }
        CompletableFuture<Shell.Result> pending = new CompletableFuture<>();
        try {
            Shell.cmd("pidof com.adguard.android com.adguard.android.lite", "printf '\\n" + ROOT_RULES_MARKER + "\\n'",
                    "iptables -t nat -S OUTPUT; iptables -t nat -S ADGUARD_OUTPUT").submit(null, result -> {
                        ROOT_PENDING.set(false);
                        pending.complete(result);
                    });
        } catch (RuntimeException failure) {
            ROOT_PENDING.set(false);
            pending.completeExceptionally(failure);
        }
        try {
            Shell.Result observed = session.await(pending, 2000, "Root 观察未在 2 秒内完成");
            List<String> output = observed.getOut();
            int marker = output.indexOf(ROOT_RULES_MARKER);
            List<String> processes = marker < 0 ? java.util.Collections.emptyList() : output.subList(0, marker);
            lines.add(processes.stream().anyMatch(line -> line.matches("[0-9 ]+"))
                    ? "检测到 AdGuard 进程运行。" : "未确认 AdGuard 主进程运行；后台服务或其他版本状态未知。");
            String rules = marker < 0 ? "" : String.join("\n", output.subList(marker + 1, output.size()));
            lines.add(rules.contains("-j ADGUARD_OUTPUT") && rules.contains("-j REDIRECT")
                    ? "检测到 AdGuard OUTPUT 重定向规则；规则存在不等于本次请求已命中。" : "未确认 AdGuard OUTPUT 重定向；VPN / 其他链的处理仍可能存在。");
        } catch (IOException failure) {
            session.checkCancelled();
            lines.add("Root 观察未完成；后台只读任务可能仍在执行，不据此判断过滤是否生效。");
        }
    }

    private static boolean hasManagementReceipt(ListenerDiagnosticSession session) throws IOException {
        for (String address : new String[]{"::1", "127.0.0.1"}) {
            session.checkCancelled();
            try { if (ListenerArrivalCheck.received(address, WebServerUtils.STATS_PORT, "localhost", session)) return true; }
            catch (IOException ignored) { session.checkCancelled(); }
        }
        return false;
    }

    private static String directResult(String address, int port, RootListenerAddresses local,
            ListenerDiagnosticSession session) throws IOException {
        session.checkCancelled();
        try {
            if (!local.exists(address)) return address + "：当前地址不属于本机，未连接。";
            return address + (ListenerArrivalCheck.received(address, port, "localhost", session) ? "：HTTP 请求已到达 AdAway" : "：没有 AdAway 回执；端口连通也不能视为已收到");
        } catch (IOException exception) { session.checkCancelled(); return address + "：没有 AdAway 回执（超时、拒绝或被其他路径处理）"; }
    }

    private static void addDomain(List<String> lines, String label, String domain, ListenerSettings settings, RootListenerAddresses local,
            ListenerDiagnosticSession session) throws IOException {
        session.checkCancelled();
        if (domain.trim().isEmpty()) return;
        List<String> tls = new ArrayList<>();
        String normalized = ListenerArrivalCheck.normalizeDomain(domain);
        session.progress("检查系统 DNS：" + normalized);
        lines.add(label + ListenerArrivalCheck.domainResult(settings, normalized, name -> ListenerArrivalCheck.resolveSystem(name, session),
                (ip, port) -> {
                    session.progress("检查域名 HTTP：" + normalized + " → " + ip);
                    boolean received = false;
                    try { received = ListenerArrivalCheck.received(ip, port, normalized, session); }
                    catch (IOException ignored) { session.checkCancelled(); }
                    session.progress("检查域名 HTTPS：" + normalized + " → " + ip);
                    tls.add("HTTPS " + normalized + " → [" + ip + "]:" + settings.https + "："
                            + ListenerTlsCheck.check(ip, settings.https, normalized, local::exists, session).describe());
                    return received;
                }, local::exists, session));
        lines.addAll(tls);
        if (tls.isEmpty()) lines.add("HTTPS " + normalized + "：未测试；系统 DNS 未提供匹配且属于本机的地址，未建立连接。");
    }
}
