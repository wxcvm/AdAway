package org.adaway.ui.compose

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import org.adaway.ui.compose.theme.AdAwayTheme

/**
 * New Material 3 Compose UI entry point.
 *
 * Kept as a separate activity from the legacy Java activities so the
 * new UI can ship and be verified incrementally; the launcher entry
 * can be switched over once the migration is complete.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AdAwayTheme {
                AdAwayApp()
            }
        }
    }
}
