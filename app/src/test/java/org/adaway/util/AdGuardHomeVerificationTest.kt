package org.adaway.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** AdGuardHomeVerification 的单测：重点是那个「DNS 已指向 ≠ HTTPS 已过滤」的坑。 */
class AdGuardHomeVerificationTest {

    private val localCa = "CN=ADBlock Local CA"

    @Test
    fun allThreeStepsPassIsOkAndKept() {
        val v = AdGuardHomeVerification.judge(true, true, "CN=ADBlock Local CA,O=ADBlock", localCa)
        assertEquals(AdGuardHomeVerification.Verdict.OK, v)
        assertFalse(AdGuardHomeVerification.shouldRollback(v))
    }

    @Test
    fun dnsAnsweredButPublicIssuerMustRollback() {
        // 这正是计划点名的第三个坑：DNS 已指向，但 HTTPS 没有被过滤
        val v = AdGuardHomeVerification.judge(true, true, "CN=WE1, O=Google Trust Services, C=US", localCa)
        assertEquals(AdGuardHomeVerification.Verdict.DNS_ONLY_NOT_FILTERED, v)
        assertTrue("必须回滚", AdGuardHomeVerification.shouldRollback(v))
    }

    @Test
    fun missingCertificateCountsAsNotFiltered() {
        val v = AdGuardHomeVerification.judge(true, true, null, localCa)
        assertEquals(AdGuardHomeVerification.Verdict.DNS_ONLY_NOT_FILTERED, v)
    }

    @Test
    fun dnsFailureAndConnectionFailureAreDistinguished() {
        val dns = AdGuardHomeVerification.judge(false, false, null, localCa)
        assertEquals(AdGuardHomeVerification.Verdict.DNS_FAILED, dns)
        val conn = AdGuardHomeVerification.judge(true, false, null, localCa)
        assertEquals(AdGuardHomeVerification.Verdict.CONNECTION_FAILED, conn)
        assertTrue(AdGuardHomeVerification.shouldRollback(dns))
        assertTrue(AdGuardHomeVerification.shouldRollback(conn))
    }
}
