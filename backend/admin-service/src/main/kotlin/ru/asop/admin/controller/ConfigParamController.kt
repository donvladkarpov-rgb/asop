package ru.asop.admin.controller

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate
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
                    val entity = ConfigParamEntity(
                        paramId = UuidUtils.newId(),
                        regionId = request.regionId,
                        organizerId = request.organizerId,
                        carrierId = request.carrierId,
                        cardsDistributorId = request.cardsDistributorId,
                        krsId = request.krsId,
                        params = objectMapper.writeValueAsString(request.params)
                    )
                    template.insert(entity).map { ResponseEntity.status(HttpStatus.CREATED).body(it.toResponse()) }
                }
            }
    }

    @PutMapping("/{id}")
    fun update(@PathVariable id: UUID, @RequestBody request: ConfigParamRequest): Mono<ResponseEntity<ConfigParamResponse>> {
        return repository.findById(id)
            .flatMap { existing ->
                val updated = existing.copy(
                    regionId = request.regionId,
                    organizerId = request.organizerId,
                    carrierId = request.carrierId,
                    cardsDistributorId = request.cardsDistributorId,
                    krsId = request.krsId,
                    params = objectMapper.writeValueAsString(request.params)
                )
                repository.save(updated).map { ResponseEntity.ok(it.toResponse()) }
            }
            .defaultIfEmpty(ResponseEntity.notFound().build())
    }

    @DeleteMapping("/{id}")
    fun delete(@PathVariable id: UUID): Mono<ResponseEntity<Void>> {
        return repository.findById(id)
            .flatMap { existing ->
                repository.save(existing.copy(deletedAt = java.time.Instant.now()))
                    .thenReturn(ResponseEntity.noContent().build<Void>())
            }
            .defaultIfEmpty(ResponseEntity.notFound().build())
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