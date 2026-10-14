package ru.asop.card.dto

import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * Строка отчёта «Z отчёт (дистрибьюторы)» — один фискальный чек дистрибьютора карт
 * (`ASOP_FISCAL_RECEIPTS.SOURCE='DISTRIBUTOR'`).
 *
 * Способ оплаты выводится из `acqReference` (как у терминала):
 * null = наличные, не null = банковская карта.
 */
data class DistributorZReportRow(
    val distributorName: String?,
    val distributorInn: String?,
    val receiptId: UUID,
    val receiptNumber: String?,
    val fiscalSign: String?,
    val createdAt: Instant,
    val amount: BigDecimal?,
    val paymentMethod: String,
    val status: String?
) {
    companion object {
        const val PAYMENT_CASH = "Наличные"
        const val PAYMENT_BANK = "Банковская карта"

        fun paymentMethod(acqReference: String?): String =
            if (acqReference.isNullOrBlank()) PAYMENT_CASH else PAYMENT_BANK
    }
}
