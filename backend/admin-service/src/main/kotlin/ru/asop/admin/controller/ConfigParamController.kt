package ru.asop.admin.controller

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Mono
import ru.asop.admin.model.ConfigParamEntity
import ru.asop.admin.repository.ConfigParamRepository
import ru.asop.common.util.UuidUtils
import java.time.Instant
import java.util.UUID

data class ConfigParamRequest(
    val regionId: UUID? = null,
    val organizerId: UUID? = null,
    val carrierId: UUID? = null,
    val cardsDistributorId: UUID? = null,
    val krsId: UUID? = null,
    val params: Map<String, Any?>
)

data class ConfigParamResponse(
    val paramId: UUID,
    val regionId: UUID?,
    val organizerId: UUID?,
    val carrierId: UUID?,
    val cardsDistributorId: UUID?,
    val krsId: UUID?,
    val params: Map<String, Any?>,
    val createdAt: java.time.Instant,
    val updatedAt: java.time.Instant,
    val deletedAt: java.time.Instant?,
    val version: Long?
)

/**
 * CRUD + разрешение иерархии параметров ASOP_CONFIG_PARAMS (серверная, на терминалы НЕ синкается).
 * Иерархия: base (все scope NULL) → region → organizer → carrier → distributor → krs.
 * Более глубокий scope перекрывает нижние; отсутствующие ключи берутся из нижнего уровня.
 */
