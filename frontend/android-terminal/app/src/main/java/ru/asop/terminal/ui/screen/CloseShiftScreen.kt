package ru.asop.terminal.ui.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import ru.asop.terminal.nfc.TonePlayer

/** Промпт 011: «Закрыть смену» — карта другого водителя/диспетчера/admin уровня перевозчика/организатора/региона/root. */
@Composable
fun CloseShiftScreen(
    onConfirmed: () -> Unit
) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { TonePlayer.readyBeep() }

    SessionFlowScreen(
        initialKind = SessionFlowViewModel.FlowKind.CLOSE_SHIFT,
        title = "Закрыть смену",
        confirmLabel = "Закрыть смену",
        onConfirmedNavigateBack = onConfirmed,
        onConfirm = { it.confirmCloseShift() }
    )
}
