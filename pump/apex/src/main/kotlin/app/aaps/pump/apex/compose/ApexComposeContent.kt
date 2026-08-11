package app.aaps.pump.apex.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
            StatusRow(stringResource(R.string.diagnostic_link_state), linkState::class.simpleName ?: "-")
            StatusRow(stringResource(R.string.diagnostic_generation), linkState.generation.toString())
            StatusRow(stringResource(R.string.diagnostic_pending), snapshot.pendingCommand ?: "-")
            StatusRow(stringResource(R.string.diagnostic_queue), snapshot.queuedCommands.toString())
            StatusRow(
                stringResource(R.string.diagnostic_control),
                stringResource(if (controlAllowed) R.string.diagnostic_control_enabled else R.string.diagnostic_control_disabled),
            )
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
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, modifier = Modifier.weight(1f))
            Text(value)
        }
    }

    private fun formatTimestamp(timestamp: Long): String =
        if (timestamp <= 0L) "-" else DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(Date(timestamp))
}
