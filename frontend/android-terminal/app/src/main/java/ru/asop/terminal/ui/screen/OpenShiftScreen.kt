package ru.asop.terminal.ui.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.compose.rememberNavController
import ru.asop.terminal.nfc.TonePlayer

/**
 * Промпт 011: «Открыть смену» — приложить карту водителя → auth → INSERT Room SESSION_OPEN.
 * Card tap requests через объединение с NFC-сессией в CardReadScreen (разово tap-and-confirm).
 */
@Composable
fun OpenShiftScreen(
    onConfirmed: () -> Unit
) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { TonePlayer.readyBeep() }

    SessionFlowScreen(
        initialKind = SessionFlowViewModel.FlowKind.OPEN_SHIFT,
        title = "Открыть смену",
        confirmLabel = "Открыть смену",
        onConfirmedNavigateBack = onConfirmed,
        onConfirm = { it.confirmOpenShift() }
    )
}
