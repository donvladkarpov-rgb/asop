package ru.asop.card.repository

import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import ru.asop.card.dto.BenefitReportRow
import ru.asop.card.dto.BenefitReportTotal
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/**
 * Сводный отчёт «Сводный, льготники»: число льготных поездок и сумма возмещения
 * по льготным категориям.
 *
 * Льготная поездка: транзакция «Оплата проезда»/«Валидация (без списания)» внутри
 * открытого рейса, НЕ отклонённая (`metadata.declined`), с разрешённой льготой:
 * `metadata.benefitId` терминала, иначе серверный фолбэк
 * `ASOP_TRANSACTION_CARDS(PAYER) → ASOP_CARDS.USER_ID → свежий ASOP_USER_BENEFITS`.
 *
 * Возмещение на поездку = тариф × доля скидки:
 *  - тариф — `ASOP_TARIFF_RATES.PRICE`: точный PATH_ID рейса → CARRIER_ID перевозчика →
 *    «любой активный» (MVP-fallback, см. TODO в LATERAL ниже);
 *  - доля — `ASOP_BENEFIT_STEPS.DISCOUNT_SHARE`, ступень по числу льготных поездок
 *    ПОЛЬЗОВАТЕЛЯ за отчётный период (`user_trips` CTE), т.к. для льготных поездок
 *    `metadata.tripsAfter = null` и остаток поездок карты бессмыслен.
 *
 * Зерно строк — (регион, категория); льгота привязана к региону, поэтому категории
 * и группируются, и фильтруются по `bn.REGION_ID`. Итоги — GROUPING SETS 2 уровней.
 */
