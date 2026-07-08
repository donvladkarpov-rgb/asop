package ru.asop.debt.service

import org.springframework.stereotype.Service
import reactor.core.publisher.Mono
import ru.asop.api.debt.dto.request.DebtCreateRequest
import ru.asop.api.debt.dto.response.DebtResponse
import ru.asop.debt.model.DebtEntity
import ru.asop.debt.repository.DebtRepository
import ru.asop.common.util.UuidUtils
import java.time.Instant
import java.util.UUID

@Service
class DebtService(
    private val debtRepository: DebtRepository
) {

    fun create(request: DebtCreateRequest): Mono<DebtResponse> {
        val now = Instant.now()
        val dueDate = now.plusSeconds(30 * 24 * 60 * 60)
        val entity = DebtEntity(
            debtId = UuidUtils.newId(),
            cardId = request.cardId,
            carrierId = request.carrierId,
            debtAmount = request.debtAmount,
            debtStatus = "OPEN",
            debtOpenedAt = now,
            debtDueDate = dueDate,
            terminalId = request.terminalId,
            sessionId = request.sessionId,
            createdAt = now,
            updatedAt = now
        )
        return debtRepository.save(entity).map { it.toResponse() }
    }

    fun getById(id: UUID): Mono<DebtResponse> {
        return debtRepository.findById(id).map { it.toResponse() }
    }
}

private fun DebtEntity.toResponse() = DebtResponse(
    id = debtId,
    cardId = cardId,
    carrierId = carrierId,
    debtAmount = debtAmount,
    debtStatus = debtStatus,
    debtOpenedAt = debtOpenedAt,
    debtDueDate = debtDueDate,
    createdAt = createdAt,
    updatedAt = updatedAt
)
