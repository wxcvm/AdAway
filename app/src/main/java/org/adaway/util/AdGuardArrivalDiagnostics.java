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
        boolean installed = AdGuardPresence.INSTANCE.isInstalled(context);
        lines.add(installed ? "已安装 AdGuard；安装状态本身不代表正在过滤。" : "未发现已知 AdGuard 包；不排除其他发行版或系统过滤。");
        if (Boolean.TRUE.equals(Shell.isAppGrantedRoot())) {
            Shell.Result process = Shell.cmd("pidof com.adguard.android com.adguard.android.lite").exec();
            lines.add(process.getOut().stream().anyMatch(line -> line.matches("[0-9 ]+")) ? "检测到 AdGuard 进程运行。" : "未确认 AdGuard 主进程运行；后台服务或其他版本状态未知。");
            Shell.Result rules = Shell.cmd("iptables -t nat -S OUTPUT; iptables -t nat -S ADGUARD_OUTPUT").exec();
            String text = String.join("\n", rules.getOut());
            lines.add(text.contains("-j ADGUARD_OUTPUT") && text.contains("-j REDIRECT")
                    ? "检测到 AdGuard OUTPUT 重定向规则；规则存在不等于本次请求已命中。" : "未确认 AdGuard OUTPUT 重定向；VPN / 其他链的处理仍可能存在。");
        } else lines.add("未读取 Root 进程/规则；不会因打开诊断自动申请 Root。");
        lines.add("VPN 状态：" + org.adaway.model.traffic.AndroidVpnObserver.read(context, false).name() + "；不据此推断 VPN 属于 AdGuard。");
        RootListenerAddresses local = new RootListenerAddresses();
        boolean management = false;
        for (String address : new String[]{"::1", "127.0.0.1"}) {
            try { if (ListenerArrivalCheck.received(address, WebServerUtils.STATS_PORT)) { management = true; break; } }
            catch (IOException ignored) { /* Try the other management family. */ }
        }
        lines.add(management ? "管理端口回执已确认：当前 AdAway 支持请求到达检测。" : "未确认管理端口回执；服务未就绪、旧版本或路径受影响，不能判定请求被谁处理。");
        for (String address : settings.probeAddresses()) {
            try {
                if (!local.exists(address)) lines.add(address + "：当前地址不属于本机，未连接。");
                else lines.add(address + (ListenerArrivalCheck.received(address, settings.http) ? "：HTTP 请求已到达 AdAway" : "：没有 AdAway 回执；端口连通也不能视为已收到"));
            } catch (IOException exception) { lines.add(address + "：没有 AdAway 回执（超时、拒绝或被其他路径处理）"); }
        }
        if (!adblockDomain.trim().isEmpty()) lines.add("广告规则测试：" + ListenerArrivalCheck.domainResult(settings, adblockDomain,
                ListenerArrivalCheck::resolveSystem, ListenerArrivalCheck::received, local::exists));
        if (!hostsDomain.trim().isEmpty()) lines.add("Hosts 规则测试：" + ListenerArrivalCheck.domainResult(settings, hostsDomain,
                ListenerArrivalCheck::resolveSystem, ListenerArrivalCheck::received, local::exists));
        lines.add("DNS 使用本应用的系统解析路径；无法仅凭 DNS 返回判断命中了哪条 AdGuard 规则。HTTPS 证书信任和其他应用路径未验证。未修改 AdGuard、网络或证书。");
        return String.join("\n", lines);
    }
}
