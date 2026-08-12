package org.adaway.ui.compose

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.outlined.DonutLarge
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
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
 * Root Compose navigation: four destinations (Overview, Statistics,
 * Rules, Logs) in a bottom navigation bar, matching the system-tool
 * pattern.
 */
object Destinations {
    const val OVERVIEW = "overview"
    const val STATS = "stats"
    const val RULES = "rules"
    const val LOGS = "logs"
    const val SETTINGS = "settings"
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
    Destination(Destinations.LOGS, R.string.compose_nav_logs, Icons.Filled.List),
    Destination(Destinations.SETTINGS, R.string.compose_nav_settings, Icons.Outlined.Settings),
)

@Composable
fun AdAwayApp() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination

    Scaffold(
        bottomBar = {
            NavigationBar {
                destinations.forEach { dest ->
                    val selected = currentDestination?.hierarchy
                        ?.any { it.route == dest.route } == true
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
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(Destinations.OVERVIEW) {
                OverviewScreen(viewModel)
            }
            composable(Destinations.STATS) {
                StatisticsScreen(viewModel)
            }
            composable(Destinations.RULES) {
                RulesScreen(viewModel)
            }
            composable(Destinations.LOGS) {
                LogsScreen(viewModel)
            }
            composable(Destinations.SETTINGS) {
                SettingsScreen(viewModel)
            }
        }
    }
}