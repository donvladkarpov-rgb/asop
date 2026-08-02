package ru.asop.gateway.controller

import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Mono
import ru.asop.gateway.service.DebtCommandService
import ru.asop.api.gateway.dto.response.AcceptedResponse
import ru.asop.api.debt.dto.request.DebtCreateRequest
import ru.asop.api.debt.dto.request.DebtRecoverRequest
import java.security.Principal
import java.util.UUID

@RestController
class DebtCommandController(
    private val debtCommandService: DebtCommandService
) {

    @PostMapping("/api/v1/sync/debts")
    fun createDebt(
        @Valid @RequestBody request: DebtCreateRequest,
        principal: Mono<Principal>
    ): Mono<ResponseEntity<AcceptedResponse>> {
        return principal
            .defaultIfEmpty(EmptyPrincipal)
            .flatMap { p ->
                debtCommandService.create(request, p)
                    .map { eventId ->
                        ResponseEntity.accepted()
                            .header("X-Event-Id", eventId.toString())
                            .body(AcceptedResponse(
                                eventId = eventId,
                                topic = "asop.debt.commands",
                                acceptedAt = java.time.Instant.now(),
                                locationHint = "/api/v1/debts/{id}"
                            ))
                    }
            }
    }

    @PutMapping("/api/v1/sync/debts/{id}/recover")
    fun recoverDebt(
        @PathVariable id: UUID,
        @RequestBody(required = false) request: DebtRecoverRequest?,
        principal: Mono<Principal>
    ): Mono<ResponseEntity<AcceptedResponse>> {
        return principal
            .defaultIfEmpty(EmptyPrincipal)
            .flatMap { p ->
                debtCommandService.recoverDebt(id, request ?: DebtRecoverRequest(), p)
                    .map { eventId ->
                        ResponseEntity.accepted()
                            .header("X-Event-Id", eventId.toString())
                            .body(AcceptedResponse(
                                eventId = eventId,
                                topic = "asop.debt.commands",
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
