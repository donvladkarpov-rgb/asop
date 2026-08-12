package ru.asop.card.service

import com.fasterxml.jackson.databind.ObjectMapper
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

/**
 * Парсит VCM1-bitmask из ASOP_CARD_MIFARES.IDENTITY_JSON. Возвращает null для не-VCM1 формата.
 *
 * Ожидаемый формат: `{"format":"VCM1", "cardId":"...", "bitmask":<int>, "entity":{...}}`. Если "format"
 * свойство отсутствует — это legacy DESfire JSON; bitmask null.
 */
fun parseVcm1Bitmask(identityJson: String?): Int? {
    if (identityJson.isNullOrBlank()) return null
    return runCatching {
        val node = ObjectMapper().readTree(identityJson)
        if (node.path("format").asText() != "VCM1") return null
        val bitmask = node.path("bitmask").asInt(-1)
        if (bitmask < 0 || bitmask > 0x3FFF) null else bitmask
    }.getOrNull()
}
