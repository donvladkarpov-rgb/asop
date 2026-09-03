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
fun RequireTerminalRegistration(
    isRegistered: Boolean,
    onNavigateToCertificate: (() -> Unit)? = null,
    title: String = "Терминал не зарегистрирован",
    message: String = "Для выполнения этой операции необходимо зарегистрировать терминал.\n\nНажмите кнопку ниже, чтобы перейти к настройке сертификата и пройти регистрацию.",
    buttonLabel: String = "К сертификату",
    content: @Composable () -> Unit
) {
    if (isRegistered) {
        content()
    } else {
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
                modifier = Modifier.height(64.dp)
            )
            Spacer(Modifier.height(24.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center
            )
            if (onNavigateToCertificate != null) {
                Spacer(Modifier.height(24.dp))
                Button(onClick = onNavigateToCertificate) {
                    Text(buttonLabel)
                }
            }
        }
    }
}
