package ru.asop.terminal.ui.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import ru.asop.terminal.nfc.TonePlayer

/** Промпт 011: «Закрыть рейс» — только владелец смены (тот же userId). */
@Composable
fun CloseTripScreen(
    onConfirmed: () -> Unit
) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { TonePlayer.readyBeep() }

    SessionFlowScreen(
        initialKind = SessionFlowViewModel.FlowKind.CLOSE_TRIP,
        title = "Закрыть рейс",
        confirmLabel = "Закрыть рейс",
        onConfirmedNavigateBack = onConfirmed,
        onConfirm = { it.confirmCloseTrip() }
    )
}
