package ru.asop.payment.handoff

import android.content.Intent
import ru.asop.payment.core.PayRequest
import ru.asop.payment.core.PayResponse

/**
 * Промпт 016 §1.3: handoff-контракт терминал → app-payment.
 * Терминал запускает `startActivityForResult(ACTION_PAY)` с EXTRA_REQUEST,
 * результат — в EXTRA_RESULT (JSON, те же поля, что POST /pay).
 */
object PaymentHandoff {
    const val ACTION_PAY = "ru.asop.payment.ACTION_PAY"
    const val EXTRA_REQUEST = "request"
    const val EXTRA_RESULT = "result"

    fun buildRequestIntent(request: PayRequest): Intent =
        Intent(ACTION_PAY).putExtra(EXTRA_REQUEST, request.toJson())

    fun readRequest(intent: Intent?): PayRequest? =
        intent?.getStringExtra(EXTRA_REQUEST)?.let { runCatching { PayRequest.fromJson(it) }.getOrNull() }

    fun writeResult(intent: Intent, response: PayResponse): Intent =
        intent.putExtra(EXTRA_RESULT, response.toJson())
}