package ru.asop.terminal.ui.screen

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import android.nfc.Tag
import com.github.f4b6a3.uuid.UuidCreator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject
import ru.asop.terminal.activation.AsopCardType
import ru.asop.terminal.activation.CardIdentityVcm1
import ru.asop.terminal.db.SyncPreferences
import ru.asop.terminal.db.TerminalKeyCryptor
import ru.asop.terminal.db.dao.PendingEventDao
import ru.asop.terminal.db.dao.ReferenceRowDao
import ru.asop.terminal.db.dao.SessionDao
import ru.asop.terminal.db.dao.TerminalKeyDao
import ru.asop.terminal.db.dao.TripPaymentDao
import ru.asop.terminal.db.entity.PendingEventEntity
import ru.asop.terminal.db.entity.SessionEntity
import ru.asop.terminal.db.entity.TripPaymentEntity
import ru.asop.terminal.network.SyncApi
import ru.asop.terminal.network.models.SessionCloseRequest
import ru.asop.terminal.network.models.SessionOpenRequest
import ru.asop.terminal.network.models.TransactionCompleteRequest
import ru.asop.terminal.nfc.QuickVcm1Reader
import ru.asop.terminal.util.JsonUtil
import ru.asop.terminal.worker.EventTypes
import javax.inject.Inject

/**
 * Промпт 011: shared ViewModel для Open/Close shift + open/close trip.
 *
 * Клиент генерирует UUIDv7 для sessionId/tripPaymentId (= idempotent).
 * Insert в Room + PendingEvent.SESSION_OPEN → SyncWorker → gateway → Kafka →
 * session-service → ON CONFLICT (SESSION_ID) DO NOTHING → безопасно от дублей при retry.
 *
 * cardAuth() ждёт NFC tap → read VCM1 (sector 1, 48 bytes) → проверяет роль DRIVER
 * и наличие user_carriers в локальном справочнике (asop_user_carriers).
 */
