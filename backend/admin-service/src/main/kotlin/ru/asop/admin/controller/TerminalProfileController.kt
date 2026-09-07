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
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import ru.asop.admin.config.DeltaSupport
import ru.asop.admin.model.TerminalProfileEntity
import ru.asop.admin.repository.TerminalProfileRepository
import ru.asop.common.util.UuidUtils
import java.util.UUID

data class TerminalProfileRequest(
    val profileName: String,
    val profileParams: Map<String, Any?>? = null,
    val isBase: Boolean = false
)

data class TerminalProfileResponse(
    val profileId: UUID,
    val profileName: String,
    val profileParams: Map<String, Any?>,
    val isBase: Boolean,
    val createdAt: java.time.Instant,
    val updatedAt: java.time.Instant,
    val deletedAt: java.time.Instant?,
    val version: Long?
)

/**
 * CRUD + delta для ASOP_TERMINAL_PROFILES (справочник профилей настроек терминалов,
 * участвует в дельта-синхронизации — доставляется терминалам).
 * PROFILE_PARAMS — JSONB (в модели String, сериализуется ObjectMapper).
 * IS_BASE — признак базового профиля: активный базовый профиль может быть ровно один
 * (уникальный частичный индекс). При сохранении профиля с IS_BASE=TRUE остальные
 * активные базовые снимаются (IS_BASE=FALSE, VERSION bump для дельты).
 */
@RestController
@RequestMapping("/api/v1/terminal-profiles")
class TerminalProfileController(
    private val repository: TerminalProfileRepository,
    private val template: R2dbcEntityTemplate,
    private val objectMapper: ObjectMapper
) {

    @GetMapping
    fun list(): Mono<ResponseEntity<List<TerminalProfileResponse>>> {
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
    ): Flux<TerminalProfileEntity> {
        return template.select(TerminalProfileEntity::class.java)
            .matching(DeltaSupport.query(versionSince, includeDeleted, limit))
            .all()
    }

    @GetMapping("/{id}")
    fun get(@PathVariable id: UUID): Mono<ResponseEntity<TerminalProfileResponse>> {
        return repository.findById(id)
            .map { ResponseEntity.ok(it.toResponse()) }
            .defaultIfEmpty(ResponseEntity.notFound().build())
    }

    @PostMapping
    fun create(@RequestBody request: TerminalProfileRequest): Mono<ResponseEntity<TerminalProfileResponse>> {
        validate(request)
        val entity = TerminalProfileEntity(
            profileId = UuidUtils.newId(),
            profileName = request.profileName,
            profileParams = request.profileParams?.let { objectMapper.writeValueAsString(it) },
            isBase = request.isBase
        )
        return clearOtherBases(entity).then(template.insert(entity))
            .map { ResponseEntity.status(HttpStatus.CREATED).body(it.toResponse()) }
    }

    @PutMapping("/{id}")
    fun update(@PathVariable id: UUID, @RequestBody request: TerminalProfileRequest): Mono<ResponseEntity<TerminalProfileResponse>> {
        validate(request)
        return repository.findById(id)
            .flatMap { existing ->
                val updated = existing.copy(
                    profileName = request.profileName,
                    profileParams = request.profileParams?.let { objectMapper.writeValueAsString(it) },
                    isBase = request.isBase
                )
                clearOtherBases(updated).then(repository.save(updated))
                    .map { ResponseEntity.ok(it.toResponse()) }
            }
            .defaultIfEmpty(ResponseEntity.notFound().build())
    }

    @DeleteMapping("/{id}")
    fun delete(@PathVariable id: UUID): Mono<ResponseEntity<Void>> {
        return repository.findById(id)
            .flatMap { existing ->
                if (existing.isBase && existing.deletedAt == null) {
                    Mono.error(IllegalArgumentException("Нельзя удалить базовый профиль терминала"))
                } else {
                    repository.save(existing.copy(deletedAt = java.time.Instant.now()))
                        .thenReturn(ResponseEntity.noContent().build<Void>())
                }
            }
            .defaultIfEmpty(ResponseEntity.notFound().build())
    }

    private fun validate(request: TerminalProfileRequest) {
        if (request.profileName.isBlank()) {
            throw IllegalArgumentException("PROFILE_NAME не может быть пустым")
        }
    }

    /** Снимает IS_BASE с остальных активных базовых профилей (с VERSION bump для дельты). */
    private fun clearOtherBases(self: TerminalProfileEntity): Mono<Void> {
        if (!self.isBase) return Mono.empty()
        return template.databaseClient.sql(
            "UPDATE ASOP_TERMINAL_PROFILES SET IS_BASE = FALSE, UPDATED_AT = now(), VERSION = nextval('asop_delta_version_seq') " +
                "WHERE IS_BASE = TRUE AND DELETED_AT IS NULL AND PROFILE_ID <> :profileId"
        ).bind("profileId", self.profileId).then()
    }

    private fun TerminalProfileEntity.toResponse() = TerminalProfileResponse(
        profileId = profileId,
        profileName = profileName,
        profileParams = profileParams?.let { paramsToMap(it) } ?: emptyMap(),
        isBase = isBase,
        createdAt = createdAt,
        updatedAt = updatedAt,
        deletedAt = deletedAt,
        version = version
    )

    private fun paramsToMap(json: String): Map<String, Any?> {
        @Suppress("UNCHECKED_CAST")
        return objectMapper.readValue(json, Any::class.java) as Map<String, Any?>
    }
}