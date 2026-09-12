package app.aaps.ui.compose.overview.enhanced

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.aaps.core.interfaces.overview.graph.BgRange
import app.aaps.ui.R
import app.aaps.ui.compose.overview.graphs.BgInfoUiState

private val green = Color(0xFF32E96C)
private val cyan = Color(0xFF6DD9ED)
private val amber = Color(0xFFFFBB68)
private val violet = Color(0xFFB89AEC)

/** Read-only operational overview; commands remain owned by the existing management UI. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EnhancedOverviewContent(
    state: OverviewDashboardState,
    bg: BgInfoUiState = BgInfoUiState(null, ""),
    target: String? = null,
    targetActive: Boolean = false,
    smbEnabled: Boolean = false,
    modeNotice: String? = null,
    modifier: Modifier = Modifier,
    banner: @Composable () -> Unit = {},
    graphs: @Composable () -> Unit = {}
) {
    var selected by rememberSaveable { mutableStateOf<Int?>(null) }
    val unknown = stringResource(R.string.apex7_unavailable_short)
    val v = state.vitals
    var displayNow by remember(state.capturedAt) { mutableLongStateOf(state.capturedAt ?: System.currentTimeMillis()) }
    LaunchedEffect(state.capturedAt) {
        val base = state.capturedAt ?: System.currentTimeMillis()
        val started = android.os.SystemClock.elapsedRealtime()
        while (true) {
            displayNow = base + android.os.SystemClock.elapsedRealtime() - started
            kotlinx.coroutines.delay(1000)
        }
    }
    val activityAge = v.activityUpdatedAt?.let { displayNow - it }?.takeIf { it >= 0 }
    val algorithmTitle = v.algorithmTitle
    fun summary(title: Int) = state.tiles.firstOrNull { it.title == title }?.summary
    val details = state.tiles + DashboardTile(R.string.apex7_target_short, target, listOf(DashboardField(R.string.apex7_target_short, target))) +
        DashboardTile(R.string.apex7_profile, v.profile, listOf(DashboardField(R.string.apex7_profile, v.profile)))
    val header: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            banner()
            BoxWithConstraints {
                val metrics: @Composable () -> Unit = {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            Metric(if (targetActive) R.string.apex7_temp_target_short else R.string.apex7_target_short, target, if (targetActive) amber else green,
                                Icons.Default.MyLocation, Modifier.weight(1f), "detail-${R.string.apex7_target_short}") { selected = R.string.apex7_target_short }
                            Metric(R.string.apex7_iob, summary(R.string.apex7_iob), cyan, Icons.Default.WaterDrop, Modifier.weight(1f)) { selected = R.string.apex7_iob }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            Metric(R.string.apex7_cob, summary(R.string.apex7_cob), amber, Icons.Default.Restaurant, Modifier.weight(1f)) { selected = R.string.apex7_cob }
                            Metric(R.string.apex7_isf_short, v.isf, cyan, Icons.Default.Functions, Modifier.weight(1f), "detail-${R.string.apex7_isfcr}",
                                v.baseIsf?.let { stringResource(R.string.apex7_base_short, it) },
                                v.units?.let { stringResource(R.string.apex7_isf_unit_label, it) }) { selected = R.string.apex7_isfcr }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            Metric(R.string.apex7_cr_short, v.cr, amber, Icons.Default.Grain, Modifier.weight(1f)) { selected = R.string.apex7_isfcr }
                            Metric(algorithmTitle, v.autoIsf, cyan, Icons.Default.BarChart, Modifier.weight(1f), "detail-${R.string.apex7_autoisf}") { selected = R.string.apex7_autoisf }
                        }
                    }
                }
                if (maxWidth < 350.dp) Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    GlucoseDial(bg, v.units, Modifier.size(140.dp))
                    metrics()
                } else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlucoseDial(bg, v.units, Modifier.size(132.dp))
                    Box(Modifier.weight(1f)) { metrics() }
                }
            }
            Surface(Modifier.fillMaxWidth().testTag("detail-${R.string.apex7_activity}").clickable { selected = R.string.apex7_activity },
                shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                Row(Modifier.padding(horizontal = 8.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.DirectionsWalk, null, tint = green, modifier = Modifier.size(20.dp))
                    Column(Modifier.weight(1f)) {
                        Text("${stringResource(R.string.apex7_activity)}: ${v.activity ?: unknown}", fontSize = 12.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium)
                        v.activityDetail?.let { Text(it, fontSize = 10.sp, lineHeight = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                    activityAge?.let { age ->
                        Text(if (age < 60_000) stringResource(R.string.apex7_updated_seconds, age / 1000)
                            else stringResource(R.string.apex7_updated_minutes, age / 60_000),
                            Modifier.testTag("activity-updated"), fontSize = 9.sp, lineHeight = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.Default.ChevronRight, null, Modifier.size(18.dp))
                }
            }
            Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Device(R.string.apex7_pump, R.drawable.ic_overview_pump, cyan,
                    when (v.pumpConnected) { true -> stringResource(R.string.apex7_connected); false -> stringResource(R.string.apex7_disconnected); null -> unknown },
                    listOfNotNull(v.reservoir, v.battery).joinToString(" / ").ifEmpty { unknown },
                    v.pumpConnected == false, Modifier.weight(1f)) { selected = R.string.apex7_pump }
                Device(R.string.apex7_site_short, R.drawable.ic_overview_cannula, violet, v.siteAge ?: unknown,
                    if (v.siteWarning) stringResource(R.string.apex7_age_warning) else null, v.siteWarning,
                    Modifier.weight(1f), "detail-${R.string.apex7_site}") { selected = R.string.apex7_site }
                Device(R.string.apex7_sensor, R.drawable.ic_overview_cgm, green, v.sensorAge ?: unknown,
                    v.bgAge?.let { "BG $it" }, bg.bgInfo?.isOutdated == true, Modifier.weight(1f)) { selected = R.string.apex7_sensor }
            }
            BoxWithConstraints(Modifier.fillMaxWidth().testTag("overview-status-strip")) {
            val rowWidth = maxOf(maxWidth, 360.dp * LocalDensity.current.fontScale)
            Row(Modifier.horizontalScroll(rememberScrollState()).width(rowWidth), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                Status(R.string.apex7_smb, when (v.smbState) {
                    OverviewSmbState.ON -> "ON"; OverviewSmbState.WAIT -> "WAIT"; OverviewSmbState.OFF -> "OFF"; OverviewSmbState.UNKNOWN -> "--"
                }, Icons.Default.Bolt, when (v.smbState) {
                    OverviewSmbState.ON -> green; OverviewSmbState.WAIT -> amber; OverviewSmbState.OFF -> MaterialTheme.colorScheme.error; OverviewSmbState.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant
                }, Modifier.weight(1f)) { selected = R.string.apex7_smb }
                Status(R.string.apex7_loop_short, v.loopAge, Icons.Default.Sync, cyan, Modifier.weight(1f), "detail-${R.string.apex7_loop}") { selected = R.string.apex7_loop }
                Status(R.string.apex7_sync_short, v.syncAge, Icons.Default.CloudQueue, cyan, Modifier.weight(1f)) { selected = R.string.apex7_pump }
                Status(R.string.apex7_profile_short, v.profile, Icons.Default.Person, violet, Modifier.weight(1.35f), "detail-${R.string.apex7_profile}") { selected = R.string.apex7_profile }
            }
            }
            modeNotice?.let {
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Default.Info, null, Modifier.size(16.dp), tint = amber)
                    Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
    BoxWithConstraints(modifier.fillMaxSize().padding(horizontal = 8.dp)) {
        if (maxWidth >= 700.dp) Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 4.dp)) { header() }
            Column(Modifier.weight(1.2f).verticalScroll(rememberScrollState()).testTag("overview-graphs")) { graphs() }
        } else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            header()
            Column(Modifier.fillMaxWidth().testTag("overview-graphs")) { graphs() }
        }
    }
    details.firstOrNull { it.title == selected }?.let { tile ->
        ModalBottomSheet(onDismissRequest = { selected = null }) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(if (tile.title == R.string.apex7_autoisf) algorithmTitle else tile.title), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    IconButton(onClick = { selected = null }) { Icon(Icons.Default.Close, stringResource(R.string.apex7_close)) }
                }
                tile.fields.forEach { field ->
                    HorizontalDivider()
                    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                        Text(stringResource(field.label), style = MaterialTheme.typography.labelMedium)
                        Text(field.value ?: stringResource(R.string.apex7_unknown), style = MaterialTheme.typography.bodyLarge)
                    }
                }
                Spacer(Modifier.height(32.dp))
            }
        }
    }
}

@Composable
private fun GlucoseDial(bg: BgInfoUiState, units: String?, modifier: Modifier) {
    val info = bg.bgInfo
    val color = when { info == null || info.isOutdated -> MaterialTheme.colorScheme.onSurfaceVariant; info.bgRange == BgRange.LOW -> MaterialTheme.colorScheme.error; info.bgRange == BgRange.HIGH -> amber; else -> green }
    Box(modifier.testTag("overview-bg"), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize().padding(4.dp)) { drawCircle(color.copy(alpha = 0.55f), style = Stroke(6.dp.toPx())) }
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text(info?.bgText ?: "--", color = color, fontSize = 44.sp, fontWeight = FontWeight.Bold,
                textDecoration = if (info?.isOutdated == true) TextDecoration.LineThrough else TextDecoration.None)
            Text(units ?: "--", fontSize = 12.sp)
            Text(listOfNotNull(info?.trendArrow?.symbol, info?.deltaText).joinToString("  "), fontSize = 16.sp)
            Text(bg.timeAgoText, fontSize = 11.sp)
        }
    }
}

@Composable
private fun Metric(label: Int, value: String?, tint: Color, icon: ImageVector, modifier: Modifier, tag: String = "detail-$label", secondary: String? = null, unit: String? = null, onClick: () -> Unit) {
    Surface(modifier.heightIn(min = 42.dp).testTag(tag).clickable(onClick = onClick), shape = RoundedCornerShape(6.dp),
        color = tint.copy(alpha = 0.08f), border = BorderStroke(0.5.dp, tint.copy(alpha = 0.4f))) {
        Row(Modifier.padding(5.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(icon, null, Modifier.size(16.dp), tint = tint)
            Column(Modifier.weight(1f)) {
                Text(stringResource(label), fontSize = 10.sp, lineHeight = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(buildAnnotatedString {
                    append(value ?: "--")
                    secondary?.let { withStyle(SpanStyle(fontSize = 9.sp, fontWeight = FontWeight.Normal)) { append(" ($it)") } }
                }, fontSize = 14.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold)
                unit?.let { Text(it, fontSize = 9.sp, lineHeight = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}

@Composable
private fun Device(label: Int, icon: Int, tint: Color, value: String, detail: String?, warning: Boolean, modifier: Modifier, tag: String = "detail-$label", onClick: () -> Unit) {
    Surface(modifier.fillMaxHeight().heightIn(min = 76.dp).testTag(tag).clickable(onClick = onClick), shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.surfaceContainer, border = BorderStroke(0.5.dp, if (warning) MaterialTheme.colorScheme.error else tint.copy(alpha = 0.25f))) {
        Column(Modifier.padding(6.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(painterResource(icon), null, Modifier.size(20.dp), tint = tint)
                Text(stringResource(label), fontSize = 11.sp, fontWeight = FontWeight.Medium)
            }
            Text(value, fontSize = 13.sp, lineHeight = 15.sp, fontWeight = FontWeight.SemiBold, color = if (warning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
            detail?.let { Text(it, fontSize = 10.sp, lineHeight = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun Status(label: Int, value: String?, icon: ImageVector, tint: Color, modifier: Modifier, tag: String = "detail-$label", onClick: () -> Unit) {
    Row(modifier.height(32.dp).testTag(tag).clickable(onClick = onClick).padding(2.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        Icon(icon, null, Modifier.size(12.dp), tint = tint)
        Text("${stringResource(label)} ${value ?: "--"}", color = tint, fontSize = 10.sp, maxLines = 1,
            fontWeight = FontWeight.Medium)
    }
}
