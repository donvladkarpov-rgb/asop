package ru.asop.admin.controller

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate
import org.springframework.data.relational.core.query.Criteria
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
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import ru.asop.admin.config.DeltaSupport
import ru.asop.admin.model.DistributorFiscalizerEntity
import ru.asop.admin.repository.DistributorFiscalizerRepository
import ru.asop.common.util.UuidUtils
import java.time.Instant
import java.util.UUID

data class DistributorFiscalizerRequest(
    val cardsDistributorId: UUID,
    val fiscalizerId: UUID,
    val contractId: UUID,
    val customApiUrl: String? = null,
    val isPrimary: Boolean = false,
    val isActive: Boolean = true,
    val settings: Map<String, Any?>? = null
)

data class DistributorFiscalizerResponse(
    val distributorFiscalizerId: UUID,
    val cardsDistributorId: UUID,
    val fiscalizerId: UUID,
    val contractId: UUID,
    val customApiUrl: String?,
    val isPrimary: Boolean,
    val isActive: Boolean,
    val settings: Map<String, Any?>,
    val createdAt: Instant,
    val updatedAt: Instant,
    val deletedAt: Instant?,
    val version: Long?
)

/**
 * CRUD + delta для ASOP_DISTRIBUTOR_FISCALIZERS (настройки фискализации дистрибьютора).
 * Не участвует в delta-flow оркестратора — дистрибьюторское приложение тянет
 * JSON-pull'ом через gateway `/api/v1/sync/distributor/distributor-fiscalizers/delta`.
 */
