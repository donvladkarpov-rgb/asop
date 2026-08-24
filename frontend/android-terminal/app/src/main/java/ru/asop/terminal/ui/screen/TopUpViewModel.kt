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
import ru.asop.terminal.db.entity.PendingEventEntity
import ru.asop.terminal.nfc.Vcm1CardAuth
import ru.asop.terminal.network.models.TransactionCompleteRequest
import ru.asop.terminal.util.JsonUtil
import ru.asop.terminal.worker.EventTypes
import javax.inject.Inject

/**
 * Промпт 014: экран «Пополнить карту».
 *
 * Flow (Feitian-совместимый — запись ТОЛЬКО в живой mfc-сессии,
 * reconnect по тому же Tag после close() на F20 падает IOException(null)):
 *  1. AUTH — поднести карту-ключ (дистрибьютор/админ и выше). Проверяем роль
 *     по VCM1 bitmask.
 *  2. TARGET_CARD — поднести пассажирскую карту: читаем tripsLeft, сессию ДЕРЖИМ открытой.
 *  3. AMOUNT — ввод числа поездок N (карту можно не убирать).
 *  4. «Продолжить» → если карта ещё в поле (сессия жива) — запись СРАЗУ через неё.
 *     Если сессия умерла (карту убрали) → шаг WRITE_CARD: поднести карту ещё раз,
 *     чтение + updateTrips в одной сессии свежего тапа.
 *
 * ВАЖНО: полагаться на ReaderMode-callback при удержании карты нельзя — Samsung
 * выдаёт повторные колбэки всплесками с гэпами 10–17 сек (замерено в логах),
 * поэтому запись при удержании идёт через сохранённую сессию, а не через новый колбэк.
 */
