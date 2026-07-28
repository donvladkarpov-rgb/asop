package ru.asop.terminal.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

@Composable
fun ProvisioningScreen(
    onProvisioned: () -> Unit,
    viewModel: TerminalViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()

    LaunchedEffect(state) {
        if (state is TerminalViewModel.UiState.Ready) {
            onProvisioned()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "ASOP Терминал",
            style = MaterialTheme.typography.headlineLarge
        )
        Spacer(Modifier.height(24.dp))

        when (state) {
            is TerminalViewModel.UiState.Idle, TerminalViewModel.UiState.Ready -> {
                Text("Необходима установка сертификата")
                Spacer(Modifier.height(16.dp))
                Button(onClick = {
                    viewModel.autoProvision()
                }) {
                    Text("Сгенерировать ключи и запросить сертификат")
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { /* Import from file - TODO */ }) {
                    Text("Импортировать PKCS#12")
                }
            }
            is TerminalViewModel.UiState.Provisioning -> {
                CircularProgressIndicator()
                Spacer(Modifier.height(16.dp))
                Text("Генерация ключей и запрос сертификата...")
            }
            is TerminalViewModel.UiState.Error -> {
                Text(
                    text = (state as TerminalViewModel.UiState.Error).message,
                    color = MaterialTheme.colorScheme.error
                )
                Spacer(Modifier.height(16.dp))
                Button(onClick = { viewModel.autoProvision() }) {
                    Text("Повторить")
                }
            }
            is TerminalViewModel.UiState.Registering -> {
                CircularProgressIndicator()
                Spacer(Modifier.height(16.dp))
                Text("Регистрация терминала...")
            }
            is TerminalViewModel.UiState.Registered -> {
                Text("Терминал зарегистрирован", color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}
