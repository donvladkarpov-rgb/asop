package ru.asop.terminal.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.ui.text.font.FontWeight

/**
 * Промпт 011: shared layout для OpenShift/CloseShift/OpenTrip/CloseTrip.
 * Конкретное поведение определяется ViewModel.kind + onConfirmAction.
 */
@Composable
fun SessionFlowScreen(
    initialKind: SessionFlowViewModel.FlowKind,
    title: String,
    confirmLabel: String,
    onConfirmedNavigateBack: () -> Unit,
    onConfirm: (SessionFlowViewModel) -> Unit,
    extraChildren: @Composable ((SessionFlowViewModel.State) -> Unit)? = null,
    viewModel: SessionFlowViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.setKind(initialKind)
    }

    LaunchedEffect(state.submitState) {
        if (state.submitState == SessionFlowViewModel.SubmitState.ACCEPTED) {
            kotlinx.coroutines.delay(700)
            onConfirmedNavigateBack()
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)

            // Step 1 — card tap
            val cardColor = when (state.cardStep) {
                SessionFlowViewModel.CardStep.WAITING_TAP -> MaterialTheme.colorScheme.primaryContainer
                SessionFlowViewModel.CardStep.AUTH_OK -> Color(0xFFD7F8D7)  // light green
                SessionFlowViewModel.CardStep.AUTH_DENIED,
                SessionFlowViewModel.CardStep.NOT_DRIVER -> Color(0xFFFFD8D8) // light red
                SessionFlowViewModel.CardStep.IDLE -> MaterialTheme.colorScheme.surfaceVariant
            }
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = cardColor),
                shape = RoundedCornerShape(8.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Шаг 1: приложите карту водителя", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    val stateCard = when (state.cardStep) {
                        SessionFlowViewModel.CardStep.WAITING_TAP -> "Ожидание NFC tap"
                        SessionFlowViewModel.CardStep.AUTH_OK -> "✓ Авторизован"
                        SessionFlowViewModel.CardStep.AUTH_DENIED -> "✗ Доступ запрещён"
                        SessionFlowViewModel.CardStep.NOT_DRIVER -> "✗ Роль не подходит"
                        SessionFlowViewModel.CardStep.IDLE -> "Готов к tap"
                    }
                    Text("Статус: $stateCard", style = MaterialTheme.typography.bodyLarge)
                    state.cardTap?.let { tap ->
                        Spacer(Modifier.height(8.dp))
                        Text("Водитель: ${tap.userFullName}", fontWeight = FontWeight.SemiBold)
                        Text("cardId: ${tap.cardId.take(8)}...")
                        Text("carrier: ${tap.carrierId?.take(8) ?: "—"}")
                        Text("Роли: ${tap.roles.joinToString(", ")}")
                    }
                    state.errorMessage?.let { err ->
                        Spacer(Modifier.height(8.dp))
                        Text("Ошибка: $err", color = MaterialTheme.colorScheme.error)
                    }
                }
            }

            // Optional extra widgets (cascade dropdowns for OpenTrip, etc.)
            extraChildren?.invoke(state)

            // Step 2 — confirm
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (state.submitState == SessionFlowViewModel.SubmitState.ACCEPTED)
                        Color(0xFFD7F8D7) else MaterialTheme.colorScheme.surface
                ),
                shape = RoundedCornerShape(8.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Шаг 2: подтвердите действие", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    state.infoMessage?.let { msg ->
                        Text("✓ $msg", fontWeight = FontWeight.SemiBold)
                    }
                    state.errorMessage?.let { it ->
                        if (state.submitState == SessionFlowViewModel.SubmitState.FAILED) {
                            Text("✗ ${state.errorMessage}", color = MaterialTheme.colorScheme.error)
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    val submitting = state.submitState == SessionFlowViewModel.SubmitState.SUBMITTING
                    val disabled = submitting || state.cardStep != SessionFlowViewModel.CardStep.AUTH_OK
                    Button(
                        onClick = { onConfirm(viewModel) },
                        enabled = !disabled,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (submitting) "Отправка…" else confirmLabel)
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = onConfirmedNavigateBack,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Отмена")
                    }
                }
            }
        }
    }
}
