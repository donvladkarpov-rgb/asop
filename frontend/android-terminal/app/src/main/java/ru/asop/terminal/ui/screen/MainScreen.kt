package ru.asop.terminal.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

@Composable
fun MainScreen(
    viewModel: TerminalViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val terminal by viewModel.terminalInfo.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Терминал ASOP",
            style = MaterialTheme.typography.headlineMedium
        )
        Spacer(Modifier.height(32.dp))

        terminal?.let { t ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    InfoRow("Статус", t.status)
                    InfoRow("Серийный номер", t.terminalSerial)
                    t.terminalNumber?.let { InfoRow("Номер", it) }
                    t.terminalModel?.let { InfoRow("Модель", it) }
                }
            }
        } ?: run {
            if (state is TerminalViewModel.UiState.Error) {
                Text(
                    text = (state as TerminalViewModel.UiState.Error).message,
                    color = MaterialTheme.colorScheme.error
                )
            } else {
                CircularProgressIndicator()
                Spacer(Modifier.height(16.dp))
                Text("Загрузка данных терминала...")
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
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
