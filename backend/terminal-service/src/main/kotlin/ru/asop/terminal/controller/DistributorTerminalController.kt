package ru.asop.terminal.controller

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
import ru.asop.common.util.UuidUtils
import ru.asop.terminal.model.DistributorTerminalEntity
import ru.asop.terminal.repository.DistributorTerminalRepository
import java.time.Instant
import java.util.UUID

data class DistributorTerminalRequest(
    val cardsDistributorId: UUID,
    val contractId: UUID? = null,
    val terminalNumber: String,
    val terminalSerial: String,
    val terminalModel: String? = null,
    val paymentProviderId: String,
    val status: String? = null,
    val molUserId: UUID? = null,
    val profileId: UUID? = null,
    val softwareVersionId: UUID? = null
)

/**
 * Регистрация серверным контуром дистрибьютора (mTLS sync, аналог TerminalRegister).
 * Upsert по TERMINAL_SERIAL — идемпотентно при re-provision.
 */
data class DistributorTerminalRegisterRequest(
    val terminalSerial: String,
    val terminalNumber: String? = null,
    val terminalModel: String? = null,
    val cardsDistributorId: UUID? = null,
    val contractId: UUID? = null,
    val paymentProviderId: String? = null,
    val status: String? = null,
    val molUserId: UUID? = null,
    val profileId: UUID? = null,
    val softwareVersionId: UUID? = null
)

data class DistributorTerminalResponse(
    val distributorTerminalId: UUID,
    val cardsDistributorId: UUID,
    val contractId: UUID?,
    val terminalNumber: String,
    val terminalSerial: String,
    val terminalModel: String?,
    val paymentProviderId: String,
    val status: String,
    val molUserId: UUID?,
    val profileId: UUID?,
    val softwareVersionId: UUID?,
    val createdAt: Instant,
    val updatedAt: Instant
)

/**
 * CRUD ASOP_DISTRIBUTOR_TERMINALS (платёжные терминалы дистрибьюторов).
 * Доступ через gateway sync-proxy (web-admin JWT / distributor-terminals resource).
 */