@HiltViewModel
class TopUpViewModel @Inject constructor(
    val nfcAdapter: NfcAdapter?,
    private val terminalKeyDao: TerminalKeyDao,
    private val terminalKeyCryptor: TerminalKeyCryptor,
    private val pendingEventDao: ru.asop.terminal.db.dao.PendingEventDao,
    private val syncPreferences: ru.asop.terminal.db.SyncPreferences,
    private val workScheduler: ru.asop.terminal.worker.WorkScheduler
) : ViewModel() {

    enum class Step { AUTH, TARGET_CARD, AMOUNT, WRITE_CARD, DONE, ERROR }

    companion object {
        private const val BUS_OWNER = "TopUp"
        private const val DEBOUNCE_MS = 1500L
        private const val TAG = "TopUpVM"
    }

    data class State(
        val step: Step = Step.AUTH,
        val message: String = "Приложите карту дистрибьютора или админа",
        val authorizedRoles: List<String> = emptyList(),
        val targetUid: String? = null,
        val targetCardId: String? = null,
        val targetTripsLeft: Int = 0,
        val entered: String = "",
        val topUpAmount: Int = 0,
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

    // Ревью-фикс: debounce — Feitian PICC шлёт onTagDiscovered повторно пока карта
    // в поле (~250мс). Без dedup: параллельные Vcm1CardAuth.read (IOException race).
    private var lastTapUidHex: String? = null
    private var lastTapAtMillis = 0L

    // Ревью-фикс: UID карты-ключа авторизации. Пока оператор держит её в поле,
    // повторные callback'и (~250мс) маршрутятся по step=TARGET_CARD — time-based
    // debounce покрывал лишь 1.5с, дальше карта дистрибьютора сама становилась
    // целью пополнения. Блокируем её UID до появления ДРУГОЙ карты.
    private var authUidHex: String? = null

    // Живая mfc-сессия шага TARGET_CARD (карта осталась на ридере). Используется
    // для мгновенной записи при «Продолжить» без ожидания нового колбэка
    // (Samsung выдаёт повторные колбэки с гэпами 10–17 сек — ждать нельзя).
    private var heldSession: Vcm1CardAuth.Session? = null

    private fun closeHeldSession() {
        heldSession?.close()
        heldSession = null
    }

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
                if (step != Step.AUTH && step != Step.TARGET_CARD && step != Step.WRITE_CARD) continue
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
            Log.d(TAG, "debounce: ignored repeat tag within ${DEBOUNCE_MS}ms (uid=$uidHex)")
            return
        }
        lastTapUidHex = uidHex
        lastTapAtMillis = now
        when (s.step) {
            Step.AUTH -> onAuthTagDiscovered(tag)
            Step.TARGET_CARD -> {
                // Карта-ключ авторизации не может быть целью пополнения — игнорируем
                // её UID, пока не поднесена другая карта (вне зависимости от debounce-окна).
                if (uidHex == authUidHex) {
                    Log.d(TAG, "ignore auth card at TARGET_CARD (uid=$uidHex)")
                    return
                }
                onTargetTagDiscovered(tag)
            }
            Step.WRITE_CARD -> onWriteTagDiscovered(tag)
            else -> Unit
        }
    }

    fun reset() {
        closeHeldSession()
        _state.value = State()
        authUidHex = null
    }

    private suspend fun loadKeys(): List<ByteArray> =
        terminalKeyDao.getActive(30)
            .mapNotNull { e -> runCatching { terminalKeyCryptor.decrypt(e.keyMaterialEnc) }.getOrNull() }

    /** Первый tap: карта-ключ авторизации оператора. */
    fun onAuthTagDiscovered(tag: Tag) {
        _state.update { it.copy(busy = true) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val keys = loadKeys()
                val outcome = Vcm1CardAuth.read(tag, keys)
                Log.i(TAG, "auth outcome: $outcome")
                when (outcome) {
                    is Vcm1CardAuth.Outcome.Ok -> {
                        val roles = AsopCardType.allRolesForBitmask(outcome.identity.bitmask).map { it.name }
                        Log.i(TAG, "auth OK: bitmask=0x${outcome.identity.bitmask.toString(16)} roles=$roles allowed=$allowedRoles")
                        val allowed = roles.any { it in allowedRoles }
                        if (allowed) {
                            // Ревью-фикс: пока карта-ключ в поле, Feitian повторно шлёт
                            // callback'и (~250мс) уже по step=TARGET_CARD. UID-guard в
                            // onTagDiscovered блокирует её как цель пополнения.
                            authUidHex = outcome.uidHex
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
            } catch (e: Exception) {
                // Ревью-фикс: без сброса busy оставался true навсегда (onTagDiscovered
                // early-return по busy) и экран переставал принимать тапы до рестарта.
                Log.w(TAG, "auth error: ${e.message}")
                _state.update { it.copy(busy = false, step = Step.ERROR, error = "Ошибка: ${e.message}") }
            }
        }
    }

    /** Второй tap: пассажирская карта → читаем tripsLeft. Сессию ДЕРЖИМ — для мгновенной записи, если карту не убрали. */
    fun onTargetTagDiscovered(tag: Tag) {
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                closeHeldSession()
                val keys = loadKeys()
                val result = Vcm1CardAuth.readWithSession(tag, keys)
                when (val outcome = result.outcome) {
                    is Vcm1CardAuth.Outcome.Ok -> {
                        Log.i(TAG, "target read OK: uid=${outcome.uidHex} cardId=${outcome.identity.cardId} tripsLeft=${outcome.identity.tripsLeft}")
                        heldSession = result.session
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
                        result.session?.close()
                        Log.w(TAG, "target read FAILED: uid=${outcome.uidHex} status=${outcome.status} details=${outcome.details}")
                        // Остаемся на TARGET_CARD — оператор может поднести карту заново.
                        _state.update { it.copy(busy = false, error = outcome.details) }
                    }
                }
            } catch (e: Exception) {
                closeHeldSession()
                Log.w(TAG, "target read error: ${e.message}")
                _state.update { it.copy(busy = false, error = "Ошибка: ${e.message}") }
            }
        }
    }

    /**
     * Транзакция пополнения на сервер (Kafka asop.transaction.commands, type=0802
     * 'Пополнение карты', result=0901 'Успешно'). Metadata несёт tripsBefore/tripsAfter/
     * tripsAt — сервер поддерживает ASOP_CARD_MIFARES.TRIPS_LEFT (last-wins по tripsAt,
     * транзакции с терминалов могут запаздывать). sessionId=null: пополнение вне рейса.
     * Отправляется и для анонимных карт — сервер должен знать их остатки.
     */
    private suspend fun reportTopUpTransaction(cardId: String?, uidHex: String?, tripsBefore: Int, topUpAmount: Int, tripsAfter: Int) {
        if (cardId == null) {
            Log.w(TAG, "reportTopUpTransaction: cardId unknown (uid=$uidHex) — skipping server report")
            return
        }
        try {
            val paymentId = com.github.f4b6a3.uuid.UuidCreator.getTimeOrderedEpoch().toString()
            val typeId = "00000000-0000-0000-0000-000000000802"   // Пополнение карты
            val resultId = "00000000-0000-0000-0000-000000000901" // Успешно
            val meta = org.json.JSONObject().apply {
                put("topUpAmount", topUpAmount)
                put("tripsBefore", tripsBefore)
                put("tripsAfter", tripsAfter)
                put("tripsAt", System.currentTimeMillis())
                put("uid", uidHex ?: org.json.JSONObject.NULL)
            }.toString()
            val payload = TransactionCompleteRequest(
                sessionId = null,
                transactionTypeId = typeId,
                transactionResultId = resultId,
                amount = 0.0,
                currency = "RUB",
                cardId = cardId,
                metadata = meta
            )
            pendingEventDao.insert(
                PendingEventEntity(
                    id = paymentId,
                    topic = "asop.transaction.commands",
                    payload = JsonUtil.encode(payload),
                    eventType = EventTypes.TRANSACTION_COMPLETE,
                    pathParam = null,
                    seq = syncPreferences.nextSeq()
                )
            )
            workScheduler.enqueueOneShotSync()
            Log.i(TAG, "top-up transaction queued: cardId=$cardId $tripsBefore+$topUpAmount=$tripsAfter")
        } catch (e: Exception) {
            // Очередь не должна рушить UX пополнения — карта уже пополнена.
            Log.w(TAG, "reportTopUpTransaction failed: ${e.message}")
        }
    }

    fun onAmountChanged(v: String) {
        _state.update { it.copy(entered = v.filter { c -> c.isDigit() }.take(5)) }
    }

    /**
     * «Продолжить»: сначала пробуем записать через живую сессию чтения (карта
     * осталась на ридере — запись мгновенная, без ожидания ReaderMode-колбэка).
     * Сессия умерла (карту убрали) → шаг WRITE_CARD: поднести карту ещё раз.
     */
    fun onTopUp() {
        val s = _state.value
        val n = s.entered.toIntOrNull() ?: 0
        if (n <= 0) {
            _state.update { it.copy(error = "Введите положительное число поездок") }
            return
        }
        if (s.targetTripsLeft + n > 0xFFFF) {
            _state.update { it.copy(error = "Превышен лимит (макс 65535 поездок)") }
            return
        }
        val newTrips = s.targetTripsLeft + n
        _state.update { it.copy(step = Step.WRITE_CARD, topUpAmount = n, error = null, busy = true) }

        val session = heldSession
        if (session != null) {
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    Log.i(TAG, "write (held session): start tripsLeft=${s.targetTripsLeft} + $n = $newTrips")
                    val updated = session.updateTrips(newTrips)
                    closeHeldSession()
                    if (updated) {
                        Log.i(TAG, "write (held session): OK, newTrips=$newTrips")
                        reportTopUpTransaction(cardId = s.targetCardId, uidHex = s.targetUid, tripsBefore = s.targetTripsLeft, topUpAmount = n, tripsAfter = newTrips)
                        _state.update {
                            it.copy(
                                busy = false, step = Step.DONE, done = true,
                                targetTripsLeft = newTrips,
                                message = "Пополнено на $n. Остаток: $newTrips поездок"
                            )
                        }
                    } else {
                        // Сессия умерла (карту убрали с поля) — ждём свежий тап.
                        Log.w(TAG, "write (held session): failed — waiting for fresh tap")
                        _state.update {
                            it.copy(busy = false, message = "Поднесите карту для записи")
                        }
                    }
                } catch (e: Exception) {
                    closeHeldSession()
                    Log.w(TAG, "write (held session) error: ${e.message}")
                    _state.update { it.copy(busy = false, message = "Поднесите карту для записи") }
                }
            }
        } else {
            _state.update { it.copy(busy = false, message = "Поднесите карту для записи") }
        }
    }

    /**
     * WRITE_CARD (карту убрали и поднесли заново): read + updateTrips в одной
     * mfc-сессии свежего Tag. Feitian F20: reconnect по тому же Tag после close()
     * падает IOException(null), поэтому запись привязана к новому тапу.
     */
    fun onWriteTagDiscovered(tag: Tag) {
        val s = _state.value
        val expectedCardId = s.targetCardId ?: run {
            _state.update { it.copy(step = Step.TARGET_CARD, message = "Карта не прочитана. Приложите карту заново") }
            return
        }
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Fresh tap supersedes held session (if instant write already failed and left it).
                closeHeldSession()
                val keys = loadKeys()
                val result = Vcm1CardAuth.readWithSession(tag, keys)
                when (val outcome = result.outcome) {
                    is Vcm1CardAuth.Outcome.Ok -> {
                        val session = result.session
                        val tappedCardId = outcome.identity.cardId?.toString()
                        if (tappedCardId != expectedCardId) {
                            session?.close()
                            Log.w(TAG, "write: card mismatch — expected=$expectedCardId tapped=$tappedCardId uid=${outcome.uidHex}")
                            _state.update {
                                it.copy(
                                    busy = false,
                                    error = "Поднесена другая карта. Приложите карту, которую пополняете"
                                )
                            }
                            return@launch
                        }
                        val newTrips = outcome.identity.tripsLeft + s.topUpAmount
                        if (newTrips > 0xFFFF) {
                            session?.close()
                            _state.update { it.copy(busy = false, step = Step.ERROR, error = "Превышен лимит (макс 65535 поездок)") }
                            return@launch
                        }
                        Log.i(TAG, "write: start tripsLeft=${outcome.identity.tripsLeft} + ${s.topUpAmount} = $newTrips (uid=${outcome.uidHex})")
                        val updated = session?.updateTrips(newTrips) == true
                        session?.close()
                        if (updated) {
                            Log.i(TAG, "write: OK, newTrips=$newTrips")
                            reportTopUpTransaction(cardId = tappedCardId, uidHex = outcome.uidHex, tripsBefore = outcome.identity.tripsLeft, topUpAmount = s.topUpAmount, tripsAfter = newTrips)
                            _state.update {
                                it.copy(
                                    busy = false, step = Step.DONE, done = true,
                                    targetTripsLeft = newTrips,
                                    message = "Пополнено на ${s.topUpAmount}. Остаток: $newTrips поездок"
                                )
                            }
                        } else {
                            // Сессия умерла в момент записи — остаемся на WRITE_CARD,
                            // повторный тап начнёт запись заново (read вернёт актуальный остаток).
                            Log.w(TAG, "write: updateTrips failed (card left the field?)")
                            _state.update {
                                it.copy(busy = false, error = "Не удалось записать. Поднесите карту ещё раз")
                            }
                        }
                    }
                    is Vcm1CardAuth.Outcome.Failed -> {
                        result.session?.close()
                        Log.w(TAG, "write read FAILED: uid=${outcome.uidHex} status=${outcome.status} details=${outcome.details}")
                        _state.update { it.copy(busy = false, error = outcome.details) }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "write error: ${e.message}")
                _state.update { it.copy(busy = false, error = "Ошибка: ${e.message}") }
            }
        }
    }
}