@Repository
class BenefitReportRepository(
    private val db: DatabaseClient
) {

    data class Filter(
        val dateFrom: LocalDate,
        val dateTo: LocalDate,
        val regionId: UUID? = null,
        val carrierId: UUID? = null,
        val benefitId: UUID? = null
    )

    private val cte = """
        WITH tx AS (
            SELECT
                t.TRANSACTION_ID,
                t.SESSION_ID,
                t.CREATED_AT AS processed_at,
                -- Время операции НА ТЕРМИНАЛЕ (metadata.tripsAt, epoch ms), иначе серверный STARTED_AT
                CASE WHEN t.METADATA->>'tripsAt' ~ '^[0-9]+$'
                     THEN to_timestamp((t.METADATA->>'tripsAt')::double precision / 1000.0)
                     ELSE t.STARTED_AT END AS trip_at,
                CASE WHEN t.METADATA->>'benefitId' ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
                     THEN (t.METADATA->>'benefitId')::uuid END AS metadata_benefit_id,
                CASE WHEN t.METADATA->>'declined' IN ('true', 'True', 'TRUE')
                     THEN TRUE ELSE FALSE END AS declined
            FROM ASOP_TRANSACTIONS t
            -- Оплата проезда + валидация без списания (пополнения сюда не попадают)
            WHERE t.TRANSACTION_TYPE_ID = ANY(string_to_array(:typeIds, ',')::uuid[])
        ),
        base AS (
            SELECT
                tx.TRANSACTION_ID,
                tx.trip_at,

                tr.PATH_ID,
                cr.CARRIER_ID AS trip_carrier_id,
                pc.USER_ID AS payer_user_id,

                COALESCE(tx.metadata_benefit_id, ub.BENEFIT_ID) AS benefit_id,
                bn.BENEFIT_CODE AS benefit_code,
                COALESCE(bn.BENEFIT_NAME, 'Неизвестная категория') AS benefit_name,
                bn.REGION_ID AS benefit_region_id,
                -- ASOP_REGIONS не имеет REGION_NAME: название региона — МУНИЦИПАЛЬНОЕ образование
                COALESCE(rg.MUNICIPAL_DIVISION, rg.ADMIN_DIVISION) AS benefit_region_name
            FROM tx
            -- INNER: только операции внутри рейса, пополнения (SESSION_ID IS NULL) исключены
            JOIN ASOP_SESSIONS tr
              ON tr.SESSION_ID = tx.SESSION_ID
            LEFT JOIN ASOP_TERMINALS tm
              ON tm.TERMINAL_ID = tr.TERMINAL_ID
            LEFT JOIN ASOP_VEHICLES v
              ON v.VEHICLE_ID = COALESCE(tr.VEHICLE_ID, tm.VEHICLE_ID)
            LEFT JOIN ASOP_CARRIERS cr
              ON cr.CARRIER_ID = COALESCE(v.CARRIER_ID, tm.CARRIER_ID)
            LEFT JOIN ASOP_TRANSACTION_CARDS tc
              ON tc.TRANSACTION_ID = tx.TRANSACTION_ID AND tc.CARD_ROLE = 'PAYER'
            LEFT JOIN ASOP_CARDS pc
              ON pc.CARD_ID = tc.CARD_ID
            -- Назначение льготы пользователю: бывает несколько, берём самое свежее
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
            LEFT JOIN ASOP_REGIONS rg
              ON rg.REGION_ID = bn.REGION_ID
            WHERE NOT tx.declined
              AND COALESCE(tx.metadata_benefit_id, ub.BENEFIT_ID) IS NOT NULL
        ),
        -- Число льготных поездок пользователя за отчётный период — ключ выбора ступени
        user_trips AS (
            SELECT b.payer_user_id AS user_id, b.benefit_id, COUNT(*) AS trips
            FROM base b
            WHERE b.payer_user_id IS NOT NULL
            GROUP BY b.payer_user_id, b.benefit_id
        ),
        agg AS (
            SELECT
                b.*,
                ut.trips AS user_benefit_trips,
                step.discount_share,
                fare.price AS fare_price
            FROM base b
            LEFT JOIN user_trips ut
              ON ut.user_id = b.payer_user_id
             AND ut.benefit_id = b.benefit_id
            -- Ступень льготы: пороги не пересекаются (TRIP_THRESHOLD_TO задан кроме последних),
            -- поэтому достаточно TRIP_THRESHOLD_FROM <= count; на всякий случай проверяем и TO.
            LEFT JOIN LATERAL (
                SELECT s.DISCOUNT_SHARE
                FROM ASOP_BENEFIT_STEPS s
                WHERE s.BENEFIT_ID = b.benefit_id
                  AND s.DELETED_AT IS NULL
                  AND s.TRIP_THRESHOLD_FROM <= COALESCE(ut.trips, 0)
                  AND (s.TRIP_THRESHOLD_TO IS NULL OR s.TRIP_THRESHOLD_TO > COALESCE(ut.trips, 0))
                ORDER BY s.TRIP_THRESHOLD_FROM DESC
                LIMIT 1
            ) step ON TRUE
            -- Тариф проезда: PATH_ID рейса → CARRIER_ID перевозчика → любой активный.
            -- TODO(prod): убрать ветку ELSE («любой активный») — для прода оставить только
            -- точное совпадение по пути/перевозчику, иначе возмещение считается по чужому
            -- тарифу другого маршрута/региона (MVP-обход, пока тарифы не заведены на все пути).
            LEFT JOIN LATERAL (
                SELECT r.PRICE
                FROM ASOP_TARIFF_RATES r
                WHERE r.IS_ACTIVE
                  AND r.DELETED_AT IS NULL
                ORDER BY
                    CASE
                        WHEN r.PATH_ID = b.PATH_ID AND b.PATH_ID IS NOT NULL THEN 0
                        WHEN r.CARRIER_ID = b.trip_carrier_id AND b.trip_carrier_id IS NOT NULL THEN 1
                        ELSE 2
                    END,
                    r.UPDATED_AT DESC
                LIMIT 1
            ) fare ON TRUE
        )
    """.trimIndent()

    private val whereClause = """
        WHERE b.trip_at >= :dateFrom::date
          AND b.trip_at <  (:dateTo::date + INTERVAL '1 day')
    """.trimIndent()

    /** Число строк отчёта = число групп (регион, категория). */
    fun count(filter: Filter): Mono<Long> {
        val sql = cte + """
            SELECT COUNT(*) AS cnt FROM (
                SELECT 1 FROM agg b
                ${conditions(filter)}
                GROUP BY b.benefit_region_id, b.benefit_id
            ) groups
        """.trimIndent()
        return bound(db.sql(sql), filter).map { (it["cnt"] as Number).toLong() }.one()
    }

    fun findRows(filter: Filter, limit: Int, offset: Int): Flux<BenefitReportRow> {
        val sql = cte + """
            SELECT
                b.benefit_region_id,
                b.benefit_region_name,
                b.benefit_id,
                b.benefit_code,
                b.benefit_name,
                COUNT(*) AS trips_count,
                COALESCE(SUM(b.fare_price * b.discount_share), 0) AS compensation,
                COUNT(*) FILTER (WHERE b.fare_price IS NULL OR b.discount_share IS NULL)
                    AS trips_without_rate
            FROM agg b
            ${conditions(filter)}
            GROUP BY
                b.benefit_region_id,
                b.benefit_region_name,
                b.benefit_id,
                b.benefit_code,
                b.benefit_name
            ORDER BY
                b.benefit_region_name ASC NULLS LAST,
                b.benefit_code ASC NULLS LAST,
                b.benefit_name ASC NULLS LAST
            LIMIT :limit OFFSET :offset
        """.trimIndent()
        return bound(db.sql(sql), filter)
            .bind("limit", limit)
            .bind("offset", offset)
            .map { row ->
                BenefitReportRow(
                    regionId = asUuid(row["benefit_region_id"]),
                    regionName = asString(row["benefit_region_name"]),
                    benefitId = asUuid(row["benefit_id"]),
                    benefitCode = asString(row["benefit_code"]),
                    benefitName = asString(row["benefit_name"]),
                    tripsCount = (row["trips_count"] as Number).toLong(),
                    compensation = asBigDecimal(row["compensation"]) ?: BigDecimal.ZERO,
                    tripsWithoutRate = (row["trips_without_rate"] as Number).toLong()
                )
            }
            .all()
    }

    /** Итоги 2 уровней (регион → категория) одним запросом (GROUPING SETS). */
    fun findTotals(filter: Filter): Flux<BenefitReportTotal> {
        val sql = cte + """
            SELECT
                GROUPING(b.benefit_region_id)::int AS g1,
                b.benefit_region_id,
                b.benefit_region_name,
                GROUPING(b.benefit_id)::int AS g2,
                b.benefit_id,
                b.benefit_code,
                b.benefit_name,
                COUNT(*) AS trips_count,
                COALESCE(SUM(b.fare_price * b.discount_share), 0) AS compensation,
                COUNT(*) FILTER (WHERE b.fare_price IS NULL OR b.discount_share IS NULL)
                    AS trips_without_rate
            FROM agg b
            ${conditions(filter)}
            GROUP BY GROUPING SETS (
                (b.benefit_region_id, b.benefit_region_name),
                (b.benefit_region_id, b.benefit_region_name,
                 b.benefit_id, b.benefit_code, b.benefit_name)
            )
        """.trimIndent()
        return bound(db.sql(sql), filter)
            .map { row ->
                // Уровень = 2 - число свёрнутых колонок ключа (g1 = регион, g2 = категория).
                val collapsed = (1..2).count { asInt(row["g$it"]) == 1 }
                val level = 2 - collapsed
                BenefitReportTotal(
                    level = level,
                    levelName = BenefitReportTotal.LEVEL_NAMES[level] ?: "Итого",
                    regionId = asUuid(row["benefit_region_id"]),
                    regionName = asString(row["benefit_region_name"]),
                    benefitId = asUuid(row["benefit_id"]),
                    benefitCode = asString(row["benefit_code"]),
                    benefitName = asString(row["benefit_name"]),
                    tripsCount = (row["trips_count"] as Number).toLong(),
                    compensation = asBigDecimal(row["compensation"]) ?: BigDecimal.ZERO,
                    tripsWithoutRate = (row["trips_without_rate"] as Number).toLong()
                )
            }
            .all()
    }

    private fun conditions(filter: Filter): String {
        // Только алиасы CTE `agg` (он наследует колонки `base`).
        val sb = StringBuilder(whereClause)
        filter.regionId?.let { sb.append(" AND b.benefit_region_id = :regionId") }
        filter.carrierId?.let { sb.append(" AND b.trip_carrier_id = :carrierId") }
        filter.benefitId?.let { sb.append(" AND b.benefit_id = :benefitId") }
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
            .bind("typeIds", DEFAULT_TRANSACTION_TYPE_IDS.joinToString(","))
        // UUID биндим как UUID, а не как строку — иначе PG не находит оператор uuid = varchar.
        filter.regionId?.let { s = s.bind("regionId", it) }
        filter.carrierId?.let { s = s.bind("carrierId", it) }
        filter.benefitId?.let { s = s.bind("benefitId", it) }
        return s
    }

    private fun asUuid(v: Any?): UUID? = when (v) {
        null -> null
        is UUID -> v
        is String -> runCatching { UUID.fromString(v) }.getOrNull()
        else -> throw IllegalStateException("Cannot convert ${v.javaClass} to UUID")
    }

    private fun asString(v: Any?): String? = v?.toString()

    private fun asBigDecimal(v: Any?): BigDecimal? = when (v) {
        null -> null
        is BigDecimal -> v
        is Number -> BigDecimal(v.toString())
        else -> throw IllegalStateException("Cannot convert ${v.javaClass} to BigDecimal")
    }

    private fun asInt(v: Any?): Int? = (v as? Number)?.toInt()

    companion object {
        /** Типы транзакций отчёта: оплата проезда + валидация без списания. */
        val DEFAULT_TRANSACTION_TYPE_IDS = TripRegistrationReportRepository.DEFAULT_TRANSACTION_TYPE_IDS
    }
}
