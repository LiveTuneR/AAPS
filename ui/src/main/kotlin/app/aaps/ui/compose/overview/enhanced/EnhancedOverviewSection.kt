package app.aaps.ui.compose.overview.enhanced

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aaps.ui.R

@Composable
fun EnhancedOverviewSection(viewModel: OverviewDashboardViewModel = hiltViewModel()) {
    val enabled by viewModel.enabled.collectAsStateWithLifecycle()
    if (!enabled) return
    val state by viewModel.state.collectAsStateWithLifecycle()
    EnhancedOverviewContent(state)
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun EnhancedOverviewContent(state: OverviewDashboardState) {
    var selected by rememberSaveable { mutableStateOf<Int?>(null) }
    val unavailable = stringResource(R.string.apex7_unknown)
    Column(Modifier.fillMaxWidth().padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.apex7_overview), style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), maxItemsInEachRow = 2) {
            state.tiles.forEach { tile ->
                OutlinedCard(Modifier.weight(1f).widthIn(min = 120.dp).clickable { selected = tile.title }, shape = RoundedCornerShape(8.dp)) {
                    Column(Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(12.dp)) {
                        Text(stringResource(tile.title), style = MaterialTheme.typography.labelLarge)
                        Text(tile.summary ?: unavailable, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
    state.tiles.firstOrNull { it.title == selected }?.let { tile ->
        ModalBottomSheet(onDismissRequest = { selected = null }) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(tile.title), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    IconButton(onClick = { selected = null }) { Icon(Icons.Default.Close, stringResource(R.string.apex7_close)) }
                }
                tile.fields.forEach { field ->
                    HorizontalDivider()
                    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                        Text(stringResource(field.label), style = MaterialTheme.typography.labelMedium)
                        Text(field.value ?: unavailable, style = MaterialTheme.typography.bodyLarge)
                    }
                }
                Spacer(Modifier.height(32.dp))
            }
        }
    }
}