@RestController
@RequestMapping("/api/v1/config-params")
class ConfigParamController(
    private val repository: ConfigParamRepository,
    private val template: R2dbcEntityTemplate,
    private val objectMapper: ObjectMapper
) {

    @GetMapping
    fun list(): Mono<ResponseEntity<List<ConfigParamResponse>>> {
        return repository.findAll()
            .map { it.toResponse() }
            .collectList()
            .map { ResponseEntity.ok(it) }
    }

    @GetMapping("/resolved")
    fun resolved(
        @RequestParam(required = false) regionId: UUID?,
        @RequestParam(required = false) organizerId: UUID?,
        @RequestParam(required = false) carrierId: UUID?,
        @RequestParam(required = false) cardsDistributorId: UUID?,
        @RequestParam(required = false) krsId: UUID?
    ): Mono<ResponseEntity<Map<String, Any?>>> {
        return repository.findAll()
            .filter { it.deletedAt == null }
            .collectList()
            .map { rows -> resolveParams(rows, regionId, organizerId, carrierId, cardsDistributorId, krsId) }
            .map { ResponseEntity.ok(it) }
    }

    @GetMapping("/base")
    fun base(): Mono<ResponseEntity<Map<String, Any?>>> {
        return repository.findBaseRow()
            .map { jsonToMap(it.params).toMap() }
            .defaultIfEmpty(emptyMap())
            .map { ResponseEntity.ok(it) }
    }

    @GetMapping("/{id}")
    fun get(@PathVariable id: UUID): Mono<ResponseEntity<ConfigParamResponse>> {
        return repository.findById(id)
            .map { ResponseEntity.ok(it.toResponse()) }
            .defaultIfEmpty(ResponseEntity.notFound().build())
    }

    @PostMapping
    fun create(@RequestBody request: ConfigParamRequest): Mono<ResponseEntity<ConfigParamResponse>> {
        return repository.findAll()
            .any { it.deletedAt == null && sameScope(it, request) }
            .flatMap { duplicate ->
                if (duplicate) {
                    Mono.just(ResponseEntity.status(HttpStatus.CONFLICT).body<ConfigParamResponse>(null))
                } else {
                    val id = UuidUtils.newId()
                    val now = Instant.now()
                    // Raw SQL: PARAMS — JSONB, явный CAST(:params AS jsonb) (иначе String → varchar → ошибка).
                    var spec = template.databaseClient.sql(
                        """
                        INSERT INTO ASOP_CONFIG_PARAMS
                          (param_id, region_id, organizer_id, carrier_id, cards_distributor_id, krs_id,
                           params, created_at, updated_at)
                        VALUES (:id, :regionId, :organizerId, :carrierId, :cardsDistributorId, :krsId,
                           CAST(:params AS jsonb), :createdAt, :updatedAt)
                        """.trimIndent()
                    )
                        .bind("id", id)
                        .bind("params", objectMapper.writeValueAsString(request.params))
                        .bind("createdAt", now)
                        .bind("updatedAt", now)
                    spec = bindUuidNullable(spec, "regionId", request.regionId)
                    spec = bindUuidNullable(spec, "organizerId", request.organizerId)
                    spec = bindUuidNullable(spec, "carrierId", request.carrierId)
                    spec = bindUuidNullable(spec, "cardsDistributorId", request.cardsDistributorId)
                    spec = bindUuidNullable(spec, "krsId", request.krsId)
                    spec.fetch().rowsUpdated()
                        .then(repository.findById(id))
                        .map { ResponseEntity.status(HttpStatus.CREATED).body(it.toResponse()) }
                }
            }
    }

    @PutMapping("/{id}")
    fun update(@PathVariable id: UUID, @RequestBody request: ConfigParamRequest): Mono<ResponseEntity<ConfigParamResponse>> {
        return repository.findById(id)
            .filter { it.deletedAt == null }
            .flatMap {
                var spec = template.databaseClient.sql(
                    """
                    UPDATE ASOP_CONFIG_PARAMS SET
                      region_id = :regionId, organizer_id = :organizerId, carrier_id = :carrierId,
                      cards_distributor_id = :cardsDistributorId, krs_id = :krsId,
                      params = CAST(:params AS jsonb), updated_at = :updatedAt
                    WHERE param_id = :id AND deleted_at IS NULL
                    """.trimIndent()
                )
                    .bind("id", id)
                    .bind("params", objectMapper.writeValueAsString(request.params))
                    .bind("updatedAt", Instant.now())
                spec = bindUuidNullable(spec, "regionId", request.regionId)
                spec = bindUuidNullable(spec, "organizerId", request.organizerId)
                spec = bindUuidNullable(spec, "carrierId", request.carrierId)
                spec = bindUuidNullable(spec, "cardsDistributorId", request.cardsDistributorId)
                spec = bindUuidNullable(spec, "krsId", request.krsId)
                spec.fetch().rowsUpdated().then(repository.findById(id))
                    .map { ResponseEntity.ok(it.toResponse()) }
            }
            .defaultIfEmpty(ResponseEntity.notFound().build())
    }

    @DeleteMapping("/{id}")
    fun delete(@PathVariable id: UUID): Mono<ResponseEntity<Void>> {
        val now = Instant.now()
        // Raw SQL (вместо repository.save): не трогаем PARAMS (JSONB) — только soft-delete.
        return template.databaseClient.sql(
            "UPDATE ASOP_CONFIG_PARAMS SET deleted_at = :now, updated_at = :now " +
                "WHERE param_id = :id AND deleted_at IS NULL"
        )
            .bind("id", id)
            .bind("now", now)
            .fetch().rowsUpdated()
            .map { updated ->
                if (updated > 0) ResponseEntity.noContent().build<Void>()
                else ResponseEntity.notFound().build()
            }
    }

    /**
     * Разрешение по цепочке base(scope null) → region → organizer → carrier → distributor → krs.
     * Стартуем с base и пошагово накладываем строки уровней, присутствующих в запросе.
     */
    private fun resolveParams(
        rows: List<ConfigParamEntity>,
        regionId: UUID?,
        organizerId: UUID?,
        carrierId: UUID?,
        cardsDistributorId: UUID?,
        krsId: UUID?
    ): Map<String, Any?> {
        var result: Map<String, Any?> = emptyMap()

        val base = rows.firstOrNull { isBaseScope(it) }
        if (base != null) result = nowJson(base)

        regionId?.let { id -> rows.firstOrNull { it.regionId == id && isRegionScope(it) } }
            ?.let { result = merge(result, nowJson(it)) }
        organizerId?.let { id -> rows.firstOrNull { it.organizerId == id && isOrganizerScope(it) } }
            ?.let { result = merge(result, nowJson(it)) }
        carrierId?.let { id -> rows.firstOrNull { it.carrierId == id && isCarrierScope(it) } }
            ?.let { result = merge(result, nowJson(it)) }
        cardsDistributorId?.let { id -> rows.firstOrNull { it.cardsDistributorId == id && isDistributorScope(it) } }
            ?.let { result = merge(result, nowJson(it)) }
        krsId?.let { id -> rows.firstOrNull { it.krsId == id && isKrsScope(it) } }
            ?.let { result = merge(result, nowJson(it)) }

        return result
    }

    private fun isBaseScope(e: ConfigParamEntity) =
        e.regionId == null && e.organizerId == null && e.carrierId == null && e.cardsDistributorId == null && e.krsId == null

    private fun isRegionScope(e: ConfigParamEntity) = e.regionId != null && e.organizerId == null &&
        e.carrierId == null && e.cardsDistributorId == null && e.krsId == null

    private fun isKrsScope(e: ConfigParamEntity) = e.regionId == null && e.organizerId == null &&
        e.carrierId == null && e.cardsDistributorId == null && e.krsId != null

    private fun isCarrierScope(e: ConfigParamEntity) = e.regionId == null && e.organizerId == null &&
        e.carrierId != null && e.cardsDistributorId == null && e.krsId == null

    private fun isDistributorScope(e: ConfigParamEntity) = e.regionId == null && e.organizerId == null &&
        e.carrierId == null && e.cardsDistributorId != null && e.krsId == null

    private fun isOrganizerScope(e: ConfigParamEntity) = e.regionId == null && e.organizerId != null &&
        e.carrierId == null && e.cardsDistributorId == null && e.krsId == null

    private fun merge(base: Map<String, Any?>, over: MutableMap<String, Any?>): Map<String, Any?> {
        val merged = base.toMutableMap()
        for ((k, v) in over) {
            merged[k] = v
        }
        return merged
    }

    private fun jsonToMap(json: String): MutableMap<String, Any?> {
        @Suppress("UNCHECKED_CAST")
        return (objectMapper.readValue<Any>(json, Any::class.java) as Map<String, Any?>).toMutableMap()
    }

    private fun sameScope(entity: ConfigParamEntity, request: ConfigParamRequest): Boolean {
        return (entity.regionId == request.regionId &&
            entity.organizerId == request.organizerId &&
            entity.carrierId == request.carrierId &&
            entity.cardsDistributorId == request.cardsDistributorId &&
            entity.krsId == request.krsId)
    }

    private fun bindUuidNullable(
        spec: DatabaseClient.GenericExecuteSpec,
        name: String,
        value: UUID?
    ): DatabaseClient.GenericExecuteSpec =
        if (value != null) spec.bind(name, value) else spec.bindNull(name, UUID::class.java)

    private fun nowJson(e: ConfigParamEntity): MutableMap<String, Any?> = jsonToMap(e.params)

    private fun ConfigParamEntity.toResponse() = ConfigParamResponse(
        paramId = paramId,
        regionId = regionId,
        organizerId = organizerId,
        carrierId = carrierId,
        cardsDistributorId = cardsDistributorId,
        krsId = krsId,
        params = jsonToMap(params),
        createdAt = createdAt,
        updatedAt = updatedAt,
        deletedAt = deletedAt,
        version = version
    )
}