package ru.asop.card.controller

import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Mono
import ru.asop.api.card.controller.CardApi
import ru.asop.api.card.dto.request.CardRegisterRequest
import ru.asop.api.card.dto.request.CardBlockRequest
import ru.asop.api.card.dto.response.CardResponse
import ru.asop.card.service.CardService
import java.security.Principal
import java.util.UUID

@RestController
class CardController(
    private val cardService: CardService
) : CardApi {

    override fun registerCard(
        request: CardRegisterRequest,
        principal: Mono<Principal>
    ): Mono<ResponseEntity<CardResponse>> {
        return cardService.register(request)
            .map { ResponseEntity.ok(it) }
    }

    override fun blockCard(
        id: UUID,
        request: CardBlockRequest,
        principal: Mono<Principal>
    ): Mono<ResponseEntity<CardResponse>> {
        return Mono.empty()
    }

    override fun getCard(id: UUID): Mono<ResponseEntity<CardResponse>> {
        return cardService.getById(id)
            .map { ResponseEntity.ok(it) }
    }
}
