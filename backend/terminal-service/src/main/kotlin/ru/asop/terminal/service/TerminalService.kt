package ru.asop.terminal.service

import org.springframework.data.r2dbc.core.R2dbcEntityTemplate
import org.springframework.stereotype.Service
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import ru.asop.api.terminal.dto.request.TerminalRegisterRequest
import ru.asop.api.terminal.dto.request.TerminalStatusChangeRequest
import ru.asop.api.terminal.dto.response.TerminalRegisterResponse
import ru.asop.api.terminal.dto.response.TerminalResponse
import ru.asop.terminal.model.TerminalEntity
import ru.asop.terminal.repository.TerminalRepository
import ru.asop.terminal.repository.TerminalProfileRepository
import ru.asop.common.util.UuidUtils
import java.time.Instant
import java.util.UUID
import java.util.Optional

@Service
class TerminalService(
    private val terminalRepository: TerminalRepository,
    private val terminalProfileRepository: TerminalProfileRepository,
    private val r2dbcTemplate: R2dbcEntityTemplate
) {

    fun list(carrierId: UUID?, regionId: UUID?): Flux<TerminalResponse> {
        val entities = when {
            carrierId != null -> terminalRepository.findByCarrierId(carrierId)
            regionId != null -> terminalRepository.findByRegionId(regionId)
            else -> terminalRepository.findAll()
        }
        return entities.map { it.toResponse() }
    }

    fun register(request: TerminalRegisterRequest): Mono<TerminalRegisterResponse> {
        val now = Instant.now()

        return resolveProfileId(request)
            .flatMap { profileId ->
                val effectiveProfileId = request.profileId ?: profileId
                resolveTerminal(request, effectiveProfileId)
                    .flatMap { entity ->
                        val updated = entity.copy(
                            terminalSerial = request.terminalSerial,
                            terminalNumber = request.terminalNumber,
                            terminalModel = request.terminalModel,
                            carrierId = request.carrierId,
                            timezone = request.timezone ?: entity.timezone,
                            profileId = effectiveProfileId,
                            updatedAt = now
                        )
                        terminalRepository.save(updated)
                            .map { TerminalRegisterResponse(
                                terminal = it.toResponse(),
                                operationStatus = "SUCCESS"
                            ) }
                    }
            }
    }

    /**
     * Определяет профиль терминала: если PROFILE_ID передан в запросе — он и используется
     * (валидность проверяется на стороне авторизованного редактора); иначе — активный
     * базовый профиль (IS_BASE=TRUE). Если базового профиля нет — терминал без профиля.
     */
    private fun resolveProfileId(request: TerminalRegisterRequest): Mono<UUID?> {
        val requested = request.profileId
        if (requested != null) return Mono.just(requested)
        return terminalProfileRepository.findBase()
            .map { Optional.of(it.profileId) }
            .defaultIfEmpty(Optional.empty())
            .map { it.orElse(null) }
    }

    private fun resolveTerminal(request: TerminalRegisterRequest, profileId: UUID?): Mono<TerminalEntity> {
        val now = Instant.now()

        val requestTerminalId = request.terminalId
        if (requestTerminalId != null) {
            return terminalRepository.findById(requestTerminalId)
                .switchIfEmpty(
                    findBySerialFallback(request, profileId, now)
                )
        }

        return terminalRepository.findByTerminalSerial(request.terminalSerial)
                .switchIfEmpty(
                    Mono.defer {
                    val entity = TerminalEntity(
                        terminalId = UuidUtils.newId(),
                        terminalSerial = request.terminalSerial,
                        terminalNumber = request.terminalNumber,
                        terminalModel = request.terminalModel,
                        carrierId = request.carrierId,
                        timezone = request.timezone,
                        status = "WAREHOUSE",
                        profileId = profileId,
                        createdAt = now,
                        updatedAt = now
                    )
                    r2dbcTemplate.insert(entity)
                }
            )
    }

    private fun findBySerialFallback(request: TerminalRegisterRequest, profileId: UUID?, now: Instant): Mono<TerminalEntity> {
        return terminalRepository.findByTerminalSerial(request.terminalSerial)
            .switchIfEmpty(
                Mono.defer {
                    val entity = TerminalEntity(
                        terminalId = UuidUtils.newId(),
                        terminalSerial = request.terminalSerial,
                        terminalNumber = request.terminalNumber,
                        terminalModel = request.terminalModel,
                        carrierId = request.carrierId,
                        timezone = request.timezone,
                        status = "WAREHOUSE",
                        profileId = profileId,
                        createdAt = now,
                        updatedAt = now
                    )
                    r2dbcTemplate.insert(entity)
                }
            )
    }

    fun getById(id: UUID): Mono<TerminalResponse> {
        return terminalRepository.findById(id).map { it.toResponse() }
    }

    fun changeStatus(id: UUID, request: TerminalStatusChangeRequest): Mono<TerminalResponse> {
        return terminalRepository.findById(id)
            .flatMap { existing ->
                val updated = existing.copy(
                    status = request.newStatus,
                    updatedAt = Instant.now()
                )
                terminalRepository.save(updated).map { it.toResponse() }
            }
    }

    fun assignCarrier(id: UUID, carrierId: UUID?): Mono<TerminalResponse> {
        return terminalRepository.findById(id)
            .flatMap { existing ->
                val updated = existing.copy(
                    carrierId = carrierId,
                    updatedAt = Instant.now()
                )
                terminalRepository.save(updated).map { it.toResponse() }
            }
    }
}

private fun TerminalEntity.toResponse() = TerminalResponse(
    id = terminalId,
    terminalSerial = terminalSerial,
    terminalNumber = terminalNumber,
    terminalModel = terminalModel,
    carrierId = carrierId,
    timezone = timezone,
    status = status,
    profileId = profileId,
    createdAt = createdAt,
    updatedAt = updatedAt
)
