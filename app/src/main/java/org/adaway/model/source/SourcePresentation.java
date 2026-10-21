package org.adaway.model.source;

import java.net.URI;
import java.util.Arrays;
import java.util.Locale;

/** Local presentation only: never downloads a title or prints subscription credentials. */
public final class SourcePresentation {
    private SourcePresentation() { }

    public static String name(String label, String address) {
        String saved = label == null ? "" : label.trim();
        try {
            URI uri = URI.create(address);
            String host = uri.getHost();
            if (host == null) return saved.isEmpty() ? "Local rules" : saved;
            String lower = host.toLowerCase(Locale.ROOT);
            boolean generated = saved.isEmpty() || saved.equalsIgnoreCase(lower.split("\\.")[0])
                    || Arrays.asList("raw", "www", "github", "gitlab").contains(saved.toLowerCase(Locale.ROOT));
            if (!generated) return saved;
            String[] path = uri.getPath() == null ? new String[0] : uri.getPath().split("/");
            if ((lower.equals("raw.githubusercontent.com") || lower.equals("github.com")) && path.length >= 3) {
                String file = path[path.length - 1];
                return path[1] + "/" + path[2] + (file.equals(path[2]) ? "" : " · " + file);
            }
            String file = path.length > 1 ? path[path.length - 1] : "";
            return (lower.startsWith("www.") ? lower.substring(4) : lower) + (file.isEmpty() ? "" : " · " + file);
        } catch (IllegalArgumentException invalid) {
            return saved.isEmpty() ? "Rules source" : saved;
        }
    }

    public static String address(String address) {
        try {
            URI uri = URI.create(address);
            if ("content".equalsIgnoreCase(uri.getScheme())) return "Local document";
            if (uri.getHost() == null) return "Invalid source address";
            return new URI(uri.getScheme(), null, uri.getHost(), uri.getPort(), uri.getPath(), null, null).toASCIIString();
        } catch (Exception invalid) {
            return "Invalid source address";
        }
    }
}