@HiltViewModel
class SessionFlowViewModel @Inject constructor(
    application: Application,
    private val sessionDao: SessionDao,
    private val tripPaymentDao: TripPaymentDao,
    private val pendingEventDao: PendingEventDao,
    private val referenceRowDao: ReferenceRowDao,
    private val terminalKeyDao: TerminalKeyDao,
    private val terminalKeyCryptor: TerminalKeyCryptor,
    private val syncPreferences: SyncPreferences,
    @Suppress("unused") private val syncApi: SyncApi,
    private val terminalDao: ReferenceRowDao = referenceRowDao
) : AndroidViewModel(application) {

    enum class FlowKind { OPEN_SHIFT, CLOSE_SHIFT, OPEN_TRIP, CLOSE_TRIP, TAP_PASSENGER }
    enum class CardStep { WAITING_TAP, AUTH_OK, AUTH_DENIED, NOT_DRIVER, NFC_ERROR, IDLE }
    enum class SubmitState { IDLE, SUBMITTING, ACCEPTED, FAILED }

    data class CardTapInfo(
        val cardId: String,
        val userId: String,
        val userFullName: String,
        val carrierId: String?,
        val roles: List<String>,
        val tapTimestamp: Long
    )

    data class State(
        val kind: FlowKind = FlowKind.OPEN_SHIFT,
        val cardStep: CardStep = CardStep.IDLE,
        val cardTap: CardTapInfo? = null,
        val submitState: SubmitState = SubmitState.IDLE,
        val errorMessage: String? = null,
        val infoMessage: String? = null,
        val openShift: SessionEntity? = null,
        val openTrip: SessionEntity? = null,
        val tripPayments: List<TripPaymentEntity> = emptyList(),
        // Промпт 011: OpenTrip cascade picker (TID → Vehicle → Route → Path)
        val tripTidId: String? = null,
        val tripTidLabel: String? = null,
        val tripVehicleId: String? = null,
        val tripVehicleLabel: String? = null,
        val tripRouteId: String? = null,
        val tripRouteLabel: String? = null,
        val tripPathId: String? = null,
        val tripPathLabel: String? = null
    ) {
        val canConfirmOpenShift: Boolean
            get() = cardStep == CardStep.AUTH_OK && submitState == SubmitState.IDLE
        val canConfirmOpenTrip: Boolean
            get() = cardStep == CardStep.AUTH_OK &&
                submitState == SubmitState.IDLE &&
                openShift != null &&
                tripVehicleId != null && tripPathId != null
    }

    fun setTripTid(id: String?, label: String?) {
        _state.update { it.copy(tripTidId = id, tripTidLabel = label) }
    }
    fun setTripVehicle(id: String?, label: String?) {
        _state.update { it.copy(tripVehicleId = id, tripVehicleLabel = label, tripRouteId = null, tripRouteLabel = null, tripPathId = null, tripPathLabel = null) }
    }
    fun setTripRoute(id: String?, label: String?) {
        _state.update { it.copy(tripRouteId = id, tripRouteLabel = label, tripPathId = null, tripPathLabel = null) }
    }
    fun setTripPath(id: String?, label: String?) {
        _state.update { it.copy(tripPathId = id, tripPathLabel = label) }
    }

    /**
     * Реактивные потоки для cascade dropdowns (TID → Vehicle → Route → Path).
     * Все фильтруются по carrierId из [State.openShift] (кроме маршрутов и путей,
     * которые глобальные по routeId и перевозчику).
     */
    fun observeTids(): Flow<List<String>> {
        val carrier = _state.value.cardTap?.carrierId ?: _state.value.openShift?.carrierId
        return if (carrier.isNullOrEmpty()) kotlinx.coroutines.flow.flowOf(emptyList())
        else terminalDao.observeTidsByCarrier(carrier)
    }

    fun observeVehicles(): Flow<List<String>> {
        val carrier = _state.value.cardTap?.carrierId ?: _state.value.openShift?.carrierId
        return if (carrier.isNullOrEmpty()) kotlinx.coroutines.flow.flowOf(emptyList())
        else terminalDao.observeVehiclesByCarrier(carrier)
    }

    fun observeRoutes(): Flow<List<String>> = terminalDao.observeAllRoutes()

    fun observePaths(): Flow<List<String>> {
        val route = _state.value.tripRouteId
        return if (route.isNullOrEmpty()) kotlinx.coroutines.flow.flowOf(emptyList())
        else terminalDao.observePathsByRoute(route)
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    fun setKind(kind: FlowKind) {
        _state.update {
            it.copy(
                kind = kind,
                cardStep = CardStep.WAITING_TAP,
                cardTap = null,
                errorMessage = null,
                infoMessage = null
            )
        }
        observeSessions()
    }

    fun clearMessages() {
        _state.update { it.copy(errorMessage = null, infoMessage = null) }
    }

    private var tripPaymentsJob: kotlinx.coroutines.Job? = null

    private fun observeSessions() {
        viewModelScope.launch {
            sessionDao.observeCurrentOpenShift().collect { shift ->
                _state.update { it.copy(openShift = shift) }
            }
        }
        viewModelScope.launch {
            sessionDao.observeCurrentOpenTrip().collect { trip ->
                _state.update { it.copy(openTrip = trip) }
                tripPaymentsJob?.cancel()
                if (trip != null) {
                    tripPaymentsJob = viewModelScope.launch {
                        observeTripPayments(trip.id)
                    }
                } else {
                    _state.update { it.copy(tripPayments = emptyList()) }
                }
            }
        }
    }

    private suspend fun observeTripPayments(tripId: String) {
        tripPaymentDao.observeForTrip(tripId).collect { ps ->
            _state.update { it.copy(tripPayments = ps) }
        }
    }

    /**
     * NFC tap auth: парсит VCM1 bytes (48-байтный sector 1), проверяет роль DRIVER /
     * CARRIER_DISPATCHER / KRS_DISPATCHER, резолвит carrier через локальный справочник
     * asop_user_carriers.
     */
    fun onCardTappedForAuth(rawCardUid: String, vcm1Bytes: ByteArray?, carrierFilter: String? = null) {
        try {
            val identity = vcm1Bytes?.let { CardIdentityVcm1.decodeFromBytes(it) }
            if (identity == null) {
                _state.update { it.copy(cardStep = CardStep.AUTH_DENIED, errorMessage = "Карта не распознана (VCM1)") }
                return
            }
            val userId = identity.entity?.id?.toString()
                ?: identity.entity?.id?.toString()
            val cardId = identity.cardId.toString()

            if (userId.isNullOrEmpty()) {
                _state.update { it.copy(cardStep = CardStep.AUTH_DENIED, errorMessage = "Карта не активирована") }
                return
            }

            val roles = AsopCardType.allRolesForBitmask(identity.bitmask)
            val hasDriverRole = roles.any {
                it == AsopCardType.DRIVER ||
                    it == AsopCardType.CARRIER_DISPATCHER ||
                    it == AsopCardType.KRS_DISPATCHER
            }
            if (!hasDriverRole) {
                _state.update {
                    it.copy(
                        cardStep = CardStep.NOT_DRIVER,
                        errorMessage = "Роль карты не позволяет открывать/закрывать смены"
                    )
                }
                return
            }

            viewModelScope.launch {
                val carrierId = lookupUserCarrier(userId, carrierFilter)
                if (carrierId == null) {
                    _state.update {
                        it.copy(
                            cardStep = CardStep.AUTH_DENIED,
                            errorMessage = "Пользователь $userId не привязан к перевозчику"
                        )
                    }
                    return@launch
                }
                val fullName = lookupUserFullName(userId) ?: userId
                val tap = CardTapInfo(
                    cardId = cardId,
                    userId = userId,
                    userFullName = fullName,
                    carrierId = carrierId,
                    roles = roles.map { it.name },
                    tapTimestamp = System.currentTimeMillis()
                )
                _state.update { it.copy(cardStep = CardStep.AUTH_OK, cardTap = tap) }
            }
        } catch (e: Exception) {
            _state.update { it.copy(cardStep = CardStep.AUTH_DENIED, errorMessage = "Ошибка парсинга карты: ${e.message}") }
        }
    }

    /**
     * Открыть смену. Если успешно:
     *  - INSERT Room sessions (status=OPEN)
     *  - emit PendingEvent SESSION_OPEN → SyncWorker
     *  - обновить SyncPreferences.lastCardId
     */
    fun confirmOpenShift() {
        val tap = _state.value.cardTap ?: return
        _state.update { it.copy(submitState = SubmitState.SUBMITTING) }
        viewModelScope.launch {
            try {
                val now = System.currentTimeMillis()
                val sessionId = UuidCreator.getTimeOrderedEpoch().toString()
                val shiftTypeId = "00000000-0000-0000-0000-000000000601"  // SHIFT
                val carrier = tap.carrierId ?: ""
                val region = syncPreferences.regionId.first()
                val timezone = syncPreferences.timezone.first()
                val terminalId = syncPreferences.terminalId.first()

                val entity = SessionEntity(
                    id = sessionId,
                    sessionTypeCode = SessionEntity.TYPE_SHIFT,
                    sessionTypeId = shiftTypeId,
                    parentSessionId = null,
                    terminalId = terminalId,
                    tidId = null,
                    openedByUserId = tap.userId,
                    closedByUserId = null,
                    cardId = tap.cardId,
                    pathId = null,
                    vehicleId = null,
                    carrierId = carrier,
                    regionId = region,
                    timezone = timezone,
                    status = SessionEntity.STATUS_OPEN,
                    openedAt = now,
                    closedAt = null,
                    openedAtLocal = now,
                    closedAtLocal = null,
                    expirationTime = now + SessionEntity.DEFAULT_EXPIRATION_HOURS * 60 * 60 * 1000
                )
                sessionDao.insert(entity)

                val payload = SessionOpenRequest(
                    sessionTypeId = shiftTypeId,
                    parentSessionId = null,
                    terminalId = terminalId,
                    tidId = null,
                    pathId = null,
                    vehicleId = null,
                    openedByUserId = tap.userId,
                    cardId = tap.cardId,
                    carrierId = carrier,
                    regionId = region,
                    timezone = timezone,
                    attributes = null
                )
                pendingEventDao.insert(
                    PendingEventEntity(
                        id = sessionId,
                        topic = "asop.session.commands",
                        payload = JsonUtil.encode(payload),
                        eventType = EventTypes.SESSION_OPEN,
                        pathParam = null
                    )
                )
                syncPreferences.setLastCardTap(null, tap.cardId)
                _state.update {
                    it.copy(
                        submitState = SubmitState.ACCEPTED,
                        infoMessage = "Смена открыта: ${tap.userFullName}",
                        cardStep = CardStep.IDLE,
                        cardTap = null
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(submitState = SubmitState.FAILED, errorMessage = "Ошибка: ${e.message}") }
            }
        }
    }

    /**
     * Открыть рейс (TRIP). Берёт tidId/vehicleId/pathId из state (выбранных в cascade dropdowns).
     */
    fun confirmOpenTrip() {
        val s = _state.value
        val shift = s.openShift
        if (shift == null) {
            _state.update { it.copy(submitState = SubmitState.FAILED, errorMessage = "Сначала откройте смену") }
            return
        }
        if (s.openTrip != null) {
            _state.update { it.copy(submitState = SubmitState.FAILED, errorMessage = "Рейс уже открыт") }
            return
        }
        if (s.tripVehicleId == null || s.tripPathId == null) {
            _state.update { it.copy(submitState = SubmitState.FAILED, errorMessage = "Выберите ТС и путь следования") }
            return
        }
        val tidId = s.tripTidId
        val vehicleId = s.tripVehicleId
        val pathId = s.tripPathId
        val tap = s.cardTap
        _state.update { it.copy(submitState = SubmitState.SUBMITTING) }
        viewModelScope.launch {
            try {
                val now = System.currentTimeMillis()
                val tripId = UuidCreator.getTimeOrderedEpoch().toString()
                val tripTypeId = "00000000-0000-0000-0000-000000000603"  // TRIP
                val entity = SessionEntity(
                    id = tripId,
                    sessionTypeCode = SessionEntity.TYPE_TRIP,
                    sessionTypeId = tripTypeId,
                    parentSessionId = shift.id,
                    terminalId = shift.terminalId,
                    tidId = tidId,
                    openedByUserId = shift.openedByUserId,
                    closedByUserId = null,
                    cardId = tap?.cardId,
                    pathId = pathId,
                    vehicleId = vehicleId,
                    carrierId = shift.carrierId,
                    regionId = shift.regionId,
                    timezone = shift.timezone,
                    status = SessionEntity.STATUS_OPEN,
                    openedAt = now,
                    closedAt = null,
                    openedAtLocal = now,
                    closedAtLocal = null,
                    expirationTime = now + SessionEntity.DEFAULT_EXPIRATION_HOURS * 60 * 60 * 1000
                )
                sessionDao.insert(entity)

                val payload = SessionOpenRequest(
                    sessionTypeId = tripTypeId,
                    parentSessionId = shift.id,
                    terminalId = shift.terminalId,
                    tidId = tidId,
                    pathId = pathId,
                    vehicleId = vehicleId,
                    openedByUserId = shift.openedByUserId,
                    cardId = tap?.cardId,
                    carrierId = shift.carrierId,
                    regionId = shift.regionId,
                    timezone = shift.timezone,
                    attributes = null
                )
                pendingEventDao.insert(
                    PendingEventEntity(
                        id = tripId,
                        topic = "asop.session.commands",
                        payload = JsonUtil.encode(payload),
                        eventType = EventTypes.SESSION_OPEN,
                        pathParam = null
                    )
                )
                _state.update {
                    it.copy(
                        submitState = SubmitState.ACCEPTED,
                        infoMessage = "Рейс открыт: tid=${tidId?.take(8) ?: "—"}, vehicle=${vehicleId?.take(8) ?: "—"}",
                        cardStep = CardStep.IDLE,
                        cardTap = null
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(submitState = SubmitState.FAILED, errorMessage = "Ошибка: ${e.message}") }
            }
        }
    }

    /**
     * Закрыть смену.
     * request = текущий водитель или admin уровня перевозчика/организатора/региона/root.
     */
    fun confirmCloseShift() {
        val tap = _state.value.cardTap ?: run {
            _state.update { it.copy(submitState = SubmitState.FAILED, errorMessage = "Сначала приложите карту") }
            return
        }
        val shift = _state.value.openShift
        if (shift == null) {
            _state.update { it.copy(submitState = SubmitState.FAILED, errorMessage = "Смена уже закрыта") }
            return
        }
        if (tap.carrierId != shift.carrierId && tap.userId != shift.openedByUserId) {
            _state.update { it.copy(submitState = SubmitState.FAILED, errorMessage = "Карта другого перевозчика (server проверяет)") }
            return
        }
        _state.update { it.copy(submitState = SubmitState.SUBMITTING) }
        viewModelScope.launch {
            try {
                val now = System.currentTimeMillis()
                sessionDao.close(shift.id, closedAt = now, closedByUserId = tap.userId)
                val payload = SessionCloseRequest(
                    reason = null,
                    regionId = shift.regionId,
                    timezone = shift.timezone,
                    cardId = tap.cardId
                )
                pendingEventDao.insert(
                    PendingEventEntity(
                        id = "close-${shift.id}",
                        topic = "asop.session.commands",
                        payload = JsonUtil.encode(payload),
                        eventType = EventTypes.SESSION_CLOSE,
                        pathParam = shift.id
                    )
                )
                _state.update {
                    it.copy(
                        submitState = SubmitState.ACCEPTED,
                        infoMessage = "Смена закрыта",
                        cardStep = CardStep.IDLE,
                        cardTap = null
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(submitState = SubmitState.FAILED, errorMessage = "Ошибка: ${e.message}") }
            }
        }
    }

    /**
     * Закрыть рейс (только владелец смены). Тот же user_id, что открывал.
     */
    fun confirmCloseTrip() {
        val tap = _state.value.cardTap
        val trip = _state.value.openTrip
        val shift = _state.value.openShift
        if (trip == null) {
            _state.update { it.copy(submitState = SubmitState.FAILED, errorMessage = "Рейс уже закрыт") }
            return
        }
        if (tap == null || (tap.userId != (shift?.openedByUserId) && tap.userId != trip.openedByUserId)) {
            _state.update { it.copy(submitState = SubmitState.FAILED, errorMessage = "Закрыть рейс может только водитель, открывший смену") }
            return
        }
        _state.update { it.copy(submitState = SubmitState.SUBMITTING) }
        viewModelScope.launch {
            try {
                val now = System.currentTimeMillis()
                sessionDao.close(trip.id, closedAt = now, closedByUserId = tap.userId)
                val payload = SessionCloseRequest(
                    reason = null,
                    regionId = trip.regionId,
                    timezone = trip.timezone,
                    cardId = tap.cardId
                )
                pendingEventDao.insert(
                    PendingEventEntity(
                        id = "close-${trip.id}",
                        topic = "asop.session.commands",
                        payload = JsonUtil.encode(payload),
                        eventType = EventTypes.SESSION_CLOSE,
                        pathParam = trip.id
                    )
                )
                _state.update {
                    it.copy(
                        submitState = SubmitState.ACCEPTED,
                        infoMessage = "Рейс закрыт",
                        cardStep = CardStep.IDLE,
                        cardTap = null
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(submitState = SubmitState.FAILED, errorMessage = "Ошибка: ${e.message}") }
            }
        }
    }

    /**
     * Прикладывание пассажирской карты (внутри открытого TRIP → trip_payments Room + emit transaction).
     */
    fun recordTripPayment(cardId: String?, tapUserId: String?) {
        val trip = _state.value.openTrip ?: return
        _state.update { it.copy(submitState = SubmitState.SUBMITTING) }
        viewModelScope.launch {
            try {
                val now = System.currentTimeMillis()
                val paymentId = UuidCreator.getTimeOrderedEpoch().toString()
                val paymentTypeId = "00000000-0000-0000-0000-000000000803"   // VALIDATION
                val resultId = "00000000-0000-0000-0000-000000000903"         // VALIDATION_ONLY
                val payment = TripPaymentEntity(
                    id = paymentId,
                    tripSessionId = trip.id,
                    cardId = cardId,
                    transactionTypeId = paymentTypeId,
                    transactionResultId = resultId,
                    amount = 0.0,
                    currency = "RUB",
                    timestamp = now,
                    regionId = trip.regionId,
                    carrierId = trip.carrierId,
                    timezone = trip.timezone,
                    lastSyncAt = null,
                    metadata = "MVP_NO_DEDUCT"
                )
                tripPaymentDao.insert(payment)

                val payload = TransactionCompleteRequest(
                    sessionId = trip.id,
                    transactionTypeId = paymentTypeId,
                    transactionResultId = resultId,
                    amount = 0.0,
                    currency = "RUB",
                    cardId = cardId,
                    metadata = "MVP_NO_DEDUCT",
                    regionId = trip.regionId,
                    carrierId = trip.carrierId,
                    timezone = trip.timezone
                )
                pendingEventDao.insert(
                    PendingEventEntity(
                        id = paymentId,
                        topic = "asop.transaction.commands",
                        payload = JsonUtil.encode(payload),
                        eventType = EventTypes.TRANSACTION_COMPLETE,
                        pathParam = null
                    )
                )
                _state.update {
                    it.copy(
                        submitState = SubmitState.ACCEPTED,
                        infoMessage = "Валидация зафиксирована",
                        cardStep = CardStep.IDLE
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(submitState = SubmitState.FAILED, errorMessage = "Ошибка: ${e.message}") }
            }
        }
    }

    private suspend fun lookupUserCarrier(userId: String, carrierFilter: String?): String? {
        return try {
            val rowJson = referenceRowDao.rawUserCarriersFor(userId)
            if (rowJson != null) {
                extractFirstCarrierId(rowJson)
            } else carrierFilter
        } catch (e: Exception) { carrierFilter }
    }

    /**
     * NFC tap event из SessionFlowScreen.enableReaderMode.
     * Читает ASOP-ключи из terminal_keys, пытается прочитать sector 1 (VCM1)
     * через [QuickVcm1Reader].
     */
    fun onTagDiscovered(tag: Tag) {
        viewModelScope.launch {
            try {
                val rows = terminalKeyDao.getActive(limit = 20)
                val asopKeys = rows.map { entity ->
                    runCatching { terminalKeyCryptor.decrypt(entity.keyMaterialEnc) }
                        .getOrDefault(ByteArray(0))
                }.filter { it.size >= 12 }
                val result = QuickVcm1Reader.readVcm1Identity(tag, asopKeys)
                if (result == null) {
                    _state.update {
                        it.copy(
                            cardStep = CardStep.NFC_ERROR,
                            errorMessage = "Не удалось прочитать сектор 1 карты. " +
                                "Проверьте, что приложили MIFARE Classic 1K/4K карту с ASOP VCM1."
                        )
                    }
                    return@launch
                }
                val (uid, vcm1Bytes) = result
                onCardTappedForAuth(uid, vcm1Bytes)
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        cardStep = CardStep.NFC_ERROR,
                        errorMessage = "Ошибка чтения NFC: ${e.message}"
                    )
                }
            }
        }
    }

    private suspend fun lookupUserFullName(userId: String): String? {
        return try {
            val rowJson = referenceRowDao.rawUserByIdRow(userId) ?: return null
            val obj = JSONObject(rowJson)
            val first = obj.optString("firstName", "")
            val last = obj.optString("lastName", "")
            if (first.isNotEmpty() && last.isNotEmpty()) "$first $last" else first.ifEmpty { null }
        } catch (e: Exception) { null }
    }

    private fun extractFirstCarrierId(rowJson: String): String? {
        val rx = Regex("\"carrierId\"\\s*:\\s*\"([^\"]+)\"")
        return rx.find(rowJson)?.groupValues?.getOrNull(1)
    }

    init { observeSessions() }

    @Suppress("unused")
    private fun touchFlows(): List<Flow<*>> = listOf(_state)
}
