package ru.asop.card.repository

import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import ru.asop.card.dto.ShiftListRow
import ru.asop.card.dto.ShiftListTotals
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Отчёт «Список смен»: строка на смену с разбивкой оплат на четыре группы
 * (БК/МФК/ТК/Нал) — см. [ShiftListRow].
 *
 * Период фильтрует СМЕНЫ по STARTED_AT; транзакции берутся по рейсам смены
 * целиком (не обрезаются по времени транзакции), т.к. смена может переходить
 * через полночь.
 */
@Repository
class ShiftListReportRepository(
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
                tr.VEHICLE_ID AS trip_vehicle_id,
                -- Начало/конец маршрута = первый/последний рейс смены, fallback — время смены
                COALESCE(ft.first_trip_start, sh.STARTED_AT) AS route_started_at,
                COALESCE(ft.last_trip_end, sh.CLOSED_AT) AS route_ended_at
            FROM ASOP_SESSIONS sh
            LEFT JOIN LATERAL (
                SELECT t.SESSION_ID, t.PATH_ID, t.VEHICLE_ID
                FROM ASOP_SESSIONS t
                WHERE t.PARENT_SESSION_ID = sh.SESSION_ID
                ORDER BY t.STARTED_AT ASC
                LIMIT 1
            ) tr ON TRUE
            LEFT JOIN LATERAL (
                SELECT MIN(t.STARTED_AT) AS first_trip_start, MAX(t.CLOSED_AT) AS last_trip_end
                FROM ASOP_SESSIONS t
                WHERE t.PARENT_SESSION_ID = sh.SESSION_ID
            ) ft ON TRUE
            WHERE sh.SESSION_TYPE_ID = '00000000-0000-0000-0000-000000000601'
              AND sh.STARTED_AT >= :dateFrom::date
              AND sh.STARTED_AT <  (:dateTo::date + INTERVAL '1 day')
        ),
        ride_rows AS (
            SELECT
                trs.PARENT_SESSION_ID AS shift_id,
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
            -- Связка платежа: TRANSACTION_ID (backfill/resolve в payment-service), fallback по
            -- acqReference для непривязанных — только на первую транзакцию с этим acq.
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
        -- Льгота: явный признак терминала, иначе назначение льготы пользователю
        -- (то же правило, что в отчёте «Сводный, льготники»).
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
        -- Число льготных поездок пользователя за период — ключ выбора ступени скидки
        user_trips AS (
            SELECT r.payer_user_id, r.benefit_id, COUNT(*) AS trips
            FROM ride_ben r
            WHERE r.payer_user_id IS NOT NULL AND r.benefit_id IS NOT NULL
            GROUP BY r.payer_user_id, r.benefit_id
        ),
        ride_money AS (
            SELECT
                r.shift_id,
                CASE
                    WHEN r.is_bank THEN 'BK'
                    WHEN r.benefit_id IS NOT NULL THEN 'MFK'
                    WHEN r.ride_like THEN 'TK'
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
            -- Ступень льготы (пороги не пересекаются — достаточно TRIP_THRESHOLD_FROM <= count)
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
            -- Тариф проезда: PATH_ID рейса → CARRIER_ID перевозчика → любой активный
            -- (MVP-fallback как в BenefitReportRepository, см. там TODO(prod)).
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
        agg AS (
            SELECT
                rm.shift_id,
                COUNT(*) FILTER (WHERE rm.bucket = 'BK') AS bk_count,
                COALESCE(SUM(rm.amount) FILTER (WHERE rm.bucket = 'BK'), 0) AS bk_sum,
                COUNT(*) FILTER (WHERE rm.bucket = 'MFK') AS mfk_count,
                COALESCE(SUM(rm.amount) FILTER (WHERE rm.bucket = 'MFK'), 0) AS mfk_sum,
                COUNT(*) FILTER (WHERE rm.bucket = 'TK') AS tk_count,
                COALESCE(SUM(rm.amount) FILTER (WHERE rm.bucket = 'TK'), 0) AS tk_sum,
                COUNT(*) FILTER (WHERE rm.bucket = 'CASH') AS cash_count,
                COALESCE(SUM(rm.amount) FILTER (WHERE rm.bucket = 'CASH'), 0) AS cash_sum
            FROM ride_money rm
            GROUP BY rm.shift_id
        ),
        base AS (
            SELECT
                sc.shift_id,
                sc.shift_started_at,
                sc.shift_closed_at,
                sc.route_started_at,
                sc.route_ended_at,
                sc.driver_user_id,
                drv.LAST_NAME AS driver_last_name,
                drv.FIRST_NAME AS driver_first_name,
                drv.LAST_NAME_INITIAL AS driver_last_name_initial,
                drv.PATRONYMIC_INITIAL AS driver_patronymic_initial,
                tm.TERMINAL_ID,
                tm.TERMINAL_SERIAL,
                v.VEHICLE_ID,
                v.VEHICLE_NUMBER,
                vt.TYPE_NAME AS vehicle_type_name,
                pth.PATH_ID,
                rt.ROUTE_ID,
                rt.ROUTE_NUMBER,
                rt.ROUTE_NAME,
                rt.ROUTE_CATEGORY,
                o.ORGANIZER_ID,
                o.ORGANIZER_NAME,
                terr.territory_names,
                cr.CARRIER_ID,
                cr.CARRIER_NAME,
                cr.REGION_ID,
                COALESCE(a.bk_count, 0) AS bk_count,
                COALESCE(a.bk_sum, 0) AS bk_sum,
                COALESCE(a.mfk_count, 0) AS mfk_count,
                COALESCE(a.mfk_sum, 0) AS mfk_sum,
                COALESCE(a.tk_count, 0) AS tk_count,
                COALESCE(a.tk_sum, 0) AS tk_sum,
                COALESCE(a.cash_count, 0) AS cash_count,
                COALESCE(a.cash_sum, 0) AS cash_sum
            FROM shift_ctx sc
            LEFT JOIN agg a ON a.shift_id = sc.shift_id
            LEFT JOIN ASOP_TERMINALS tm ON tm.TERMINAL_ID = sc.TERMINAL_ID
            LEFT JOIN ASOP_USERS drv ON drv.USER_ID = sc.driver_user_id
            LEFT JOIN ASOP_VEHICLES v
              ON v.VEHICLE_ID = COALESCE(sc.trip_vehicle_id, sc.shift_vehicle_id, tm.VEHICLE_ID)
            LEFT JOIN ASOP_VEHICLE_TYPES vt ON vt.VEHICLE_TYPE_ID = v.VEHICLE_TYPE_ID
            LEFT JOIN ASOP_PATHS pth ON pth.PATH_ID = sc.path_id
            LEFT JOIN ASOP_ROUTES rt ON rt.ROUTE_ID = pth.ROUTE_ID
            LEFT JOIN ASOP_ORGANIZERS o ON o.ORGANIZER_ID = rt.ORGANIZER_ID
            -- Территории организатора: у организатора их может быть несколько — все через запятую
            LEFT JOIN LATERAL (
                SELECT string_agg(DISTINCT COALESCE(ter.MUNICIPAL_DIVISION, ter.ADMIN_DIVISION), ', ') AS territory_names
                FROM ASOP_ORGANIZER_TERRITORIES ot
                JOIN ASOP_TERRITORIES ter ON ter.TERRITORY_ID = ot.TERRITORY_ID
                WHERE ot.ORGANIZER_ID = o.ORGANIZER_ID
                  AND ot.DELETED_AT IS NULL
                  AND ter.DELETED_AT IS NULL
            ) terr ON TRUE
            LEFT JOIN ASOP_CARRIERS cr ON cr.CARRIER_ID = COALESCE(v.CARRIER_ID, tm.CARRIER_ID)
        )
    """.trimIndent()

    fun count(filter: Filter): Mono<Long> {
        val sql = cte + "SELECT COUNT(*) AS cnt FROM base b ${conditions(filter)}"
        return bound(db.sql(sql), filter).map { (it["cnt"] as Number).toLong() }.one()
    }

    fun findRows(filter: Filter, limit: Int, offset: Int): Flux<ShiftListRow> {
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
                val startedAt = asInstant(row["shift_started_at"])
                val closedAt = asInstant(row["shift_closed_at"])
                ShiftListRow(
                    carrierName = asString(row["carrier_name"]),
                    shiftId = asUuidRequired(row["shift_id"], "shift_id"),
                    driverLastName = asString(row["driver_last_name"]),
                    driverFirstName = asString(row["driver_first_name"]),
                    driverLastNameInitial = asString(row["driver_last_name_initial"]),
                    driverPatronymicInitial = asString(row["driver_patronymic_initial"]),
                    shiftStartedAt = startedAt,
                    shiftClosedAt = closedAt,
                    durationText = ShiftListRow.durationText(startedAt, closedAt),
                    terminalSerial = asString(row["terminal_serial"]),
                    vehicleNumber = asString(row["vehicle_number"]),
                    vehicleTypeName = asString(row["vehicle_type_name"]),
                    routeNumber = asString(row["route_number"]),
                    routeName = asString(row["route_name"]),
                    organizerName = asString(row["organizer_name"]),
                    territoryNames = asString(row["territory_names"]),
                    routeCategoryLabel = ShiftListRow.routeCategoryLabel(asString(row["route_category"])),
                    routeStartedAt = asInstant(row["route_started_at"]),
                    routeEndedAt = asInstant(row["route_ended_at"]),
                    bkCount = asLong(row["bk_count"]) ?: 0L,
                    bkSum = asBigDecimal(row["bk_sum"]) ?: BigDecimal.ZERO,
                    mfkCount = asLong(row["mfk_count"]) ?: 0L,
                    mfkSum = asBigDecimal(row["mfk_sum"]) ?: BigDecimal.ZERO,
                    tkCount = asLong(row["tk_count"]) ?: 0L,
                    tkSum = asBigDecimal(row["tk_sum"]) ?: BigDecimal.ZERO,
                    cashCount = asLong(row["cash_count"]) ?: 0L,
                    cashSum = asBigDecimal(row["cash_sum"]) ?: BigDecimal.ZERO
                )
            }
            .all()
    }

    /** Итоги по ПОЛНОМУ отфильтрованному набору — не зависят от limit/offset. */
    fun findTotals(filter: Filter): Mono<ShiftListTotals> {
        val sql = cte + """
            SELECT
                COUNT(*) AS shifts_count,
                COALESCE(SUM(b.bk_count), 0) AS bk_count,
                COALESCE(SUM(b.bk_sum), 0) AS bk_sum,
                COALESCE(SUM(b.mfk_count), 0) AS mfk_count,
                COALESCE(SUM(b.mfk_sum), 0) AS mfk_sum,
                COALESCE(SUM(b.tk_count), 0) AS tk_count,
                COALESCE(SUM(b.tk_sum), 0) AS tk_sum,
                COALESCE(SUM(b.cash_count), 0) AS cash_count,
                COALESCE(SUM(b.cash_sum), 0) AS cash_sum
            FROM base b
            ${conditions(filter)}
        """.trimIndent()
        return bound(db.sql(sql), filter)
            .map { row ->
                ShiftListTotals(
                    shiftsCount = asLong(row["shifts_count"]) ?: 0L,
                    bkCount = asLong(row["bk_count"]) ?: 0L,
                    bkSum = asBigDecimal(row["bk_sum"]) ?: BigDecimal.ZERO,
                    mfkCount = asLong(row["mfk_count"]) ?: 0L,
                    mfkSum = asBigDecimal(row["mfk_sum"]) ?: BigDecimal.ZERO,
                    tkCount = asLong(row["tk_count"]) ?: 0L,
                    tkSum = asBigDecimal(row["tk_sum"]) ?: BigDecimal.ZERO,
                    cashCount = asLong(row["cash_count"]) ?: 0L,
                    cashSum = asBigDecimal(row["cash_sum"]) ?: BigDecimal.ZERO
                )
            }
            .one()
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
