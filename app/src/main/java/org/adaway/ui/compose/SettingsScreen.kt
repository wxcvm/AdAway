package org.adaway.ui.compose

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.adaway.R

/**
 * Settings navigation owns stable category IDs (0..6), search and saveable scroll.
 * Each category owns only its content; retained observations stay in StatsViewModel.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: StatsViewModel,
    certificateObservation: CertificateObservation = viewModel.certificateObservation,
) {
    val networkActions = rememberNetworkSettingsActions()
    var refreshKey by remember { mutableStateOf(0) }
    // Keep the chosen section across activity recreation, but never persist
    // transient UI state or change the underlying settings.
    var selectedSection by rememberSaveable { mutableIntStateOf(-1) }
    var settingsSearch by rememberSaveable { mutableStateOf("") }
    BackHandler(enabled = selectedSection >= 0) { selectedSection = -1 }
    // A section owns its scroll state. Restore during composition, never scroll after the first frame.
    val sectionStates = rememberSaveableStateHolder()
    // Keep one saveable scroll state per fixed category outside the conditional
    // detail subtree, available before that category's first layout.
    val sectionScrolls = (0..6).map { section ->
        androidx.compose.runtime.key(section) { rememberScrollState() }
    }
    val sectionTitles = listOf(
        stringResource(R.string.compose_settings_section_1_title),
        stringResource(R.string.compose_settings_section_2_title),
        stringResource(R.string.compose_settings_section_3_title),
        stringResource(R.string.compose_settings_section_4_title),
        stringResource(R.string.compose_settings_section_5_title),
        stringResource(R.string.compose_settings_section_6_title),
        stringResource(R.string.compose_settings_section_7_title),
    )
    val sectionDescriptions = listOf(
        stringResource(R.string.compose_settings_section_1_description),
        stringResource(R.string.compose_settings_section_2_description),
        stringResource(R.string.compose_settings_section_3_description),
        stringResource(R.string.compose_settings_section_4_description),
        stringResource(R.string.compose_settings_section_5_description),
        stringResource(R.string.compose_settings_section_6_description),
        stringResource(R.string.compose_settings_section_7_description),
    )

    // Search uses existing localized option names. A hit opens its category,
    // never changes a preference and never silently activates network mode.
    val sectionSearchHints = listOf(
        listOf(
            stringResource(R.string.compose_settings_block_mode_title),
            stringResource(R.string.compose_settings_allowlist_title),
            stringResource(R.string.compose_tun_diagnostic_title),
            "AdGuard",
        ),
        listOf(stringResource(R.string.compose_settings_light_mode), "HTTP", "HTTPS"),
        listOf(
            stringResource(R.string.compose_settings_cert_title),
            stringResource(R.string.compose_settings_pin_policy),
        ),
        listOf(stringResource(R.string.compose_settings_cache_reuse)),
        listOf(
            stringResource(R.string.compose_settings_charts_title),
            stringResource(R.string.compose_settings_realtime),
        ),
        listOf(
            stringResource(R.string.compose_settings_autostart),
            stringResource(R.string.compose_settings_theme_title),
        ),
        listOf(
            stringResource(R.string.compose_settings_backup),
            stringResource(R.string.compose_settings_debug_title),
            stringResource(R.string.compose_settings_ipv6),
            stringResource(R.string.compose_settings_about_title),
        ),
    )

    Scaffold(
        modifier = Modifier.background(MaterialTheme.colorScheme.background).testTag("settings-screen"),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (selectedSection < 0) stringResource(R.string.compose_settings_title)
                        else sectionTitles[selectedSection],
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    if (selectedSection >= 0) {
                        IconButton(onClick = { selectedSection = -1 }, modifier = Modifier.testTag("settings-section-back")) {
                            Icon(
                                Icons.Outlined.ArrowBack,
                                contentDescription = stringResource(R.string.compose_settings_home_back),
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).fillMaxSize().background(MaterialTheme.colorScheme.background),
        ) {
            if (selectedSection < 0) {
                SettingsCategoryNavigation(
                    titles = sectionTitles,
                    descriptions = sectionDescriptions,
                    keywords = listOf(
                        stringResource(R.string.compose_settings_section_1_keywords),
                        stringResource(R.string.compose_settings_section_2_keywords),
                        stringResource(R.string.compose_settings_section_3_keywords),
                        stringResource(R.string.compose_settings_section_4_keywords),
                        stringResource(R.string.compose_settings_section_5_keywords),
                        stringResource(R.string.compose_settings_section_6_keywords),
                        stringResource(R.string.compose_settings_section_7_keywords),
                    ).mapIndexed { index, aliases ->
                        aliases + " " + sectionSearchHints[index].joinToString(" ")
                    },
                    search = settingsSearch,
                    onSearchChange = { settingsSearch = it },
                    onSelect = { selectedSection = it },
                )
            } else {
                sectionStates.SaveableStateProvider(selectedSection) {
                val settingsScroll = sectionScrolls[selectedSection]
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .testTag("settings-section-scroll-$selectedSection")
                        .verticalScroll(settingsScroll)
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    SettingsCategoryIntroduction(
                        index = selectedSection + 1,
                        title = sectionTitles[selectedSection],
                        description = sectionDescriptions[selectedSection],
                    )
                    when (selectedSection) {
                        0 -> NetworkSettingsSection(viewModel, networkActions, onPreferencesChanged = { refreshKey++ })
                        1 -> WebServerSettingsSection()
                        2 -> CertificateSettingsSection(viewModel, certificateObservation)
                        3 -> ContentResponseSettingsCard(cache = viewModel.contentResponseCache)
                        4 -> StatisticsSettingsSection(viewModel)
                        5 -> AppSettingsSection(viewModel, refreshKey, onPreferencesChanged = { refreshKey++ })
                        6 -> ToolsSettingsSection(viewModel)
                    else -> Unit
                    }
                }
                }
            }
        }
    }
}
