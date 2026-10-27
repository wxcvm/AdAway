package org.adaway.ui.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.adaway.R
import org.adaway.util.ListenerResponseCheck

/** Pure rendering of one completed observation; no transport, polling or inference from today's rules. */
@Composable
internal fun ResponseReportSummary(
    report: ListenerResponseCheck.Report,
    browserEnabled: Boolean,
    onOpenBrowser: (String, String) -> Unit,
) {
    Text(stringResource(R.string.response_summary_observed, report.startedUtc),
        style = MaterialTheme.typography.labelMedium)
    if (report.observations.isEmpty()) {
        Text(stringResource(R.string.response_summary_no_targets), modifier = Modifier.testTag("response-summary-empty"))
    }
    for (observation in report.observations) {
        val id = observation.family.toString() + "-" + observation.https
        Card(modifier = Modifier.fillMaxWidth().testTag("response-summary-" + id),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("IPv" + observation.family + " · " + if (observation.https) "HTTPS" else "HTTP",
                    style = MaterialTheme.typography.titleSmall)
                SelectionContainer { Text(observation.url, style = MaterialTheme.typography.bodySmall) }
                Text(stringResource(when (observation.state) {
                    ListenerResponseCheck.State.RESPONSE -> R.string.response_summary_complete
                    ListenerResponseCheck.State.INCOMPLETE -> R.string.response_summary_incomplete
                    ListenerResponseCheck.State.INVALID_RESPONSE -> R.string.response_summary_invalid
                    ListenerResponseCheck.State.UNAVAILABLE -> R.string.response_summary_unavailable
                    ListenerResponseCheck.State.CERTIFICATE_REJECTED -> R.string.response_summary_certificate
                    ListenerResponseCheck.State.SKIPPED -> R.string.response_summary_skipped
                    else -> R.string.response_summary_unknown
                }), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("response-state-" + id))
                val unknown = stringResource(R.string.response_summary_unknown)
                val status = if (observation.status > 0) observation.status.toString()
                    else unknown
                Text(stringResource(R.string.response_summary_status, status), modifier = Modifier.testTag("response-status-" + id))
                // A 403 is an observed denial, never evidence of an advertising rule hit.
                if (observation.status == 403) Text(stringResource(R.string.response_summary_forbidden),
                    style = MaterialTheme.typography.bodySmall)
                val contentType = observation.contentType.ifEmpty { unknown }
                Text(stringResource(R.string.response_summary_type, contentType))
                Text(stringResource(R.string.response_summary_length,
                    if (observation.declaredLength < 0) unknown
                    else observation.declaredLength.toString()))
                Text(stringResource(R.string.response_summary_bytes, observation.observedBytes,
                    ListenerResponseCheck.RESPONSE_LIMIT, observation.bodyBytes),
                    style = MaterialTheme.typography.bodySmall)
                if (observation.bodyBytes > 0 && observation.sampleTruncated) Text(stringResource(R.string.response_summary_sample_prefix),
                    style = MaterialTheme.typography.bodySmall)
                if (observation.local) {
                    OutlinedButton({ onOpenBrowser(observation.address, observation.url) }, enabled = browserEnabled,
                        modifier = Modifier.testTag("response-browser-" + id)) {
                        Text(stringResource(R.string.response_check_browser, observation.url))
                    }
                }
            }
        }
    }
}
