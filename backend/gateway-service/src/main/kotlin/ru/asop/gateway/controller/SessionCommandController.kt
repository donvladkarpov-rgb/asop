package ru.asop.gateway.controller

import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Mono
import ru.asop.gateway.service.SessionCommandService
import ru.asop.api.gateway.dto.response.AcceptedResponse
import ru.asop.api.session.dto.request.SessionOpenRequest
import ru.asop.api.session.dto.request.SessionCloseRequest
import java.security.Principal
import java.util.UUID

@RestController
class SessionCommandController(
    private val sessionCommandService: SessionCommandService
) {

    @PostMapping("/api/v1/sync/sessions/open")
    fun openSession(
        @Valid @RequestBody request: SessionOpenRequest,
        @RequestHeader(value = "X-Event-Seq", required = false) seqHeader: Long?,
        principal: Mono<Principal>
    ): Mono<ResponseEntity<AcceptedResponse>> {
        return principal
            .defaultIfEmpty(EmptyPrincipal)
            .flatMap { p ->
                sessionCommandService.openSession(request, p, seqHeader ?: 0L)
                    .map { eventId ->
                        ResponseEntity.accepted()
                            .header("X-Event-Id", eventId.toString())
                            .body(AcceptedResponse(
                                eventId = eventId,
                                topic = "asop.session.commands",
                                acceptedAt = java.time.Instant.now(),
                                locationHint = "/api/v1/sessions/{id}"
                            ))
                    }
            }
    }

    @PutMapping("/api/v1/sync/sessions/{id}/close")
    fun closeSession(
        @PathVariable id: UUID,
        @Valid @RequestBody request: SessionCloseRequest,
        @RequestHeader(value = "X-Event-Seq", required = false) seqHeader: Long?,
        principal: Mono<Principal>
    ): Mono<ResponseEntity<AcceptedResponse>> {
        return principal
            .defaultIfEmpty(EmptyPrincipal)
            .flatMap { p ->
                sessionCommandService.closeSession(id, request, p, seqHeader ?: 0L)
                    .map { eventId ->
                        ResponseEntity.accepted()
                            .header("X-Event-Id", eventId.toString())
                            .body(AcceptedResponse(
                                eventId = eventId,
                                topic = "asop.session.commands",
                                acceptedAt = java.time.Instant.now(),
                                locationHint = "/api/v1/sessions/{id}"
                            ))
                    }
            }
    }

    private object EmptyPrincipal : Principal {
        override fun getName(): String = ""
    }
}
