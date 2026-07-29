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
import ru.asop.common.util.UuidUtils
import java.time.Instant
import java.util.UUID

@Service
class TerminalService(
    private val terminalRepository: TerminalRepository,
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

        return resolveTerminal(request)
            .flatMap { entity ->
                val updated = entity.copy(
                    terminalSerial = request.terminalSerial,
                    terminalNumber = request.terminalNumber,
                    terminalModel = request.terminalModel,
                    carrierId = request.carrierId,
                    timezone = request.timezone ?: entity.timezone,
                    updatedAt = now
                )
                terminalRepository.save(updated)
                    .map { TerminalRegisterResponse(
                        terminal = it.toResponse(),
                        operationStatus = "SUCCESS"
                    ) }
            }
    }

    private fun resolveTerminal(request: TerminalRegisterRequest): Mono<TerminalEntity> {
        val now = Instant.now()

        val requestTerminalId = request.terminalId
        if (requestTerminalId != null) {
            return terminalRepository.findById(requestTerminalId)
                .switchIfEmpty(
                    findBySerialFallback(request, now)
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
                        createdAt = now,
                        updatedAt = now
                    )
                    r2dbcTemplate.insert(entity)
                }
            )
    }

    private fun findBySerialFallback(request: TerminalRegisterRequest, now: Instant): Mono<TerminalEntity> {
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
    createdAt = createdAt,
    updatedAt = updatedAt
)
