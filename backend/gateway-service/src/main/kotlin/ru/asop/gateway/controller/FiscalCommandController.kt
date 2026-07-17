package ru.asop.gateway.controller

import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Mono
import ru.asop.gateway.service.FiscalCommandService
import ru.asop.api.gateway.dto.response.AcceptedResponse
import ru.asop.api.fiscal.dto.request.FiscalReceiptRequest
import java.security.Principal

@RestController
class FiscalCommandController(
    private val fiscalCommandService: FiscalCommandService
) {

    @PostMapping("/api/v1/sync/fiscal/receipts")
    fun requestReceipt(
        @Valid @RequestBody request: FiscalReceiptRequest,
        principal: Mono<Principal>
    ): Mono<ResponseEntity<AcceptedResponse>> {
        return principal
            .defaultIfEmpty(EmptyPrincipal)
            .flatMap { p ->
                fiscalCommandService.requestReceipt(request, p)
                    .map { eventId ->
                        ResponseEntity.accepted()
                            .header("X-Event-Id", eventId.toString())
                            .body(AcceptedResponse(
                                eventId = eventId,
                                topic = "asop.fiscal.commands",
                                acceptedAt = java.time.Instant.now(),
                                locationHint = "/api/v1/fiscal/receipts/{id}"
                            ))
                    }
            }
    }

    private object EmptyPrincipal : Principal {
        override fun getName(): String = "terminal"
    }
}
