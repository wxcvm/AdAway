package org.adaway.util;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * Regression tests for the {@code /control} request body.
 *
 * <p>The native server gained a per-run token ({@code control_token.txt}) and
 * refuses every {@code /control} call that does not carry it. The Android
 * client sent no token and talked to the HTTP port instead of the management
 * port, so reload-images / flush-stats / shutdown were answered with 403. The
 * body builder and the token parser are pure JVM logic and are covered here so
 * the contract cannot silently rot again.</p>
 */
public class WebServerControlTest {

    private static final String TOKEN = "0123456789abcdef0123456789abcdef";

    @Test
    public void bodyWithoutTokenKeepsTheOldShape() {
        assertEquals("cmd=reload_config", WebServerControl.buildControlBody("reload_config", null));
        assertEquals("cmd=flush_stats", WebServerControl.buildControlBody("flush_stats", ""));
    }

    @Test
    public void bodyCarriesTheTokenWhenItIsAvailable() {
        assertEquals("cmd=shutdown&token=" + TOKEN,
                WebServerControl.buildControlBody("shutdown", TOKEN));
    }

    @Test
    public void parsesATokenFileWithTrailingWhitespace() {
        assertEquals(TOKEN, WebServerControl.parseToken(TOKEN + "\n"));
        assertEquals(TOKEN, WebServerControl.parseToken("  " + TOKEN + " \r\n"));
        assertEquals(TOKEN.toUpperCase(), WebServerControl.parseToken(TOKEN.toUpperCase()));
    }

    @Test
    public void rejectsAnythingThatIsNotAToken() {
        assertNull(WebServerControl.parseToken(null));
        assertNull(WebServerControl.parseToken(""));
        assertNull(WebServerControl.parseToken("   "));
        assertNull(WebServerControl.parseToken("not-a-token"));
        // 31 and 33 characters
        assertNull(WebServerControl.parseToken("0123456789abcdef0123456789abcde"));
        assertNull(WebServerControl.parseToken("0123456789abcdef0123456789abcdeff"));
        // right length, non-hex character
        assertNull(WebServerControl.parseToken("0123456789abcdef0123456789abcdeg"));
    }
}
