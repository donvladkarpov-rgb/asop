package ru.asop.card.repository

import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import ru.asop.card.dto.ZReportRow
import ru.asop.card.dto.ZReportSegment
import ru.asop.card.dto.ZReportTotals
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Отчёт «Z отчёт» — кассовый Z-отчёт терминала за смену.
 *
 * Структура: сводка по сменам ([ZReportRow]) + детализация по маршрутам/рейсам
 * ([ZReportSegment]). Категории оплат (аналог групп «Списка смен»):
 *  - Наличные      — bucket CASH;
 *  - Банковская карта — bucket BK;
 *  - МФК (МайФер)  — bucket MIFARE (= TK/ride_like, списание поездок с карты);
 *  - Школьный      — bucket BENEFIT (= MFK/benefit_id, любая льгота с гос. возмещением).
 * Итог «Билетов» = сумма четырёх категорий.
 *
 * Период фильтрует СМЕНЫ по STARTED_AT; транзакции берутся по рейсам смены целиком.
 */
@Repository
class ZReportRepository(
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
        ride_rows AS (
            SELECT
                trs.PARENT_SESSION_ID AS shift_id,
                trs.SESSION_ID AS trip_id,
                t.TRANSACTION_ID,
                trs.PATH_ID AS trip_path_id,
                COALESCE(v.CARRIER_ID, tm.CARRIER_ID) AS trip_carrier_id,
                pc.USER_ID AS payer_user_id,
                CASE WHEN t.METADATA->>'benefitId' ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
                     THEN (t.METADATA->>'benefitId')::uuid END AS meta_benefit_id,
                COALESCE((t.METADATA->>'bankCard')::boolean, FALSE) OR bp.PAYMENT_ID IS NOT NULL AS is_bank,
                (t.METADATA->>'tripsAfter') IS NOT NULL
                    OR (t.METADATA->>'tripsBefore') IS NOT NULL
                    OR COALESCE((t.METADATA->>'tripsDebited')::numeric, 0) > 0 AS ride_like,
                bp.AMOUNT AS bank_amount,
                (t.METADATA->>'amount')::numeric AS metadata_amount,
                t.AMOUNT AS tx_amount
            FROM ASOP_TRANSACTIONS t
            JOIN ASOP_SESSIONS trs ON trs.SESSION_ID = t.SESSION_ID
            JOIN shift_ctx sc ON sc.shift_id = trs.PARENT_SESSION_ID
            LEFT JOIN ASOP_TERMINALS tm ON tm.TERMINAL_ID = trs.TERMINAL_ID
            LEFT JOIN ASOP_VEHICLES v ON v.VEHICLE_ID = COALESCE(trs.VEHICLE_ID, tm.VEHICLE_ID)
            LEFT JOIN ASOP_TRANSACTION_CARDS tc
              ON tc.TRANSACTION_ID = t.TRANSACTION_ID AND tc.CARD_ROLE = 'PAYER'
            LEFT JOIN ASOP_CARDS pc ON pc.CARD_ID = tc.CARD_ID
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
              AND t.TRANSACTION_RESULT_ID IN ('00000000-0000-0000-0000-000000000901', '00000000-0000-0000-0000-000000000903')
              AND COALESCE(t.METADATA->>'declined', 'false') NOT IN ('true', 'True', 'TRUE')
        ),
        ride_ben AS (
            SELECT r.*,
                CASE WHEN r.meta_benefit_id IS NOT NULL THEN r.meta_benefit_id ELSE ub.BENEFIT_ID END AS benefit_id
            FROM ride_rows r
            LEFT JOIN LATERAL (
                SELECT u.BENEFIT_ID
                FROM ASOP_USER_BENEFITS u
                WHERE u.USER_ID = r.payer_user_id
                  AND u.DELETED_AT IS NULL
                  AND u.VALID_FROM <= NOW()
                  AND (u.VALID_UNTIL IS NULL OR u.VALID_UNTIL >= NOW())
                ORDER BY u.VALID_FROM DESC
                LIMIT 1
            ) ub ON TRUE
        ),
        user_trips AS (
            SELECT r.payer_user_id, r.benefit_id, COUNT(*) AS trips
            FROM ride_ben r
            WHERE r.payer_user_id IS NOT NULL AND r.benefit_id IS NOT NULL
            GROUP BY r.payer_user_id, r.benefit_id
        ),
        ride_money AS (
            SELECT
                r.shift_id,
                r.trip_id,
                CASE
                    WHEN r.is_bank THEN 'BK'
                    WHEN r.benefit_id IS NOT NULL THEN 'BENEFIT'
                    WHEN r.ride_like THEN 'MIFARE'
                    ELSE 'CASH'
                END AS bucket,
                CASE
                    WHEN r.is_bank THEN COALESCE(r.bank_amount, r.metadata_amount, r.tx_amount, 0)
                    WHEN r.benefit_id IS NOT NULL THEN COALESCE(f.price * step.discount_share, 0)
                    WHEN r.ride_like THEN COALESCE(f.price, 0)
                    ELSE COALESCE(r.metadata_amount, r.tx_amount, 0)
                END AS amount
            FROM ride_ben r
            LEFT JOIN user_trips ut
              ON ut.payer_user_id = r.payer_user_id
             AND ut.benefit_id = r.benefit_id
            LEFT JOIN LATERAL (
                SELECT s.DISCOUNT_SHARE
                FROM ASOP_BENEFIT_STEPS s
                WHERE s.BENEFIT_ID = r.benefit_id
                  AND s.DELETED_AT IS NULL
                  AND s.TRIP_THRESHOLD_FROM <= COALESCE(ut.trips, 0)
                  AND (s.TRIP_THRESHOLD_TO IS NULL OR s.TRIP_THRESHOLD_TO > COALESCE(ut.trips, 0))
                ORDER BY s.TRIP_THRESHOLD_FROM DESC
                LIMIT 1
            ) step ON TRUE
            LEFT JOIN LATERAL (
                SELECT r2.PRICE
                FROM ASOP_TARIFF_RATES r2
                WHERE r2.IS_ACTIVE AND r2.DELETED_AT IS NULL
                ORDER BY
                    CASE
                        WHEN r2.PATH_ID = r.trip_path_id AND r.trip_path_id IS NOT NULL THEN 0
                        WHEN r2.CARRIER_ID = r.trip_carrier_id AND r.trip_carrier_id IS NOT NULL THEN 1
                        ELSE 2
                    END,
                    r2.UPDATED_AT DESC
                LIMIT 1
            ) f ON TRUE
        ),
        shift_agg AS (
            SELECT
                rm.shift_id,
                COUNT(*) FILTER (WHERE rm.bucket = 'CASH') AS cash_count,
                COALESCE(SUM(rm.amount) FILTER (WHERE rm.bucket = 'CASH'), 0) AS cash_sum,
                COUNT(*) FILTER (WHERE rm.bucket = 'BK') AS bank_count,
                COALESCE(SUM(rm.amount) FILTER (WHERE rm.bucket = 'BK'), 0) AS bank_sum,
                COUNT(*) FILTER (WHERE rm.bucket = 'MIFARE') AS mifare_count,
                COALESCE(SUM(rm.amount) FILTER (WHERE rm.bucket = 'MIFARE'), 0) AS mifare_sum,
                COUNT(*) FILTER (WHERE rm.bucket = 'BENEFIT') AS benefit_count,
                COALESCE(SUM(rm.amount) FILTER (WHERE rm.bucket = 'BENEFIT'), 0) AS benefit_sum
            FROM ride_money rm
            GROUP BY rm.shift_id
        ),
        trip_agg AS (
            SELECT
                rm.trip_id,
                COUNT(*) FILTER (WHERE rm.bucket = 'CASH') AS cash_count,
                COALESCE(SUM(rm.amount) FILTER (WHERE rm.bucket = 'CASH'), 0) AS cash_sum,
                COUNT(*) FILTER (WHERE rm.bucket = 'BK') AS bank_count,
                COALESCE(SUM(rm.amount) FILTER (WHERE rm.bucket = 'BK'), 0) AS bank_sum,
                COUNT(*) FILTER (WHERE rm.bucket = 'MIFARE') AS mifare_count,
                COALESCE(SUM(rm.amount) FILTER (WHERE rm.bucket = 'MIFARE'), 0) AS mifare_sum,
                COUNT(*) FILTER (WHERE rm.bucket = 'BENEFIT') AS benefit_count,
                COALESCE(SUM(rm.amount) FILTER (WHERE rm.bucket = 'BENEFIT'), 0) AS benefit_sum
            FROM ride_money rm
            GROUP BY rm.trip_id
        ),
        base AS (
            SELECT
                sc.shift_id,
                sc.shift_started_at,
                sc.shift_closed_at,
                sc.driver_user_id,
                drv.LAST_NAME AS driver_last_name,
                drv.FIRST_NAME AS driver_first_name,
                drv.LAST_NAME_INITIAL AS driver_last_name_initial,
                drv.PATRONYMIC_INITIAL AS driver_patronymic_initial,
                tm.TERMINAL_ID,
                tm.TERMINAL_SERIAL,
                v.VEHICLE_ID,
                v.VEHICLE_NUMBER,
                pth.PATH_ID,
                rt.ROUTE_ID,
                rt.ROUTE_NUMBER,
                rt.ROUTE_NAME,
                o.ORGANIZER_ID,
                o.ORGANIZER_NAME,
                cr.CARRIER_ID,
                cr.CARRIER_NAME,
                cr.INN AS carrier_inn,
                cr.REGION_ID,
                COALESCE(a.cash_count, 0) AS cash_count,
                COALESCE(a.cash_sum, 0) AS cash_sum,
                COALESCE(a.bank_count, 0) AS bank_count,
                COALESCE(a.bank_sum, 0) AS bank_sum,
                COALESCE(a.mifare_count, 0) AS mifare_count,
                COALESCE(a.mifare_sum, 0) AS mifare_sum,
                COALESCE(a.benefit_count, 0) AS benefit_count,
                COALESCE(a.benefit_sum, 0) AS benefit_sum
            FROM shift_ctx sc
            LEFT JOIN shift_agg a ON a.shift_id = sc.shift_id
            LEFT JOIN ASOP_TERMINALS tm ON tm.TERMINAL_ID = sc.TERMINAL_ID
            LEFT JOIN ASOP_USERS drv ON drv.USER_ID = sc.driver_user_id
            LEFT JOIN ASOP_VEHICLES v
              ON v.VEHICLE_ID = COALESCE(sc.trip_vehicle_id, sc.shift_vehicle_id, tm.VEHICLE_ID)
            LEFT JOIN ASOP_PATHS pth ON pth.PATH_ID = sc.path_id
            LEFT JOIN ASOP_ROUTES rt ON rt.ROUTE_ID = pth.ROUTE_ID
            LEFT JOIN ASOP_ORGANIZERS o ON o.ORGANIZER_ID = rt.ORGANIZER_ID
            LEFT JOIN ASOP_CARRIERS cr ON cr.CARRIER_ID = COALESCE(v.CARRIER_ID, tm.CARRIER_ID)
        ),
        segment AS (
            SELECT
                ta.trip_id,
                tr.PARENT_SESSION_ID AS shift_id,
                tr.STARTED_AT AS segment_started_at,
                tr.CLOSED_AT AS segment_ended_at,
                sc.driver_user_id,
                tm.TERMINAL_ID,
                v.VEHICLE_ID,
                pth.PATH_ID,
                rt.ROUTE_ID,
                rt.ROUTE_NUMBER,
                rt.ROUTE_NAME,
                o.ORGANIZER_ID,
                cr.CARRIER_ID,
                cr.REGION_ID,
                ta.cash_count,
                ta.cash_sum,
                ta.bank_count,
                ta.bank_sum,
                ta.mifare_count,
                ta.mifare_sum,
                ta.benefit_count,
                ta.benefit_sum
            FROM trip_agg ta
            JOIN ASOP_SESSIONS tr ON tr.SESSION_ID = ta.trip_id
            JOIN shift_ctx sc ON sc.shift_id = tr.PARENT_SESSION_ID
            LEFT JOIN ASOP_TERMINALS tm ON tm.TERMINAL_ID = COALESCE(tr.TERMINAL_ID, sc.TERMINAL_ID)
            LEFT JOIN ASOP_VEHICLES v ON v.VEHICLE_ID = COALESCE(tr.VEHICLE_ID, tm.VEHICLE_ID)
            LEFT JOIN ASOP_PATHS pth ON pth.PATH_ID = tr.PATH_ID
            LEFT JOIN ASOP_ROUTES rt ON rt.ROUTE_ID = pth.ROUTE_ID
            LEFT JOIN ASOP_ORGANIZERS o ON o.ORGANIZER_ID = rt.ORGANIZER_ID
            LEFT JOIN ASOP_CARRIERS cr ON cr.CARRIER_ID = COALESCE(v.CARRIER_ID, tm.CARRIER_ID)
        )
    """.trimIndent()

    fun count(filter: Filter): Mono<Long> {
        val sql = cte + "SELECT COUNT(*) AS cnt FROM base b ${conditions(filter, "b")}"
        return bound(db.sql(sql), filter).map { (it["cnt"] as Number).toLong() }.one()
    }

    fun findRows(filter: Filter, limit: Int, offset: Int): Flux<ZReportRow> {
        val sql = cte + """
            SELECT * FROM base b
            ${conditions(filter, "b")}
            ORDER BY
                b.ORGANIZER_NAME ASC NULLS LAST,
                b.CARRIER_NAME ASC NULLS LAST,
                b.shift_started_at ASC NULLS LAST
            LIMIT :limit OFFSET :offset
        """.trimIndent()
        return bound(db.sql(sql), filter)
            .bind("limit", limit)
            .bind("offset", offset)
            .map { row ->
                val startedAt = asInstant(row["shift_started_at"])
                val closedAt = asInstant(row["shift_closed_at"])
                ZReportRow(
                    carrierName = asString(row["carrier_name"]),
                    carrierInn = asString(row["carrier_inn"]),
                    shiftId = asUuidRequired(row["shift_id"], "shift_id"),
                    driverLastName = asString(row["driver_last_name"]),
                    driverFirstName = asString(row["driver_first_name"]),
                    driverLastNameInitial = asString(row["driver_last_name_initial"]),
                    driverPatronymicInitial = asString(row["driver_patronymic_initial"]),
                    shiftStartedAt = startedAt,
                    shiftClosedAt = closedAt,
                    durationText = ZReportRow.durationText(startedAt, closedAt),
                    terminalSerial = asString(row["terminal_serial"]),
                    vehicleNumber = asString(row["vehicle_number"]),
                    routeNumber = asString(row["route_number"]),
                    routeName = asString(row["route_name"]),
                    organizerName = asString(row["organizer_name"]),
                    cashCount = asLong(row["cash_count"]) ?: 0L,
                    cashSum = asBigDecimal(row["cash_sum"]) ?: BigDecimal.ZERO,
                    bankCount = asLong(row["bank_count"]) ?: 0L,
                    bankSum = asBigDecimal(row["bank_sum"]) ?: BigDecimal.ZERO,
                    mifareCount = asLong(row["mifare_count"]) ?: 0L,
                    mifareSum = asBigDecimal(row["mifare_sum"]) ?: BigDecimal.ZERO,
                    benefitCount = asLong(row["benefit_count"]) ?: 0L,
                    benefitSum = asBigDecimal(row["benefit_sum"]) ?: BigDecimal.ZERO
                )
            }
            .all()
    }

    /** Детализация по маршрутам/рейсам — для смен из отфильтрованного набора. */
    fun findSegments(filter: Filter): Flux<ZReportSegment> {
        val sql = cte + """
            SELECT * FROM segment s
            ${conditions(filter, "s")}
            ORDER BY
                s.ORGANIZER_ID ASC NULLS LAST,
                s.CARRIER_ID ASC NULLS LAST,
                s.segment_started_at ASC NULLS LAST
        """.trimIndent()
        return bound(db.sql(sql), filter)
            .map { row ->
                ZReportSegment(
                    shiftId = asUuidRequired(row["shift_id"], "shift_id"),
                    routeNumber = asString(row["route_number"]),
                    routeName = asString(row["route_name"]),
                    segmentStartedAt = asInstant(row["segment_started_at"]),
                    segmentEndedAt = asInstant(row["segment_ended_at"]),
                    cashCount = asLong(row["cash_count"]) ?: 0L,
                    cashSum = asBigDecimal(row["cash_sum"]) ?: BigDecimal.ZERO,
                    bankCount = asLong(row["bank_count"]) ?: 0L,
                    bankSum = asBigDecimal(row["bank_sum"]) ?: BigDecimal.ZERO,
                    mifareCount = asLong(row["mifare_count"]) ?: 0L,
                    mifareSum = asBigDecimal(row["mifare_sum"]) ?: BigDecimal.ZERO,
                    benefitCount = asLong(row["benefit_count"]) ?: 0L,
                    benefitSum = asBigDecimal(row["benefit_sum"]) ?: BigDecimal.ZERO
                )
            }
            .all()
    }

    /** Итоги по ПОЛНОМУ отфильтрованному набору — не зависят от limit/offset. */
    fun findTotals(filter: Filter): Mono<ZReportTotals> {
        val sql = cte + """
            SELECT
                COUNT(*) AS shifts_count,
                COALESCE(SUM(b.cash_count), 0) AS cash_count,
                COALESCE(SUM(b.cash_sum), 0) AS cash_sum,
                COALESCE(SUM(b.bank_count), 0) AS bank_count,
                COALESCE(SUM(b.bank_sum), 0) AS bank_sum,
                COALESCE(SUM(b.mifare_count), 0) AS mifare_count,
                COALESCE(SUM(b.mifare_sum), 0) AS mifare_sum,
                COALESCE(SUM(b.benefit_count), 0) AS benefit_count,
                COALESCE(SUM(b.benefit_sum), 0) AS benefit_sum
            FROM base b
            ${conditions(filter, "b")}
        """.trimIndent()
        return bound(db.sql(sql), filter)
            .map { row ->
                ZReportTotals(
                    shiftsCount = asLong(row["shifts_count"]) ?: 0L,
                    cashCount = asLong(row["cash_count"]) ?: 0L,
                    cashSum = asBigDecimal(row["cash_sum"]) ?: BigDecimal.ZERO,
                    bankCount = asLong(row["bank_count"]) ?: 0L,
                    bankSum = asBigDecimal(row["bank_sum"]) ?: BigDecimal.ZERO,
                    mifareCount = asLong(row["mifare_count"]) ?: 0L,
                    mifareSum = asBigDecimal(row["mifare_sum"]) ?: BigDecimal.ZERO,
                    benefitCount = asLong(row["benefit_count"]) ?: 0L,
                    benefitSum = asBigDecimal(row["benefit_sum"]) ?: BigDecimal.ZERO
                )
            }
            .one()
    }

    private fun conditions(filter: Filter, alias: String): String {
        var s = ""
        if (filter.regionId != null) s += " AND $alias.REGION_ID = :regionId"
        if (filter.organizerId != null) s += " AND $alias.ORGANIZER_ID = :organizerId"
        if (filter.carrierId != null) s += " AND $alias.CARRIER_ID = :carrierId"
        if (filter.routeId != null) s += " AND $alias.ROUTE_ID = :routeId"
        if (filter.pathId != null) s += " AND $alias.PATH_ID = :pathId"
        if (filter.vehicleId != null) s += " AND $alias.VEHICLE_ID = :vehicleId"
        if (filter.terminalId != null) s += " AND $alias.TERMINAL_ID = :terminalId"
        if (filter.driverId != null) s += " AND $alias.driver_user_id = :driverId"
        return if (s.isEmpty()) "" else "WHERE TRUE$s"
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
