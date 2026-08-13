package ru.asop.gateway.controller

import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Mono
import ru.asop.gateway.service.TransactionCommandService
import ru.asop.api.gateway.dto.response.AcceptedResponse
import ru.asop.api.gateway.dto.request.TransactionCompleteRequest
import java.security.Principal

@RestController
class TransactionCommandController(
    private val transactionCommandService: TransactionCommandService
) {

    @PostMapping("/api/v1/sync/transactions")
    fun completeTransaction(
        @Valid @RequestBody request: TransactionCompleteRequest,
        @RequestHeader(value = "X-Event-Seq", required = false) seqHeader: Long?,
        principal: Mono<Principal>
    ): Mono<ResponseEntity<AcceptedResponse>> {
        return principal
            .defaultIfEmpty(EmptyPrincipal)
            .flatMap { p ->
                transactionCommandService.complete(request, p, seqHeader ?: 0L)
                    .map { eventId ->
                        ResponseEntity.accepted()
                            .header("X-Event-Id", eventId.toString())
                            .body(AcceptedResponse(
                                eventId = eventId,
                                topic = "asop.transaction.commands",
                                acceptedAt = java.time.Instant.now(),
                                locationHint = "/api/v1/transactions/{id}"
                            ))
                    }
            }
    }

    private object EmptyPrincipal : Principal {
        override fun getName(): String = "terminal"
    }
}
