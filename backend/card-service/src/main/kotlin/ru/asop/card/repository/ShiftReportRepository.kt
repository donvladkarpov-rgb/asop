package ru.asop.card.repository

import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import ru.asop.card.dto.ShiftReportRow
import ru.asop.card.dto.ShiftReportTotal
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@Repository
class ShiftReportRepository(
    private val db: DatabaseClient
) {

    data class Filter(
        val dateFrom: LocalDate,
        val dateTo: LocalDate,
        val regionId: UUID? = null,
        val organizerId: UUID? = null,
        val carrierId: UUID? = null,
        val routeId: UUID? = null,
        val pathId: UUID? = null,
        val vehicleId: UUID? = null,
        val terminalId: UUID? = null,
        val driverId: UUID? = null
    )

    private val cte = """
        WITH shift_ctx AS (
            SELECT
                sh.SESSION_ID AS shift_id,
                sh.STARTED_AT AS shift_started_at,
                sh.CLOSED_AT AS shift_closed_at,
                sh.OPENED_BY_USER_ID AS driver_user_id,
                sh.TERMINAL_ID,
                COALESCE(sh.PATH_ID, tr.PATH_ID) AS path_id,
                sh.VEHICLE_ID AS shift_vehicle_id,
                tr.VEHICLE_ID AS trip_vehicle_id
            FROM ASOP_SESSIONS sh
            LEFT JOIN LATERAL (
                SELECT t.SESSION_ID, t.PATH_ID, t.VEHICLE_ID
                FROM ASOP_SESSIONS t
                WHERE t.PARENT_SESSION_ID = sh.SESSION_ID
                ORDER BY t.STARTED_AT ASC
                LIMIT 1
            ) tr ON TRUE
            WHERE sh.SESSION_TYPE_ID = '00000000-0000-0000-0000-000000000601'
              AND sh.STARTED_AT >= :dateFrom::date
              AND sh.STARTED_AT <  (:dateTo::date + INTERVAL '1 day')
        ),
        tx AS (
            SELECT
                tr.PARENT_SESSION_ID AS shift_id,
                COUNT(*) AS transactions_count,
                COUNT(*) FILTER (WHERE t.TRANSACTION_RESULT_ID = '00000000-0000-0000-0000-000000000901') AS successful_card_transactions,
                COUNT(*) FILTER (
                    WHERE t.TRANSACTION_RESULT_ID = '00000000-0000-0000-0000-000000000902'
                       OR t.TRANSACTION_RESULT_ID = '00000000-0000-0000-0000-000000000904'
                       OR t.TRANSACTION_RESULT_ID = '00000000-0000-0000-0000-000000000905'
                ) AS failed_card_transactions,
                COUNT(*) FILTER (WHERE bp.PAYMENT_ID IS NOT NULL) AS cashless_count,
                COALESCE(SUM(bp.AMOUNT) FILTER (WHERE bp.PAYMENT_ID IS NOT NULL), 0) AS cashless_amount,
                -- Сумма без скидки: используем appliedTariff/regTariff из метаданных? В отчёте это SUM bp.amount?
                -- В исходном XLS: "Сумма безнал (без скидки)" — 15785.0 для 451 безнал. Иногда бывает > безнал.
                -- Попробуем взять MAX(p.AMOUNT_WITHOUT_DISCOUNT) или сумму? Либо NULL не трогаем — часто равна full fare.
                COALESCE(SUM(NULL::numeric) FILTER (WHERE bp.PAYMENT_ID IS NOT NULL), 0) AS cashless_amount_without_discount,
                COUNT(*) FILTER (WHERE bp.PAYMENT_ID IS NULL AND t.TRANSACTION_RESULT_ID = '00000000-0000-0000-0000-000000000901' AND COALESCE((t.METADATA->>'tripsDebited')::numeric, 0) = 0 AND COALESCE((t.METADATA->>'tripsAfter')::numeric, 0) = 0) AS cash_count,
                COALESCE(SUM(COALESCE((t.METADATA->>'amount')::numeric, t.AMOUNT, 0)) FILTER (WHERE bp.PAYMENT_ID IS NULL AND t.TRANSACTION_RESULT_ID = '00000000-0000-0000-0000-000000000901' AND COALESCE((t.METADATA->>'tripsDebited')::numeric, 0) = 0 AND COALESCE((t.METADATA->>'tripsAfter')::numeric, 0) = 0), 0) AS cash_amount
            FROM ASOP_TRANSACTIONS t
            JOIN ASOP_SESSIONS tr ON tr.SESSION_ID = t.SESSION_ID
            -- Связка платежа: TRANSACTION_ID (backfill/resolve в payment-service), fallback по
            -- acqReference для непривязанных (отчёт пришёл раньше транзакции) — только на первую
            -- транзакцию с этим acq, чтобы дубликаты не задвоили безнал.
            LEFT JOIN LATERAL (
                SELECT p.PAYMENT_ID, p.AMOUNT
                FROM ASOP_BANK_PAYMENTS p
                WHERE p.PAYMENT_TYPE = 'FARE' AND p.DELETED_AT IS NULL
                  AND (p.TRANSACTION_ID = t.TRANSACTION_ID
                       OR (p.TRANSACTION_ID IS NULL
                           AND t.METADATA->>'acqReference' IS NOT NULL
                           AND p.ACQUIRER_REFERENCE = t.METADATA->>'acqReference'
                           AND NOT EXISTS (
                               SELECT 1 FROM ASOP_TRANSACTIONS t2
                               WHERE t2.METADATA->>'acqReference' = t.METADATA->>'acqReference'
                                 AND t2.CREATED_AT < t.CREATED_AT
                           )))
                ORDER BY CASE WHEN p.TRANSACTION_ID = t.TRANSACTION_ID THEN 0 ELSE 1 END,
                         p.CREATED_AT DESC
                LIMIT 1
            ) bp ON TRUE
            WHERE t.TRANSACTION_TYPE_ID IN ('00000000-0000-0000-0000-000000000801', '00000000-0000-0000-0000-000000000803')
              AND t.CREATED_AT >= :dateFrom::date
              AND t.CREATED_AT <  (:dateTo::date + INTERVAL '1 day')
              AND tr.PARENT_SESSION_ID IS NOT NULL
            GROUP BY tr.PARENT_SESSION_ID
        ),
        base AS (
            SELECT
                sc.shift_id,
                sc.shift_started_at,
                sc.shift_closed_at,
                tm.TERMINAL_ID,
                tm.TERMINAL_NUMBER,
                tm.TERMINAL_SERIAL,
                drv.USER_ID AS driver_user_id,
                drv.LAST_NAME AS driver_last_name,
                drv.FIRST_NAME AS driver_first_name,
                v.VEHICLE_ID,
                v.VEHICLE_NUMBER,
                v.VEHICLE_NAME,
                vt.TYPE_NAME AS vehicle_type_name,
                vm.MODEL_NAME AS vehicle_model_name,
                pth.PATH_ID,
                rt.ROUTE_ID,
                rt.ROUTE_NUMBER,
                rt.ROUTE_NAME,
                o.ORGANIZER_ID,
                o.ORGANIZER_NAME,
                cr.CARRIER_ID,
                cr.CARRIER_NAME,
                cr.REGION_ID,
                COALESCE(tx.transactions_count, 0) AS transactions_count,
                COALESCE(tx.successful_card_transactions, 0) AS successful_card_transactions,
                COALESCE(tx.failed_card_transactions, 0) AS failed_card_transactions,
                COALESCE(tx.cashless_count, 0) AS cashless_count,
                COALESCE(tx.cashless_amount, 0) AS cashless_amount,
                COALESCE(tx.cashless_amount_without_discount, 0) AS cashless_amount_without_discount,
                COALESCE(tx.cash_count, 0) AS cash_count,
                COALESCE(tx.cash_amount, 0) AS cash_amount
            FROM shift_ctx sc
            LEFT JOIN tx ON tx.shift_id = sc.shift_id
            LEFT JOIN ASOP_TERMINALS tm ON tm.TERMINAL_ID = sc.TERMINAL_ID
            LEFT JOIN ASOP_USERS drv ON drv.USER_ID = sc.driver_user_id
            LEFT JOIN ASOP_VEHICLES v ON v.VEHICLE_ID = COALESCE(sc.trip_vehicle_id, sc.shift_vehicle_id, tm.VEHICLE_ID)
            LEFT JOIN ASOP_VEHICLE_TYPES vt ON vt.VEHICLE_TYPE_ID = v.VEHICLE_TYPE_ID
            LEFT JOIN ASOP_VEHICLE_MODELS vm ON vm.VEHICLE_MODEL_ID = v.VEHICLE_MODEL_ID
            LEFT JOIN ASOP_PATHS pth ON pth.PATH_ID = sc.path_id
            LEFT JOIN ASOP_ROUTES rt ON rt.ROUTE_ID = pth.ROUTE_ID
            LEFT JOIN ASOP_ORGANIZERS o ON o.ORGANIZER_ID = rt.ORGANIZER_ID
            LEFT JOIN ASOP_CARRIERS cr ON cr.CARRIER_ID = COALESCE(v.CARRIER_ID, tm.CARRIER_ID)
        )
    """.trimIndent()

    fun count(filter: Filter): Mono<Long> {
        val sql = cte + "SELECT COUNT(*) AS cnt FROM base b ${conditions(filter)}"
        return bound(db.sql(sql), filter).map { (it["cnt"] as Number).toLong() }.one()
    }

    fun findRows(filter: Filter, limit: Int, offset: Int): Flux<ShiftReportRow> {
        val sql = cte + """
            SELECT * FROM base b
            ${conditions(filter)}
            ORDER BY
                b.ORGANIZER_NAME ASC NULLS LAST,
                b.CARRIER_NAME ASC NULLS LAST,
                b.ROUTE_NUMBER ASC NULLS LAST,
                b.shift_started_at ASC NULLS LAST
            LIMIT :limit OFFSET :offset
        """.trimIndent()
        return bound(db.sql(sql), filter)
            .bind("limit", limit)
            .bind("offset", offset)
            .map { row ->
                val transactionsCount = asLong(row["transactions_count"]) ?: 0L
                val failed = asLong(row["failed_card_transactions"]) ?: 0L
                val cashlessCount = asLong(row["cashless_count"]) ?: 0L
                val failedShare = if (transactionsCount == 0L) BigDecimal.ZERO else BigDecimal.valueOf(failed)
                    .multiply(BigDecimal.valueOf(100))
                    .divide(BigDecimal.valueOf(transactionsCount), 2, RoundingMode.HALF_UP)
                val cashlessShare = if (transactionsCount == 0L) BigDecimal.ZERO else BigDecimal.valueOf(cashlessCount)
                    .multiply(BigDecimal.valueOf(100))
                    .divide(BigDecimal.valueOf(transactionsCount), 2, RoundingMode.HALF_UP)
                ShiftReportRow(
                    organizerId = asUuid(row["organizer_id"]),
                    organizerName = asString(row["organizer_name"]),
                    carrierId = asUuid(row["carrier_id"]),
                    carrierName = asString(row["carrier_name"]),
                    routeId = asUuid(row["route_id"]),
                    routeNumber = asString(row["route_number"]),
                    routeName = asString(row["route_name"]),
                    shiftId = asUuidRequired(row["shift_id"], "shift_id"),
                    vehicleTypeName = asString(row["vehicle_type_name"]),
                    vehicleModelName = asString(row["vehicle_model_name"]),
                    vehicleNumber = asString(row["vehicle_number"]),
                    vehicleName = asString(row["vehicle_name"]),
                    terminalSerial = asString(row["terminal_serial"]),
                    terminalNumber = asString(row["terminal_number"]),
                    shiftStartedAt = asInstant(row["shift_started_at"]),
                    shiftClosedAt = asInstant(row["shift_closed_at"]),
                    transactionsCount = transactionsCount,
                    successfulCardTransactions = asLong(row["successful_card_transactions"]) ?: 0L,
                    failedCardTransactions = failed,
                    failedSharePct = failedShare,
                    cashlessAmount = asBigDecimal(row["cashless_amount"]) ?: BigDecimal.ZERO,
                    cashlessAmountWithoutDiscount = asBigDecimal(row["cashless_amount_without_discount"]) ?: BigDecimal.ZERO,
                    cashlessCount = cashlessCount,
                    cashlessSharePct = cashlessShare,
                    cashAmount = asBigDecimal(row["cash_amount"]) ?: BigDecimal.ZERO,
                    cashCount = asLong(row["cash_count"]) ?: 0L
                )
            }
            .all()
    }

    fun findTotals(filter: Filter): Flux<ShiftReportTotal> {
        val sql = cte + """
            SELECT
                GROUPING(b.ORGANIZER_ID)::int AS g1, b.ORGANIZER_ID, b.ORGANIZER_NAME,
                GROUPING(b.CARRIER_ID)::int    AS g2, b.CARRIER_ID, b.CARRIER_NAME,
                GROUPING(b.ROUTE_ID)::int      AS g3, b.ROUTE_ID, b.ROUTE_NUMBER, b.ROUTE_NAME,
                GROUPING(b.shift_id)::int      AS g4, b.shift_id,
                COUNT(*) AS shifts_count,
                COALESCE(SUM(b.transactions_count), 0) AS transactions_count,
                COALESCE(SUM(b.cashless_amount), 0) AS cashless_amount,
                COALESCE(SUM(b.cash_amount), 0) AS cash_amount
            FROM base b
            ${conditions(filter)}
            GROUP BY GROUPING SETS (
                (b.ORGANIZER_ID, b.ORGANIZER_NAME),
                (b.ORGANIZER_ID, b.ORGANIZER_NAME, b.CARRIER_ID, b.CARRIER_NAME),
                (b.ORGANIZER_ID, b.ORGANIZER_NAME, b.CARRIER_ID, b.CARRIER_NAME, b.ROUTE_ID, b.ROUTE_NUMBER, b.ROUTE_NAME),
                (b.ORGANIZER_ID, b.ORGANIZER_NAME, b.CARRIER_ID, b.CARRIER_NAME, b.ROUTE_ID, b.ROUTE_NUMBER, b.ROUTE_NAME, b.shift_id)
            )
        """.trimIndent()
        return bound(db.sql(sql), filter)
            .map { row ->
                val collapsed = (1..4).count { asInt(row["g$it"]) == 1 }
                val level = 4 - collapsed
                ShiftReportTotal(
                    level = level,
                    levelName = ShiftReportTotal.LEVEL_NAMES[level] ?: "Итого",
                    organizerId = asUuid(row["organizer_id"]),
                    organizerName = asString(row["organizer_name"]),
                    carrierId = asUuid(row["carrier_id"]),
                    carrierName = asString(row["carrier_name"]),
                    routeId = asUuid(row["route_id"]),
                    routeLabel = routeLabel(asString(row["route_number"]), asString(row["route_name"])),
                    shiftId = asUuid(row["shift_id"]),
                    shiftsCount = (row["shifts_count"] as Number).toLong(),
                    transactionsCount = asLong(row["transactions_count"]) ?: 0L,
                    cashlessAmount = asBigDecimal(row["cashless_amount"]) ?: BigDecimal.ZERO,
                    cashAmount = asBigDecimal(row["cash_amount"]) ?: BigDecimal.ZERO
                )
            }
            .all()
    }

    private fun conditions(filter: Filter): String {
        var s = ""
        if (filter.regionId != null) s += " AND b.REGION_ID = :regionId"
        if (filter.organizerId != null) s += " AND b.ORGANIZER_ID = :organizerId"
        if (filter.carrierId != null) s += " AND b.CARRIER_ID = :carrierId"
        if (filter.routeId != null) s += " AND b.ROUTE_ID = :routeId"
        if (filter.pathId != null) s += " AND b.PATH_ID = :pathId"
        if (filter.vehicleId != null) s += " AND b.VEHICLE_ID = :vehicleId"
        if (filter.terminalId != null) s += " AND b.TERMINAL_ID = :terminalId"
        if (filter.driverId != null) s += " AND b.driver_user_id = :driverId"
        return if (s.isEmpty()) "" else s
    }

    private fun bound(sql: DatabaseClient.GenericExecuteSpec, filter: Filter): DatabaseClient.GenericExecuteSpec {
        var s = sql
        filter.regionId?.let { s = s.bind("regionId", it) }
        filter.organizerId?.let { s = s.bind("organizerId", it) }
        filter.carrierId?.let { s = s.bind("carrierId", it) }
        filter.routeId?.let { s = s.bind("routeId", it) }
        filter.pathId?.let { s = s.bind("pathId", it) }
        filter.vehicleId?.let { s = s.bind("vehicleId", it) }
        filter.terminalId?.let { s = s.bind("terminalId", it) }
        filter.driverId?.let { s = s.bind("driverId", it) }
        return s.bind("dateFrom", filter.dateFrom).bind("dateTo", filter.dateTo)
    }

    private fun routeLabel(number: String?, name: String?): String? = when {
        name.isNullOrBlank() -> number
        number.isNullOrBlank() -> name
        name.contains(number) -> name
        else -> listOfNotNull(number, name).joinToString(" ").takeIf { it.isNotBlank() }
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

    private fun asInt(v: Any?): Int? = (v as? Number)?.toInt()
}
