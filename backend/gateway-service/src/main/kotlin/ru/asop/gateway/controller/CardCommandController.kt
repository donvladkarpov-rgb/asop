package ru.asop.gateway.controller

import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Mono
import ru.asop.gateway.service.CardCommandService
import ru.asop.api.gateway.dto.response.AcceptedResponse
import ru.asop.api.card.dto.request.CardRegisterRequest
import ru.asop.api.card.dto.request.CardBlockRequest
import java.security.Principal
import java.util.UUID

@RestController
class CardCommandController(
    private val cardCommandService: CardCommandService
) {

    @PostMapping("/api/v1/sync/cards/register")
    fun registerCard(
        @Valid @RequestBody request: CardRegisterRequest,
        principal: Mono<Principal>
    ): Mono<ResponseEntity<AcceptedResponse>> {
        return principal
            .defaultIfEmpty(EmptyPrincipal)
            .flatMap { p ->
                cardCommandService.register(request, p)
                    .map { eventId ->
                        ResponseEntity.accepted()
                            .header("X-Event-Id", eventId.toString())
                            .body(AcceptedResponse(
                                eventId = eventId,
                                topic = "asop.card.commands",
                                acceptedAt = java.time.Instant.now(),
                                locationHint = "/api/v1/cards/{id}"
                            ))
                    }
            }
    }

    @PostMapping("/api/v1/sync/cards/{id}/block")
    fun blockCard(
        @PathVariable id: UUID,
        @Valid @RequestBody request: CardBlockRequest,
        principal: Mono<Principal>
    ): Mono<ResponseEntity<AcceptedResponse>> {
        return principal
            .defaultIfEmpty(EmptyPrincipal)
            .flatMap { p ->
                cardCommandService.blockCard(id, request, p)
                    .map { eventId ->
                        ResponseEntity.accepted()
                            .header("X-Event-Id", eventId.toString())
                            .body(AcceptedResponse(
                                eventId = eventId,
                                topic = "asop.card.commands",
                                acceptedAt = java.time.Instant.now(),
                                locationHint = null
                            ))
                    }
            }
    }

    private object EmptyPrincipal : Principal {
        override fun getName(): String = "terminal"
    }
}
