package ru.asop.distributor.core

import android.nfc.Tag
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.github.f4b6a3.uuid.UuidCreator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import ru.asop.nfc.AsopCardType
import ru.asop.nfc.CardIdentityVcm1
import ru.asop.nfc.Vcm1CardAuth

/**
 * Флоу пополнения у дистрибьютора (bank_card_api.md §4.1):
 * карта дистрибьютора (auth) → карта пассажира → сумма поездок (tariff-rates)
 * → POST /pay (app-payment) → APPROVED → запись tripsLeft (VCM1, локально).
 */
class TopUpViewModel(
    private val keys: KeyProvider,
    private val tariffs: TariffProvider,
    private val payments: PaymentClient
) {

    enum class Step { IDLE, DISTRIBUTOR_SCAN, DISTRIBUTOR_AUTHED, PASSENGER_SCAN, PASSENGER_READ, PAYING, DONE }

    data class UiState(
        val step: Step = Step.IDLE,
        val message: String = "Готов. Приложите карту дистрибьютора",
        val operatorCardId: String? = null,
        val operatorRoles: List<String> = emptyList(),
        val canTopUp: Boolean = false,
        val passengerCardId: String? = null,
        val passengerUid: String? = null,
        val passengerTrips: Int? = null,
        val payStatus: String? = null,
        val payError: String? = null,
        val newTrips: Int? = null,
        val lastAmount: Double? = null
    )

    var state by mutableStateOf(UiState())
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var passengerSession: Vcm1CardAuth.Session? = null

    fun reset() {
        passengerSession?.close()
        passengerSession = null
        state = UiState()
    }

    fun onTag(tag: Tag) {
        when (state.step) {
            Step.IDLE, Step.DISTRIBUTOR_SCAN -> scanDistributor(tag)
            Step.PASSENGER_SCAN -> scanPassenger(tag)
            else -> Log.i("TopUp", "игнор тапа на шаге ${state.step}")
        }
    }

    fun beginPassengerScan() {
        state = state.copy(step = Step.PASSENGER_SCAN, message = "Приложите карту пассажира",
            payError = null, payStatus = null)
    }

    fun pay(amount: Double) {
        val session = passengerSession
        val currentTrips = state.passengerTrips ?: 0
        if (session == null) {
            state = state.copy(step = Step.IDLE, message = "Сессия карты потеряна — повторите с начала")
            return
        }
        if (amount <= 0) {
            state = state.copy(payError = "Укажите сумму больше нуля")
            return
        }
        state = state.copy(step = Step.PAYING, message = "Ожидание оплаты...",
            payStatus = null, payError = null, lastAmount = amount)
        val requestId = UuidCreator.getTimeOrderedEpoch().toString()
        scope.launch {
            val result = payments.pay(requestId, amount, "TOPUP", message = "Пополнение транспортной карты")
            if (result == null) {
                state = state.copy(step = Step.PASSENGER_READ,
                    payError = "Нет связи с app-payment (417: сервер эквайринга недоступен локально)")
                return@launch
            }
            when (result.status) {
                "APPROVED" -> {
                    val tripsToAdd = tariffs.tripsFor(amount)
                    val newTrips = (currentTrips + tripsToAdd).coerceAtMost(0xFFFF)
                    val written = session.updateTrips(newTrips)
                    session.close()
                    passengerSession = null
                    state = state.copy(
                        step = if (written) Step.DONE else Step.PASSENGER_READ,
                        message = if (written) "Пополнение успешно" else "Карта приняла платёж, но tripsLeft не записаны",
                        payStatus = "APPROVED",
                        newTrips = if (written) newTrips else null,
                        payError = if (written) null else "Запись tripsLeft не подтверждена — карта могла быть снята раньше времени"
                    )
                }
                else -> {
                    state = state.copy(step = Step.PASSENGER_READ,
                        payStatus = result.status,
                        payError = result.errorMessage ?: result.errorCode ?: "Платёж не одобрен (${result.status})")
                }
            }
        }
    }

    /** Только наличные: запишем поездки без банковской оплаты (PASSENGER_ANONYMOUS-кейс). */
    fun cashIndex(amount: Double) {
        if (amount > 0) payCash(amount)
    }

    private fun payCash(amount: Double) {
        val session = passengerSession
        val currentTrips = state.passengerTrips ?: 0
        if (session == null) return
        val tripsToAdd = tariffs.tripsFor(amount)
        val newTrips = (currentTrips + tripsToAdd).coerceAtMost(0xFFFF)
        scope.launch {
            val written = session.updateTrips(newTrips)
            session.close()
            passengerSession = null
            state = state.copy(
                step = if (written) Step.DONE else Step.PASSENGER_READ,
                message = if (written) "Наличные: пополнение успешно" else "Наличные: запиись не удалась",
                payStatus = "CASH",
                newTrips = if (written) newTrips else null,
                payError = if (written) null else "Запись tripsLeft не подтверждена"
            )
        }
    }

    // ---------- NFC ----------

    private fun scanDistributor(tag: Tag) {
        state = state.copy(step = Step.DISTRIBUTOR_SCAN, message = "Чтение карты дистрибьютора...")
        scope.launch {
            val outcome = Vcm1CardAuth.read(tag, keys.candidateKeys())
            if (outcome is Vcm1CardAuth.Outcome.Ok) {
                val roles = AsopCardType.allRolesForBitmask(outcome.identity.bitmask)
                val canTopUp = roles.any {
                    it in setOf(AsopCardType.DISTRIBUTOR_ADMIN, AsopCardType.DISTRIBUTOR_DISPATCHER, AsopCardType.SUPER_ADMIN)
                }
                state = state.copy(
                    step = Step.DISTRIBUTOR_AUTHED,
                    operatorCardId = outcome.identity.cardId.toString(),
                    operatorRoles = roles.map { it.role },
                    canTopUp = canTopUp,
                    message = if (canTopUp) "Дистрибьютор авторизован. Приложите карту пассажира"
                              else "Роль не позволяет пополнение: ${roles.joinToString { it.label }}"
                )
            } else {
                val fail = outcome as Vcm1CardAuth.Outcome.Failed
                state = state.copy(step = Step.IDLE,
                    message = "Дистрибьютор: ${fail.details}")
            }
        }
    }

    private fun scanPassenger(tag: Tag) {
        state = state.copy(step = Step.PASSENGER_SCAN, message = "Чтение карты пассажира...")
        scope.launch {
            val result = Vcm1CardAuth.readWithSession(tag, keys.candidateKeys())
            val ok = result.outcome as? Vcm1CardAuth.Outcome.Ok
            if (ok != null) {
                passengerSession?.close()
                passengerSession = result.session
                state = state.copy(
                    step = Step.PASSENGER_READ,
                    passengerCardId = ok.identity.cardId.toString(),
                    passengerUid = ok.uidHex,
                    passengerTrips = ok.identity.tripsLeft,
                    message = "Карта пассажира прочитана. Введите сумму пополнения"
                )
            } else {
                val fail = result.outcome as Vcm1CardAuth.Outcome.Failed
                state = state.copy(step = Step.DISTRIBUTOR_AUTHED,
                    message = "Пассажир: ${fail.details}. Приложите другую карту")
            }
        }
    }
}