@RestController
@RequestMapping("/api/v1/distributor-fiscalizers")
class DistributorFiscalizerController(
    private val repository: DistributorFiscalizerRepository,
    private val template: R2dbcEntityTemplate,
    private val objectMapper: ObjectMapper
) {

    @GetMapping
    fun list(
        @RequestParam(required = false) cardsDistributorId: UUID?
    ): Mono<ResponseEntity<List<DistributorFiscalizerResponse>>> {
        val source = cardsDistributorId?.let { repository.findByCardsDistributorId(it) } ?: repository.findAll()
        return source
            .filter { it.deletedAt == null }
            .map { it.toResponse() }
            .collectList()
            .map { ResponseEntity.ok(it) }
    }

    @GetMapping("/delta")
    fun listDelta(
        @RequestParam(required = false) versionSince: Long?,
        @RequestParam(required = false) includeDeleted: Boolean,
        @RequestParam(required = false, defaultValue = "10000") limit: Int,
        @RequestParam(required = false) cardsDistributorId: UUID?
    ): Flux<DistributorFiscalizerEntity> {
        val extra = cardsDistributorId?.let { Criteria.where("cards_distributor_id").`is`(it) }
        return template.select(DistributorFiscalizerEntity::class.java)
            .matching(DeltaSupport.query(versionSince, includeDeleted, limit, extra))
            .all()
    }

    @GetMapping("/{id}")
    fun get(@PathVariable id: UUID): Mono<ResponseEntity<DistributorFiscalizerResponse>> {
        return repository.findById(id)
            .map { ResponseEntity.ok(it.toResponse()) }
            .defaultIfEmpty(ResponseEntity.notFound().build())
    }

    @PostMapping
    fun create(@RequestBody request: DistributorFiscalizerRequest): Mono<ResponseEntity<DistributorFiscalizerResponse>> {
        validate(request)
        val id = UuidUtils.newId()
        val now = Instant.now()
        var spec = template.databaseClient.sql(
            """
            INSERT INTO ASOP_DISTRIBUTOR_FISCALIZERS
              (distributor_fiscalizer_id, cards_distributor_id, fiscalizer_id, contract_id, custom_api_url,
               is_primary, is_active, settings, created_at, updated_at)
            VALUES (:id, :cardsDistributorId, :fiscalizerId, :contractId, :customApiUrl,
               :isPrimary, :isActive, CAST(:settings AS jsonb), :createdAt, :updatedAt)
            """.trimIndent()
        )
            .bind("id", id)
            .bind("cardsDistributorId", request.cardsDistributorId)
            .bind("fiscalizerId", request.fiscalizerId)
            .bind("contractId", request.contractId)
            .bind("isPrimary", request.isPrimary)
            .bind("isActive", request.isActive)
            .bind("createdAt", now)
            .bind("updatedAt", now)
        spec = bindNullable(spec, "customApiUrl", request.customApiUrl)
        spec = bindNullable(spec, "settings", request.settings?.let { objectMapper.writeValueAsString(it) })
        return spec.fetch().rowsUpdated()
            .then(repository.findById(id))
            .map { ResponseEntity.status(HttpStatus.CREATED).body(it.toResponse()) }
    }

    @PutMapping("/{id}")
    fun update(
        @PathVariable id: UUID,
        @RequestBody request: DistributorFiscalizerRequest
    ): Mono<ResponseEntity<DistributorFiscalizerResponse>> {
        validate(request)
        return repository.findById(id)
            .filter { it.deletedAt == null }
            .flatMap {
                var spec = template.databaseClient.sql(
                    """
                    UPDATE ASOP_DISTRIBUTOR_FISCALIZERS SET
                      cards_distributor_id = :cardsDistributorId, fiscalizer_id = :fiscalizerId, contract_id = :contractId,
                      custom_api_url = :customApiUrl, is_primary = :isPrimary, is_active = :isActive,
                      settings = CAST(:settings AS jsonb), updated_at = :updatedAt
                    WHERE distributor_fiscalizer_id = :id AND deleted_at IS NULL
                    """.trimIndent()
                )
                    .bind("id", id)
                    .bind("cardsDistributorId", request.cardsDistributorId)
                    .bind("fiscalizerId", request.fiscalizerId)
                    .bind("contractId", request.contractId)
                    .bind("isPrimary", request.isPrimary)
                    .bind("isActive", request.isActive)
                    .bind("updatedAt", Instant.now())
                spec = bindNullable(spec, "customApiUrl", request.customApiUrl)
                spec = bindNullable(spec, "settings", request.settings?.let { objectMapper.writeValueAsString(it) })
                spec.fetch().rowsUpdated().then(repository.findById(id))
                    .map { ResponseEntity.ok(it.toResponse()) }
            }
            .defaultIfEmpty(ResponseEntity.notFound().build())
    }

    @DeleteMapping("/{id}")
    fun delete(@PathVariable id: UUID): Mono<ResponseEntity<Void>> {
        val now = Instant.now()
        return template.databaseClient.sql(
            "UPDATE ASOP_DISTRIBUTOR_FISCALIZERS SET deleted_at = :now, updated_at = :now " +
                "WHERE distributor_fiscalizer_id = :id AND deleted_at IS NULL"
        )
            .bind("id", id)
            .bind("now", now)
            .fetch().rowsUpdated()
            .map { updated ->
                if (updated > 0) ResponseEntity.noContent().build<Void>()
                else ResponseEntity.notFound().build()
            }
    }

    private fun bindNullable(
        spec: DatabaseClient.GenericExecuteSpec,
        name: String,
        value: String?
    ): DatabaseClient.GenericExecuteSpec =
        if (value != null) spec.bind(name, value) else spec.bindNull(name, String::class.java)

    private fun validate(request: DistributorFiscalizerRequest) {
        if (request.customApiUrl != null && request.customApiUrl.length > 500) {
            throw IllegalArgumentException("CUSTOM_API_URL не длиннее 500 символов")
        }
    }

    private fun DistributorFiscalizerEntity.toResponse() = DistributorFiscalizerResponse(
        distributorFiscalizerId = distributorFiscalizerId,
        cardsDistributorId = cardsDistributorId,
        fiscalizerId = fiscalizerId,
        contractId = contractId,
        customApiUrl = customApiUrl,
        isPrimary = isPrimary,
        isActive = isActive,
        settings = settings?.let { settingsToMap(it) } ?: emptyMap(),
        createdAt = createdAt,
        updatedAt = updatedAt,
        deletedAt = deletedAt,
        version = version
    )

    private fun settingsToMap(json: String): Map<String, Any?> {
        @Suppress("UNCHECKED_CAST")
        return objectMapper.readValue(json, Any::class.java) as Map<String, Any?>
    }
}
