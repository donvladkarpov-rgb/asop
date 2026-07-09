package ru.asop.session.service

import org.springframework.stereotype.Service
import reactor.core.publisher.Mono
import ru.asop.api.session.dto.request.SessionOpenRequest
import ru.asop.api.session.dto.request.SessionCloseRequest
import ru.asop.api.session.dto.response.SessionResponse
import ru.asop.session.model.SessionEntity
import ru.asop.session.repository.SessionRepository
import ru.asop.common.util.UuidUtils
import java.time.Instant
import java.util.UUID

@Service
class SessionService(
    private val sessionRepository: SessionRepository
) {

    fun open(request: SessionOpenRequest): Mono<SessionResponse> {
        val now = Instant.now()
        val entity = SessionEntity(
            sessionId = UuidUtils.newId(),
            sessionTypeId = request.sessionTypeId,
            parentSessionId = request.parentSessionId,
            terminalId = request.terminalId,
            pathId = request.pathId,
            vehicleId = request.vehicleId,
            status = "IN_PROGRESS",
            startedAt = now,
            createdAt = now,
            updatedAt = now
        )
        return sessionRepository.save(entity).map { it.toResponse() }
    }

    fun close(id: UUID, request: SessionCloseRequest): Mono<SessionResponse> {
        val now = Instant.now()
        return sessionRepository.findById(id)
            .flatMap { existing ->
                val updated = existing.copy(
                    status = "CLOSED",
                    closedAt = now,
                    updatedAt = now
                )
                sessionRepository.save(updated).map { it.toResponse() }
            }
    }

    fun getById(id: UUID): Mono<SessionResponse> {
        return sessionRepository.findById(id).map { it.toResponse() }
    }
}

private fun SessionEntity.toResponse() = SessionResponse(
    id = sessionId,
    sessionTypeId = sessionTypeId,
    parentSessionId = parentSessionId,
    terminalId = terminalId,
    pathId = pathId,
    vehicleId = vehicleId,
    status = status,
    startedAt = startedAt,
    closedAt = closedAt,
    createdAt = createdAt,
    updatedAt = updatedAt
)
