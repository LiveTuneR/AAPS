package app.aaps.pump.apex.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.ui.compose.ComposablePluginContent
import app.aaps.core.ui.compose.LocalSnackbarHostState
import app.aaps.core.ui.compose.ToolbarConfig
import app.aaps.pump.apex.ApexCommDirector
import app.aaps.pump.apex.ApexCompatibility
import app.aaps.pump.apex.ApexPump
import app.aaps.pump.apex.ApexPumpPlugin
import app.aaps.pump.apex.bolus.ApexBolusCoordinator
import app.aaps.pump.apex.bolus.ApexBolusState
import app.aaps.pump.apex.R
import app.aaps.pump.apex.connectivity.FirmwareVersion
import app.aaps.pump.apex.diagnostics.ApexTrace
import app.aaps.pump.apex.utils.keys.ApexBooleanKey
import app.aaps.pump.apex.utils.keys.ApexStringKey
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

class ApexComposeContent(
    private val pluginName: String,
    private val plugin: ApexPumpPlugin,
    private val pump: ApexPump,
    private val commDirector: ApexCommDirector,
    private val trace: ApexTrace,
    private val bolusCoordinator: ApexBolusCoordinator,
    private val preferences: Preferences,
) : ComposablePluginContent {

    @Composable
    override fun Render(
        setToolbarConfig: (ToolbarConfig) -> Unit,
        onNavigateBack: () -> Unit,
        onSettings: (() -> Unit)?,
    ) {
        val context = LocalContext.current
        val snackbar = LocalSnackbarHostState.current
        val scope = rememberCoroutineScope()
        val linkState by commDirector.linkState.collectAsStateWithLifecycle()
        val lastDataTime by pump.lastDataTimeFlow.collectAsStateWithLifecycle()
        val reservoir by pump.reservoirFlow.collectAsStateWithLifecycle()
        val battery by pump.batteryFlow.collectAsStateWithLifecycle()
        val unresolvedBolus by bolusCoordinator.active.collectAsStateWithLifecycle()
        var reconciling by remember { mutableStateOf(false) }
        var showManualConfirmation by remember { mutableStateOf(false) }
        var showBolusDetails by remember { mutableStateOf(false) }
        var diagnosticTick by remember { mutableIntStateOf(0) }
        LaunchedEffect(Unit) {
            while (true) {
                delay(1_000L)
                diagnosticTick++
            }
        }
        @Suppress("UNUSED_VARIABLE") val tick = diagnosticTick
        val snapshot = commDirector.diagnosticSnapshot()
        val selectedVersion = FirmwareVersion.entries.firstOrNull {
            it.name == preferences.get(ApexStringKey.FirmwareVer)
        } ?: FirmwareVersion.AUTO
        val controlAllowed = ApexCompatibility.isControlEligible(
            version = pump.firmwareVersion,
            selectedVersion = selectedVersion,
            enabled = preferences.get(ApexBooleanKey.EnableExperimentalControl),
        )

        val navigationIcon: @Composable () -> Unit = {
            IconButton(onClick = onNavigateBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(app.aaps.core.ui.R.string.back))
            }
        }
        val actions: @Composable RowScope.() -> Unit = {
            IconButton(onClick = { plugin.reconnectForUi() }) {
                Icon(Icons.Filled.Bluetooth, contentDescription = stringResource(R.string.diagnostic_reconnect))
            }
            IconButton(onClick = { scope.launch { plugin.refreshForUi() } }) {
                Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.diagnostic_refresh))
            }
            IconButton(onClick = {
                scope.launch {
                    trace.record(
                        "manual_export_requested",
                        generation = snapshot.generation,
                        fields = mapOf(
                            "state" to snapshot.state,
                            "queuedCommands" to snapshot.queuedCommands,
                            "pendingCommand" to snapshot.pendingCommand,
                            "stateAgeMs" to snapshot.stateAgeMs,
                            "progressAgeMs" to snapshot.progressAgeMs,
                        ),
                    )
                    val result = runCatching { trace.share(context) }
                    if (result.isFailure) snackbar.showSnackbar(context.getString(R.string.diagnostic_export_failed))
                }
            }) {
                Icon(Icons.Filled.Share, contentDescription = stringResource(R.string.diagnostic_export))
            }
            onSettings?.let { openSettings ->
                IconButton(onClick = openSettings) {
                    Icon(Icons.Filled.Settings, contentDescription = stringResource(app.aaps.core.ui.R.string.settings))
                }
            }
        }
        LaunchedEffect(Unit) {
            setToolbarConfig(ToolbarConfig(title = pluginName, navigationIcon = navigationIcon, actions = actions))
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            StatusRow(stringResource(R.string.diagnostic_link_state), linkStateLabel(linkState))
            StatusRow(stringResource(R.string.diagnostic_generation), linkState.generation.toString())
            StatusRow(stringResource(R.string.diagnostic_pending), snapshot.pendingCommand ?: "-")
            StatusRow(stringResource(R.string.diagnostic_queue), snapshot.queuedCommands.toString())
            StatusRow(
                stringResource(R.string.diagnostic_control),
                stringResource(
                    when {
                        unresolvedBolus != null -> R.string.bolus_reconciliation_required
                        controlAllowed -> R.string.diagnostic_control_enabled
                        else -> R.string.diagnostic_control_disabled
                    },
                ),
            )
            if (unresolvedBolus == null) {
                StatusRow(stringResource(R.string.diagnostic_bolus_state), stringResource(R.string.diagnostic_reconciliation_not_required))
            }
            unresolvedBolus?.let { operation ->
                HorizontalDivider()
                Text(stringResource(R.string.bolus_uncertain_title), style = MaterialTheme.typography.titleMedium)
                StatusRow(stringResource(R.string.diagnostic_bolus_state), bolusStateLabel(operation.state))
                StatusRow(stringResource(R.string.diagnostic_bolus_operation), operation.operationUuid.take(8))
                StatusRow(stringResource(R.string.diagnostic_bolus_requested), String.format("%.3f U / %d", operation.requestedUnits, operation.encodedSteps))
                StatusRow(stringResource(R.string.diagnostic_bolus_created), formatTimestamp(operation.createdUtc))
                StatusRow(stringResource(R.string.diagnostic_bolus_transport), transportLabel(operation.transportWriteIssued))
                StatusRow(stringResource(R.string.diagnostic_bolus_accepted), yesNoUnknown(operation.acceptedObserved.takeIf { operation.acceptedUtc != null }))
                StatusRow(stringResource(R.string.diagnostic_bolus_progress), "${operation.highestProgressSteps} / ${operation.encodedSteps}")
                StatusRow(stringResource(R.string.diagnostic_bolus_completed), yesNoUnknown(operation.completedObserved))
                StatusRow(stringResource(R.string.diagnostic_bolus_latest_history), historyResult(operation.latestHistoryResult, operation.latestHistoryCheckedUtc))
                StatusRow(stringResource(R.string.diagnostic_bolus_full_history), historyResult(operation.fullHistoryResult, operation.fullHistoryCheckedUtc))
                StatusRow(stringResource(R.string.diagnostic_bolus_last_error), reconciliationReasonLabel(operation.reconciliationReason))
                StatusRow(stringResource(R.string.diagnostic_bolus_age), formatDuration(System.currentTimeMillis() - operation.createdUtc))
                StatusRow(stringResource(R.string.diagnostic_bolus_last_reconcile), formatTimestamp(operation.lastReconciliationUtc ?: 0L))
                StatusRow(stringResource(R.string.diagnostic_bolus_attempts), "${operation.automaticAttempts} auto / ${operation.manualAttempts} manual")
                StatusRow(stringResource(R.string.diagnostic_bolus_next_attempt), formatTimestamp(operation.nextAutomaticAttemptUtc ?: 0L))
                StatusRow(stringResource(R.string.diagnostic_control), stringResource(R.string.diagnostic_insulin_blocked))
                Text(stringResource(R.string.bolus_blocked_commands))
                Button(
                    onClick = {
                        scope.launch {
                            reconciling = true
                            val resolved = plugin.reconcileBolusForUi() && bolusCoordinator.current() == null
                            snackbar.showSnackbar(context.getString(if (resolved) R.string.diagnostic_reconcile_success else R.string.diagnostic_reconcile_failed))
                            reconciling = false
                        }
                    },
                    enabled = !reconciling,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.diagnostic_reconcile_now)) }
                OutlinedButton(onClick = { showBolusDetails = !showBolusDetails }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.diagnostic_details))
                }
                if (showBolusDetails) {
                    Text(
                        "${operation.state.name}\n${operation.transportOutcome ?: "UNKNOWN"}\n" +
                            "generation=${operation.transportGeneration ?: snapshot.generation}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (bolusCoordinator.canOperatorResolve(operation.operationUuid)) {
                    OutlinedButton(onClick = { showManualConfirmation = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.diagnostic_manual_resolve))
                    }
                }
                if (showManualConfirmation) {
                    AlertDialog(
                        onDismissRequest = { showManualConfirmation = false },
                        title = { Text(stringResource(R.string.diagnostic_manual_title)) },
                        text = { Text(stringResource(R.string.diagnostic_manual_message, operation.requestedUnits, operation.operationUuid.take(8))) },
                        confirmButton = {
                            Button(onClick = {
                                showManualConfirmation = false
                                scope.launch {
                                    val recorded = plugin.operatorConfirmBolusNotDelivered(operation.operationUuid)
                                    snackbar.showSnackbar(context.getString(if (recorded) R.string.diagnostic_manual_recorded else R.string.diagnostic_reconcile_failed))
                                }
                            }) { Text(stringResource(R.string.diagnostic_manual_confirm)) }
                        },
                        dismissButton = {
                            TextButton(onClick = { showManualConfirmation = false }) {
                                Text(stringResource(android.R.string.cancel))
                            }
                        },
                    )
                }
            }
            HorizontalDivider()
            StatusRow(
                stringResource(R.string.diagnostic_firmware),
                pump.firmwareVersion?.let { "${it.firmwareMajor}.${it.firmwareMinor}" } ?: "-",
            )
            StatusRow(
                stringResource(R.string.diagnostic_protocol),
                pump.firmwareVersion?.let { "${it.protocolMajor}.${it.protocolMinor}" } ?: "-",
            )
            StatusRow(stringResource(R.string.diagnostic_last_data), formatTimestamp(lastDataTime))
            StatusRow(stringResource(R.string.diagnostic_reservoir), String.format("%.3f U", reservoir.cU))
            StatusRow(stringResource(R.string.diagnostic_battery), battery?.let { "$it%" } ?: "-")
        }
    }

    @Composable
    private fun StatusRow(label: String, value: String) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Text(value, style = MaterialTheme.typography.bodyMedium)
        }
    }

    @Composable
    private fun bolusStateLabel(state: ApexBolusState): String = stringResource(when (state) {
        ApexBolusState.CONFIRMED_DELIVERED -> R.string.bolus_state_confirmed
        ApexBolusState.DEFINITELY_NOT_DELIVERED, ApexBolusState.REJECTED_BEFORE_DELIVERY -> R.string.bolus_state_not_delivered
        ApexBolusState.PARTIALLY_DELIVERED_CONFIRMED -> R.string.bolus_state_partial
        ApexBolusState.RECONCILIATION_REQUIRED -> R.string.bolus_state_reconciliation
        ApexBolusState.OPERATOR_CONFIRMED_NOT_DELIVERED -> R.string.bolus_state_operator_not_delivered
        else -> R.string.bolus_state_uncertain
    })

    @Composable
    private fun linkStateLabel(state: ApexCommDirector.LinkState): String = stringResource(when (state) {
        ApexCommDirector.LinkState.Stopped -> R.string.link_state_stopped
        is ApexCommDirector.LinkState.Disconnected -> R.string.link_state_disconnected
        is ApexCommDirector.LinkState.Connecting -> R.string.link_state_connecting
        is ApexCommDirector.LinkState.Handshaking -> R.string.link_state_handshaking
        is ApexCommDirector.LinkState.Ready -> R.string.link_state_ready
        is ApexCommDirector.LinkState.Incompatible -> R.string.link_state_incompatible
        is ApexCommDirector.LinkState.Backoff -> R.string.link_state_backoff
    })

    @Composable
    private fun transportLabel(issued: Boolean?): String = stringResource(when (issued) {
        true -> R.string.diagnostic_transport_sent
        false -> R.string.diagnostic_transport_not_sent
        null -> R.string.diagnostic_transport_unknown
    })

    @Composable
    private fun yesNoUnknown(value: Boolean?): String = stringResource(when (value) {
        true -> R.string.diagnostic_yes
        false -> R.string.diagnostic_no
        null -> R.string.diagnostic_no_data
    })

    @Composable
    private fun historyResult(result: String?, timestamp: Long?): String =
        "${result ?: stringResource(R.string.diagnostic_no_data)} · ${formatTimestamp(timestamp ?: 0L)}"

    @Composable
    private fun reconciliationReasonLabel(reason: String?): String = when (reason) {
        "legacy_transport_evidence_incomplete" -> stringResource(R.string.diagnostic_reason_legacy_unknown)
        "full_history_no_match" -> stringResource(R.string.diagnostic_reason_full_no_match)
        "latest_history_no_match" -> stringResource(R.string.diagnostic_reason_latest_no_match)
        "different_pump_identity" -> stringResource(R.string.diagnostic_reason_different_pump)
        "history_query_timeout" -> stringResource(R.string.diagnostic_reason_query_timeout)
        "reconnect_failed" -> stringResource(R.string.diagnostic_reason_reconnect_failed)
        null -> stringResource(R.string.diagnostic_no_data)
        else -> reason
    }

    private fun formatTimestamp(timestamp: Long): String =
        if (timestamp <= 0L) "-" else DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(Date(timestamp))

    private fun formatDuration(durationMs: Long): String = "${durationMs.coerceAtLeast(0L) / 1_000}s"
}
