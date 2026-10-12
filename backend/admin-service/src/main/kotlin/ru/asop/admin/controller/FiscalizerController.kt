package ru.asop.admin.controller

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
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import ru.asop.admin.config.DeltaSupport
import ru.asop.admin.model.FiscalizerEntity
import ru.asop.admin.repository.FiscalizerRepository
import ru.asop.common.util.UuidUtils
import java.time.Instant
import java.util.UUID

data class FiscalizerRequest(
    val fiscalizerCode: String,
    val fiscalizerName: String,
    val baseApiUrl: String? = null,
    val description: String? = null,
    val isActive: Boolean = true
)

data class FiscalizerResponse(
    val fiscalizerId: UUID,
    val fiscalizerCode: String,
    val fiscalizerName: String,
    val baseApiUrl: String?,
    val description: String?,
    val isActive: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
    val deletedAt: Instant?,
    val version: Long?
)

/**
 * CRUD + delta для ASOP_FISCALIZERS (словарь фискализаторов, глобальный справочник
 * в delta-flow оркестратора — base_api_url нужен терминалам для построения URL чека).
 */
@RestController
@RequestMapping("/api/v1/fiscalizers")
class FiscalizerController(
    private val repository: FiscalizerRepository,
    private val template: R2dbcEntityTemplate
) {

    @GetMapping
    fun list(): Mono<ResponseEntity<List<FiscalizerResponse>>> {
        return repository.findAll()
            .filter { it.deletedAt == null }
            .map { it.toResponse() }
            .collectList()
            .map { ResponseEntity.ok(it) }
    }

    @GetMapping("/delta")
    fun listDelta(
        @RequestParam(required = false) versionSince: Long?,
        @RequestParam(required = false) includeDeleted: Boolean,
        @RequestParam(required = false, defaultValue = "10000") limit: Int
    ): Flux<FiscalizerEntity> {
        return template.select(FiscalizerEntity::class.java)
            .matching(DeltaSupport.query(versionSince, includeDeleted, limit))
            .all()
    }

    @GetMapping("/{id}")
    fun get(@PathVariable id: UUID): Mono<ResponseEntity<FiscalizerResponse>> {
        return repository.findById(id)
            .map { ResponseEntity.ok(it.toResponse()) }
            .defaultIfEmpty(ResponseEntity.notFound().build())
    }

    @PostMapping
    fun create(@RequestBody request: FiscalizerRequest): Mono<ResponseEntity<FiscalizerResponse>> {
        validate(request)
        val entity = FiscalizerEntity(
            fiscalizerId = UuidUtils.newId(),
            fiscalizerCode = request.fiscalizerCode.trim(),
            fiscalizerName = request.fiscalizerName.trim(),
            baseApiUrl = request.baseApiUrl,
            description = request.description,
            isActive = request.isActive
        )
        return template.insert(entity)
            .map { ResponseEntity.status(HttpStatus.CREATED).body(it.toResponse()) }
    }

    @PutMapping("/{id}")
    fun update(@PathVariable id: UUID, @RequestBody request: FiscalizerRequest): Mono<ResponseEntity<FiscalizerResponse>> {
        validate(request)
        return repository.findById(id)
            .flatMap { existing ->
                val updated = existing.copy(
                    fiscalizerCode = request.fiscalizerCode.trim(),
                    fiscalizerName = request.fiscalizerName.trim(),
                    baseApiUrl = request.baseApiUrl,
                    description = request.description,
                    isActive = request.isActive
                )
                repository.save(updated).map { ResponseEntity.ok(it.toResponse()) }
            }
            .defaultIfEmpty(ResponseEntity.notFound().build())
    }

    @DeleteMapping("/{id}")
    fun delete(@PathVariable id: UUID): Mono<ResponseEntity<Void>> {
        return repository.findById(id)
            .filter { it.deletedAt == null }
            .flatMap { existing ->
                repository.save(existing.copy(deletedAt = Instant.now()))
                    .thenReturn(ResponseEntity.noContent().build<Void>())
            }
            .defaultIfEmpty(ResponseEntity.notFound().build())
    }

    private fun validate(request: FiscalizerRequest) {
        if (request.fiscalizerCode.isBlank()) {
            throw IllegalArgumentException("FISCALIZER_CODE не может быть пустым")
        }
        if (request.fiscalizerName.isBlank()) {
            throw IllegalArgumentException("FISCALIZER_NAME не может быть пустым")
        }
    }

    private fun FiscalizerEntity.toResponse() = FiscalizerResponse(
        fiscalizerId = fiscalizerId,
        fiscalizerCode = fiscalizerCode,
        fiscalizerName = fiscalizerName,
        baseApiUrl = baseApiUrl,
        description = description,
        isActive = isActive,
        createdAt = createdAt,
        updatedAt = updatedAt,
        deletedAt = deletedAt,
        version = version
    )
}
