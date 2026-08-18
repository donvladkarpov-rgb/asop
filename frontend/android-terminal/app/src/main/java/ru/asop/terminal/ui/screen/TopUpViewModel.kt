package ru.asop.terminal.ui.screen

import android.nfc.NfcAdapter
import android.nfc.Tag
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.asop.terminal.activation.AsopCardType
import ru.asop.terminal.db.TerminalKeyCryptor
import ru.asop.terminal.db.dao.TerminalKeyDao
import ru.asop.terminal.nfc.MifareClassicCardWriter
import ru.asop.terminal.nfc.Vcm1CardAuth
import javax.inject.Inject

/**
 * Промпт 014: экран «Пополнить карту».
 *
 * Flow:
 *  1. AuthForm — поднести карту-ключ (дистрибьютор/админ и выше). Проверяем роль
 *     по VCM1 bitmask (DISTRIBUTOR_ADMIN / DISTRIBUTOR_DISPATCHER / и выше по иерархии).
 *  2. TargetCard — поднести пассажирскую карту для пополнения.
 *  3. Amount — ввести число поездок N, записать tripsLeft = текущее + N.
 */
@HiltViewModel
class TopUpViewModel @Inject constructor(
    val nfcAdapter: NfcAdapter?,
    private val terminalKeyDao: TerminalKeyDao,
    private val terminalKeyCryptor: TerminalKeyCryptor
) : ViewModel() {

    enum class Step { AUTH, TARGET_CARD, AMOUNT, DONE, ERROR }

    companion object {
        private const val BUS_OWNER = "TopUp"
        private const val DEBOUNCE_MS = 1500L
    }

    data class State(
        val step: Step = Step.AUTH,
        val message: String = "Приложите карту дистрибьютора или админа",
        val authorizedRoles: List<String> = emptyList(),
        val targetUid: String? = null,
        val targetCardId: String? = null,
        val targetTripsLeft: Int = 0,
        val entered: String = "",
        val busy: Boolean = false,
        val done: Boolean = false,
        val error: String? = null
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    // Роли, которым разрешено пополнение (дистрибьютор + всё выше по иерархии).
    private val allowedRoles = setOf(
        AsopCardType.DISTRIBUTOR_ADMIN.name,
        AsopCardType.DISTRIBUTOR_DISPATCHER.name,
        AsopCardType.KRS_ADMIN.name,
        AsopCardType.ORGANIZER_ADMIN.name,
        AsopCardType.REGION_ADMIN.name,
        AsopCardType.SUPER_ADMIN.name
    )

    private var heldTag: Tag? = null

    // Ревью-фикс: debounce — Feitian PICC шлёт onTagDiscovered повторно пока карта
    // в поле (~250мс). Без dedup: параллельные Vcm1CardAuth.read (IOException race)
    // и повторный callback после завершения auth маршрутится в TARGET_CARD —
    // карта дистрибьютора становится целью пополнения.
    private var lastTapUidHex: String? = null
    private var lastTapAtMillis = 0L

    init {
        // Промпт 014: Feitian F20 fallback — опрос NfcTagBus (foreground dispatch,
        // MainActivity.onNewIntent → NfcTagBus.publish). Единый вход onTagDiscovered
        // маршрутизирует по ЖИВОМУ _state.value.step (ревью: замыкание state.step
        // в ReaderMode-callback замирало на AUTH).
        // Ревью-фикс: claim НЕ здесь — TopUpViewModel живёт пока entry в backstack.
        // Права на таги берём на время ВИДИМОСТИ экрана (TopUpScreen.DisposableEffect
        // → onScreenEnter/onScreenExit), иначе drawer-навигация оставляет claim висеть
        // и dormant-loop съедает таги SessionFlow.
        viewModelScope.launch {
            while (true) {
                kotlinx.coroutines.delay(300L)
                // Потребляем только в шагах, которым нужен таг; в AMOUNT/DONE/ERROR
                // не дренируем bus. consumeIfOwner — только пока владеем claim'ом.
                val step = _state.value.step
                if (step != Step.AUTH && step != Step.TARGET_CARD) continue
                val pending = ru.asop.terminal.NfcTagBus.consumeIfOwner(BUS_OWNER) ?: continue
                onTagDiscovered(pending)
            }
        }
    }

    /** Вызывается из TopUpScreen.DisposableEffect — claim на время видимости экрана. */
    fun onScreenEnter() {
        ru.asop.terminal.NfcTagBus.claim(BUS_OWNER)
    }

    fun onScreenExit() {
        ru.asop.terminal.NfcTagBus.release(BUS_OWNER)
    }

    override fun onCleared() {
        // Safety net: если экран не успел вызвать onScreenExit (процесс убил composition).
        ru.asop.terminal.NfcTagBus.release(BUS_OWNER)
        super.onCleared()
    }

    /** Единая точка входа тага (ReaderMode callback + NfcTagBus). Роутит по текущему шагу. */
    fun onTagDiscovered(tag: Tag) {
        val s = _state.value
        if (s.busy) return
        val now = System.currentTimeMillis()
        val uidHex = tag.id.joinToString("") { "%02X".format(it) }
        if (uidHex == lastTapUidHex && (now - lastTapAtMillis) < DEBOUNCE_MS) {
            Log.d("TopUpVM", "debounce: ignored repeat tag within ${DEBOUNCE_MS}ms (uid=$uidHex)")
            return
        }
        lastTapUidHex = uidHex
        lastTapAtMillis = now
        when (s.step) {
            Step.AUTH -> onAuthTagDiscovered(tag)
            Step.TARGET_CARD -> onTargetTagDiscovered(tag)
            else -> Unit
        }
    }

    fun reset() {
        _state.value = State()
        heldTag = null
    }

    /** Первый tap: карта-ключ авторизации оператора. */
    fun onAuthTagDiscovered(tag: Tag) {
        _state.update { it.copy(busy = true) }
        viewModelScope.launch(Dispatchers.IO) {
            val keys = terminalKeyDao.getActive(30)
                .mapNotNull { e -> runCatching { terminalKeyCryptor.decrypt(e.keyMaterialEnc) }.getOrNull() }
            val outcome = Vcm1CardAuth.read(tag, keys)
            android.util.Log.i("TopUpVM", "auth outcome: $outcome")
            when (outcome) {
                is Vcm1CardAuth.Outcome.Ok -> {
                    val roles = AsopCardType.allRolesForBitmask(outcome.identity.bitmask).map { it.name }
                    android.util.Log.i("TopUpVM", "auth OK: bitmask=0x${outcome.identity.bitmask.toString(16)} roles=$roles allowed=$allowedRoles")
                    val allowed = roles.any { it in allowedRoles }
                    if (allowed) {
                        // Ревью-фикс: карта ещё в поле → повторный callback (~250мс) уже
                        // маршрутится по step=TARGET_CARD. Продлеваем debounce-окно для этого UID.
                        lastTapAtMillis = System.currentTimeMillis()
                        _state.update {
                            it.copy(
                                busy = false,
                                step = Step.TARGET_CARD,
                                message = "Авторизация OK (${roles.joinToString()}). Приложите карту для пополнения",
                                authorizedRoles = roles
                            )
                        }
                    } else {
                        _state.update {
                            it.copy(busy = false, step = Step.ERROR, error = "Роль ${roles.joinToString()} не может пополнять карты")
                        }
                    }
                }
                is Vcm1CardAuth.Outcome.Failed -> {
                    _state.update {
                        it.copy(busy = false, step = Step.ERROR, error = outcome.details)
                    }
                }
            }
        }
    }

    /** Второй tap: пассажирская карта → читаем текущее tripsLeft. */
    fun onTargetTagDiscovered(tag: Tag) {
        _state.update { it.copy(busy = true) }
        viewModelScope.launch(Dispatchers.IO) {
            heldTag = tag
            val keys = terminalKeyDao.getActive(30)
                .mapNotNull { e -> runCatching { terminalKeyCryptor.decrypt(e.keyMaterialEnc) }.getOrNull() }
            val outcome = Vcm1CardAuth.read(tag, keys)
            when (outcome) {
                is Vcm1CardAuth.Outcome.Ok -> {
                    _state.update {
                        it.copy(
                            step = Step.AMOUNT,
                            busy = false,
                            targetUid = outcome.uidHex,
                            targetCardId = outcome.identity.cardId?.toString(),
                            targetTripsLeft = outcome.identity.tripsLeft,
                            message = "Остаток на карте: ${outcome.identity.tripsLeft}. Сколько поездок добавить?"
                        )
                    }
                }
                is Vcm1CardAuth.Outcome.Failed -> {
                    _state.update { it.copy(step = Step.ERROR, busy = false, error = outcome.details) }
                }
            }
        }
    }

    fun onAmountChanged(v: String) {
        _state.update { it.copy(entered = v.filter { c -> c.isDigit() }.take(5)) }
    }

    fun onTopUp() {
        val s = _state.value
        val n = s.entered.toIntOrNull() ?: 0
        val tag = heldTag ?: return
        if (n <= 0) {
            _state.update { it.copy(error = "Введите положительное число поездок") }
            return
        }
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val keys = terminalKeyDao.getActive(30)
                    .mapNotNull { e -> runCatching { terminalKeyCryptor.decrypt(e.keyMaterialEnc) }.getOrNull() }
                val newTrips = s.targetTripsLeft + n
                if (newTrips > 0xFFFF) {
                    _state.update { it.copy(busy = false, step = Step.ERROR, error = "Превышен лимит (макс 65535 поездок)") }
                    return@launch
                }
                val writer = MifareClassicCardWriter()
                val result = writer.writeTripsLeft(tag, keys, newTrips)
                if (result != null && result.second == newTrips) {
                    _state.update {
                        it.copy(
                            busy = false, step = Step.DONE, done = true,
                            targetTripsLeft = newTrips,
                            message = "Пополнено на $n. Остаток: $newTrips поездок"
                        )
                    }
                } else {
                    _state.update { it.copy(busy = false, step = Step.ERROR, error = "Не удалось записать на карту") }
                }
            } catch (e: Exception) {
                Log.w("TopUpVM", "topUp error: ${e.message}")
                _state.update { it.copy(busy = false, step = Step.ERROR, error = "Ошибка: ${e.message}") }
            }
        }
    }
}
