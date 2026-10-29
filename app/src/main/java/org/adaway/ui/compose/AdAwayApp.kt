package org.adaway.ui.compose

/**
 * 应用根导航组件：五个主入口 + 次级功能中心。
 *
 * 页面：
 *  - overview  概览（服务状态、hosts 统计、Web 服务器控制）
 *  - stats     统计（累计/环形图/趋势图/活跃应用/证书）
 *  - rules     规则（hosts 黑白名单 + 订阅源管理）
 *  - more      更多功能（日志、放行、覆盖响应、网络中心）
 *  - settings  设置（外观/数据/Web服务器/图表/应用监控/关于）
 */

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.outlined.DonutLarge
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import org.adaway.R

/**
 * Root Compose navigation: Overview, Statistics, Rules, Settings, More.
 * Secondary screens are reachable from More without crowding five primary tabs.
 */
object Destinations {
    const val OVERVIEW = "overview"
    const val STATS = "stats"
    const val RULES = "rules"
    const val SETTINGS = "settings"
    const val NETWORK = "network"
    const val MORE = "more"
    /** Secondary page (not a bottom-bar tab): the full request log. */
    const val LOGS = "logs"
    /** Secondary page: per-domain exemptions (解除过滤), reachable from the rules page. */
    const val EXEMPT = "rules/exempt"
    /** 二级页：按域覆盖响应（仍然拦截，只是换一种回答方式），同样从规则页进入。 */
    const val OVERRIDES = "rules/overrides"
    /** 三级页：单个应用的明细，从统计页「活跃应用」点进去；路由带 uid 参数。 */
    const val APP_DETAIL = "stats/app"
}

private data class Destination(
    val route: String,
    @androidx.annotation.StringRes val labelRes: Int,
    val icon: ImageVector,
)

private val destinations = listOf(
    Destination(Destinations.OVERVIEW, R.string.compose_nav_overview, Icons.Filled.Home),
    Destination(Destinations.STATS, R.string.compose_nav_stats, Icons.Outlined.DonutLarge),
    Destination(Destinations.RULES, R.string.compose_nav_rules, Icons.Outlined.Shield),
    Destination(Destinations.SETTINGS, R.string.compose_nav_settings, Icons.Outlined.Settings),
    Destination(Destinations.MORE, R.string.compose_nav_more, Icons.Outlined.Menu),
)

@Composable
fun AdAwayApp() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination
    // Full-screen secondary workflows already provide their own Back action.
    // Keeping the primary navigation here wastes valuable space on phones.
    val showBottomBar = currentDestination?.route !in setOf(
        Destinations.LOGS,
        Destinations.EXEMPT,
        Destinations.OVERRIDES,
        "${Destinations.APP_DETAIL}/{uid}",
    )

    Scaffold(
        // Child page TopAppBars handle the status bar inset. The root scaffold
        // owns only the bottom NavigationBar; consuming statusBars here adds a
        // second blank strip above the active page on many Android versions.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (showBottomBar) NavigationBar(
                modifier = Modifier.testTag("main-bottom-navigation"),
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                tonalElevation = 0.dp,
            ) {
                destinations.forEach { dest ->
                    val selected = currentDestination?.hierarchy
                        ?.any { it.route == dest.route } == true ||
                        (dest.route == Destinations.MORE && currentDestination?.route in
                            listOf(Destinations.NETWORK, Destinations.LOGS, Destinations.EXEMPT, Destinations.OVERRIDES))
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            navController.navigate(dest.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(dest.icon, contentDescription = null) },
                        label = { Text(stringResource(dest.labelRes)) },
                    )
                }
            }
        },
    ) { innerPadding ->
        val viewModel: StatsViewModel = viewModel()
        NavHost(
            navController = navController,
            startDestination = Destinations.OVERVIEW,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding),
        ) {
            composable(Destinations.OVERVIEW) {
                OverviewScreen(
                    viewModel = viewModel,
                    onOpenStats = {
                        navController.navigate(Destinations.STATS) {
                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    onOpenRules = {
                        navController.navigate(Destinations.RULES) {
                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    onOpenLogs = { navController.navigate(Destinations.LOGS) },
                )
            }
            composable(Destinations.STATS) {
                StatisticsScreen(
                    viewModel,
                    onOpenLogs = { navController.navigate(Destinations.LOGS) },
                    onOpenApp = { uid -> navController.navigate("${Destinations.APP_DETAIL}/$uid") },
                )
            }
            composable(Destinations.RULES) {
                RulesScreen(
                    viewModel,
                    onOpenExempt = { navController.navigate(Destinations.EXEMPT) },
                    onOpenOverrides = { navController.navigate(Destinations.OVERRIDES) },
                )
            }
            composable(Destinations.MORE) {
                MoreScreen(
                    onOpenLogs = { navController.navigate(Destinations.LOGS) },
                    onOpenExempt = { navController.navigate(Destinations.EXEMPT) },
                    onOpenOverrides = { navController.navigate(Destinations.OVERRIDES) },
                    onOpenNetwork = { navController.navigate(Destinations.NETWORK) },
                    onOpenSettings = { navController.navigate(Destinations.SETTINGS) },
                )
            }
            composable(Destinations.NETWORK) {
                NetworkCenterScreen(
                    onOpenSettings = { navController.navigate(Destinations.SETTINGS) },
                    onOpenRules = { navController.navigate(Destinations.RULES) },
                    onOpenStats = { navController.navigate(Destinations.STATS) },
                )
            }
            composable(Destinations.SETTINGS) {
                SettingsScreen(viewModel)
            }
            composable(Destinations.EXEMPT) {
                ExemptionRulesScreen(viewModel, onBack = { navController.popBackStack() })
            }
            composable(Destinations.OVERRIDES) {
                RuleOverridesScreen(onBack = { navController.popBackStack() })
            }
            /* 三级页：uid 走路径参数；无效 uid 交给详情页显示空状态，不崩 */
            composable("${Destinations.APP_DETAIL}/{uid}") { entry ->
                val uid = entry.arguments?.getString("uid")?.toIntOrNull() ?: -1
                AppDetailScreen(viewModel, uid, onBack = { navController.popBackStack() })
            }
            composable(Destinations.LOGS) {
                QueryLogScreen(viewModel, onBack = { navController.popBackStack() })
            }
        }
    }
}
