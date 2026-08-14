package org.adaway.ui.compose

/**
 * ADBlock (AdAway fork) Compose UI 入口 Activity。
 *
 * 职责：
 *  1. 首次启动引导（firstRunSetup）：静默选择 ROOT hosts 方法并同步规则源；
 *  2. 根据用户偏好（theme_mode：0=跟随系统 / 1=浅色 / 2=深色）应用主题；
 *  3. 挂载 Compose 导航根组件 AdAwayApp。
 *
 * 注意：本 Activity 不再调用 enableEdgeToEdge()，避免状态栏 inset
 * 双重叠加导致各页面顶部出现空白（历史 BUG）。
 */

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.graphics.toArgb
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
        firstRunSetup()
        setContent {
            val context = androidx.compose.ui.platform.LocalContext.current
            val mode = org.adaway.ui.compose.themeMode(context)
            val darkTheme = when (mode) {
                1 -> false
                2 -> true
                else -> androidx.compose.foundation.isSystemInDarkTheme()
            }
            AdAwayTheme(darkTheme = darkTheme) {
                // 状态栏/导航栏颜色跟随 Compose 主题（修复旧红色 statusBar）
                val surfaceColor = androidx.compose.material3.MaterialTheme.colorScheme.surface
                androidx.compose.runtime.SideEffect {
                    val window = (context as android.app.Activity).window
                    window.statusBarColor = surfaceColor.toArgb()
                    window.navigationBarColor = surfaceColor.toArgb()
                    // 浅色主题时状态栏图标用深色
                    val isLight = !darkTheme
                    window.decorView.systemUiVisibility = if (isLight) {
                        window.decorView.systemUiVisibility or android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                    } else {
                        window.decorView.systemUiVisibility and android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
                    }
                }
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