@RestController
@RequestMapping("/api/v1/distributor-terminals")
class DistributorTerminalController(
    private val repository: DistributorTerminalRepository,
    private val template: R2dbcEntityTemplate
) {

    @GetMapping
    fun list(
        @RequestParam(required = false) cardsDistributorId: UUID?
    ): Flux<DistributorTerminalResponse> {
        val flux = if (cardsDistributorId != null) {
            repository.findByCardsDistributorId(cardsDistributorId)
        } else {
            repository.findAll()
        }
        return flux.map { it.toResponse() }
    }

    @GetMapping("/{id}")
    fun get(@PathVariable id: UUID): Mono<ResponseEntity<DistributorTerminalResponse>> {
        return repository.findById(id)
            .map { ResponseEntity.ok(it.toResponse()) }
            .defaultIfEmpty(ResponseEntity.notFound().build())
    }

    /**
     * Идемпотентная регистрация/обновление терминала дистрибьютора по TERMINAL_SERIAL.
     * Для новой серии обязательны cardsDistributorId + paymentProviderId (NOT NULL в DDL);
     * terminalNumber при отсутствии = terminalSerial.
     */
    @PostMapping("/register")
    fun register(@RequestBody request: DistributorTerminalRegisterRequest): Mono<ResponseEntity<DistributorTerminalResponse>> {
        if (request.terminalSerial.isBlank()) {
            return Mono.just(ResponseEntity.badRequest().build())
        }
        return repository.findByTerminalSerial(request.terminalSerial)
            .next()
            .flatMap { existing ->
                val updated = existing.copy(
                    cardsDistributorId = request.cardsDistributorId ?: existing.cardsDistributorId,
                    contractId = request.contractId ?: existing.contractId,
                    terminalNumber = request.terminalNumber ?: existing.terminalNumber,
                    terminalModel = request.terminalModel ?: existing.terminalModel,
                    paymentProviderId = request.paymentProviderId ?: existing.paymentProviderId,
                    status = request.status ?: existing.status,
                    molUserId = request.molUserId ?: existing.molUserId,
                    profileId = request.profileId ?: existing.profileId,
                    softwareVersionId = request.softwareVersionId ?: existing.softwareVersionId,
                    updatedAt = Instant.now()
                )
                repository.save(updated).map { ResponseEntity.ok(it.toResponse()) }
            }
            .switchIfEmpty(
                Mono.defer {
                    val cardsId = request.cardsDistributorId
                    val provider = request.paymentProviderId
                    if (cardsId != null && !provider.isNullOrBlank()) {
                        val entity = DistributorTerminalEntity(
                            distributorTerminalId = UuidUtils.newId(),
                            cardsDistributorId = cardsId,
                            contractId = request.contractId,
                            terminalNumber = request.terminalNumber?.takeIf { it.isNotBlank() } ?: request.terminalSerial,
                            terminalSerial = request.terminalSerial,
                            terminalModel = request.terminalModel,
                            paymentProviderId = provider,
                            status = request.status ?: "WAREHOUSE",
                            molUserId = request.molUserId,
                            profileId = request.profileId,
                            softwareVersionId = request.softwareVersionId,
                            createdAt = Instant.now(),
                            updatedAt = Instant.now()
                        )
                        template.insert(entity).map {
                            ResponseEntity.status(HttpStatus.CREATED).body(it.toResponse())
                        }
                    } else {
                        Mono.just(ResponseEntity.status(HttpStatus.BAD_REQUEST).build())
                    }
                }
            )
    }

    @PostMapping
    fun create(@RequestBody request: DistributorTerminalRequest): Mono<ResponseEntity<DistributorTerminalResponse>> {
        val entity = request.toEntity(UuidUtils.newId(), Instant.now())
        return template.insert(entity)
            .map { ResponseEntity.status(HttpStatus.CREATED).body(it.toResponse()) }
    }

    @PutMapping("/{id}")
    fun update(@PathVariable id: UUID, @RequestBody request: DistributorTerminalRequest): Mono<ResponseEntity<DistributorTerminalResponse>> {
        return repository.findById(id)
            .flatMap { existing ->
                val updated = existing.copy(
                    cardsDistributorId = request.cardsDistributorId,
                    contractId = request.contractId,
                    terminalNumber = request.terminalNumber,
                    terminalSerial = request.terminalSerial,
                    terminalModel = request.terminalModel,
                    paymentProviderId = request.paymentProviderId,
                    status = request.status ?: existing.status,
                    molUserId = request.molUserId,
                    profileId = request.profileId,
                    softwareVersionId = request.softwareVersionId,
                    updatedAt = Instant.now()
                )
                repository.save(updated).map { ResponseEntity.ok(it.toResponse()) }
            }
            .defaultIfEmpty(ResponseEntity.notFound().build())
    }

    @DeleteMapping("/{id}")
    fun delete(@PathVariable id: UUID): Mono<ResponseEntity<Void>> {
        return repository.deleteById(id).thenReturn(ResponseEntity.noContent().build())
    }

    private fun DistributorTerminalRequest.toEntity(id: UUID, now: Instant) = DistributorTerminalEntity(
        distributorTerminalId = id,
        cardsDistributorId = cardsDistributorId,
        contractId = contractId,
        terminalNumber = terminalNumber,
        terminalSerial = terminalSerial,
        terminalModel = terminalModel,
        paymentProviderId = paymentProviderId,
        status = status ?: "WAREHOUSE",
        molUserId = molUserId,
        profileId = profileId,
        softwareVersionId = softwareVersionId,
        createdAt = now,
        updatedAt = now
    )

    private fun DistributorTerminalEntity.toResponse() = DistributorTerminalResponse(
        distributorTerminalId = distributorTerminalId,
        cardsDistributorId = cardsDistributorId,
        contractId = contractId,
        terminalNumber = terminalNumber,
        terminalSerial = terminalSerial,
        terminalModel = terminalModel,
        paymentProviderId = paymentProviderId,
        status = status,
        molUserId = molUserId,
        profileId = profileId,
        softwareVersionId = softwareVersionId,
        createdAt = createdAt,
        updatedAt = updatedAt
    )
}