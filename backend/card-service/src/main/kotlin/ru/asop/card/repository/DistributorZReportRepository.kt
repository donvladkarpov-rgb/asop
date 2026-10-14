package ru.asop.card.repository

import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import ru.asop.card.dto.DistributorZReportRow
import ru.asop.card.dto.DistributorZReportTotals
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Отчёт «Z отчёт (дистрибьюторы)» — реестр фискальных чеков дистрибьюторов карт
 * (`ASOP_FISCAL_RECEIPTS.SOURCE='DISTRIBUTOR'`). Строка = один чек.
 *
 * Способ оплаты — по `acqReference` (null = наличные, не null = банковская карта).
 */
@Repository
class DistributorZReportRepository(
    private val db: DatabaseClient
) {

    data class Filter(
        val dateFrom: LocalDate,
        val dateTo: LocalDate,
        val cardsDistributorId: UUID? = null,
        /** null | "CASH" | "BANK" — способ оплаты (по acqReference). */
        val paymentMethod: String? = null,
        /** null | конкретный STATUS чека (PENDING/CONFIRMED/RETRY/…). */
        val status: String? = null
    )

    private val select = """
        SELECT
            fr.RECEIPT_ID,
            fr.RECEIPT_NUMBER,
            fr.FISCAL_SIGN,
            fr.CREATED_AT,
            fr.AMOUNT,
            fr.ACQ_REFERENCE,
            fr.STATUS,
            cd.DISTRIBUTOR_NAME,
            cd.INN AS distributor_inn
        FROM ASOP_FISCAL_RECEIPTS fr
        LEFT JOIN ASOP_CARDS_DISTRIBUTORS cd ON cd.CARDS_DISTRIBUTOR_ID = fr.CARDS_DISTRIBUTOR_ID
        WHERE fr.SOURCE = 'DISTRIBUTOR'
          AND fr.CREATED_AT >= :dateFrom::date
          AND fr.CREATED_AT <  (:dateTo::date + INTERVAL '1 day')
    """.trimIndent()

    fun count(filter: Filter): Mono<Long> {
        val sql = """
            SELECT COUNT(*) AS cnt
            FROM ASOP_FISCAL_RECEIPTS fr
            WHERE fr.SOURCE = 'DISTRIBUTOR'
              AND fr.CREATED_AT >= :dateFrom::date
              AND fr.CREATED_AT <  (:dateTo::date + INTERVAL '1 day')
              ${conditions(filter)}
        """.trimIndent()
        return bound(db.sql(sql), filter).map { (it["cnt"] as Number).toLong() }.one()
    }

    fun findRows(filter: Filter, limit: Int, offset: Int): Flux<DistributorZReportRow> {
        val sql = select + """
            ${conditions(filter)}
            ORDER BY fr.CREATED_AT ASC
            LIMIT :limit OFFSET :offset
        """.trimIndent()
        return bound(db.sql(sql), filter)
            .bind("limit", limit)
            .bind("offset", offset)
            .map { row ->
                DistributorZReportRow(
                    distributorName = asString(row["distributor_name"]),
                    distributorInn = asString(row["distributor_inn"]),
                    receiptId = asUuidRequired(row["receipt_id"], "receipt_id"),
                    receiptNumber = asString(row["receipt_number"]),
                    fiscalSign = asString(row["fiscal_sign"]),
                    createdAt = asInstant(row["created_at"])
                        ?: throw IllegalStateException("NULL in required report column created_at"),
                    amount = asBigDecimal(row["amount"]),
                    paymentMethod = DistributorZReportRow.paymentMethod(asString(row["acq_reference"])),
                    status = asString(row["status"])
                )
            }
            .all()
    }

    /** Итоги по ПОЛНОМУ отфильтрованному набору — не зависят от limit/offset. */
    fun findTotals(filter: Filter): Mono<DistributorZReportTotals> {
        val sql = """
            SELECT
                COUNT(*) AS receipts_count,
                COUNT(*) FILTER (WHERE fr.ACQ_REFERENCE IS NULL) AS cash_count,
                COALESCE(SUM(fr.AMOUNT) FILTER (WHERE fr.ACQ_REFERENCE IS NULL), 0) AS cash_sum,
                COUNT(*) FILTER (WHERE fr.ACQ_REFERENCE IS NOT NULL) AS bank_count,
                COALESCE(SUM(fr.AMOUNT) FILTER (WHERE fr.ACQ_REFERENCE IS NOT NULL), 0) AS bank_sum
            FROM ASOP_FISCAL_RECEIPTS fr
            WHERE fr.SOURCE = 'DISTRIBUTOR'
              AND fr.CREATED_AT >= :dateFrom::date
              AND fr.CREATED_AT <  (:dateTo::date + INTERVAL '1 day')
              ${conditions(filter)}
        """.trimIndent()
        return bound(db.sql(sql), filter)
            .map { row ->
                DistributorZReportTotals(
                    receiptsCount = asLong(row["receipts_count"]) ?: 0L,
                    cashCount = asLong(row["cash_count"]) ?: 0L,
                    cashSum = asBigDecimal(row["cash_sum"]) ?: BigDecimal.ZERO,
                    bankCount = asLong(row["bank_count"]) ?: 0L,
                    bankSum = asBigDecimal(row["bank_sum"]) ?: BigDecimal.ZERO
                )
            }
            .one()
    }

    private fun conditions(filter: Filter): String {
        var s = ""
        if (filter.cardsDistributorId != null) s += " AND fr.CARDS_DISTRIBUTOR_ID = :cardsDistributorId"
        when (filter.paymentMethod) {
            "CASH" -> s += " AND fr.ACQ_REFERENCE IS NULL"
            "BANK" -> s += " AND fr.ACQ_REFERENCE IS NOT NULL"
        }
        if (filter.status != null) s += " AND fr.STATUS = :status"
        return s
    }

    private fun bound(sql: DatabaseClient.GenericExecuteSpec, filter: Filter): DatabaseClient.GenericExecuteSpec {
        var s = sql.bind("dateFrom", filter.dateFrom).bind("dateTo", filter.dateTo)
        filter.cardsDistributorId?.let { s = s.bind("cardsDistributorId", it) }
        filter.status?.let { s = s.bind("status", it) }
        return s
    }

    private fun asUuid(v: Any?): UUID? = when (v) {
        null -> null
        is UUID -> v
        is String -> runCatching { UUID.fromString(v) }.getOrNull()
        else -> throw IllegalStateException("Cannot convert ${v.javaClass} to UUID")
    }

    private fun asUuidRequired(v: Any?, column: String): UUID = asUuid(v) ?: error("NULL in required report column $column")

    private fun asString(v: Any?): String? = v?.toString()

    private fun asInstant(v: Any?): Instant? = when (v) {
        null -> null
        is Instant -> v
        is java.time.OffsetDateTime -> v.toInstant()
        else -> throw IllegalStateException("Cannot convert ${v.javaClass} to Instant")
    }

    private fun asBigDecimal(v: Any?): BigDecimal? = when (v) {
        null -> null
        is BigDecimal -> v
        is Number -> BigDecimal.valueOf(v.toDouble())
        else -> throw IllegalStateException("Cannot convert ${v.javaClass} to BigDecimal")
    }

    private fun asLong(v: Any?): Long? = (v as? Number)?.toLong()
}
