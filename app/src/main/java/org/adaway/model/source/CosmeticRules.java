package org.adaway.model.source;

import java.util.LinkedHashSet;

/**
 * Collector for AdGuard / Adblock Plus <b>cosmetic</b> rules
 * (<code>##selector</code>, <code>example.com##selector</code>,
 * <code>#@#</code>, <code>#$#</code>).
 *
 * <p>These rules cannot be expressed in a hosts file, so they are not stored
 * in the database: they are gathered while the sources are parsed and written
 * to <code>&lt;resource&gt;/cosmetic.css</code> for the transparent proxy of the
 * "hijack" blocking mode, which injects them into every filtered HTML page -
 * i.e. the very same element-hiding AdGuard does.</p>
 *
 * @author ADBlock
 */
public final class CosmeticRules {
    /**
     * Upper bound to keep the injected stylesheet small.
     */
    private static final int MAX_RULES = 20000;
    /**
     * The collected CSS selectors (insertion ordered, de-duplicated).
     */
    private static final LinkedHashSet<String> SELECTORS = new LinkedHashSet<>();
    /**
     * Number of cosmetic rules seen (including the ones dropped).
     */
    private static int seen;

    private CosmeticRules() {
    }

    /**
     * Try to handle a source line as a cosmetic rule.
     *
     * @param line The raw source line.
     * @return {@code true} when the line was a cosmetic rule (and must not be
     * treated as a hosts entry), {@code false} otherwise.
     */
    public static boolean collect(String line) {
        if (line == null) {
            return false;
        }
        String rule = line.trim();
        if (rule.isEmpty() || rule.startsWith("!")) {
            return false;
        }
        int exception = rule.indexOf("#@#");
        int style = rule.indexOf("#$#");
        int hide = rule.indexOf("##");
        if (hide < 0 && exception < 0 && style < 0) {
            return false;
        }
        seen++;
        // #@# (exception) and #$# (style/scriptlet) carry no plain selector.
        if (hide < 0 || exception >= 0) {
            return true;
        }
        String selector = rule.substring(hide + 2).trim();
        if (selector.isEmpty() || selector.length() > 200
                || selector.indexOf('{') >= 0 || selector.indexOf('}') >= 0) {
            return true;
        }
        synchronized (SELECTORS) {
            if (SELECTORS.size() < MAX_RULES) {
                SELECTORS.add(selector);
            }
        }
        return true;
    }

    /**
     * Forget everything collected so far (called before a new sync).
     */
    public static void clear() {
        synchronized (SELECTORS) {
            SELECTORS.clear();
            seen = 0;
        }
    }

    /**
     * @return The number of cosmetic rules seen since the last {@link #clear()}.
     */
    public static int getSeenCount() {
        synchronized (SELECTORS) {
            return seen;
        }
    }

    /**
     * Build the stylesheet injected into proxied pages.
     *
     * @return The CSS text (empty when no cosmetic rule was collected).
     */
    public static String toCss() {
        StringBuilder builder = new StringBuilder(64 * 1024);
        builder.append("/* ADBlock cosmetic rules (AdGuard/adblock syntax) */\n");
        synchronized (SELECTORS) {
            for (String selector : SELECTORS) {
                builder.append(selector).append("{display:none !important;}\n");
            }
        }
        return builder.toString();
    }
}
