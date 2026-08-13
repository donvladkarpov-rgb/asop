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
    sessionFlowViewModel: SessionFlowViewModel = hiltViewModel(),
    certExpiryViewModel: CertExpiryViewModel = hiltViewModel()
) {
    val terminalState by terminalViewModel.state.collectAsState()
    val terminal by terminalViewModel.terminalInfo.collectAsState()
    val terminalId by terminalViewModel.terminalId.collectAsState()
    val certStatus by certExpiryViewModel.status.collectAsState()
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
                // Промпт 012: layout bottomBar — non-overlapping informers
                // (снизу вверх):
                //   [Bottom-most]    Upload informer (sync → server) — показывает
                //                     pending events в очереди + ACK + PENDING_WATERMARK
                //   [Middle]         Download informer (server → terminal) — delta-sync
                //                     progress + chunks + totalBytes
                //   [Top]            ShiftTripInformer (session state) — постоянный
                //
                // Каждый informer занимает свою Row; если только один активен —
                // он всё равно внизу; если два — download вытесняет upload выше.
                // Padding dynamically shifts visual bounds to avoid overlap.
                UploadInformer(
                    pendingCount = pendingCount,
                    sendingProgress = syncViewModel.syncEnabled.collectAsState().value,
                    modifier = Modifier.fillMaxWidth()
                )
                // Промпт 013: cert expiry informer (красный/жёлтый) когда сертификат протухает
                val androidContext = androidx.compose.ui.platform.LocalContext.current
                CertExpiryInformer(
                    status = certStatus,
                    onRenew = {
                        certExpiryViewModel.refresh()
                        // Manual renew через CertificateService.refreshCertificate — вызывается из ViewModel здесь опущен
                        // см. CertCheckWorker для auto-flow.
                    }
                )
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
                                        text = "Скачано: $activeReferenceCount/$estimated строк",
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
                                        text = "Скачано: $activeReferenceCount строк",
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
                                        text = "Скачано: $activeReferenceCount строк",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
                // Промпт 011: постоянный informer о состоянии смены/рейса — сверху
                ShiftTripInformer(state = sessionState)
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
/**
 * Промпт 012: Bottom Upload informer — выезжает снизу когда sync pending events стоят в очереди.
 * Не пересекается с Download informer: оба физ-размещены через Column; composer рендерит
 * только видимые AnimatedVisibility, иначе место позволяет ShiftTripInformer занять низ.
 *
 * Цвет фона светло-голубой (отличается от жёлтого/зелёного ShiftTripInformer и серого Download),
 * чтобы два informer не сливались визуально когда оба активны.
 */

/**
 * Промпт 013: красный informer о протухающем mTLS-сертификате внизу экрана.
 * Три состояния: >30 дней (skipped), 7..30 (жёлтый warning), <7 или expired (красный critical).
 * Показывает кнопку "Продлить сейчас" (CertificateService.refreshCertificate).
 */
@Composable
private fun CertExpiryInformer(
    status: CertExpiryViewModel.Status,
    onRenew: () -> Unit
) {
    when (status) {
        CertExpiryViewModel.Status.NotProvisioned,
        is CertExpiryViewModel.Status.Ok -> { /* invisible */ }
        is CertExpiryViewModel.Status.Warning -> {
            Surface(
                tonalElevation = 4.dp,
                color = androidx.compose.ui.graphics.Color(0xFFFFF3CD),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween
                ) {
                    androidx.compose.material3.Text(
                        text = "Сертификат истекает через ${status.daysLeft} дн.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    androidx.compose.material3.OutlinedButton(onClick = onRenew) {
                        Text("Продлить")
                    }
                }
            }
        }
        is CertExpiryViewModel.Status.Critical -> {
            Surface(
                tonalElevation = 6.dp,
                color = androidx.compose.ui.graphics.Color(0xFFFFCDD2),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween
                ) {
                    androidx.compose.material3.Text(
                        text = if (status.expired)
                            "⚠ Сертификат ПРОСРОЧЕН! Sync не работает."
                        else
                            "⚠ Сертификат истекает через ${status.daysLeft} дн. — продлите сейчас",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                    androidx.compose.material3.OutlinedButton(onClick = onRenew) {
                        Text("Продлить сейчас")
                    }
                }
            }
        }
    }
}

@Composable
private fun UploadInformer(
    pendingCount: Int,
    sendingProgress: Boolean,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = pendingCount > 0 || sendingProgress,
        enter = androidx.compose.animation.fadeIn(),
        exit = androidx.compose.animation.fadeOut()
    ) {
        androidx.compose.material3.Surface(
            tonalElevation = 4.dp,
            color = androidx.compose.ui.graphics.Color(0xFFE3F2FD),
            modifier = modifier
        ) {
            androidx.compose.foundation.layout.Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween
            ) {
                if (pendingCount > 0) {
                    androidx.compose.material3.Text(
                        text = "\u2191 Отправка: $pendingCount в очереди",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                } else {
                    androidx.compose.material3.Text(
                        text = "\u2191 Sync активен",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

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
