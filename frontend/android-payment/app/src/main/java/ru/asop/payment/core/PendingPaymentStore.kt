package ru.asop.payment.core

import android.content.Context
import ru.asop.payment.db.PaymentDatabase
import ru.asop.payment.db.PendingPaymentEntity
import org.json.JSONObject

/**
 * Обёртка над Room-кэшем локальных результатов /pay.
 * [findByIdempotentKey] — возврат сохранённого ответа при повторном requestId.
 * [persist] — сохранение результата (requestId, paymentId, payload).
 */
class PendingPaymentStore private constructor(context: Context) {

    private val dao = PaymentDatabase.get(context).pendingPaymentDao()

    suspend fun findByIdempotentKey(requestId: String): PayResponse? {
        val raw = dao.findByIdempotentKey(requestId) ?: return null
        return runCatching { PayResponse.fromJson(JSONObject(raw)) }.getOrNull()
    }

    suspend fun persist(response: PayResponse, paymentType: String = "FARE") {
        dao.insert(
            PendingPaymentEntity(
                requestId = response.requestId,
                paymentId = response.paymentId ?: "",
                payloadJson = response.toJson(),
                paymentType = paymentType
            )
        )
    }

    companion object {
        @Volatile private var instance: PendingPaymentStore? = null
        fun get(context: Context): PendingPaymentStore =
            instance ?: synchronized(this) {
                instance ?: PendingPaymentStore(context.applicationContext).also { instance = it }
            }
    }
}