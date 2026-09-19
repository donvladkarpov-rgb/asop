package ru.asop.payment.core

import android.content.Context
import android.util.Log
import ru.asop.payment.BuildConfig

/**
 * Обработка платежа. Для PoC — детерминированный mock по копейкам суммы
 * (как MockAcquirerAdapter на сервере — см. prompt_016 §3.1):
 *  .01 → DECLINED (05), .02 → TIMEOUT, .03 → DEFERRED (74), .04 → REAUTH (PENDING до онлайн),
 *  иначе APPROVED с MOCK-референсом.
 *
 * Реальный EMV-контур (FTSDK Emv + key-инжекция ВТБ) подключается после предоставления
 * интерфейса/ключей банка — см. VtbSirposAdapter (backend) и §4.6 bank_card.md.
 */
class PayProcessor(private val context: Context) {

    private val tag = "PayProcessor"

    /** Выполняет /pay. В PoC-режиме не блокирует на EMV — возвращает сразу. */
    suspend fun process(req: PayRequest): PayResponse {
        val stubCard = CardInfo(
            maskedPan = "2200 00•• •••• 1234",
            panLast4 = "1234",
            bin = "220000",
            expiry = "12/28",
            holdername = null,
            cardToken = null
        )

        // Фаза 0: фиксируем доступность NFC-ридера на F20.
        val probe = CardProbe.get(context).checkNfc()
        if (!probe.available) {
            Log.w(tag, "NFC reader unavailable: checkCode=${probe.checkCode} err=${probe.error}")
        }

        if (!BuildConfig.PO_C_MOCK_EMV) {
            // Реальный EMV — TBD (интерфейс ВТБ). Возвращаем TIMEOUT, чтобы не «съесть» карту.
            return response(req, PayStatus.TIMEOUT, errorCode = "91", errorMessage = "EMV kernel не настроен (TBD)")
        }

        val kopecks = kotlin.math.round(req.amount * 100.0).toInt() % 100
        return when (kopecks) {
            1 -> response(req, PayStatus.DECLINED, errorCode = "05", errorMessage = "Do not honor")
            2 -> response(req, PayStatus.TIMEOUT, errorCode = "91", errorMessage = "Card not presented")
            3 -> response(req, PayStatus.DEFERRED, errorCode = "74", errorMessage = "Deferred authorization")
            4 -> response(req, PayStatus.PENDING, errorCode = "76", errorMessage = "Re-auth required")
            else -> response(
                req, PayStatus.APPROVED,
                acqReference = "MOCK-${req.requestId}",
                rrn = "000000" + req.requestId.take(6),
                authCode = (1000..9999).random().toString(),
                card = stubCard
            )
        }
    }

    /** void: в PoC-моде онлайн-attempt не контактировал с эквайером — void не нужен, но локальный статус сохраняем. */
    suspend fun void(req: PayRequest): PayResponse {
        val previous = store.findByIdempotentKey(req.requestId)
        if (previous == null) return response(req, PayStatus.VOID_FAILED, errorCode = "11", errorMessage = "Session unknown")
        if (previous.status != PayStatus.PENDING) return response(req, PayStatus.VOID_FAILED, errorCode = "60", errorMessage = "Not reversible")
        return response(req, PayStatus.VOIDED)
    }

    private fun response(
        req: PayRequest,
        status: PayStatus,
        acqReference: String? = null,
        rrn: String? = null,
        authCode: String? = null,
        errorCode: String? = null,
        errorMessage: String? = null,
        card: CardInfo? = null
    ): PayResponse = PayResponse(
        requestId = req.requestId,
        status = status,
        paymentId = PaymentKey.newId(),
        amount = req.amount,
        acqReference = acqReference,
        rrn = rrn,
        authCode = authCode,
        errorCode = errorCode,
        errorMessage = errorMessage,
        approvedOffline = false,
        card = card
    )

    private val store: PendingPaymentStore = PendingPaymentStore.get(context)
}