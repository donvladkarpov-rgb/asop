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
import ru.asop.terminal.NfcTagBus
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
import ru.asop.terminal.nfc.Vcm1CardAuth
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
    private val workScheduler: ru.asop.terminal.worker.WorkScheduler,
    private val terminalDao: ReferenceRowDao = referenceRowDao
) : AndroidViewModel(application) {

    enum class FlowKind { OPEN_SHIFT, CLOSE_SHIFT, OPEN_TRIP, CLOSE_TRIP, TAP_PASSENGER }
    enum class CardStep {
        WAITING_TAP,      // экран ждёт tap
        PROCESSING,        // tag detected, читаем VCM1 (UI показывает UID)
        AUTH_OK,           // // успешно прочитана VCM1 + DRIVER/CARRIER_DISPATCHER-роль
        AUTH_DENIED,       // // auth не прошёл / ключ не тот / карта не активирована
        NOT_DRIVER,        // // карта ОК, но роль не подходит (не DRIVER)
        NFC_ERROR,         // // quick-vcm1-reader упал / не MifareClassic / auth error и т. п.
        IDLE               // // дефолт до setKind
    }
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
        val lastTapUidHex: String? = null,
        val lastTapAtMillis: Long? = null,
        val cardTap: CardTapInfo? = null,
        val submitState: SubmitState = SubmitState.IDLE,
        val errorMessage: String? = null,
        val infoMessage: String? = null,
        val openShift: SessionEntity? = null,
        val openTrip: SessionEntity? = null,
        val tripPayments: List<TripPaymentEntity> = emptyList(),
        // Промпт 014: feedback для экрана ожидания пассажиров
        val validationResult: Boolean? = null,
        val validationResultTime: Long = 0L,
        // Промпт 011: OpenTrip cascade picker (TID → Vehicle → Route → Path)
        val tripTidId: String? = null,
        val tripTidLabel: String? = null,
        val tripVehicleId: String? = null,
        val tripVehicleLabel: String? = null,
        val tripRouteId: String? = null,
        val tripRouteLabel: String? = null,
        val tripPathId: String? = null,
        val tripPathLabel: String? = null,
        // Промпт 011: подпись для ShiftTripInformer (вернее — имена, а не ID).
        val informerText: String? = null
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

    private var passengerModeEnabled = false

    /** Промпт 014: переключение в режим приёма пассажиров после открытия рейса. */
    fun switchToPassengerMode() {
        if (passengerModeEnabled) return
        passengerModeEnabled = true
        _state.update {
            it.copy(
                kind = FlowKind.TAP_PASSENGER,
                cardStep = CardStep.IDLE,
                submitState = SubmitState.IDLE,
                infoMessage = "Рейс активен. Ждите карты пассажиров",
                cardTap = null
            )
        }
    }

    fun exitPassengerMode() {
        // НЕ сбрасываем passengerModeEnabled — он защищает auto-switch LaunchedEffect
        // от повторного входа при изменении kind с OPEN_TRIP → TAP_PASSENGER.
        _state.update {
            it.copy(
                kind = FlowKind.OPEN_TRIP,
                submitState = SubmitState.ACCEPTED,
                cardStep = CardStep.IDLE,
                infoMessage = "Режим ожидания завершён. Рейс активен.",
                cardTap = null
            )
        }
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        // Feitian F20 fallback: опрос NfcTagBus на случай если ReaderMode binder не
        // зарегистрирован и единственный путь к карте — через ForegroundDispatch /
        // onNewIntent в MainActivity.
        viewModelScope.launch {
            while (true) {
                kotlinx.coroutines.delay(300L)
                if (state.value.cardStep == CardStep.WAITING_TAP ||
                    state.value.cardStep == CardStep.IDLE) {
                    val pending = NfcTagBus.consume()
                    if (pending != null) {
                        android.util.Log.i("SessionFlowVM", "NFC bus yielded tag, calling onTagDiscovered")
                        onTagDiscovered(pending)
                    }
                }
            }
        }
    }

    fun setKind(kind: FlowKind) {
        _state.update {
            it.copy(
                kind = kind,
                submitState = SubmitState.IDLE,
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
                refreshInformer()
            }
        }
        viewModelScope.launch {
            sessionDao.observeCurrentOpenTrip().collect { trip ->
                _state.update { it.copy(openTrip = trip) }
                refreshInformer()
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

    /** Промпт 011: informer на MainScreen показывает ИМЕНА (а не UUID). */
    private fun refreshInformer() {
        viewModelScope.launch {
            val s = _state.value
            val shift = s.openShift
            val trip = s.openTrip
            val text = when {
                trip != null && shift != null -> {
                    val driver = if (shift.openedByUserId != null)
                        lookupUserFullName(shift.openedByUserId) ?: shift.openedByUserId.take(8)
                    else "—"
                    val vehicle = trip.vehicleId?.let { lookupVehicleLabel(it) } ?: "—"
                    val path = trip.pathId?.let { lookupPathLabel(it) } ?: "—"
                    "Рейс открыт: водитель=$driver, ТС=$vehicle, маршрут=$path"
                }
                shift != null -> {
                    val driver = if (shift.openedByUserId != null)
                        lookupUserFullName(shift.openedByUserId) ?: shift.openedByUserId.take(8)
                    else "—"
                    "Смена открыта: водитель=$driver"
                }
                else -> "Смена закрыта. Откройте смену через меню."
            }
            _state.update { it.copy(informerText = text) }
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
                ru.asop.terminal.nfc.TonePlayer.errorBeep()
                return
            }
            val userId = identity.entity?.id?.toString()
                ?: identity.entity?.id?.toString()
            val cardId = identity.cardId.toString()

            if (userId.isNullOrEmpty()) {
                _state.update { it.copy(cardStep = CardStep.AUTH_DENIED, errorMessage = "Карта не активирована") }
                ru.asop.terminal.nfc.TonePlayer.errorBeep()
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
                ru.asop.terminal.nfc.TonePlayer.errorBeep()
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
                    ru.asop.terminal.nfc.TonePlayer.errorBeep()
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
                ru.asop.terminal.nfc.TonePlayer.tapBeep()
            }
        } catch (e: Exception) {
            _state.update { it.copy(cardStep = CardStep.AUTH_DENIED, errorMessage = "Ошибка парсинга карты: ${e.message}") }
            ru.asop.terminal.nfc.TonePlayer.errorBeep()
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
                    sessionId = sessionId,
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
                   ,
                        seq = syncPreferences.nextSeq())
                )
                syncPreferences.setLastCardTap(null, tap.cardId)
                workScheduler.enqueueOneShotSync()
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
                    sessionId = tripId,
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
                   ,
                        seq = syncPreferences.nextSeq())
                )
                workScheduler.enqueueOneShotSync()
                _state.update {
                    it.copy(
                        submitState = SubmitState.IDLE,
                        kind = FlowKind.TAP_PASSENGER,
                        infoMessage = "Рейс открыт. Ждите карты пассажиров",
                        cardStep = CardStep.IDLE,
                        cardTap = null,
                        tripTidId = null,
                        tripTidLabel = null,
                        tripVehicleId = null,
                        tripVehicleLabel = null,
                        tripRouteId = null,
                        tripRouteLabel = null,
                        tripPathId = null,
                        tripPathLabel = null,
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(submitState = SubmitState.FAILED, errorMessage = "Ошибка: ${e.message}") }
            }
        }
    }

    /**
     * Закрыть смену (промпт 011 §13, §16).
     *
     * Закрыть может ЛЮБОЙ из:
     *  - водитель (сам открывший)
     *  - другой водитель того же перевозчика
     *  - диспетчер перевозчика
     *  - админ перевозчика / организатора перевозок / региона / root
     *
     * Client-side НЕ ограничивает — серверная SessionService.canClose()
     * делает cascading check через ASOP_USER_CARRIERS / ASOP_USER_REGIONS /
     * ASOP_USER_ROLES.
     */
    fun confirmCloseShift() {
        val tap = _state.value.cardTap ?: run {
            _state.update { it.copy(submitState = SubmitState.FAILED, errorMessage = "Сначала приложите карту водителя/админа") }
            return
        }
        val shift = _state.value.openShift
        if (shift == null) {
            _state.update { it.copy(submitState = SubmitState.FAILED, errorMessage = "Смена уже закрыта") }
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
                    cardId = tap.cardId,
                    closedByUserId = tap.userId
                )
                pendingEventDao.insert(
                    PendingEventEntity(
                        id = "close-${shift.id}",
                        topic = "asop.session.commands",
                        payload = JsonUtil.encode(payload),
                        eventType = EventTypes.SESSION_CLOSE,
                        pathParam = shift.id
                   ,
                        seq = syncPreferences.nextSeq())
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
     * Закрыть рейс (промпт 011 §12, §17).
     *
     * Закрыть может ЛЮБОЙ из:
     *  - водитель, открывший смену (или открывший этот рейс)
     *  - другой водитель того же перевозчика
     *  - диспетчер / админ перевозчика / организатора / региона / root
     *
     * Client-side НЕ ограничивает — серверная SessionService.canClose()
     * делает cascading check через ASOP_USER_CARRIERS / ASOP_USER_REGIONS /
     * ASOP_USER_ROLES.
     */
    fun confirmCloseTrip() {
        val tap = _state.value.cardTap
        val trip = _state.value.openTrip
        if (trip == null) {
            _state.update { it.copy(submitState = SubmitState.FAILED, errorMessage = "Рейс уже закрыт") }
            return
        }
        if (tap == null) {
            _state.update { it.copy(submitState = SubmitState.FAILED, errorMessage = "Сначала приложите карту водителя/админа") }
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
                    cardId = tap.cardId,
                    closedByUserId = tap.userId
                )
                pendingEventDao.insert(
                    PendingEventEntity(
                        id = "close-${trip.id}",
                        topic = "asop.session.commands",
                        payload = JsonUtil.encode(payload),
                        eventType = EventTypes.SESSION_CLOSE,
                        pathParam = trip.id
                   ,
                        seq = syncPreferences.nextSeq())
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
                   ,
                        seq = syncPreferences.nextSeq())
                )
                workScheduler.enqueueOneShotSync()
                val resultTime = System.currentTimeMillis()
                _state.update {
                    it.copy(
                        submitState = SubmitState.IDLE,
                        infoMessage = "Валидация зафиксирована",
                        cardStep = CardStep.IDLE,
                        validationResult = true,
                        validationResultTime = resultTime
                    )
                }
                kotlinx.coroutines.delay(1200)
                _state.update {
                    if (it.validationResultTime == resultTime) {
                        it.copy(validationResult = null, validationResultTime = 0L)
                    } else it
                }
            } catch (e: Exception) {
                val resultTime = System.currentTimeMillis()
                _state.update {
                    it.copy(
                        submitState = SubmitState.IDLE,
                        validationResult = false,
                        validationResultTime = resultTime,
                        errorMessage = "Ошибка валидации: ${e.message}"
                    )
                }
                kotlinx.coroutines.delay(1200)
                _state.update {
                    if (it.validationResultTime == resultTime) {
                        it.copy(validationResult = null, validationResultTime = 0L)
                    } else it
                }
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
     * Использует [Vcm1CardAuth] — единый читатель VCM1, идентичный процедуре «Прочитать карту».
     * Раньше был свой QuickVcm1Reader. read() (упрощённый): он плохо обрабатывал
     * TagLostException на clone-картах (CRYPTO1-сессия обрывалась посреди readBlock, и
     * пользователь видел «Исключение при чтении: null» без объяснений). Vcm1CardAuth
     * делегирует в MifareClassicReader.read() — у которого многопроходный retry на каждый
     * сектор с правильной обработкой IOException / TagLostException.
     */
    /**
     * Anti-duplicate debounce: Feitian PICC ReaderMode посылает `onTagDiscovered`
     * callback ПОВТОРНО пока карта держится в поле (типично каждые ~250мс при hold
     * ~1с). Без dedup второй callback стартует параллельный read, который
     * приходит после первого состояния PROCESSING и перетирает state в READ_FAILED
     * (race в Feitian PICC — пока первый read открыл mfc.connect, второй запускает
     * mfc.connect с тем же tag handle → IOException). Драйвер видит, что на короткий
     * tap всё работает, а если передержать — READ_FAILED.
     *
     * Решение: если новый callback пришёл с тем же UID в течение [DEBOUNCE_MS] —
     * игнорируем. После [DEBOUNCE_MS] (читать закончили) — пускаем второй read,
     * потому что пользователь мог переподнести карту.
     */
    private val debounceMs = 1500L

    fun onTagDiscovered(tag: Tag) {
        try {
            android.util.Log.i("SessionFlowVM", "onTagDiscovered: techList=${tag.techList.joinToString(",")}, uid=${tag.id.joinToString("") { "%02X".format(it) }}")
            // Промпт 014: писк убран отсюда — не сигнализируем «карта обнаружена»,
            // а пищим только после завершения операции (AUTH_OK / AUTH_DENIED).
        } catch (_: Exception) { }
        val now = System.currentTimeMillis()
        val newUidHex = tag.id.joinToString("") { "%02X".format(it) }
        val lastUid = _state.value.lastTapUidHex
        val lastAt = _state.value.lastTapAtMillis
        val sameUid = lastUid != null && lastUid == newUidHex
        val tooSoon = lastAt != null && (now - lastAt) < debounceMs
        if (sameUid && tooSoon) {
            android.util.Log.d(
                "SessionFlowVM",
                "debounce: ignored repeat tag within ${debounceMs}ms (uid=$newUidHex)"
            )
            return
        }
        // Шаг 1: мгновенный UI feedback — пользователь видит, что tap обнаружен.
        _state.update {
            it.copy(
                cardStep = CardStep.PROCESSING,
                lastTapUidHex = newUidHex,
                lastTapAtMillis = now,
                errorMessage = null
            )
        }
        viewModelScope.launch {
            try {
                val rows = terminalKeyDao.getActive(limit = 20)
                val asopKeys = rows.mapNotNull { entity ->
                    runCatching { terminalKeyCryptor.decrypt(entity.keyMaterialEnc) }
                        .getOrNull()
                }
                android.util.Log.i("SessionFlowVM", "ASOP-keys loaded: ${asopKeys.size}")
                val outcome = ru.asop.terminal.nfc.Vcm1CardAuth.read(tag, asopKeys)
                when (outcome) {
                    is ru.asop.terminal.nfc.Vcm1CardAuth.Outcome.Ok -> {
                        android.util.Log.i("SessionFlowVM",
                            "VCM1 auth OK: uid=${outcome.uidHex}, " +
                                "bitmask=0x${outcome.identity.bitmask.toString(16)}, " +
                                "cardId=${outcome.identity.cardId}")
                        android.util.Log.d("SessionFlowVM", "VCM1 OK handler: kind=${_state.value.kind}")
                        if (_state.value.kind == FlowKind.TAP_PASSENGER) {
                            val roles = ru.asop.terminal.activation.AsopCardType
                                .allRolesForBitmask(outcome.identity.bitmask)
                            val isDriver = roles.any {
                                it == ru.asop.terminal.activation.AsopCardType.DRIVER ||
                                it == ru.asop.terminal.activation.AsopCardType.CARRIER_DISPATCHER ||
                                it == ru.asop.terminal.activation.AsopCardType.KRS_DISPATCHER
                            }
                            if (isDriver) {
                                ru.asop.terminal.nfc.TonePlayer.tapBeep()
                                exitPassengerMode()
                            } else {
                                recordTripPayment(
                                    cardId = outcome.identity.cardId?.toString(),
                                    tapUserId = outcome.identity.entity?.id?.toString()
                                )
                            }
                        } else {
                            onCardTappedForAuth(outcome.uidHex, outcome.rawVcm1Bytes)
                        }
                    }
                    is ru.asop.terminal.nfc.Vcm1CardAuth.Outcome.Failed -> {
                        android.util.Log.w("SessionFlowVM",
                            "VCM1 auth failed: uid=${outcome.uidHex}, status=${outcome.status}, " +
                                "details=${outcome.details}")
                        ru.asop.terminal.nfc.TonePlayer.errorBeep()
                        val failTime = System.currentTimeMillis()
                        _state.update {
                            val s = it.copy(
                                cardStep = CardStep.NFC_ERROR,
                                errorMessage = outcome.details
                            )
                            if (s.kind == FlowKind.TAP_PASSENGER) {
                                s.copy(validationResult = false, validationResultTime = failTime)
                            } else s
                        }
                        if (_state.value.kind == FlowKind.TAP_PASSENGER) {
                            kotlinx.coroutines.delay(1200)
                            _state.update {
                                if (it.validationResultTime == failTime) {
                                    it.copy(validationResult = null, validationResultTime = 0L)
                                } else it
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("SessionFlowVM", "readVcm1 outer catch", e)
                ru.asop.terminal.nfc.TonePlayer.errorBeep()
                _state.update {
                    it.copy(
                        cardStep = CardStep.NFC_ERROR,
                        errorMessage = "Ошибка чтения NFC: ${e.javaClass.simpleName} ${e.message ?: "(без сообщения)"}"
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

    /** Подпись ТС из справочника vehicles (reference_rows) по UUID. */
    private suspend fun lookupVehicleLabel(vehicleId: String): String? {
        return lookupLabelFromTable("vehicles", vehicleId, "name", "registrationNumber", "plateNumber")
    }

    /** Подпись маршрута/пути из справочника по UUID. */
    private suspend fun lookupPathLabel(pathId: String): String? {
        return lookupLabelFromTable("paths", pathId, "name", "code", "routeName")
    }

    /** Ищет payload-строку по id в reference_rows и возвращает первое непустое поле-метку. */
    private suspend fun lookupLabelFromTable(table: String, id: String, vararg fields: String): String? {
        return try {
            val row = referenceRowDao.getActiveByTable(table).firstOrNull { row ->
                val json = org.json.JSONObject(row.payloadJson)
                json.optString("id") == id || json.optString("pathId") == id || json.optString("vehicleId") == id
            } ?: return null
            val obj = org.json.JSONObject(row.payloadJson)
            fields.firstNotNullOfOrNull { f ->
                val v = obj.optString(f)
                v.takeIf { it.isNotBlank() }
            }
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
