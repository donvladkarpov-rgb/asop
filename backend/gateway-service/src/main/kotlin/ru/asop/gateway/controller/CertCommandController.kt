package ru.asop.gateway.controller

import jakarta.validation.Valid
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Mono
import ru.asop.api.gateway.dto.response.AcceptedResponse
import ru.asop.api.terminal.dto.request.CertSignRequest
import ru.asop.gateway.service.CertCommandService
import java.time.Instant

@RestController
class CertCommandController(
    private val certCommandService: CertCommandService
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @PostMapping("/api/v1/terminals/cert-sign")
    fun requestCertSign(
        @Valid @RequestBody request: CertSignRequest
    ): Mono<ResponseEntity<AcceptedResponse>> {
        log.info(
            "CertSign requested: terminalSerial={}, terminalId={}, carrierId={}",
            request.terminalSerial, request.terminalId, request.carrierId
        )
        return certCommandService.publish(request)
            .map { eventId ->
                ResponseEntity.accepted()
                    .header("X-Event-Id", eventId.toString())
                    .body(AcceptedResponse(
                        eventId = eventId,
                        topic = "asop.terminal.cert.commands",
                        acceptedAt = Instant.now(),
                        locationHint = "/api/v1/terminals/{id}"
                    ))
            }
    }

    /** Отдельный cert-sign для android-distributor: сага сохраняет ASOP_DISTRIBUTOR_TERMINALS. */
    @PostMapping("/api/v1/distributor-terminals/cert-sign")
    fun requestDistributorCertSign(
        @Valid @RequestBody request: CertSignRequest
    ): Mono<ResponseEntity<AcceptedResponse>> {
        if (!request.distributor) {
            return Mono.just(ResponseEntity.badRequest().build())
        }
        log.info(
            "Distributor cert-sign requested: terminalSerial={}, distributorId={}, provider={}",
            request.terminalSerial, request.cardsDistributorId, request.paymentProviderId
        )
        return certCommandService.publishDistributor(request)
            .map { eventId ->
                ResponseEntity.accepted()
                    .header("X-Event-Id", eventId.toString())
                    .body(AcceptedResponse(
                        eventId = eventId,
                        topic = "asop.terminal.cert.commands",
                        acceptedAt = Instant.now(),
                        locationHint = "/api/v1/distributor-terminals/{id}"
                    ))
            }
    }
}
