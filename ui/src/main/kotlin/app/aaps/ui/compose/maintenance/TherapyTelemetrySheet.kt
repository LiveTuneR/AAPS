package app.aaps.ui.compose.maintenance

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aaps.ui.R
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TherapyTelemetrySheet(viewModel: MaintenanceViewModel,onDismiss: () -> Unit) {
    val health by viewModel.telemetryHealth.collectAsStateWithLifecycle()
    val busy by viewModel.telemetryBusy.collectAsStateWithLifecycle()
    var days by remember { mutableIntStateOf(7) }
    var interval by remember { mutableStateOf<Long?>(null) }
    var retention by remember(health.retentionDays) { mutableFloatStateOf(health.retentionDays.toFloat()) }
    ModalBottomSheet(onDismissRequest=onDismiss,sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.telemetry_title),style=MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.telemetry_size,health.bytesOnDisk/1024,health.writerDrops),style=MaterialTheme.typography.bodyMedium)
            if (health.storagePressure || health.writerDrops>0 || health.corruptedRecords>0 || health.lastErrorType!=null)
                Text(stringResource(R.string.telemetry_incomplete),color=MaterialTheme.colorScheme.error)
            Text(stringResource(R.string.telemetry_retention,retention.roundToInt()))
            Slider(value=retention,onValueChange={ retention=it },valueRange=4f..14f,steps=9,
                onValueChangeFinished={ viewModel.setTelemetryRetention(retention.roundToInt()) })
            HorizontalDivider()
            Text(stringResource(R.string.telemetry_period,days))
            Slider(value=days.toFloat(),onValueChange={ days=it.roundToInt() },valueRange=1f..14f,steps=12,enabled=!busy)
            Text(stringResource(R.string.telemetry_interval))
            FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                listOf(null,60_000L,120_000L,300_000L).forEach { value ->
                    FilterChip(selected=interval==value,onClick={ interval=value },enabled=!busy,label={
                        Text(if (value==null) stringResource(R.string.apex7_unknown) else stringResource(R.string.apex7_minutes,value/60_000))
                    })
                }
            }
            Button(onClick={ viewModel.exportTelemetry(days,interval) },enabled=!busy) {
                Icon(Icons.Default.FileDownload,contentDescription=null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(if (busy) R.string.telemetry_exporting else R.string.telemetry_export))
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
