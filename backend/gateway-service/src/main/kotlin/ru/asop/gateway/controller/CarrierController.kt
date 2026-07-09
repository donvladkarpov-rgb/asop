package ru.asop.gateway.controller

import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Mono
import ru.asop.api.gateway.controller.CarrierApi
import ru.asop.api.gateway.dto.response.AcceptedResponse
import ru.asop.api.gateway.dto.request.CarrierCreateRequest
import ru.asop.gateway.service.CarrierCommandService
import java.security.Principal
import java.time.Instant

@RestController
class CarrierController(
    private val carrierCommandService: CarrierCommandService
) : CarrierApi {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun createCarrier(
        request: CarrierCreateRequest,
        principal: Mono<Principal>
    ): Mono<ResponseEntity<AcceptedResponse>> {
        return principal
            .defaultIfEmpty(EmptyPrincipal)
            .flatMap { p ->
                carrierCommandService.createCarrier(request, p)
                    .map { eventId ->
                        val response = AcceptedResponse(
                            eventId = eventId,
                            topic = "asop.carrier.commands",
                            acceptedAt = Instant.now(),
                            locationHint = "/api/v1/carriers/{id}"
                        )
                        ResponseEntity
                            .accepted()
                            .header("X-Event-Id", eventId.toString())
                            .body(response)
                    }
            }
    }

    private object EmptyPrincipal : Principal {
        override fun getName(): String = "anonymous"
    }
}