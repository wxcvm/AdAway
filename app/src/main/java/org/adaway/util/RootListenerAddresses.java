package org.adaway.util;

import com.topjohnwu.superuser.Shell;
import java.io.IOException;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Collections;
import java.util.Set;

/** Alias operations are limited to lo, /32 or /128, and already-validated IP literals. */
public final class RootListenerAddresses implements ListenerAddressTransaction.Backend {
    @Override public boolean exists(String address) throws IOException {
        InetAddress target = InetAddress.getByName(address);
        // Linux routes the entire IPv4 loopback block locally; no alias is needed.
        if (!address.contains(":") && (target.getAddress()[0] & 255) == 127) return true;
        try {
            java.util.Enumeration<NetworkInterface> networks = NetworkInterface.getNetworkInterfaces();
            if (networks == null) throw new IOException("本机网络接口不可用");
            for (NetworkInterface network : Collections.list(networks))
                for (InetAddress assigned : Collections.list(network.getInetAddresses()))
                    if (assigned.equals(target)) return true;
            return false;
        } catch (SocketException exception) { throw new IOException("无法枚举本机地址", exception); }
    }

    private static String command(String operation, String address) {
        if (!ListenerAddressTransaction.canCreate(address)) throw new IllegalArgumentException("不支持自动创建的地址");
        boolean ipv6 = address.contains(":");
        return "/system/bin/ip " + (ipv6 ? "-6" : "-4") + " address " + operation + " " + address
                + (ipv6 ? "/128" : "/32") + " dev lo" + (ipv6 && operation.equals("add") ? " nodad" : "");
    }

    @Override public void add(String address) throws IOException {
        execute(command("add", address), "创建失败（需要 Root）");
    }

    @Override public void remove(String address) throws IOException {
        execute(command("del", address), "地址清理失败");
    }

    private static void execute(String command, String failure) throws IOException {
        try {
            Shell.Result result = Shell.cmd(command).exec();
            if (!result.isSuccess()) throw new IOException(failure + "：" + String.join(" ", result.getErr()));
        } catch (RuntimeException exception) { throw new IOException(failure + "：Root shell 不可用", exception); }
    }

    /** Boot only recreates explicitly owned aliases; no default route, DNS or physical NIC changes. */
    public static String bootCommands(Set<String> addresses) {
        StringBuilder script = new StringBuilder();
        for (String address : new java.util.TreeSet<>(addresses)) {
            if (!ListenerAddressTransaction.canCreate(address)) throw new IllegalArgumentException("无效的托管地址");
            boolean ipv6 = address.contains(":");
            script.append("  if ! /system/bin/ip ").append(ipv6 ? "-6" : "-4")
                    .append(" address show to ").append(address).append(ipv6 ? "/128" : "/32")
                    .append(" | grep -q '").append(ipv6 ? "inet6 " : "inet ").append("'; then\n    ")
                    .append(command("add", address)).append(" >> \"$LOG\" 2>&1 || { echo 'Listener address preparation failed' >> \"$LOG\"; exit 1; }\n  fi\n");
        }
        return script.toString();
    }
}
