package ru.asop.gateway.controller

import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Mono
import ru.asop.gateway.service.GpsCommandService
import ru.asop.api.gateway.dto.response.AcceptedResponse
import ru.asop.api.gateway.dto.request.GpsPositionReport
import java.security.Principal

@RestController
class GpsCommandController(
    private val gpsCommandService: GpsCommandService
) {

    @PostMapping("/api/v1/sync/gps/positions")
    fun reportPosition(
        @Valid @RequestBody request: GpsPositionReport,
        principal: Mono<Principal>
    ): Mono<ResponseEntity<AcceptedResponse>> {
        return principal
            .defaultIfEmpty(EmptyPrincipal)
            .flatMap { p ->
                gpsCommandService.reportPosition(request, p)
                    .map { eventId ->
                        ResponseEntity.accepted()
                            .header("X-Event-Id", eventId.toString())
                            .body(AcceptedResponse(
                                eventId = eventId,
                                topic = "asop.gps.commands",
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
