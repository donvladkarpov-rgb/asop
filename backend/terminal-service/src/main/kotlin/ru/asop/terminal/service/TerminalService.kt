package ru.asop.terminal.service

import org.springframework.stereotype.Service
import reactor.core.publisher.Mono
import ru.asop.api.terminal.dto.request.TerminalRegisterRequest
import ru.asop.api.terminal.dto.request.TerminalStatusChangeRequest
import ru.asop.api.terminal.dto.response.TerminalResponse
import ru.asop.terminal.model.TerminalEntity
import ru.asop.terminal.repository.TerminalRepository
import ru.asop.common.util.UuidUtils
import java.time.Instant
import java.util.UUID

@Service
class TerminalService(
    private val terminalRepository: TerminalRepository
) {

    fun register(request: TerminalRegisterRequest): Mono<TerminalResponse> {
        val now = Instant.now()
        val entity = TerminalEntity(
            terminalId = UuidUtils.newId(),
            terminalSerial = request.terminalSerial,
            terminalNumber = request.terminalNumber,
            terminalModel = request.terminalModel,
            carrierId = request.carrierId,
            status = "WAREHOUSE",
            createdAt = now,
            updatedAt = now
        )
        return terminalRepository.save(entity).map { it.toResponse() }
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
}

private fun TerminalEntity.toResponse() = TerminalResponse(
    id = terminalId,
    terminalSerial = terminalSerial,
    terminalNumber = terminalNumber,
    terminalModel = terminalModel,
    carrierId = carrierId,
    status = status,
    createdAt = createdAt,
    updatedAt = updatedAt
)
