package ru.asop.terminal.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

@Composable
fun RegistrationScreen(
    onRegistered: () -> Unit,
    viewModel: TerminalViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()

    var serial by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("") }
    var number by remember { mutableStateOf("") }

    LaunchedEffect(state) {
        if (state is TerminalViewModel.UiState.Registered) {
            onRegistered()
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
            text = "Регистрация терминала",
            style = MaterialTheme.typography.headlineSmall
        )
        Spacer(Modifier.height(24.dp))

        OutlinedTextField(
            value = serial,
            onValueChange = { serial = it },
            label = { Text("Серийный номер") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = model,
            onValueChange = { model = it },
            label = { Text("Модель") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = number,
            onValueChange = { number = it },
            label = { Text("Номер терминала") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(24.dp))

        Button(
            onClick = { viewModel.registerTerminal(serial, model.ifBlank { null }, number.ifBlank { null }) },
            enabled = serial.isNotBlank() && state !is TerminalViewModel.UiState.Registering,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (state is TerminalViewModel.UiState.Registering) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp
                )
                Spacer(Modifier.width(8.dp))
            }
            Text("Зарегистрировать")
        }

        if (state is TerminalViewModel.UiState.Error) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = (state as TerminalViewModel.UiState.Error).message,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}
