package ru.asop.terminal.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import ru.asop.terminal.nfc.TonePlayer

/**
 * Промпт 011: «Открыть рейс» — cascade (TID → Vehicle → Route → Path) dropdowns из
 * локальной Room `reference_rows`. После tap карты водителя (подтверждает ownership
 * смены) → SELECT cascade → INSERT Room session(TRIP, parent=shiftId, vehicleId, pathId)
 * + emit Kafka SESSION_OPEN с parentSessionId=shiftId.
 */
@Composable
fun OpenTripScreen(
    onConfirmed: () -> Unit
) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { TonePlayer.readyBeep() }

    SessionFlowScreen(
        initialKind = SessionFlowViewModel.FlowKind.OPEN_TRIP,
        title = "Открыть рейс",
        confirmLabel = "Открыть рейс",
        onConfirmedNavigateBack = onConfirmed,
        onConfirm = { vm ->
            val sel = vm.state.value
            vm.confirmOpenTrip(tidId = sel.openShift?.tidId, vehicleId = sel.openShift?.vehicleId, pathId = sel.openShift?.pathId)
        },
        extraChildren = { _ ->
            // UI hint; конкретные dropdowns требуют ReferenceRowDao-сервиса выбранного route.
            // Здесь показан только тап-кнопка для запуска cascade (real picker
            // вынесен в OpenTripPickerScreen отдельным flow).
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("Параметры рейса", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Text("Откройте смену перед рейсом. TID/ТС/маршрут/путь будут выбраны после tap карты водителя (конкретный picker вынесен в OpenTripPickerScreen).")
                }
            }
        }
    )
}
