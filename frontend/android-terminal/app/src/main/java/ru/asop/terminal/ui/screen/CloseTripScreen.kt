package ru.asop.terminal.ui.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import ru.asop.terminal.nfc.TonePlayer

/**
 * Промпт 011 §12/§17: «Закрыть рейс» — может любой водитель перевозчика
 * (открывший смену/рейс или другой), диспетчер, админ перевозчика /
 * организатора / региона / root. Client-side НЕ ограничивает —
 * cascading auth делает server-side SessionService.canClose().
 */
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
