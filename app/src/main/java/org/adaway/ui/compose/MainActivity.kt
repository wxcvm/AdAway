package org.adaway.ui.compose

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.adaway.AdAwayApplication
import org.adaway.helper.PreferenceHelper
import org.adaway.model.adblocking.AdBlockMethod
import org.adaway.ui.compose.theme.AdAwayTheme
import timber.log.Timber

/**
 * New Material 3 Compose UI entry point.
 *
 * First-run setup: the legacy VPN method was removed, so there is no
 * welcome wizard anymore. On first launch we silently default to the
 * ROOT hosts-file method and kick off an initial hosts-source sync in
 * the background (mirrors the old WelcomeSyncFragment behaviour).
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        firstRunSetup()
        setContent {
            AdAwayTheme {
                AdAwayApp()
            }
        }
    }

    private fun firstRunSetup() {
        if (PreferenceHelper.getAdBlockMethod(this) != AdBlockMethod.UNDEFINED) {
            return
        }
        PreferenceHelper.setAbBlockMethod(this, AdBlockMethod.ROOT)
        val application = application as AdAwayApplication
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    val sourceModel = application.getSourceModel()
                    sourceModel.enableAllSources()
                    sourceModel.retrieveHostsSources()
                    application.getAdBlockModel().apply()
                    Timber.i("First-run setup: initial hosts sync completed")
                } catch (e: Exception) {
                    Timber.w(e, "First-run setup: initial sync failed")
                }
            }
        }
    }
}
