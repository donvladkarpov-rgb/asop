package ru.asop.terminal.ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import ru.asop.terminal.service.GpsTrackingService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    terminalViewModel: TerminalViewModel = hiltViewModel(),
    syncViewModel: SyncViewModel = hiltViewModel(),
    referenceSyncViewModel: ReferenceSyncViewModel = hiltViewModel(),
    sessionFlowViewModel: SessionFlowViewModel = hiltViewModel()
) {
    val terminalState by terminalViewModel.state.collectAsState()
    val terminal by terminalViewModel.terminalInfo.collectAsState()
    val terminalId by terminalViewModel.terminalId.collectAsState()
    val pendingCount by syncViewModel.pendingCount.collectAsState()
    val currentSession by syncViewModel.currentSession.collectAsState()
    val lastSyncTime by syncViewModel.lastSyncTime.collectAsState()
    val activeReferenceCount by referenceSyncViewModel.activeReferenceCount.collectAsState()
    val pendingDeltaCount by referenceSyncViewModel.pendingDeltaCount.collectAsState()
    val deltaProgress by referenceSyncViewModel.deltaProgress.collectAsState()
    val sessionState by sessionFlowViewModel.state.collectAsState()

    LaunchedEffect(terminalId) {
        if (terminalId != null && terminal == null) {
            terminalViewModel.loadTerminal(terminalId!!)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("ASOP Терминал") },
                actions = {
                    if (pendingCount > 0) {
                        BadgedBox(badge = {
                            Badge { Text("$pendingCount") }
                        }) {
                            Text("Ожидание")
                        }
                        Spacer(Modifier.width(12.dp))
                    }
                    Switch(
                        checked = syncViewModel.syncEnabled.collectAsState().value,
                        onCheckedChange = { syncViewModel.setSyncEnabled(it) }
                    )
                    Spacer(Modifier.width(8.dp))
                }
            )
        },
        bottomBar = {
            Column {
                // Промпт 011: постоянный informer о состоянии смены/рейса (поверх sync progress)
                ShiftTripInformer(state = sessionState)
                val syncActive = pendingDeltaCount > 0 || deltaProgress != null
                AnimatedVisibility(
                    visible = syncActive,
                    enter = slideInVertically { it },
                    exit = slideOutVertically { it }
                ) {
                    Surface(
                        tonalElevation = 2.dp,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column {
                            val p = deltaProgress
                            if (p != null && p.totalChunks > 0) {
                                LinearProgressIndicator(
                                    progress = { p.fraction },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            } else {
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                if (p != null && p.totalChunks > 0 && p.fraction > 0f) {
                                    val estimated = (activeReferenceCount / p.fraction).toInt()
                                    Text(
                                        text = "Справочники: $activeReferenceCount/$estimated строк",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = "${p.currentChunk}/${p.totalChunks}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                } else if (pendingDeltaCount > 0) {
                                    Text(
                                        text = "Справочники: $activeReferenceCount строк",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = "Загрузка...",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                } else {
                                    Text(
                                        text = "Справочники: $activeReferenceCount строк",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Terminal info card
            item {
                terminal?.let { t ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            InfoRow("Статус", t.status)
                            InfoRow("Серийный номер", t.terminalSerial)
                            t.terminalNumber?.let { InfoRow("Номер", it) }
                            t.terminalModel?.let { InfoRow("Модель", it) }
                        }
                    }
                } ?: run {
                    if (terminalState is TerminalViewModel.UiState.Error) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer
                            )
                        ) {
                            Text(
                                text = (terminalState as TerminalViewModel.UiState.Error).message,
                                modifier = Modifier.padding(16.dp),
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    } else {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                CircularProgressIndicator()
                                Spacer(Modifier.height(8.dp))
                                Text("Загрузка данных терминала...")
                            }
                        }
                    }
                }
            }

            // Sync status card
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Синхронизация",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(Modifier.height(8.dp))
                        InfoRow("Ожидают отправки", "$pendingCount")
                        currentSession?.let { session ->
                            InfoRow(
                                "Сессия",
                                if (session.status == "OPEN") "Открыта" else "Закрыта"
                            )
                        }
                        lastSyncTime?.let { time ->
                            val sdf = SimpleDateFormat("HH:mm:ss, dd.MM.yyyy", Locale.getDefault())
                            InfoRow("Последняя синхр.", sdf.format(Date(time)))
                        }

                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = { syncViewModel.triggerSync() },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Синхронизировать сейчас")
                        }
                    }
                }
            }

            // GPS tracking toggle
            item {
                GpsTrackingCard()
            }
        }
    }
}

@Composable
private fun GpsTrackingCard() {
    var gpsActive by remember { mutableStateOf(false) }
    val context = LocalContext.current
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    text = "GPS-трекинг",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = if (gpsActive) "Активен" else "Остановлен",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (gpsActive) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = gpsActive,
                onCheckedChange = { active ->
                    gpsActive = active
                    if (active) {
                        GpsTrackingService.start(context)
                    } else {
                        GpsTrackingService.stop(context)
                    }
                }
            )
        }
    }
}

@Composable
internal fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Text(
            text = "$label:",
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.width(140.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge
        )
    }
}

/**
 * Промпт 011: постоянный informer внизу экрана.
 *  - SHIFT OPEN, TRIP CLOSED  → зелёный  «Смена открыта: {userFullName}».
 *  - SHIFT OPEN, TRIP OPEN    → зелёный  «Рейс открыт: tid=…, vehicle=…».
 *  - обе CLOSED               → серый    «Смена закрыта. Откройте смену».
 */
@Composable
private fun ShiftTripInformer(state: SessionFlowViewModel.State) {
    val shift = state.openShift
    val trip = state.openTrip
    val bg = when {
        trip != null -> androidx.compose.ui.graphics.Color(0xFFD7F8D7)
        shift != null -> androidx.compose.ui.graphics.Color(0xFFFFE8C5) // light amber
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val msg = when {
        trip != null && shift != null -> "Рейс открыт: vehicle=${trip.vehicleId?.take(8) ?: "—"}, path=${trip.pathId?.take(8) ?: "—"}"
        shift != null -> "Смена открыта: ${shift.openedByUserId?.take(8) ?: "—"}"
        else -> "Смена закрыта. Откройте смену через меню."
    }
    Surface(
        tonalElevation = 3.dp,
        modifier = Modifier.fillMaxWidth(),
        color = bg
    ) {
        Text(
            text = msg,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}
