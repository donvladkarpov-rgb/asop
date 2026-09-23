package ru.asop.payment.network

import org.json.JSONObject
import ru.asop.payment.core.PayResponse
import ru.asop.payment.core.PayStatus

/**
 * Промпт 016 §3.1/§5: маппинг локального результата → `PaymentReportRequest`
 * (см. payment-api dto/request/PaymentReportRequest.kt).
 */
object ReportPayload {

    /** Мэпинг локальных статусов на ASOP_BANK_PAYMENTS.STATUS (bank_card_api.md §5). */
    fun serverStatus(local: PayStatus): String? = when (local) {
        PayStatus.APPROVED -> "AUTHORIZED"
        PayStatus.DECLINED -> "DECLINED"
        PayStatus.TIMEOUT -> "FAILED"
        PayStatus.DEFERRED -> "DEFERRED"
        PayStatus.CANCELED -> "REVERSED"
        PayStatus.VOIDED -> "REVERSED"
        // PENDING / VOID_FAILED — нетерминальные, не репортятся.
        PayStatus.PENDING -> null
        PayStatus.VOID_FAILED -> null
    }

    fun build(response: PayResponse, paymentType: String): JSONObject? {
        val status = serverStatus(response.status) ?: return null
        return JSONObject()
            .putOpt("paymentId", response.paymentId)
            .putOpt("requestId", response.requestId)
            .putOpt("cardToken", response.card?.cardToken)
            .put("amount", response.amount ?: 0.0)
            .put("currency", "RUB")
            .put("paymentType", paymentType)
            .putOpt("acqReference", response.acqReference)
            .putOpt("rrn", response.rrn)
            .putOpt("authCode", response.authCode)
            .put("status", status)
            .putOpt("errorCode", response.errorCode)
            .putOpt("errorMessage", response.errorMessage)
    }
}