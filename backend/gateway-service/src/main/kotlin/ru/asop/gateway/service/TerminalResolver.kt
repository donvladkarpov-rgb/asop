package ru.asop.gateway.service

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono
import ru.asop.api.terminal.dto.response.TerminalResponse
import ru.asop.gateway.config.ServiceRegistry
import java.util.UUID

data class TerminalContext(
    val terminalId: UUID,
    val carrierId: UUID? = null,
    val regionId: UUID? = null
)

/**
 * Разрешает terminalId → (carrierId, regionId) для дельта-синхронизации.
 * Terminal-service: GET /api/v1/terminals/{id} → carrierId.
 * Carrier-service: GET /api/v1/carriers/{id} → regionId (если перевозчик есть).
 */
@Service
class TerminalResolver(
    @Qualifier("proxyWebClient") private val webClient: WebClient,
    private val serviceRegistry: ServiceRegistry
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun resolve(terminalId: UUID): Mono<TerminalContext> {
        val terminalBase = serviceRegistry.getBaseUrl("terminals")
            ?: return Mono.empty()
        val carrierBase = serviceRegistry.getBaseUrl("carriers")

        return webClient.get()
            .uri("$terminalBase/api/v1/terminals/{id}", terminalId)
            .retrieve()
            .bodyToMono(TerminalResponse::class.java)
            .flatMap { terminal ->
                val carrierId = terminal.carrierId
                if (carrierId != null && carrierBase != null) {
                    webClient.get()
                        .uri("$carrierBase/api/v1/carriers/{id}", carrierId)
                        .retrieve()
                        .bodyToMono(ru.asop.api.carrier.dto.response.CarrierResponse::class.java)
                        .map { carrier ->
                            TerminalContext(terminalId, carrierId, carrier.regionId)
                        }
                        .onErrorResume { err ->
                            log.warn("Carrier lookup failed for {}: {}", carrierId, err.message)
                            Mono.just(TerminalContext(terminalId, carrierId))
                        }
                } else {
                    Mono.just(TerminalContext(terminalId, carrierId))
                }
            }
            .onErrorResume { err ->
                log.warn("Terminal lookup failed for {}: {}", terminalId, err.message)
                Mono.just(TerminalContext(terminalId))
            }
    }
}
