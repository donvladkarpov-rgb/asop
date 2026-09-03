package ru.asop.terminal.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@Composable
fun WelcomeScreen(
    terminalRegistered: Boolean,
    onNavigateToMain: () -> Unit,
    onNavigateToCertificate: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.Warning,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.height(72.dp)
        )
        Spacer(Modifier.height(24.dp))
        Text(
            text = if (terminalRegistered) "Добро пожаловать" else "Терминал не зарегистрирован",
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = if (terminalRegistered) {
                "Терминал зарегистрирован и готов к работе.\n\nНажмите кнопку, чтобы перейти в приложение."
            } else {
                "Для работы с приложением необходимо выпустить сертификат и зарегистрировать терминал.\n\nНажмите кнопку ниже, чтобы начать."
            },
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(32.dp))
        Button(onClick = {
            if (terminalRegistered) onNavigateToMain() else onNavigateToCertificate()
        }) {
            Text(
                text = if (terminalRegistered) "Войти" else "Настроить сертификат",
                style = MaterialTheme.typography.titleMedium
            )
        }
    }
}
