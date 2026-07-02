package ru.asop.gateway.controller

import jakarta.validation.Valid
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Mono
import ru.asop.common.exception.BusinessException
import ru.asop.common.exception.ErrorCode
import ru.asop.common.kafka.KafkaTopic
import ru.asop.dto.carrier.CarrierCreateRequest
import ru.asop.gateway.dto.AcceptedResponse
import ru.asop.gateway.service.CarrierCommandService
import java.security.Principal
import java.time.Instant

@RestController
@RequestMapping("/api/v1/carriers")
class CarrierController(
    private val carrierCommandService: CarrierCommandService
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * POST /api/v1/carriers
     *
     * Создаёт команду на создание перевозчика.
     * - Валидирует JWT (через Spring Security)
     * - Валидирует DTO (через Jakarta Validation)
     * - Отправляет событие в Kafka
     * - Возвращает 202 Accepted с eventId
     */
    @PostMapping
    fun createCarrier(
        @Valid @RequestBody request: CarrierCreateRequest,
        principal: Mono<Principal>
    ): Mono<ResponseEntity<AcceptedResponse>> {
        return principal
            .defaultIfEmpty(EmptyPrincipal)
            .flatMap { p ->
                carrierCommandService.createCarrier(request, p)
                    .map { eventId ->
                        val response = AcceptedResponse(
                            eventId = eventId,
                            topic = KafkaTopic.CARRIER_COMMANDS,
                            acceptedAt = Instant.now(),
                            locationHint = "/api/v1/carriers/{id}"
                        )
                        ResponseEntity
                            .accepted()
                            .header("X-Event-Id", eventId.toString())
                            .body(response)
                    }
            }
            .onErrorResume { error ->
                log.error("Failed to process create carrier request", error)
                val ex = when (error) {
                    is BusinessException -> error
                    is IllegalArgumentException -> BusinessException(
                        errorCode = ErrorCode.VALIDATION_ERROR,
                        message = error.message ?: "Validation error"
                    )
                    else -> BusinessException(
                        errorCode = ErrorCode.KAFKA_PRODUCE_FAILED,
                        message = "Failed to send command to Kafka: ${error.message}",
                        cause = error
                    )
                }
                Mono.just(
                    ResponseEntity
                        .status(ex.errorCode.httpStatus)
                        .body(
                            AcceptedResponse(
                                eventId = java.util.UUID.randomUUID(),
                                topic = KafkaTopic.CARRIER_COMMANDS,
                                acceptedAt = Instant.now()
                            )
                        )
                )
            }
    }

    private object EmptyPrincipal : Principal {
        override fun getName(): String = "anonymous"
    }
}