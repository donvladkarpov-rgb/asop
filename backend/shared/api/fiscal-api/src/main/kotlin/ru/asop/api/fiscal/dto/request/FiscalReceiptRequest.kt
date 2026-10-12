package ru.asop.api.fiscal.dto.request

import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Positive
import java.math.BigDecimal
import java.util.UUID

data class FiscalReceiptRequest(
    /**
     * Клиентский receiptId (UUIDv7), генерируется терминалом/дистрибьютором для офлайн-QR
     * (fiscalUrl = template(receiptId)). Если не передан — генерирует gateway (промпт 017 §5/§10).
     */
    val receiptId: UUID? = null,

    /** NULL для дистрибьюторских чеков и терминальных до резолва транзакции в fiscal-service. */
    val transactionId: UUID? = null,

    @field:NotNull
    @field:Positive
    val amount: BigDecimal,

    val description: String? = null,

    val regionId: UUID? = null,

    val carrierId: UUID? = null,

    val sessionId: UUID? = null,

    val cardsDistributorId: UUID? = null,

    /** TERMINAL | DISTRIBUTOR. */
    val source: String? = null,

    /** Ссылка эквайрера — общий ключ дедупа с авто-контуром фискализации по payment-событию. */
    val acqReference: String? = null,

    val rrn: String? = null,

    val timezone: String? = null
)
