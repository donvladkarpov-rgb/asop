package ru.asop.terminal.ui.screen

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.nfc.NfcAdapter
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.ui.text.font.FontWeight
import ru.asop.terminal.nfc.Vcm1CardAuth
import ru.asop.terminal.nfc.NfcReaderRefCount

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
    val context = LocalContext.current
    val activity = remember { resolveActivity(context) }
    val nfcAdapter = remember { NfcAdapter.getDefaultAdapter(context) }
    var nfcEnabled by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.setKind(initialKind)
        nfcEnabled = true
    }

    LaunchedEffect(state.submitState) {
        if (state.submitState == SessionFlowViewModel.SubmitState.ACCEPTED &&
            state.kind != SessionFlowViewModel.FlowKind.TAP_PASSENGER
        ) {
            kotlinx.coroutines.delay(700)
            onConfirmedNavigateBack()
        }
    }

    // Промпт 014: если рейс уже открыт (перезапуск приложения), переключаемся
    // в режим ожидания пассажиров, не показывая cascade dropdowns.
    LaunchedEffect(state.openTrip, state.kind) {
        android.util.Log.d("SessionFlowScreen", "openTrip=${state.openTrip?.id?.take(12)}, kind=${state.kind}")
        if (state.openTrip != null && state.kind == SessionFlowViewModel.FlowKind.OPEN_TRIP) {
            android.util.Log.i("SessionFlowScreen", "auto-switch to TAP_PASSENGER")
            kotlinx.coroutines.delay(100)
            viewModel.switchToPassengerMode()
        }
    }

    DisposableEffect(nfcAdapter, activity, nfcEnabled) {
        // Промпт 013: НЕ кейаться на state.cardStep! Раньше при переходе в
        // PROCESSING DisposableEffect перезапускался: disabled → re-enabled
        // ReaderMode/ForegroundDispatch ПРЯМО посреди read (между Room-запросом
        // ключей и mfc.connect()), из-за чего Feitian PiccService терял tag
        // ("not detect tag !") и connect() падал с IOException 'null'
        // → READ_FAILED "Исключение при чтении".
        // Вместо этого держим активный reader всё время жизни экрана: IDLE,
        // WAITING_TAP, PROCESSING, ERROR (повторный тап после ошибки), даже
        // AUTH_OK (после успешного auth ждём кнопку; выключение — race).
        //
        // Промпт 013b/014: при ре-навигации старый экран может «доживать»
        // в composition и его onDispose выполняется ПОЗЖЕ (в логах — через ~440мс
        // после того как новый экран уже заармил reader). Без рефкаунта этот
        // поздний onDispose вызывал disableReaderMode + disableForegroundDispatch
        // и убивал читалку нового экрана → tap после повторного входа «ничего не делал».
        // Решение: общий NfcReaderRefCount (SessionFlow + CardActivation + CardRead) —
        // disable только когда НИКТО больше не держит reader.
        val needsActiveReader = nfcEnabled
        // Ревью-фикс: шину NfcTagBus потребляет только ВИДИМЫЙ экран — dormant-VM
        // в backstack больше не перехватывает таги (см. SessionFlowViewModel.onScreenEnter).
        viewModel.onScreenEnter()
        // Ревью-фикс: acquire/release СИММЕТРИЧНО в рамках ЭТОГО инстанса эффекта.
        // Эффект перезапускается при флипе nfcEnabled false→true: первый инстанс
        // не приобретал, но его onDispose делал release → рефкаунт андерфлоу →
        // late-dispose прошлого экрана гасил reader активного («смена не закрывается»).
        var acquired = false
        if (nfcAdapter != null && activity != null && nfcAdapter.isEnabled && needsActiveReader) {
            Log.i("SessionNFC", "try enableReaderMode on activity=${activity.javaClass.simpleName}")
            try {
                nfcAdapter.enableReaderMode(
                    activity,
                    { tag -> viewModel.onTagDiscovered(tag) },
                    NfcAdapter.FLAG_READER_NFC_A or
                        NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK or
                        NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS,
                    null
                )
                Log.i("SessionNFC", "enableReaderMode returned (Feitian PiccService may override)")
            } catch (e: Exception) {
                Log.w("SessionNFC", "enableReaderMode failed: ${e.message}")
            }
            // Fallback: foreground dispatch через TAG_DISCOVERED intent.
            // Feitian F20 проприетарный PiccService может игнорировать ReaderMode
            // но пропускать foreground-dispatch; onNewIntent в MainActivity
            // форвардит в ViewModel.onTagDiscovered.
            try {
                val intent = android.content.Intent(activity, activity.javaClass).apply {
                    addFlags(android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                val pendingIntent = android.app.PendingIntent.getActivity(
                    activity, 0, intent,
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                        android.app.PendingIntent.FLAG_MUTABLE
                )
                val filters = arrayOf(
                    android.content.IntentFilter(android.nfc.NfcAdapter.ACTION_TAG_DISCOVERED),
                    android.content.IntentFilter(android.nfc.NfcAdapter.ACTION_TECH_DISCOVERED)
                )
                val techLists = arrayOf(
                    arrayOf("android.nfc.tech.MifareClassic"),
                    arrayOf("android.nfc.tech.IsoDep")
                )
                nfcAdapter.enableForegroundDispatch(activity, pendingIntent, filters, techLists)
                Log.i("SessionNFC", "enableForegroundDispatch armed (TAG_DISCOVERED + TECH_DISCOVERED)")
            } catch (e: Exception) {
                Log.w("SessionNFC", "enableForegroundDispatch failed: ${e.message}")
            }
            NfcReaderRefCount.acquire()
            acquired = true
        }
        onDispose {
            viewModel.onScreenExit()
            // Промпт 013b/014: не вызываем disable без рефкаунта — иначе late onDispose
            // старого экрана убивает reader активного. Дизейблим только если refcount==0.
            if (acquired && nfcAdapter != null && activity != null && NfcReaderRefCount.releaseAndShouldDisable()) {
                Log.i("SessionNFC", "onDispose: refcount==0, disabling reader")
                try { nfcAdapter.disableReaderMode(activity) } catch (_: Exception) {}
                try { nfcAdapter.disableForegroundDispatch(activity) } catch (_: Exception) {}
            }
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        if (state.kind == SessionFlowViewModel.FlowKind.TAP_PASSENGER) {
            // Промпт 014: режим ожидания пассажиров — только круг/галочка/крест + счётчик
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                extraChildren?.invoke(state)
            }
        } else {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)

            // Промпт 011 §13/§16/§17: подсказка кто может закрывать смену/рейс
            val closeHint = when (state.kind) {
                SessionFlowViewModel.FlowKind.CLOSE_SHIFT ->
                    "Закрыть смену может любой водитель этого перевозчика, " +
                        "диспетчер, админ перевозчика / организатора / региона / root."
                SessionFlowViewModel.FlowKind.CLOSE_TRIP ->
                    "Закрыть рейс может любой водитель этого перевозчика, " +
                        "диспетчер, админ перевозчика / организатора / региона / root."
                else -> null
            }
            if (closeHint != null) {
                Text(
                    closeHint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Step 1 — card tap
            val cardColor = when (state.cardStep) {
                SessionFlowViewModel.CardStep.WAITING_TAP -> MaterialTheme.colorScheme.primaryContainer
                SessionFlowViewModel.CardStep.PROCESSING -> Color(0xFFFFF4C2) // light amber
                SessionFlowViewModel.CardStep.AUTH_OK -> Color(0xFFD7F8D7)  // light green
                SessionFlowViewModel.CardStep.AUTH_DENIED,
                SessionFlowViewModel.CardStep.NOT_DRIVER,
                SessionFlowViewModel.CardStep.NFC_ERROR -> Color(0xFFFFD8D8) // light red
                SessionFlowViewModel.CardStep.IDLE -> MaterialTheme.colorScheme.surfaceVariant
            }
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = cardColor),
                shape = RoundedCornerShape(8.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Шаг 1: приложите карту водителя/админа", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    val stateCard = when (state.cardStep) {
                        SessionFlowViewModel.CardStep.WAITING_TAP -> "Ожидание NFC tap"
                        SessionFlowViewModel.CardStep.PROCESSING -> "⟳ Чтение карты…"
                        SessionFlowViewModel.CardStep.AUTH_OK -> "✓ Авторизован"
                        SessionFlowViewModel.CardStep.AUTH_DENIED -> "✗ Доступ запрещён"
                        SessionFlowViewModel.CardStep.NOT_DRIVER -> "✗ Роль не подходит"
                        SessionFlowViewModel.CardStep.NFC_ERROR -> "✗ NFC ошибка"
                        SessionFlowViewModel.CardStep.IDLE -> "Готов к tap"
                    }
                    Text("Статус: $stateCard", style = MaterialTheme.typography.bodyLarge)
                    if (state.cardStep == SessionFlowViewModel.CardStep.PROCESSING &&
                        state.lastTapUidHex != null
                    ) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "UID=${state.lastTapUidHex}…",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
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
        } // end else (non-passenger)
    }
}

private fun resolveActivity(ctx: Context): Activity? {
    var c: Context? = ctx
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}
