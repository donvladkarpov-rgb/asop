package ru.asop.api.card.controller

import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import reactor.core.publisher.Mono
import ru.asop.api.card.dto.request.CardActivateRequest
import ru.asop.api.card.dto.request.CardRegisterRequest
import ru.asop.api.card.dto.request.CardBlockRequest
import ru.asop.api.card.dto.response.CardActivateResponse
import ru.asop.api.card.dto.response.CardResponse
import java.security.Principal
import java.util.UUID

@RequestMapping("/api/v1/cards")
interface CardApi {

    @PostMapping("/register")
    fun registerCard(
        @Valid @RequestBody request: CardRegisterRequest,
        principal: Mono<Principal>
    ): Mono<ResponseEntity<CardResponse>>

    @PostMapping("/activate")
    fun activateCard(
        @Valid @RequestBody request: CardActivateRequest,
        principal: Mono<Principal>
    ): Mono<ResponseEntity<CardActivateResponse>>

    @PostMapping("/{id}/block")
    fun blockCard(
        @PathVariable id: UUID,
        @Valid @RequestBody request: CardBlockRequest,
        principal: Mono<Principal>
    ): Mono<ResponseEntity<CardResponse>>

    @GetMapping("/{id}")
    fun getCard(
        @PathVariable id: UUID
    ): Mono<ResponseEntity<CardResponse>>
}
