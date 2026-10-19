package org.adaway.util;

import java.net.InetAddress;
import java.net.UnknownHostException;

/** Literal-only listener configuration. Validation never resolves a hostname. */
public final class ListenerSettings {
    public final String address;
    public final boolean all;
    public final int http;
    public final int https;

    public ListenerSettings(String address, boolean all, int http, int https) {
        this.address = address == null ? "" : address.trim();
        this.all = all;
        this.http = http;
        this.https = https;
        if (!this.address.isEmpty() && !isLiteral(this.address))
            throw new IllegalArgumentException("请输入完整 IPv4 或 IPv6 地址；不支持主机名、方括号或区域编号。");
        if (http < 1 || http > 65535 || https < 1 || https > 65535)
            throw new IllegalArgumentException("端口必须在 1–65535 之间。");
        if (http == https || http == 8686 || https == 8686)
            throw new IllegalArgumentException("HTTP、HTTPS 和管理端口 8686 不能相同。");
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
        return address.isEmpty() || address.equals("127.0.0.1") || address.equals("0.0.0.0");
    }

    public String arguments() {
        return " --bind " + (all ? "all" : "loop")
                + (address.isEmpty() ? "" : " --bind-ip " + address)
                + " --http-port " + http + " --https-port " + https + " --stats-port 8686";
    }

    public String probeAddress() {
        if (address.isEmpty() || address.equals("0.0.0.0")) return "127.0.0.1";
        return address.equals("::") ? "::1" : address;
    }
}
