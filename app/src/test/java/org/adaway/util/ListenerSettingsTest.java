package org.adaway.util;

import org.junit.Test;
import static org.junit.Assert.*;

public class ListenerSettingsTest {
    @Test public void explicitFamiliesStayIndependentAndBothReachStartup() {
        ListenerSettings pair = new ListenerSettings("127.0.0.2", "::1", 18080, 18443);
        assertTrue(pair.arguments().contains("--listen-ipv4 127.0.0.2 --listen-ipv6 ::1"));
        assertArrayEquals(new String[]{"127.0.0.2", "::1"}, pair.probeAddresses());
        assertTrue(new ListenerSettings("127.0.0.1", "::1",80,443).supportsRootRedirect());
        assertFalse(new ListenerSettings("", "::1",80,443).supportsRootRedirect());
        assertTrue(new ListenerSettings("", "::1",80,443).arguments().contains("--listen-ipv4 -"));
        assertThrows(IllegalArgumentException.class, () -> new ListenerSettings("", "",80,443));
        assertThrows(IllegalArgumentException.class, () -> new ListenerSettings("::1", "",80,443));
        assertThrows(IllegalArgumentException.class, () -> new ListenerSettings("", "127.0.0.1",80,443));
    }

    @Test public void legacySingleAddressDoesNotImplicitlyEnableOtherFamily() {
        assertEquals("", new ListenerSettings("127.0.0.2", false,80,443).ipv6);
        assertEquals("", new ListenerSettings("::1", false,80,443).ipv4);
        assertEquals("::", new ListenerSettings("", true,80,443).ipv6);
        assertFalse(new ListenerSettings("", false,80,443).explicitPair);
    }
    @Test public void onlyLiteralAddressesReachLaunchArguments() {
        for (String address : new String[]{"127.0.0.2", "::1", "2001:db8::1", "::ffff:127.0.0.1"}) {
            ListenerSettings value = new ListenerSettings(address, true, 18080, 18443);
            assertTrue(value.arguments().contains(" --bind-ip " + address));
        }
        for (String address : new String[]{"localhost", "127.1", "01.2.3.4", "127.0.0.1;id", "[::1]", "fe80::1%1", "256.0.0.1", "host:80"})
            assertFalse(address, ListenerSettings.isLiteral(address));
    }
    @Test public void rejectsOverlappingAndReservedPorts() {
        for (int[] pair : new int[][]{{80,80},{8686,443},{80,8686},{0,443},{80,65536}}) {
            assertThrows(IllegalArgumentException.class, () -> new ListenerSettings("", false, pair[0], pair[1]));
        }
    }
    @Test public void rootRedirectAndWildcardProbeHaveExplicitBoundaries() {
        assertTrue(new ListenerSettings("0.0.0.0",false,80,443).supportsRootRedirect());
        assertFalse(new ListenerSettings("192.0.2.1",false,80,443).supportsRootRedirect());
        assertEquals("::1",new ListenerSettings("::",false,80,443).probeAddress());
        assertFalse(new ListenerSettings("",false,80,443).arguments().contains("--bind-ip"));
    }
}
