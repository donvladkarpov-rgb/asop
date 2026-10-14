package ru.asop.distributor.core

import android.nfc.Tag
import android.nfc.tech.MifareClassic
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.github.f4b6a3.uuid.UuidCreator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import ru.asop.distributor.BuildConfig
import ru.asop.distributor.network.DistributorSyncApi
import ru.asop.distributor.network.models.FiscalReceiptRequest
import ru.asop.nfc.AsopCardType
import ru.asop.nfc.Vcm1CardAuth

/**
 * Флоу пополнения у дистрибьютора (bank_card_api.md §4.1, целевой флоу):
 * карта дистрибьютора (auth) → сумма + способ (нал/карта) →
 * «карта»: банковская карта пассажира (только HTTP /pay в app-payment, MIFARE локально) →
 * MIFARE пассажира → запись поездок (read+write в одной сессии) →
 * фискальный чек (промпт 017 §10): POST /sync/fiscal/receipts (source=DISTRIBUTOR) + QR ~3 с.
 *
 * Дистрибьютор НЕ читает банковскую карту (EMV) — только MIFARE. Не-MIFARE на шаге
 * «банк-карта» → HTTP /pay в app-payment (пассажир тапает карту повторно на FTSDK).
 */
class TopUpViewModel(
    private val keys: KeyProvider,
    private val tariffs: TariffProvider,
    private val payments: PaymentClient,
    private val fiscalizers: FiscalizerProvider,
    private val syncApi: DistributorSyncApi
) {

    enum class Step {
        IDLE, DISTRIBUTOR_SCAN, DISTRIBUTOR_AUTHED, METHOD, BANK_CARD, MIFARE_WRITE, PAYING, DONE
    }

    enum class Method { CASH, CARD }

    data class FiscalQr(val receiptId: String, val url: String)

    data class UiState(
        val step: Step = Step.IDLE,
        val message: String = "Готов. Приложите карту дистрибьютора",
        val operatorCardId: String? = null,
        val operatorRoles: List<String> = emptyList(),
        val canTopUp: Boolean = false,
        val method: Method? = null,
        val amount: Double? = null,
        val payStatus: String? = null,
        val payError: String? = null,
        val passengerCardId: String? = null,
        val passengerUid: String? = null,
        val passengerTrips: Int? = null,
        val newTrips: Int? = null,
        val lastAmount: Double? = null,
        val acqReference: String? = null,
        val fiscalQr: FiscalQr? = null
    )

    var state by mutableStateOf(UiState())
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var passengerSession: Vcm1CardAuth.Session? = null

    companion object {
        private const val FISCAL_QR_DISPLAY_MS = 3000L
    }

    fun reset() {
        passengerSession?.close()
        passengerSession = null
        state = UiState()
    }

    fun onTag(tag: Tag) {
        Log.i("TopUp", "onTag step=${state.step} techs=${tag.techList.toList()}")
        when (state.step) {
            Step.IDLE, Step.DISTRIBUTOR_SCAN -> scanDistributor(tag)
            Step.BANK_CARD -> scanBankCard(tag)
            Step.MIFARE_WRITE -> scanMifareWrite(tag)
            else -> Log.i("TopUp", "игнор тапа на шаге ${state.step}")
        }
    }

    /** После авторизации оператора — к вводу суммы/способа. */
    fun beginMethod() {
        state = state.copy(
            step = Step.METHOD,
            message = "Введите сумму и выберите способ оплаты",
            payError = null, payStatus = null
        )
    }

    /** Наличные: сразу к записи поездок на MIFARE пассажира. */
    fun payCash(amount: Double) {
        if (amount <= 0) {
            state = state.copy(payError = "Укажите сумму больше нуля")
            return
        }
        state = state.copy(
            step = Step.MIFARE_WRITE, method = Method.CASH, amount = amount,
            message = "Приложите MIFARE пассажира для записи поездок",
            payError = null, payStatus = null, acqReference = null
        )
    }

    /** Картой: сначала банковская карта (HTTP /pay в app-payment). */
    fun payCard(amount: Double) {
        if (amount <= 0) {
            state = state.copy(payError = "Укажите сумму больше нуля")
            return
        }
        state = state.copy(
            step = Step.BANK_CARD, method = Method.CARD, amount = amount,
            message = "Приложите банковскую карту пассажира",
            payError = null, payStatus = null
        )
    }

    // ---------- NFC ----------

    private fun scanDistributor(tag: Tag) {
        state = state.copy(step = Step.DISTRIBUTOR_SCAN, message = "Чтение карты дистрибьютора…")
        scope.launch {
            val outcome = Vcm1CardAuth.read(tag, keys.candidateKeys())
            if (outcome is Vcm1CardAuth.Outcome.Ok) {
                val roles = AsopCardType.allRolesForBitmask(outcome.identity.bitmask)
                val canTopUp = roles.any {
                    it in setOf(
                        AsopCardType.DISTRIBUTOR_ADMIN,
                        AsopCardType.DISTRIBUTOR_DISPATCHER,
                        AsopCardType.SUPER_ADMIN
                    )
                }
                state = state.copy(
                    step = Step.DISTRIBUTOR_AUTHED,
                    operatorCardId = outcome.identity.cardId.toString(),
                    operatorRoles = roles.map { it.role },
                    canTopUp = canTopUp,
                    message = if (canTopUp) "Дистрибьютор авторизован"
                    else "Роль не позволяет пополнение: ${roles.joinToString { it.label }}"
                )
            } else {
                val fail = outcome as Vcm1CardAuth.Outcome.Failed
                state = state.copy(step = Step.IDLE, message = "Дистрибьютор: ${fail.details}")
            }
        }
    }

    private fun scanBankCard(tag: Tag) {
        val amount = state.amount
        if (amount == null) {
            state = state.copy(payError = "Сумма не задана")
            return
        }
        if (MifareClassic.get(tag) != null) {
            state = state.copy(payError = "Это MIFARE, а не банковская карта. Приложите банковскую карту")
            return
        }
        state = state.copy(
            step = Step.PAYING, message = "Ожидание оплаты…",
            payError = null, payStatus = null, lastAmount = amount
        )
        val requestId = UuidCreator.getTimeOrderedEpoch().toString()
        scope.launch {
            // На устройствах без FTSDK (Samsung) app-payment не может прочитать карту сам —
            // читаем её здесь через IsoDep и передаём открытые данные в /pay.
            val card = EmvIsoDepReader.read(tag)
            val result = payments.pay(requestId, amount, "TOPUP", message = "Пополнение транспортной карты", card = card)
            if (result == null) {
                state = state.copy(step = Step.BANK_CARD, payError = "Нет связи с app-payment")
                return@launch
            }
            when (result.status) {
                "APPROVED" -> state = state.copy(
                    step = Step.MIFARE_WRITE, payStatus = "APPROVED",
                    acqReference = result.acqReference,
                    message = "Оплачено. Приложите MIFARE пассажира для записи поездок"
                )
                else -> state = state.copy(
                    step = Step.BANK_CARD, payStatus = result.status,
                    payError = result.errorMessage ?: result.errorCode ?: "Платёж не одобрен (${result.status})"
                )
            }
        }
    }

    private fun scanMifareWrite(tag: Tag) {
        val amount = state.amount ?: return
        if (MifareClassic.get(tag) == null) {
            state = state.copy(payError = "Это не MIFARE-карта. Приложите карту пассажира")
            return
        }
        state = state.copy(step = Step.PAYING, message = "Запись поездок…")
        scope.launch {
            val result = Vcm1CardAuth.readWithSession(tag, keys.candidateKeys())
            val ok = result.outcome as? Vcm1CardAuth.Outcome.Ok
            if (ok != null && result.session != null) {
                val session = result.session!!
                passengerSession?.close()
                passengerSession = session
                val currentTrips = ok.identity.tripsLeft
                val tripsToAdd = tariffs.tripsFor(amount)
                val newTrips = (currentTrips + tripsToAdd).coerceAtMost(0xFFFF)
                val written = session.updateTrips(newTrips)
                session.close()
                passengerSession = null
                if (written) {
                    // Промпт 017 §10: фискализация продажи дистрибьютора (нал или карта).
                    // Только после успешной записи поездок — повторный тап при неудаче не
                    // создаст двойной чек (запись повторяется, но fiscalize вызывается один раз).
                    fiscalize(amount, state.acqReference)
                }
                state = state.copy(
                    step = if (written) Step.DONE else Step.MIFARE_WRITE,
                    message = if (written) "Пополнение успешно" else "Запись не удалась — приложите карту ещё раз",
                    passengerCardId = ok.identity.cardId.toString(),
                    passengerUid = ok.uidHex,
                    passengerTrips = currentTrips,
                    newTrips = if (written) newTrips else null,
                    payError = if (written) null else "Запись tripsLeft не подтверждена — карта могла быть снята раньше времени"
                )
            } else {
                val fail = result.outcome as Vcm1CardAuth.Outcome.Failed
                state = state.copy(step = Step.MIFARE_WRITE, payError = "Пассажир: ${fail.details}")
            }
        }
    }

    /**
     * Промпт 017 §10: запрос фискального чека дистрибьютора + показ QR ~3 с.
     * `source=DISTRIBUTOR`, `transactionId` отсутствует (у дистрибьютора нет серверной
     * транзакции) — fiscal-service резолвит фискализатора по `cardsDistributorId`.
     * Сбой сети/сервера не ломает флоу: sale уже завершён, QR показан оптимистично.
     */
    private fun fiscalize(amount: Double, acqReference: String?) {
        scope.launch {
            try {
                val receiptId = UuidCreator.getTimeOrderedEpoch().toString()
                val cardsDistributorId = BuildConfig.DISTRIBUTOR_CARDS_DISTRIBUTOR_ID
                val request = FiscalReceiptRequest(
                    receiptId = receiptId,
                    amount = amount,
                    description = "Пополнение транспортной карты",
                    cardsDistributorId = cardsDistributorId.ifBlank { null },
                    source = "DISTRIBUTOR",
                    acqReference = acqReference
                )
                val url = fiscalizers.resolveUrl(cardsDistributorId, receiptId)
                state = state.copy(fiscalQr = FiscalQr(receiptId, url))
                val resp = runCatching { syncApi.requestFiscalReceipt(request) }.getOrNull()
                if (resp != null && !resp.isSuccessful) {
                    Log.w("TopUp", "fiscal HTTP ${resp.code()}: ${resp.errorBody()?.string()}")
                }
                kotlinx.coroutines.delay(FISCAL_QR_DISPLAY_MS)
                if (state.fiscalQr?.receiptId == receiptId) {
                    state = state.copy(fiscalQr = null)
                }
            } catch (e: Exception) {
                Log.w("TopUp", "fiscalize failed: ${e.message}")
            }
        }
    }
}
