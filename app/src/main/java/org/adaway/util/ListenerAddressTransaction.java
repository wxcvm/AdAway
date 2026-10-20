package org.adaway.util;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

/** Owns only addresses added by this attempt; an existing address is never removed. */
public final class ListenerAddressTransaction {
    public interface Backend {
        boolean exists(String address) throws IOException;
        void add(String address) throws IOException;
        void remove(String address) throws IOException;
    }

    private final Backend backend;
    private final List<String> added = new ArrayList<>();

    public ListenerAddressTransaction(Backend backend) { this.backend = backend; }

    /** The boot script is often written BEFORE the first successful address creation. */
    public static java.util.Set<String> startupAliases(ListenerSettings settings, boolean autoCreate) {
        java.util.Set<String> aliases = new java.util.TreeSet<>();
        if (autoCreate) for (String address : new String[]{settings.ipv4, settings.ipv6}) {
            // All of 127/8 already routes locally; never add a redundant loopback alias.
            if (canCreate(address) && !address.startsWith("127.")) aliases.add(address);
        }
        return aliases;
    }

    public static boolean isSelected(ListenerSettings settings, String address) {
        try {
            InetAddress candidate = InetAddress.getByName(address);
            return (!settings.ipv4.isEmpty() && candidate.equals(InetAddress.getByName(settings.ipv4)))
                    || (!settings.ipv6.isEmpty() && candidate.equals(InetAddress.getByName(settings.ipv6)));
        } catch (UnknownHostException exception) { return false; }
    }

    /** Only private unicast aliases are created. Public, mapped and link-local IPs stay manual. */
    public static boolean canCreate(String address) {
        if (!ListenerSettings.isLiteral(address)) return false;
        try {
            byte[] bytes = InetAddress.getByName(address).getAddress();
            if (address.contains(":")) return bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc;
            int first = bytes[0] & 255, second = bytes[1] & 255;
            return first == 10 || first == 127 || (first == 172 && second >= 16 && second <= 31)
                    || (first == 192 && second == 168);
        } catch (UnknownHostException exception) { return false; }
    }

    public void prepare(ListenerSettings settings) throws IOException {
        for (String address : new String[]{settings.ipv4, settings.ipv6}) {
            if (address.isEmpty() || address.equals("0.0.0.0") || address.equals("::") || backend.exists(address)) continue;
            if (!canCreate(address)) throw new IOException("不能自动创建 " + address + "；仅支持私有 IPv4 和 IPv6 ULA 本机地址。");
            backend.add(address);
            added.add(address);
            if (!backend.exists(address)) throw new IOException("地址已添加但尚不可用：" + address);
        }
    }

    public List<String> added() { return new ArrayList<>(added); }

    /** A cleanup failure is surfaced, never reported as a successful rollback. */
    public String rollback() {
        List<String> failures = new ArrayList<>();
        for (int index = added.size() - 1; index >= 0; index--) {
            String address = added.get(index);
            try { backend.remove(address); added.remove(index); }
            catch (IOException exception) { failures.add(address + "：" + exception.getMessage()); }
        }
        return String.join("；", failures);
    }
}
