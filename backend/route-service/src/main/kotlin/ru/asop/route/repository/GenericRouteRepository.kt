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
    fun list(info: ResourceInfo): Flux<Map<String, Any?>> {
        val selectClause = info.selectColumns ?: "*"
        val sql = "SELECT $selectClause FROM ${info.tableName} ORDER BY ${info.pkColumn} ASC"
        return db.sql(sql).fetch().all()
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
