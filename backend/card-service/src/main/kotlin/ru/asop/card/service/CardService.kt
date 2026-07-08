package ru.asop.card.service

import org.springframework.stereotype.Service
import reactor.core.publisher.Mono
import ru.asop.api.card.dto.request.CardRegisterRequest
import ru.asop.api.card.dto.request.CardBlockRequest
import ru.asop.api.card.dto.response.CardResponse
import ru.asop.card.model.CardEntity
import ru.asop.card.repository.CardRepository
import ru.asop.common.util.UuidUtils
import java.time.Instant
import java.util.UUID

@Service
class CardService(
    private val cardRepository: CardRepository
) {

    fun register(request: CardRegisterRequest): Mono<CardResponse> {
        val now = Instant.now()
        val entity = CardEntity(
            cardId = UuidUtils.newId(),
            cardTypeId = request.cardTypeId,
            userId = request.userId,
            isPrimary = false,
            registeredAt = now,
            createdAt = now,
            updatedAt = now
        )
        return cardRepository.save(entity).map { it.toResponse() }
    }

    fun getById(id: UUID): Mono<CardResponse> {
        return cardRepository.findById(id).map { it.toResponse() }
    }
}

private fun CardEntity.toResponse() = CardResponse(
    id = cardId,
    cardTypeId = cardTypeId,
    userId = userId,
    isPrimary = isPrimary,
    registeredAt = registeredAt,
    createdAt = createdAt,
    updatedAt = updatedAt
)
