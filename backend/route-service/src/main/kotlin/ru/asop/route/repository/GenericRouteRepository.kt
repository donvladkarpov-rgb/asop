package ru.asop.route.repository

import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

/**
 * Generic CRUD через {@link DatabaseClient}.
 * Используется в route-service для всех 10 таблиц маршрутов.
 *
 * Защита от SQL-инъекций: все имена таблиц/колонок берутся из белого списка
 * {@link RouteTableRegistry}, значения параметризованы.
 */
@Repository
class GenericRouteRepository(
    private val db: DatabaseClient
) {
    private companion object {
        val REGION_ID_TABLES = setOf(
            "ASOP_FARE_ZONES",
            "ASOP_TRANSPORT_STOPS",
            "ASOP_ROUTES",
            "ASOP_PATHS",
            "ASOP_PATH_TRANSPORT_STOPS",
            "ASOP_SCHEDULE"
        )
        val CARRIER_ID_TABLES = setOf(
            "ASOP_PATH_SERVICES",
            "ASOP_PATH_DISCOUNTS",
            "ASOP_VEHICLES"
        )
    }

    fun list(info: ResourceInfo): Flux<Map<String, Any?>> {
        val selectClause = info.selectColumns ?: "*"
        val sql = "SELECT $selectClause FROM ${info.tableName} ORDER BY ${info.pkColumn} ASC"
        return db.sql(sql).fetch().all()
    }

    /**
     * Список с опциональными scope-фильтрами (web-admin глобальный фильтр).
     * Семантика как у [findDelta], но без version/deleted-условий:
     *  • REGION_ID_TABLES — прямой region_id = :regionId;
     *  • CARRIER_ID_TABLES — carrier_id = :carrierId, а при заданном только регионе —
     *    carrier_id IN (перевозчики региона);
     *  • regionJoinClause (path-benefits, contract-routes) — JOIN-фильтр по региону.
     * Таблицы вне этих множеств (vehicle-types/models — глобальные справочники) не фильтруются.
     */
    fun listFiltered(
        info: ResourceInfo,
        regionId: UUID?,
        carrierId: UUID?
    ): Flux<Map<String, Any?>> {
        val selectClause = info.selectColumns ?: "*"
        if (regionId == null && carrierId == null) return list(info)

        val useJoin = regionId != null && info.regionJoinClause != null
        val joinSql: String = if (useJoin) " ${info.regionJoinClause}" else ""

        val conditions = mutableListOf<String>()
        var bindRegion = false
        var bindCarrier = false
        if (regionId != null && !useJoin && info.tableName in REGION_ID_TABLES) {
            conditions += "${info.tableName}.region_id = :regionId"
            bindRegion = true
        }
        if (info.tableName in CARRIER_ID_TABLES) {
            if (carrierId != null) {
                conditions += "${info.tableName}.carrier_id = :carrierId"
                bindCarrier = true
            } else if (regionId != null) {
                conditions +=
                    "${info.tableName}.carrier_id IN (SELECT c.carrier_id FROM ASOP_CARRIERS c WHERE c.region_id = :regionId AND c.deleted_at IS NULL)"
                bindRegion = true
            }
        }
        if (conditions.isEmpty() && !useJoin) return list(info) // JOIN сам несёт фильтр региона — не выходим
        val sql = "SELECT $selectClause FROM ${info.tableName}$joinSql WHERE ${conditions.joinToString(" AND ")} ORDER BY ${info.pkColumn} ASC"
        var spec: DatabaseClient.GenericExecuteSpec = db.sql(sql)
        if (bindRegion || useJoin) spec = spec.bind("regionId", regionId!!)
        if (bindCarrier) spec = spec.bind("carrierId", carrierId!!)
        return spec.fetch().all()
    }

    fun getById(info: ResourceInfo, id: UUID): Mono<Map<String, Any?>> {
        val selectClause = info.selectColumns ?: "*"
        val sql = "SELECT $selectClause FROM ${info.tableName} WHERE ${info.pkColumn} = :id LIMIT 1"
        return db.sql(sql)
            .bind("id", id)
            .fetch()
            .one()
            .defaultIfEmpty(emptyMap())
    }

    fun create(info: ResourceInfo, id: UUID, data: Map<String, Any?>): Mono<Map<String, Any?>> {
        val all = LinkedHashMap<String, Any?>(data)
        all[info.idSnake] = id
        val columns = all.keys.toList()
        if (columns.isEmpty()) {
            return Mono.error(IllegalArgumentException("Body must contain at least one field"))
        }
        val placeholders = columns.joinToString(", ") { col ->
            if (all[col] == null) "NULL"
            else info.columnExprs[col] ?: ":$col"
        }
        val sql = "INSERT INTO ${info.tableName} (${columns.joinToString(", ")}) VALUES ($placeholders)"
        val spec: DatabaseClient.GenericExecuteSpec = db.sql(sql)
        var current = spec
        for ((k, v) in all) {
            if (v != null) current = bindValue(current, k, v)
        }
        return current.fetch().rowsUpdated().then(getById(info, id))
    }

    fun update(info: ResourceInfo, id: UUID, data: Map<String, Any?>): Mono<Map<String, Any?>> {
        if (data.isEmpty()) {
            return getById(info, id)
        }
        val setClause = data.keys.joinToString(", ") { col ->
            if (data[col] == null) "$col = NULL"
            else info.columnExprs[col]?.let { "$col = $it" } ?: "$col = :$col"
        }
        val sql = "UPDATE ${info.tableName} SET $setClause WHERE ${info.pkColumn} = :id"
        val spec: DatabaseClient.GenericExecuteSpec = db.sql(sql).bind("id", id)
        var current = spec
        for ((k, v) in data) {
            if (v != null) current = bindValue(current, k, v)
        }
        return current.fetch().rowsUpdated().then(getById(info, id))
    }

    fun delete(info: ResourceInfo, id: UUID): Mono<Long> {
        val sql = "DELETE FROM ${info.tableName} WHERE ${info.pkColumn} = :id"
        return db.sql(sql).bind("id", id).fetch().rowsUpdated()
    }

    fun findDelta(
        info: ResourceInfo,
        versionSince: Long?,
        includeDeleted: Boolean,
        regionId: UUID?,
        carrierId: UUID?,
        limit: Int
    ): Flux<Map<String, Any?>> {
        val selectClause = info.selectColumns ?: "*"

        // Промпт 010: JOIN-based region filter для таблиц без собственного region_id.
        val useJoin = regionId != null && info.regionJoinClause != null
        val joinSql: String = if (useJoin) " ${info.regionJoinClause}" else ""

        // Все WHERE-условия и ORDER BY используют префикс таблицы (= info.tableName) во избежание
        // неоднозначности при JOIN. selectColumns может содержать как псевдо-префиксные, так и bare-колонки.
        val conditions = mutableListOf<String>()
        versionSince?.let { conditions += "${info.tableName}.version > :since" }
        if (!includeDeleted) conditions += "${info.tableName}.deleted_at IS NULL"
        if (regionId != null && !useJoin && info.tableName in REGION_ID_TABLES)
            conditions += "${info.tableName}.region_id = :regionId"
        if (carrierId != null && info.tableName in CARRIER_ID_TABLES)
            conditions += "${info.tableName}.carrier_id = :carrierId"
        val whereClause = if (conditions.isEmpty()) "" else conditions.joinToString(" AND ", prefix = "WHERE ")
        val sql = "SELECT $selectClause FROM ${info.tableName}$joinSql $whereClause ORDER BY ${info.tableName}.version ASC LIMIT :limit"
        var spec: DatabaseClient.GenericExecuteSpec = db.sql(sql).bind("limit", limit)
        versionSince?.let { spec = spec.bind("since", it) }
        if (useJoin) spec = spec.bind("regionId", regionId!!)
        if (regionId != null && !useJoin && info.tableName in REGION_ID_TABLES) spec = spec.bind("regionId", regionId)
        if (carrierId != null && info.tableName in CARRIER_ID_TABLES) spec = spec.bind("carrierId", carrierId)
        return spec.fetch().all()
    }

    private fun bindValue(
        spec: DatabaseClient.GenericExecuteSpec,
        key: String,
        value: Any?
    ): DatabaseClient.GenericExecuteSpec {
        return when (value) {
            null -> spec.bindNull(key, Any::class.java)
            is UUID -> spec.bind(key, value)
            is Number -> spec.bind(key, value)
            is Boolean -> spec.bind(key, value)
            is String -> spec.bind(key, value)
            is Instant -> spec.bind(key, value)
            is LocalDate -> spec.bind(key, value)
            is LocalTime -> spec.bind(key, value)
            else -> spec.bind(key, value.toString())
        }
    }
}
