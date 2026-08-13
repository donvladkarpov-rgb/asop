package ru.asop.gateway.controller

import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Mono
import ru.asop.gateway.service.AuditCommandService
import ru.asop.api.gateway.dto.response.AcceptedResponse
import ru.asop.api.audit.dto.request.AuditTaskCreateRequest
import java.security.Principal

@RestController
class AuditCommandController(
    private val auditCommandService: AuditCommandService
) {

    @PostMapping("/api/v1/sync/audit/tasks")
    fun createTask(
        @Valid @RequestBody request: AuditTaskCreateRequest,
        @RequestHeader(value = "X-Event-Seq", required = false) seqHeader: Long?,
        principal: Mono<Principal>
    ): Mono<ResponseEntity<AcceptedResponse>> {
        return principal
            .defaultIfEmpty(EmptyPrincipal)
            .flatMap { p ->
                auditCommandService.createTask(request, p, seqHeader ?: 0L)
                    .map { eventId ->
                        ResponseEntity.accepted()
                            .header("X-Event-Id", eventId.toString())
                            .body(AcceptedResponse(
                                eventId = eventId,
                                topic = "asop.audit.commands",
                                acceptedAt = java.time.Instant.now(),
                                locationHint = "/api/v1/audit/tasks/{id}"
                            ))
                    }
            }
    }

    private object EmptyPrincipal : Principal {
        override fun getName(): String = "terminal"
    }
}
