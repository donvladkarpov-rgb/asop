package ru.asop.card.repository

import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import ru.asop.card.dto.TripRegistrationReportRow
import ru.asop.card.dto.TripRegistrationReportTotal
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Реестр транзакций по операциям регистрации проезда.
 *
 * Отчёт — сквозной JOIN по всей БД (рейс/смена в ASOP_SESSIONS, ТС/маршрут/пункты в
 * route-таблицах, карты/тарифы/льготы в card-таблицах), поэтому собирается одним
 * DatabaseClient-запросом, а не через @Query-маппинг на 40 колонок.
 *
 * INNER JOIN на сессию-рейс: в отчёт попадают только операции регистрации проезда,
 * пополнения карт (SESSION_ID IS NULL) исключены.
 *
 * Запрос разбит на два CTE:
 *  - `tx`    — транзакция + распарсенный JSON metadata (tripsAt / tripsAfter / benefitId);
 *  - `base`  — все JOIN'ы, использующие распарсенные значения metadata.
 * Так не приходится ссылаться на вычисленные колонки того же уровня SELECT.
 */
@Repository
class TripRegistrationReportRepository(
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
        val driverId: UUID? = null,
        val transactionTypeIds: List<UUID> = emptyList()
    )

    private val cte = """
        WITH tx AS (
            SELECT
                t.TRANSACTION_ID,
                t.SESSION_ID,
                t.TRANSACTION_TYPE_ID,
                t.TRANSACTION_RESULT_ID,
                t.CREATED_AT AS processed_at,
                -- Время операции НА ТЕРМИНАЛЕ (metadata.tripsAt, epoch ms), иначе серверный STARTED_AT
                CASE WHEN t.METADATA->>'tripsAt' ~ '^[0-9]+$'
                     THEN to_timestamp((t.METADATA->>'tripsAt')::double precision / 1000.0)
                     ELSE t.STARTED_AT END AS trip_at,
                CASE WHEN t.METADATA->>'tripsAfter' ~ '^[0-9]+$'
                     THEN (t.METADATA->>'tripsAfter')::int END AS trips_after,
                CASE WHEN t.METADATA->>'benefitId' ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
                     THEN (t.METADATA->>'benefitId')::uuid END AS metadata_benefit_id
            FROM ASOP_TRANSACTIONS t
        ),
        base AS (
            SELECT
                tx.TRANSACTION_ID,
                tx.TRANSACTION_TYPE_ID,
                tx.trip_at,
                tx.trips_after,
                tx.processed_at,

                o.ORGANIZER_ID,
                o.ORGANIZER_NAME,
                cr.CARRIER_ID,
                cr.CARRIER_NAME,
                cr.REGION_ID,
                rt.ROUTE_ID,
                rt.ROUTE_NUMBER,
                rt.ROUTE_NAME,
                pth.PATH_ID,

                sh.SESSION_ID AS shift_id,
                sh.STARTED_AT AS shift_started_at,

                tr.SESSION_ID AS trip_id,
                tr.SESSION_TYPE_ID AS trip_session_type_id,
                tr.STARTED_AT AS trip_started_at,

                tm.TERMINAL_ID,
                tm.TERMINAL_NUMBER,
                tm.TERMINAL_SERIAL,
                tr.OPENED_BY_USER_ID AS driver_user_id,
                drv.LAST_NAME AS driver_last_name,
                drv.FIRST_NAME AS driver_first_name,
                drv.LAST_NAME_INITIAL AS driver_last_name_initial,
                drv.PATRONYMIC_INITIAL AS driver_patronymic_initial,
                drv.BIRTH_DATE AS driver_birth_date,

                vt.TYPE_NAME AS vehicle_type_name,
                vm.MODEL_NAME AS vehicle_model_name,
                v.VEHICLE_ID,
                v.VEHICLE_NUMBER,
                v.VEHICLE_NAME,

                s1.STOP_NAME AS origin_stop_name,
                s2.STOP_NAME AS destination_stop_name,

                trow.TRANSACTION_RESULT_NAME AS status,

                COALESCE(bn.BENEFIT_NAME, 'Без льготы') AS passenger_category,
                bs.DISCOUNT_SHARE AS benefit_share,

                tt.NAME AS tariff_type_name,
                CASE WHEN bp.PAYMENT_ID IS NOT NULL
                     THEN 'Банковская карта'
                     ELSE 'МИФЕР' END AS payment_form,
                COALESCE(bp.AMOUNT, 0) AS paid_amount,

                CASE
                    WHEN bp.PAYMENT_ID IS NOT NULL
                        THEN CONCAT(BTRIM(COALESCE(cb.BIN, '')), '******', BTRIM(COALESCE(cb.PAN_LAST4, '')))
                    WHEN cm.UID IS NOT NULL THEN encode(cm.UID, 'hex')
                    ELSE NULL
                END AS card_number,
                bp.RRN,

                fr.STATUS AS fiscal_status,
                fr.CREATED_AT AS fiscal_created_at,
                fr.CONFIRMED_AT AS fiscal_confirmed_at
            FROM tx
            JOIN ASOP_SESSIONS tr
              ON tr.SESSION_ID = tx.SESSION_ID
            LEFT JOIN ASOP_SESSIONS sh
              ON sh.SESSION_ID = tr.PARENT_SESSION_ID
            LEFT JOIN ASOP_TERMINALS tm
              ON tm.TERMINAL_ID = tr.TERMINAL_ID
            LEFT JOIN ASOP_USERS drv
              ON drv.USER_ID = tr.OPENED_BY_USER_ID
            LEFT JOIN ASOP_VEHICLES v
              ON v.VEHICLE_ID = COALESCE(tr.VEHICLE_ID, tm.VEHICLE_ID)
            LEFT JOIN ASOP_VEHICLE_TYPES vt
              ON vt.VEHICLE_TYPE_ID = v.VEHICLE_TYPE_ID
            LEFT JOIN ASOP_VEHICLE_MODELS vm
              ON vm.VEHICLE_MODEL_ID = v.VEHICLE_MODEL_ID
            LEFT JOIN ASOP_PATHS pth
              ON pth.PATH_ID = tr.PATH_ID
            LEFT JOIN ASOP_ROUTES rt
              ON rt.ROUTE_ID = pth.ROUTE_ID
            LEFT JOIN ASOP_ORGANIZERS o
              ON o.ORGANIZER_ID = rt.ORGANIZER_ID
            LEFT JOIN ASOP_CARRIERS cr
              ON cr.CARRIER_ID = COALESCE(v.CARRIER_ID, tm.CARRIER_ID)
            LEFT JOIN ASOP_TRANSPORT_STOPS s1
              ON s1.STOP_ID = pth.START_STOP_ID
            LEFT JOIN ASOP_TRANSPORT_STOPS s2
              ON s2.STOP_ID = pth.END_STOP_ID
            LEFT JOIN ASOP_TRANSACTION_RESULTS trow
              ON trow.TRANSACTION_RESULT_ID = tx.TRANSACTION_RESULT_ID
            LEFT JOIN ASOP_TRANSACTION_CARDS tc
              ON tc.TRANSACTION_ID = tx.TRANSACTION_ID AND tc.CARD_ROLE = 'PAYER'
            LEFT JOIN ASOP_CARDS pc
              ON pc.CARD_ID = tc.CARD_ID
            LEFT JOIN ASOP_CARD_BANKS cb
              ON cb.CARD_ID = tc.CARD_ID
            LEFT JOIN ASOP_CARD_MIFARES cm
              ON cm.CARD_ID = tc.CARD_ID
            LEFT JOIN ASOP_CARD_TARIFFS ct
              ON ct.CARD_TARIFF_ID = tc.TARIFF_APPLIED_ID
            -- Действующий тариф карты: активных тарифов может быть несколько (история
            -- перевыпуска), берём последний — иначе строка транзакции продублируется.
            LEFT JOIN LATERAL (
                SELECT a.TARIFF_TYPE_ID
                FROM ASOP_CARD_TARIFFS a
                WHERE a.CARD_ID = tc.CARD_ID
                  AND a.IS_ACTIVE
                  AND a.DELETED_AT IS NULL
                ORDER BY a.CREATED_AT DESC
                LIMIT 1
            ) ct_active ON TRUE
            LEFT JOIN ASOP_TARIFF_TYPES tt
              ON tt.TARIFF_TYPE_ID = COALESCE(ct.TARIFF_TYPE_ID, ct_active.TARIFF_TYPE_ID)
            -- Льгота пассажира: у пользователя бывает несколько действующих назначений,
            -- берём самое свежее, иначе строка транзакции продублируется.
            LEFT JOIN LATERAL (
                SELECT u.BENEFIT_ID
                FROM ASOP_USER_BENEFITS u
                WHERE u.USER_ID = pc.USER_ID
                  AND u.DELETED_AT IS NULL
                  AND u.VALID_FROM <= NOW()
                  AND (u.VALID_UNTIL IS NULL OR u.VALID_UNTIL >= NOW())
                ORDER BY u.VALID_FROM DESC
                LIMIT 1
            ) ub ON TRUE
            LEFT JOIN ASOP_BENEFITS bn
              ON bn.BENEFIT_ID = COALESCE(tx.metadata_benefit_id, ub.BENEFIT_ID)
            -- Платёж за проезд: на транзакцию ожидается один, но при ретраях эквайера
            -- строк может быть несколько — берём последний.
            LEFT JOIN LATERAL (
                SELECT p.PAYMENT_ID, p.AMOUNT, p.RRN
                FROM ASOP_BANK_PAYMENTS p
                WHERE p.TRANSACTION_ID = tx.TRANSACTION_ID
                  AND p.PAYMENT_TYPE = 'FARE'
                  AND p.DELETED_AT IS NULL
                ORDER BY p.CREATED_AT DESC
                LIMIT 1
            ) bp ON TRUE
            -- Фискальный чек: на транзакцию может быть несколько попыток — берём последний.
            LEFT JOIN LATERAL (
                SELECT f.STATUS, f.CREATED_AT, f.CONFIRMED_AT
                FROM ASOP_FISCAL_RECEIPTS f
                WHERE f.TRANSACTION_ID = tx.TRANSACTION_ID
                ORDER BY f.CREATED_AT DESC
                LIMIT 1
            ) fr ON TRUE
            -- Шаг льготы под текущий остаток поездок: пороги пересекаются (последние шаги
            -- с TRIP_THRESHOLD_TO IS NULL), поэтому берём максимальный подходящий FROM.
            LEFT JOIN LATERAL (
                SELECT s.DISCOUNT_SHARE
                FROM ASOP_BENEFIT_STEPS s
                WHERE s.BENEFIT_ID = COALESCE(tx.metadata_benefit_id, ub.BENEFIT_ID)
                  AND s.TRIP_THRESHOLD_FROM <= COALESCE(tx.trips_after, 0)
                ORDER BY s.TRIP_THRESHOLD_FROM DESC
                LIMIT 1
            ) bs ON TRUE
        )
    """.trimIndent()

    private val whereClause = """
        WHERE b.trip_at >= :dateFrom::date
          AND b.trip_at <  (:dateTo::date + INTERVAL '1 day')
    """.trimIndent()

    fun count(filter: Filter): Mono<Long> {
        val sql = cte + """
            SELECT COUNT(*) AS cnt FROM base b
            ${conditions(filter)}
        """.trimIndent()
        return bound(db.sql(sql), filter).map { (it["cnt"] as Number).toLong() }.one()
    }

    fun findRows(filter: Filter, limit: Int, offset: Int): Flux<TripRegistrationReportRow> {
        val sql = cte + """
            SELECT * FROM base b
            ${conditions(filter)}
            ORDER BY
                b.ORGANIZER_NAME ASC NULLS LAST,
                b.CARRIER_NAME ASC NULLS LAST,
                b.ROUTE_NUMBER ASC NULLS LAST,
                b.shift_started_at ASC NULLS LAST,
                b.trip_started_at ASC NULLS LAST,
                b.trip_at ASC
            LIMIT :limit OFFSET :offset
        """.trimIndent()
        return bound(db.sql(sql), filter)
            .bind("limit", limit)
            .bind("offset", offset)
            .map { row ->
                val paymentForm = asString(row["payment_form"])
                TripRegistrationReportRow(
                    organizerId = asUuid(row["organizer_id"]),
                    organizerName = asString(row["organizer_name"]),
                    carrierId = asUuid(row["carrier_id"]),
                    carrierName = asString(row["carrier_name"]),
                    routeId = asUuid(row["route_id"]),
                    routeNumber = asString(row["route_number"]),
                    routeName = asString(row["route_name"]),
                    shiftId = asUuid(row["shift_id"]),
                    tripId = asUuidRequired(row["trip_id"], "trip_id"),
                    tripSessionTypeId = asUuid(row["trip_session_type_id"]),
                    vehicleTypeName = asString(row["vehicle_type_name"]),
                    vehicleModelName = asString(row["vehicle_model_name"]),
                    vehicleNumber = asString(row["vehicle_number"]),
                    vehicleName = asString(row["vehicle_name"]),
                    shiftStartedAt = asInstant(row["shift_started_at"]),
                    terminalNumber = asString(row["terminal_number"]),
                    terminalSerial = asString(row["terminal_serial"]),
                    driverLastName = asString(row["driver_last_name"]),
                    driverFirstName = asString(row["driver_first_name"]),
                    driverLastNameInitial = asString(row["driver_last_name_initial"]),
                    driverPatronymicInitial = asString(row["driver_patronymic_initial"]),
                    driverBirthDate = asLocalDate(row["driver_birth_date"]),
                    cardNumber = asString(row["card_number"]),
                    transactionId = asUuidRequired(row["transaction_id"], "transaction_id"),
                    tripAt = asInstantRequired(row["trip_at"], "trip_at"),
                    processedAt = asInstantRequired(row["processed_at"], "processed_at"),
                    status = asString(row["status"]),
                    passengerCategory = asString(row["passenger_category"]),
                    paymentForm = paymentForm,
                    serviceName = "Проезд",
                    tariffTypeName = asString(row["tariff_type_name"]),
                    // Банковская карта: сумма списана эквайером, тариф в ASOP_CARD_TARIFFS не участвует.
                    // МИФЕР: оплаты нет, поездка списана из остатка поездок карты.
                    appliedTariff = if (paymentForm == PAYMENT_FORM_BANK) null else APPLIED_TARIFF_TRIPS,
                    regulatedTariff = null,
                    benefitShare = asBigDecimal(row["benefit_share"]),
                    benefitTripsAfter = asInt(row["trips_after"]),
                    amount = asBigDecimal(row["paid_amount"]) ?: BigDecimal.ZERO,
                    originStopName = asString(row["origin_stop_name"]),
                    destinationStopName = asString(row["destination_stop_name"]),
                    fiscalStatus = asString(row["fiscal_status"]),
                    fiscalCreatedAt = asInstant(row["fiscal_created_at"]),
                    fiscalConfirmedAt = asInstant(row["fiscal_confirmed_at"]),
                    rrn = asString(row["rrn"])
                )
            }
            .all()
    }

    /** Итоги по 5 уровням группировки одним запросом (GROUPING SETS). */
    fun findTotals(filter: Filter): Flux<TripRegistrationReportTotal> {
        val sql = cte + """
            SELECT
                GROUPING(b.ORGANIZER_ID)::int AS g1, b.ORGANIZER_ID, b.ORGANIZER_NAME,
                GROUPING(b.CARRIER_ID)::int    AS g2, b.CARRIER_ID, b.CARRIER_NAME,
                GROUPING(b.ROUTE_ID)::int      AS g3, b.ROUTE_ID, b.ROUTE_NUMBER, b.ROUTE_NAME,
                GROUPING(b.shift_id)::int      AS g4, b.shift_id,
                GROUPING(b.trip_id)::int       AS g5, b.trip_id,
                COUNT(*) AS trips_count,
                COALESCE(SUM(b.paid_amount), 0) AS amount
            FROM base b
            ${conditions(filter)}
            GROUP BY GROUPING SETS (
                (b.ORGANIZER_ID, b.ORGANIZER_NAME),
                (b.ORGANIZER_ID, b.ORGANIZER_NAME, b.CARRIER_ID, b.CARRIER_NAME),
                (b.ORGANIZER_ID, b.ORGANIZER_NAME, b.CARRIER_ID, b.CARRIER_NAME,
                 b.ROUTE_ID, b.ROUTE_NUMBER, b.ROUTE_NAME),
                (b.ORGANIZER_ID, b.ORGANIZER_NAME, b.CARRIER_ID, b.CARRIER_NAME,
                 b.ROUTE_ID, b.ROUTE_NUMBER, b.ROUTE_NAME, b.shift_id),
                (b.ORGANIZER_ID, b.ORGANIZER_NAME, b.CARRIER_ID, b.CARRIER_NAME,
                 b.ROUTE_ID, b.ROUTE_NUMBER, b.ROUTE_NAME, b.shift_id, b.trip_id)
            )
        """.trimIndent()
        return bound(db.sql(sql), filter)
            .map { row ->
                // Уровень группировки = 5 - (число свёрнутых колонок ключа).
                // Набор «только организатор» сворачивает carrier/route/shift/trip → 4 свёрнутых → уровень 1.
                val collapsed = (1..5).count { asInt(row["g$it"]) == 1 }
                val level = 5 - collapsed
                TripRegistrationReportTotal(
                    level = level,
                    levelName = TripRegistrationReportTotal.LEVEL_NAMES[level] ?: "Итого",
                    organizerId = asUuid(row["organizer_id"]),
                    organizerName = asString(row["organizer_name"]),
                    carrierId = asUuid(row["carrier_id"]),
                    carrierName = asString(row["carrier_name"]),
                    routeId = asUuid(row["route_id"]),
                    routeLabel = routeLabel(asString(row["route_number"]), asString(row["route_name"])),
                    // На агрегирующих уровнях shift_id/trip_id свёрнуты (GROUPING = 1) → NULL, это норма.
                    shiftId = asUuid(row["shift_id"]),
                    tripId = asUuid(row["trip_id"]),
                    tripsCount = (row["trips_count"] as Number).toLong(),
                    amount = asBigDecimal(row["amount"]) ?: BigDecimal.ZERO
                )
            }
            .all()
    }

    private fun conditions(filter: Filter): String {
        // Только алиасы CTE `base` — внешние алиасы JOIN'ов (cr/pth/v/tr) тут не в области видимости.
        val sb = StringBuilder(whereClause)
        if (filter.transactionTypeIds.isNotEmpty()) {
            sb.append(" AND b.TRANSACTION_TYPE_ID = ANY(string_to_array(:typeIds, ',')::uuid[])")
        }
        filter.regionId?.let { sb.append(" AND b.REGION_ID = :regionId") }
        filter.organizerId?.let { sb.append(" AND b.ORGANIZER_ID = :organizerId") }
        filter.carrierId?.let { sb.append(" AND b.CARRIER_ID = :carrierId") }
        filter.routeId?.let { sb.append(" AND b.ROUTE_ID = :routeId") }
        filter.pathId?.let { sb.append(" AND b.PATH_ID = :pathId") }
        filter.vehicleId?.let { sb.append(" AND b.VEHICLE_ID = :vehicleId") }
        filter.terminalId?.let { sb.append(" AND b.TERMINAL_ID = :terminalId") }
        filter.driverId?.let { sb.append(" AND b.driver_user_id = :driverId") }
        return sb.toString()
    }

    private fun bound(
        spec: DatabaseClient.GenericExecuteSpec,
        filter: Filter
    ): DatabaseClient.GenericExecuteSpec {
        // NB: DatabaseClient.bind() возвращает НОВЫЙ immutable spec — результат обязателен.
        var s = spec
            .bind("dateFrom", filter.dateFrom)
            .bind("dateTo", filter.dateTo)
        if (filter.transactionTypeIds.isNotEmpty()) {
            s = s.bind("typeIds", filter.transactionTypeIds.joinToString(","))
        }
        // UUID биндим как UUID, а не как строку — иначе PG не находит оператор uuid = varchar.
        filter.regionId?.let { s = s.bind("regionId", it) }
        filter.organizerId?.let { s = s.bind("organizerId", it) }
        filter.carrierId?.let { s = s.bind("carrierId", it) }
        filter.routeId?.let { s = s.bind("routeId", it) }
        filter.pathId?.let { s = s.bind("pathId", it) }
        filter.vehicleId?.let { s = s.bind("vehicleId", it) }
        filter.terminalId?.let { s = s.bind("terminalId", it) }
        filter.driverId?.let { s = s.bind("driverId", it) }
        return s
    }

    private fun joinLabels(vararg parts: String?): String? =
        parts.filterNotNull().filter { it.isNotBlank() }.joinToString(" ").takeIf { it.isNotBlank() }

    /** Название маршрута обычно уже содержит номер («Маршрут №101 (CITY)») — не дублируем его в подписи итога. */
    private fun routeLabel(number: String?, name: String?): String? = when {
        name.isNullOrBlank() -> number
        number.isNullOrBlank() -> name
        name.contains(number) -> name
        else -> joinLabels(number, name)
    }

    private fun asUuid(v: Any?): UUID? = when (v) {
        null -> null
        is UUID -> v
        is String -> v.toUUID()
        else -> throw IllegalStateException("Cannot convert ${v.javaClass} to UUID")
    }

    private fun asString(v: Any?): String? = v?.toString()

    /** NOT NULL-колонки INNER JOIN'ов — пустое значение означает баг в запросе, а не «нет данных». */
    private fun asUuidRequired(v: Any?, column: String): UUID =
        asUuid(v) ?: error("NULL in required report column $column")

    private fun asInstantRequired(v: Any?, column: String): Instant =
        asInstant(v) ?: error("NULL in required report column $column")

    private fun asInstant(v: Any?): Instant? = when (v) {
        null -> null
        is Instant -> v
        is java.time.OffsetDateTime -> v.toInstant()
        else -> throw IllegalStateException("Cannot convert ${v.javaClass} to Instant")
    }

    private fun asLocalDate(v: Any?): LocalDate? = when (v) {
        null -> null
        is LocalDate -> v
        is java.sql.Date -> v.toLocalDate()
        else -> throw IllegalStateException("Cannot convert ${v.javaClass} to LocalDate")
    }

    private fun asBigDecimal(v: Any?): BigDecimal? = when (v) {
        null -> null
        is BigDecimal -> v
        is Number -> BigDecimal(v.toString())
        else -> throw IllegalStateException("Cannot convert ${v.javaClass} to BigDecimal")
    }

    private fun asInt(v: Any?): Int? = (v as? Number)?.toInt()

    private fun String.toUUID(): UUID? =
        runCatching { UUID.fromString(this) }.getOrNull()

    companion object {
        const val PAYMENT_FORM_BANK = "Банковская карта"
        const val PAYMENT_FORM_MIFARE = "МИФЕР"
        const val APPLIED_TARIFF_TRIPS = "Списание поездок"

        /** Типы транзакций отчёта по умолчанию: оплата проезда + валидация без списания. */
        val DEFAULT_TRANSACTION_TYPE_IDS = listOf(
            UUID.fromString("00000000-0000-0000-0000-000000000801"),
            UUID.fromString("00000000-0000-0000-0000-000000000803")
        )
    }
}