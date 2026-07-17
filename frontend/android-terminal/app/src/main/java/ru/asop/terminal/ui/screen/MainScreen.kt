package ru.asop.terminal.ui.screen

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
    syncViewModel: SyncViewModel = hiltViewModel()
) {
    val terminalState by terminalViewModel.state.collectAsState()
    val terminal by terminalViewModel.terminalInfo.collectAsState()
    val pendingCount by syncViewModel.pendingCount.collectAsState()
    val currentSession by syncViewModel.currentSession.collectAsState()
    val lastSyncTime by syncViewModel.lastSyncTime.collectAsState()

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
