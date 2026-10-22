package org.adaway.util;

import java.net.InetAddress;
import java.net.UnknownHostException;

/** Literal-only listener configuration. Validation never resolves a hostname. */
public final class ListenerSettings {
    public final String address;
    public final boolean all;
    public final int http;
    public final int https;
    public final String ipv4;
    public final String ipv6;
    public final boolean explicitPair;

    public ListenerSettings(String address, boolean all, int http, int https) {
        this(address, all, http, https, null, null);
    }

    /** An empty family is explicitly disabled; at least one must remain enabled. */
    public ListenerSettings(String ipv4, String ipv6, int http, int https) {
        this("", false, http, https, ipv4 == null ? "" : ipv4.trim(), ipv6 == null ? "" : ipv6.trim());
    }

    private ListenerSettings(String address, boolean all, int http, int https, String ipv4, String ipv6) {
        this.address = address == null ? "" : address.trim();
        this.all = all;
        this.http = http;
        this.https = https;
        this.explicitPair = ipv4 != null;
        this.ipv4 = explicitPair ? ipv4 : this.address.isEmpty() ? (all ? "0.0.0.0" : "127.0.0.1")
                : this.address.contains(":") ? "" : this.address;
        this.ipv6 = explicitPair ? ipv6 : this.address.isEmpty() ? (all ? "::" : "::1")
                : this.address.contains(":") ? this.address : "";
        if (explicitPair) validatePair(this.ipv4, this.ipv6);
        if (!this.address.isEmpty() && !isLiteral(this.address))
            throw new IllegalArgumentException("请输入完整 IPv4 或 IPv6 地址；不支持主机名、方括号或区域编号。");
        if (http < 1 || http > 65535 || https < 1 || https > 65535)
            throw new IllegalArgumentException("端口必须在 1–65535 之间。");
        if (http == https || http == 8686 || https == 8686)
            throw new IllegalArgumentException("HTTP、HTTPS 和管理端口 8686 不能相同。");
    }

    private static void validatePair(String ipv4, String ipv6) {
        if (ipv4.isEmpty() && ipv6.isEmpty())
            throw new IllegalArgumentException("IPv4 和 IPv6 至少启用一个；留空表示关闭该协议监听。");
        if (!ipv4.isEmpty() && (!isLiteral(ipv4) || ipv4.contains(":")))
            throw new IllegalArgumentException("请输入完整 IPv4 地址，例如 127.0.0.1；不要填写 IPv6 或主机名。");
        if (!ipv6.isEmpty() && (!isLiteral(ipv6) || !ipv6.contains(":")))
            throw new IllegalArgumentException("请输入完整 IPv6 地址，例如 ::1；不支持方括号或区域编号。");
    }

    public static boolean isLiteral(String value) {
        if (value == null || value.isEmpty() || value.length() > 45) return false;
        if (value.indexOf(':') >= 0) {
            if (!value.matches("[0-9a-fA-F:.]+")) return false;
            try { InetAddress.getByName(value); return true; }
            catch (UnknownHostException exception) { return false; }
        }
        String[] parts = value.split("\\.", -1);
        if (parts.length != 4) return false;
        for (String part : parts) {
            if (!part.matches("0|[1-9]\\d{0,2}") || Integer.parseInt(part) > 255) return false;
        }
        return true;
    }

    public boolean supportsRootRedirect() {
        return ipv4.equals("127.0.0.1") || ipv4.equals("0.0.0.0");
    }

    public String arguments() {
        return " --bind " + (all ? "all" : "loop")
                + (explicitPair ? " --listen-ipv4 " + (ipv4.isEmpty() ? "-" : ipv4)
                    + " --listen-ipv6 " + (ipv6.isEmpty() ? "-" : ipv6)
                    : address.isEmpty() ? "" : " --bind-ip " + address)
                + " --http-port " + http + " --https-port " + https + " --stats-port 8686";
    }

    public String probeAddress() {
        return probeAddresses()[0];
    }

    public String[] probeAddresses() {
        java.util.ArrayList<String> result = new java.util.ArrayList<>();
        if (!ipv4.isEmpty()) result.add(ipv4.equals("0.0.0.0") ? "127.0.0.1" : ipv4);
        if (!ipv6.isEmpty() && (explicitPair || ipv4.isEmpty())) result.add(ipv6.equals("::") ? "::1" : ipv6);
        return result.toArray(new String[0]);
    }

    /** Diagnostics include optional legacy IPv6; readiness retains legacy fallback. */
    public String[] diagnosticAddresses() {
        java.util.ArrayList<String> result = new java.util.ArrayList<>(java.util.Arrays.asList(probeAddresses()));
        if (!ipv6.isEmpty()) {
            String address6 = ipv6.equals("::") ? "::1" : ipv6;
            if (!result.contains(address6)) result.add(address6);
        }
        return result.toArray(new String[0]);
    }
}
