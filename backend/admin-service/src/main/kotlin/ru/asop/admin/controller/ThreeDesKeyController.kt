package ru.asop.admin.controller

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import ru.asop.admin.config.DeltaSupport
import ru.asop.admin.model.ThreeDesKeyEntity
import ru.asop.admin.repository.ThreeDesKeyRepository
import ru.asop.common.util.UuidUtils
import org.springframework.web.reactive.function.client.WebClient
import java.util.UUID

data class ThreeDesKeyResponse(
    val keyId: UUID,
    val keyMaterial: String,
    val createdAt: java.time.Instant,
    val updatedAt: java.time.Instant,
    val deletedAt: java.time.Instant?,
    val version: Long?
)

/**
 * CRUD + delta для ASOP_3DES_KEYS.
 * KEY_MATERIAL в ответах всегда зашифрован (публичный ключ сервера). Добавление — генерация
 * через crypto-service. Удаление — только SOFT (DELETED_AT), физическое удаление запрещено.
 */
@RestController
@RequestMapping("/api/v1/three-des-keys")
class ThreeDesKeyController(
    private val repository: ThreeDesKeyRepository,
    private val template: R2dbcEntityTemplate,
    private val cryptoWebClient: org.springframework.web.reactive.function.client.WebClient
) {

    @GetMapping
    fun list(): Mono<ResponseEntity<List<ThreeDesKeyResponse>>> {
        return repository.findAll()
            .map { it.toResponse() }
            .collectList()
            .map { ResponseEntity.ok(it) }
    }

    @GetMapping("/delta")
    fun listDelta(
        @RequestParam(required = false) versionSince: Long?,
        @RequestParam(required = false) includeDeleted: Boolean,
        @RequestParam(required = false, defaultValue = "10000") limit: Int
    ): Flux<ThreeDesKeyEntity> {
        return template.select(ThreeDesKeyEntity::class.java)
            .matching(DeltaSupport.query(versionSince, includeDeleted, limit))
            .all()
    }

    @PostMapping
    fun generate(): Mono<ResponseEntity<ThreeDesKeyResponse>> {
        return generateFromCrypto()
            .flatMap { cipherBase64 ->
                val entity = ThreeDesKeyEntity(
                    keyId = UuidUtils.newId(),
                    keyMaterial = cipherBase64
                )
                template.insert(entity).map { ResponseEntity.status(HttpStatus.CREATED).body(it.toResponse()) }
            }
            .onErrorResume {
                Mono.just(ResponseEntity.status(HttpStatus.BAD_GATEWAY).build())
            }
    }

    // @Delete — soft delete через метку DELETED_AT
    @DeleteMapping("/{id}")
    fun delete(@PathVariable id: UUID): Mono<ResponseEntity<Void>> {
        return repository.findById(id)
            .flatMap { existing ->
                val softDelete = existing.copy(deletedAt = java.time.Instant.now())
                repository.save(softDelete).thenReturn(ResponseEntity.noContent().build<Void>())
            }
            .defaultIfEmpty(ResponseEntity.notFound().build())
    }

    private fun generateFromCrypto(): Mono<String> {
        val isDocker = System.getenv("ASOP_ENV") == "docker"
        val base = if (isDocker) "https://crypto-service:8081" else "https://localhost:8081"
        return cryptoWebClient.post()
            .uri("$base/api/v1/keys/generate")
            .retrieve()
            .bodyToMono(GenerateResponse::class.java)
            .map { it.cipherBase64 }
    }

    private fun ThreeDesKeyEntity.toResponse() = ThreeDesKeyResponse(
        keyId = keyId,
        keyMaterial = keyMaterial,
        createdAt = createdAt,
        updatedAt = updatedAt,
        deletedAt = deletedAt,
        version = version
    )
}

data class GenerateResponse(
    val keyId: String,
    val cipherBase64: String
)