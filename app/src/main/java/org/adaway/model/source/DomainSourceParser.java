package org.adaway.model.source;

import com.google.common.net.InetAddresses;
import com.google.common.net.InternetDomainName;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.net.IDN;

/** Domain-only Hosts/ABP import. Conditional rules are unsupported, never broadened. */
public final class DomainSourceParser {
    private DomainSourceParser() { }
    public enum Kind { BLOCK, ALLOW, REPLACE }
    public static final class Entry {
        public final Kind kind;
        public final org.adaway.model.traffic.DomainRule.Scope scope;
        public final String domain, address;
        Entry(Kind kind, String domain, String address) {
            this(kind, domain, address, domain.indexOf('*') >= 0 || domain.indexOf('?') >= 0
                    ? org.adaway.model.traffic.DomainRule.Scope.WILDCARD : org.adaway.model.traffic.DomainRule.Scope.EXACT);
        }
        Entry(Kind kind, String domain, String address, org.adaway.model.traffic.DomainRule.Scope scope) {
            this.kind = kind; this.domain = domain; this.address = address; this.scope = scope;
        }
    }
    public static final class Parsed {
        public final List<Entry> entries = new ArrayList<>();
        public boolean unsupported, cosmetic;
    }
    public static String canonicalDomain(String raw) {
        if (raw == null || raw.length() > 254) return null;
        final String hostName;
        try { hostName = IDN.toASCII(raw); }
        catch (IllegalArgumentException e) { return null; }
        String host = hostName.toLowerCase(Locale.ROOT);
        if (host.endsWith(".")) host = host.substring(0, host.length() - 1);
        if (host.isEmpty() || host.length() > 253) return null;
        for (String label : host.split("\\.", -1)) {
            if (label.isEmpty() || label.length() > 63 || !label.matches("[a-z0-9_-]+")) return null;
        }
        return host;
    }
    private static String allowPattern(String raw) {
        final String ascii;
        try { ascii = IDN.toASCII(raw); } catch (IllegalArgumentException e) { return null; }
        String host = ascii.toLowerCase(Locale.ROOT);
        if (host.endsWith(".")) host = host.substring(0, host.length() - 1);
        String cleared = host.replace("*", "").replace("?", "");
        String replaced = host.replace('*', 'a').replace('?', 'a');
        return InternetDomainName.isValid(cleared) || InternetDomainName.isValid(replaced) ? host : null;
    }
    private static String importDomain(String raw) {
        String domain = canonicalDomain(raw);
        return domain != null && InternetDomainName.isValid(domain) ? domain : null;
    }
    public static Parsed parse(String raw, boolean allowSource, boolean redirects) {
        Parsed result = new Parsed();
        String line = raw.trim();
        if (line.isEmpty() || line.startsWith("!") || line.startsWith("[Adblock")) return result;
        String first = line.split("\\s+", 2)[0];
        boolean hosts = InetAddresses.isInetAddress(first);
        if (!hosts && (line.contains("##") || line.contains("#@#") || line.contains("#$#"))) {
            result.cosmetic = true; return result;
        }
        if (line.startsWith("#")) return result;
        if (line.startsWith("||") || line.startsWith("@@")) {
            boolean exception = line.startsWith("@@");
            String rule = exception ? line.substring(2) : line;
            if (!rule.startsWith("||")) { result.unsupported = true; return result; }
            rule = rule.substring(2);
            if (rule.endsWith("^")) rule = rule.substring(0, rule.length() - 1);
            if (rule.indexOf('^') >= 0 || rule.matches(".*[/$*?|#:].*")) {
                result.unsupported = true; return result;
            }
            String domain = importDomain(rule);
            if (domain == null || domain.indexOf('.') < 0) throw new IllegalArgumentException("Invalid ABP domain");
            result.entries.add(new Entry(exception || allowSource ? Kind.ALLOW : Kind.BLOCK, domain, null, org.adaway.model.traffic.DomainRule.Scope.SUFFIX));
            return result;
        }
        int comment = line.indexOf('#');
        if (comment >= 0) line = line.substring(0, comment).trim();
        if (line.isEmpty()) return result;
        String[] parts = line.split("\\s+");
        if (allowSource && parts.length == 1) {
            String domain = allowPattern(parts[0]);
            if (domain == null) throw new IllegalArgumentException("Invalid allow pattern");
            result.entries.add(new Entry(Kind.ALLOW, domain, null)); return result;
        }
        if (parts.length < 2) {
            String domain = importDomain(parts[0]);
            if (domain != null && domain.indexOf('.') >= 0 && domain.length() >= 4)
                result.entries.add(new Entry(Kind.BLOCK, domain, null));
            else result.unsupported = true;
            return result;
        }
        // Parses only literals: never resolves a name on the network.
        java.net.InetAddress ip = InetAddresses.forString(parts[0]);
        boolean zero = ip.isAnyLocalAddress() || parts[0].equals("127.0.0.1") || parts[0].equals("::1");
        for (int i = 1; i < parts.length; i++) {
            Kind kind = allowSource ? Kind.ALLOW : zero ? Kind.BLOCK : Kind.REPLACE;
            String domain = kind == Kind.REPLACE ? allowPattern(parts[i]) : importDomain(parts[i]);
            if (domain == null) throw new IllegalArgumentException("Invalid Hosts domain");
            if (domain.equals("localhost")) continue;
            if (kind == Kind.REPLACE && !redirects) continue;
            result.entries.add(new Entry(kind, domain, kind == Kind.REPLACE ? parts[0] : null));
        }
        return result;
    }
}
