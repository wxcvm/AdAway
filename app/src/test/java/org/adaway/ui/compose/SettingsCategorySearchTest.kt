package org.adaway.ui.compose

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Settings search must never mutate persisted preferences or network state. */
class SettingsCategorySearchTest {
    @Test fun multipleTermsCanMatchAcrossIndependentFields() {
        assertTrue(settingsCategoryMatches(
            title = "Blocking & redirection",
            description = "AdGuard coexistence and application exceptions",
            keywords = "DNS VPN TUN",
            search = "adguard dns",
        ))
        assertTrue(settingsCategoryMatches(
            "Web server", "HTTP and HTTPS listener", "ports", "  HTTPS   WEB "
        ))
        assertFalse(settingsCategoryMatches(
            "Web server", "HTTP and HTTPS listener", "ports", "adguard dns"
        ))
    }

    @Test fun blankAndUnicodeSearchStayPredictable() {
        assertTrue(settingsCategoryMatches("证书", "HTTPS", "CA", "  "))
        assertTrue(settingsCategoryMatches("证书管理", "信任设置", "CA", "证书 信任"))
        assertFalse(settingsCategoryMatches("证书管理", "信任设置", "CA", "证书 网络"))
        assertTrue(settingsCategoryMatches("App appearance", "Theme", "", "THEME app"))
    }
}
