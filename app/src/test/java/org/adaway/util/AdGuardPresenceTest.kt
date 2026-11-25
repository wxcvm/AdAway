package org.adaway.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdGuardPresenceTest {
    @Test fun customAddressesFollowSavedListener() {
        val result = AdGuardPresence.listenerSuggestion(ListenerSettings("127.0.0.2", "fd6b:2a91:84f0::2", 80, 443))
        assertEquals("127.0.0.2", result.ipv4)
        assertEquals("fd6b:2a91:84f0::2", result.ipv6)
        assertTrue(result.standardPorts)
    }

    @Test fun disabledFamilyIsNotCopied() {
        val result = AdGuardPresence.listenerSuggestion(ListenerSettings("127.0.0.2", "", 8080, 8443))
        assertEquals("A = 127.0.0.2", result.copyText())
        assertFalse(result.standardPorts)
    }

    @Test fun wildcardListenersUseLoopbackCandidates() {
        val result = AdGuardPresence.listenerSuggestion(ListenerSettings("0.0.0.0", "::", 80, 443))
        assertEquals("A = 127.0.0.1\nAAAA = ::1", result.copyText())
    }
}
