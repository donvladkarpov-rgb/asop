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
import ru.asop.admin.model.FiscalizerTokenEntity
import ru.asop.admin.repository.FiscalizerTokenRepository
import ru.asop.admin.util.TokenCipher
import ru.asop.common.util.UuidUtils
import java.time.Instant
import java.util.UUID

data class FiscalizerTokenRequest(
    val carrierFiscalizerId: UUID? = null,
    val distributorFiscalizerId: UUID? = null,
    /** Plaintext токена — только на запись, в ответах не возвращается. */
    val token: String? = null,
    val validFrom: Instant? = null,
    val validUntil: Instant? = null,
    val status: String? = null
)

data class FiscalizerTokenResponse(
    val tokenId: UUID,
    val carrierFiscalizerId: UUID?,
    val distributorFiscalizerId: UUID?,
    val tokenHint: String?,
    val validFrom: Instant,
    val validUntil: Instant,
    val status: String,
    val createdAt: Instant,
    val updatedAt: Instant
)

/**
 * CRUD для ASOP_FISCALIZER_TOKENS (секретные токены API фискализаторов).
 * Токен шифруется AES-GCM при сохранении ([TokenCipher]), в ответах — только
 * tokenHint (`****1234`). НЕ участвует в дельта-синхронизации (секреты не покидают
 * сервер — решение 2026-10-08). Таблица без VERSION/DELETED_AT → физическое удаление.
 */
@RestController
@RequestMapping("/api/v1/fiscalizer-tokens")
class FiscalizerTokenController(
    private val repository: FiscalizerTokenRepository,
    private val cipher: TokenCipher,
    private val template: R2dbcEntityTemplate
) {

    @GetMapping
    fun list(
        @RequestParam(required = false) carrierFiscalizerId: UUID?,
        @RequestParam(required = false) distributorFiscalizerId: UUID?
    ): Mono<ResponseEntity<List<FiscalizerTokenResponse>>> {
        val source = when {
            carrierFiscalizerId != null -> repository.findByCarrierFiscalizerId(carrierFiscalizerId)
            distributorFiscalizerId != null -> repository.findByDistributorFiscalizerId(distributorFiscalizerId)
            else -> repository.findAll()
        }
        return source.map { it.toResponse() }.collectList().map { ResponseEntity.ok(it) }
    }

    @GetMapping("/{id}")
    fun get(@PathVariable id: UUID): Mono<ResponseEntity<FiscalizerTokenResponse>> {
        return repository.findById(id)
            .map { ResponseEntity.ok(it.toResponse()) }
            .defaultIfEmpty(ResponseEntity.notFound().build())
    }

    @PostMapping
    fun create(@RequestBody request: FiscalizerTokenRequest): Mono<ResponseEntity<FiscalizerTokenResponse>> {
        validateLink(request)
        val token = request.token?.trim()
        if (token.isNullOrBlank()) {
            return Mono.error(IllegalArgumentException("TOKEN не может быть пустым"))
        }
        validateDates(request.validFrom, request.validUntil)
        validateStatus(request.status)
        val now = Instant.now()
        val entity = FiscalizerTokenEntity(
            tokenId = UuidUtils.newId(),
            carrierFiscalizerId = request.carrierFiscalizerId,
            distributorFiscalizerId = request.distributorFiscalizerId,
            tokenEncrypted = cipher.encrypt(token),
            tokenHint = hintOf(token),
            validFrom = request.validFrom ?: now,
            validUntil = request.validUntil ?: now.plusSeconds(365L * 24 * 3600),
            status = request.status ?: "ACTIVE",
            createdAt = now,
            updatedAt = now
        )
        return template.insert(entity)
            .map { ResponseEntity.status(HttpStatus.CREATED).body(it.toResponse()) }
    }

    @PutMapping("/{id}")
    fun update(
        @PathVariable id: UUID,
        @RequestBody request: FiscalizerTokenRequest
    ): Mono<ResponseEntity<FiscalizerTokenResponse>> {
        validateDates(request.validFrom, request.validUntil)
        validateStatus(request.status)
        // Если пришла хотя бы одна ссылка — перепривязываем токен (ровно одна из двух).
        val linkProvided = request.carrierFiscalizerId != null || request.distributorFiscalizerId != null
        if (linkProvided) validateLink(request)
        return repository.findById(id)
            .flatMap { existing ->
                val token = request.token?.trim()
                if (token != null && token.isEmpty()) {
                    throw IllegalArgumentException("TOKEN не может быть пустым")
                }
                val updated = existing.copy(
                    carrierFiscalizerId = if (linkProvided) request.carrierFiscalizerId else existing.carrierFiscalizerId,
                    distributorFiscalizerId = if (linkProvided) request.distributorFiscalizerId else existing.distributorFiscalizerId,
                    tokenEncrypted = token?.let { cipher.encrypt(it) } ?: existing.tokenEncrypted,
                    tokenHint = token?.let { hintOf(it) } ?: existing.tokenHint,
                    validFrom = request.validFrom ?: existing.validFrom,
                    validUntil = request.validUntil ?: existing.validUntil,
                    status = request.status ?: existing.status,
                    updatedAt = Instant.now()
                )
                if (!updated.validUntil.isAfter(updated.validFrom)) {
                    throw IllegalArgumentException("VALID_UNTIL должен быть позже VALID_FROM")
                }
                repository.save(updated)
                    .map { ResponseEntity.ok(it.toResponse()) }
            }
            .switchIfEmpty(Mono.just(ResponseEntity.notFound().build<FiscalizerTokenResponse>()))
    }

    @DeleteMapping("/{id}")
    fun delete(@PathVariable id: UUID): Mono<ResponseEntity<Void>> {
        return repository.findById(id)
            .flatMap { repository.deleteById(id).thenReturn(ResponseEntity.noContent().build<Void>()) }
            .defaultIfEmpty(ResponseEntity.notFound().build())
    }

    /** chk_ft_link: ровно одна из двух ссылок. */
    private fun validateLink(request: FiscalizerTokenRequest) {
        val links = listOfNotNull(request.carrierFiscalizerId, request.distributorFiscalizerId)
        if (links.size != 1) {
            throw IllegalArgumentException("Токен должен быть привязан ровно к одной связи (carrier или distributor)")
        }
    }

    private fun validateDates(validFrom: Instant?, validUntil: Instant?) {
        if (validFrom != null && validUntil != null && !validUntil.isAfter(validFrom)) {
            throw IllegalArgumentException("VALID_UNTIL должен быть позже VALID_FROM")
        }
    }

    private fun validateStatus(status: String?) {
        if (status != null && status !in setOf("ACTIVE", "EXPIRED", "REVOKED")) {
            throw IllegalArgumentException("STATUS должен быть ACTIVE, EXPIRED или REVOKED")
        }
    }

    private fun hintOf(token: String): String = "****" + token.takeLast(4)

    private fun FiscalizerTokenEntity.toResponse() = FiscalizerTokenResponse(
        tokenId = tokenId,
        carrierFiscalizerId = carrierFiscalizerId,
        distributorFiscalizerId = distributorFiscalizerId,
        tokenHint = tokenHint,
        validFrom = validFrom,
        validUntil = validUntil,
        status = status,
        createdAt = createdAt,
        updatedAt = updatedAt
    )
}
