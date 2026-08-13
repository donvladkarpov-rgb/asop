package ru.asop.terminal.ui.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import ru.asop.terminal.nfc.TonePlayer

/**
 * Промпт 011 §13/§16: «Закрыть смену» — может ЛЮБОЙ водитель перевозчика
 * (открывший или другой), диспетчер перевозчика, админ перевозчика /
 * организатора перевозок / региона / root. Client-side НЕ ограничивает —
 * cascading auth делает server-side SessionService.canClose().
 */
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
