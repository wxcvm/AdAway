package org.adaway.util;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/** Bounded management-only URL, shared by the production reader and JVM tests. */
public final class QueryLogRequest {
    private QueryLogRequest() {}
    public static String path(int offset, int limit, String generation, String anchor, String key,
                              Integer action, Integer family, Integer type, Integer uid, String query) {
        if (offset < 0 || offset > 10000 || limit < 1 || limit > 1000 || query == null ||
            query.getBytes(StandardCharsets.UTF_8).length > 191) throw new IllegalArgumentException("Invalid history query");
        for (int i=0;i<query.length();i++) {
            char c=query.charAt(i);
            if (c < 32 || (c >= 127 && c <= 159)) throw new IllegalArgumentException("Invalid query text");
            if (Character.isHighSurrogate(c)) {
                if (++i >= query.length() || !Character.isLowSurrogate(query.charAt(i))) throw new IllegalArgumentException("Invalid UTF-8 text");
            } else if (Character.isLowSurrogate(c)) throw new IllegalArgumentException("Invalid UTF-8 text");
        }
        if ((action != null && (action < 0 || action > 3)) ||
            (family != null && family != 0 && family != 4 && family != 6) ||
            (type != null && (type < -1 || type > 65534)) || (uid != null && uid < -1))
            throw new IllegalArgumentException("Invalid history filter");
        boolean cursor = generation != null || anchor != null || key != null;
        if (cursor && (generation == null || !generation.matches("[0-9a-f]{32}") ||
            anchor == null || !anchor.matches("[0-9]{1,20}") || key == null || !key.matches("[0-9a-f]{64}")))
            throw new IllegalArgumentException("Incomplete history cursor");
        StringBuilder path = new StringBuilder("/internal-qlog?filterVersion=1&offset=").append(offset).append("&limit=").append(limit);
        if (action != null) path.append("&action=").append(action);
        if (family != null) path.append("&family=").append(family);
        if (type != null) path.append("&type=").append(type);
        if (uid != null) path.append("&uid=").append(uid);
        if (!query.isEmpty()) {
            try { path.append("&q=").append(URLEncoder.encode(query,"UTF-8")); }
            catch (java.io.UnsupportedEncodingException impossible) { throw new AssertionError(impossible); }
        }
        if (cursor) path.append("&generation=").append(generation).append("&anchor=").append(anchor).append("&queryKey=").append(key);
        return path.toString();
    }
}
