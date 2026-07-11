package ru.asop.gateway.controller

import jakarta.validation.Valid
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Mono
import ru.asop.api.terminal.dto.request.CertSignRequest
import ru.asop.gateway.model.EventStatus
import ru.asop.gateway.service.CertCommandService

/**
 * Открытый (open HTTPS) endpoint для подписания нового X.509 сертификата терминала.
 * Используется Android-приложением терминала при первом запуске
 * (mTLS ещё недоступен — chicken-and-egg).
 *
 * Поток (choreographed saga, см. doc/context.md):
 *   1. Android → POST /api/v1/terminals/cert-sign (HTTPS, no auth)
 *   2. Gateway → Kafka asop.terminal.cert.commands + EventService.createPending
 *   3. Gateway → 202 Accepted + X-Event-Id
 *   4. crypto-service подписывает → asop.terminal.cert.issued
 *   5. terminal-service сохраняет в БД → asop.terminal.cert.events
 *   6. Gateway consumer → EventService.complete(eventId, resultData)
 *   7. Android polling GET /api/v1/events/{eventId} → COMPLETED + cert
 */
@RestController
class CertCommandController(
    private val certCommandService: CertCommandService
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @PostMapping("/api/v1/terminals/cert-sign")
    fun requestCertSign(
        @Valid @RequestBody request: CertSignRequest
    ): Mono<ResponseEntity<EventStatus>> {
        log.info(
            "CertSign requested: terminalSerial={}, terminalId={}, carrierId={}",
            request.terminalSerial, request.terminalId, request.carrierId
        )
        return certCommandService.publish(request)
            .map { status ->
                ResponseEntity.accepted()
                    .header("X-Event-Id", status.eventId.toString())
                    .body(status)
            }
    }
}