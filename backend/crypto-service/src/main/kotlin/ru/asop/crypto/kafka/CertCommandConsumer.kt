package ru.asop.crypto.kafka

import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.config.KafkaListenerEndpointRegistry
import org.springframework.kafka.listener.MessageListenerContainer
import org.springframework.messaging.handler.annotation.Header
import org.springframework.stereotype.Component
import ru.asop.crypto.service.CertIssuedPublisher
import ru.asop.crypto.service.RootCaService
import ru.asop.crypto.service.TerminalCertService
import ru.asop.kafka.events.terminal.CertSignRequested
import java.security.KeyFactory
import java.security.PublicKey
import java.security.cert.X509Certificate
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.UUID
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers
import java.util.concurrent.atomic.AtomicBoolean
import jakarta.annotation.PreDestroy
import reactor.core.Disposable

/**
 * Слушает asop.terminal.cert.commands от Gateway, выпускает X.509 через
 * Intermediate CA и публикует результат в asop.terminal.cert.issued.
 *
 * X-Event-Id из Gateway пробрасывается в CertIssued.correlationId,
 * чтобы terminal-service и gateway-consumer могли коррелировать событие
 * с исходным запросом.
 *
 * {@code autoStartup = "false"} — listener не стартует автоматически (на cold start
 * Kafka может быть ещё не готова, см. {@code RetryKafkaStartup}). Запускается
 * вручную через {@link KafkaListenerEndpointRegistry} после retry-проверки
 * доступности Kafka. Это устраняет race-condition при первом старте.
 */
@Component
class CertCommandConsumer(
    private val terminalCertService: TerminalCertService,
    private val rootCaService: RootCaService,
    private val certIssuedPublisher: CertIssuedPublisher,
    private val listenerRegistry: KafkaListenerEndpointRegistry
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val listenerStarted = AtomicBoolean(false)
    private var kafkaStartupDisposable: Disposable? = null

    @KafkaListener(
        id = "crypto-service-cert-commands",
        topics = ["\${asop.kafka.topics.terminal-cert-commands}"],
        groupId = "crypto-service-cert-commands",
        autoStartup = "false"
    )
    fun onCertSignRequested(
        event: CertSignRequested,
        @Header(name = "X-Event-Id", required = false) eventIdHeader: ByteArray?
    ) {
        val eventId = parseEventId(eventIdHeader, event.correlationId)
        log.info(
            "CertSignRequested received: eventId={}, terminalSerial={}, carrierId={}",
            eventId, event.terminalSerial, event.carrierId
        )

        try {
            val publicKey = parsePublicKey(event.publicKeyBase64)
            val carrierIdStr = event.carrierId?.toString() ?: "UNASSIGNED"

            val certificate = terminalCertService.issueTerminalCertificate(
                terminalSerial = event.terminalSerial,
                carrierId = carrierIdStr,
                publicKey = publicKey
            )

            val certificateBase64 = Base64.getEncoder().encodeToString(certificate.encoded)
            val serialNumber = certificate.serialNumber.toString(16)
            val validFrom = certificate.notBefore.toInstant()
            val validUntil = certificate.notAfter.toInstant()
            val caChain = buildCaChainPem()

            val terminalId = event.terminalId ?: UUID.randomUUID()

            certIssuedPublisher.publishIssued(
                eventId = eventId,
                terminalId = terminalId,
                terminalSerial = event.terminalSerial,
                terminalNumber = event.terminalNumber,
                certificateBase64 = certificateBase64,
                serialNumber = serialNumber,
                validFrom = validFrom,
                validUntil = validUntil,
                caChain = caChain
            ).subscribe(
                { /* success: log already below */ },
                { err ->
                    log.error(
                        "Failed to publish CertIssued event: eventId={}, terminalSerial={}, error={}",
                        eventId, event.terminalSerial, err.message, err
                    )
                }
            )

            log.info(
                "CertIssued published: eventId={}, terminalSerial={}, certSerial={}, validUntil={}",
                eventId, event.terminalSerial, serialNumber, validUntil
            )
        } catch (e: Exception) {
            log.error(
                "Failed to issue cert: eventId={}, terminalSerial={}, error={}",
                eventId, event.terminalSerial, e.message, e
            )
            certIssuedPublisher.publishFailed(
                eventId = eventId,
                terminalSerial = event.terminalSerial,
                terminalId = event.terminalId,
                reason = e.message ?: e::class.simpleName ?: "Unknown error"
            ).subscribe(
                { /* success: nothing */ },
                { err ->
                    log.error(
                        "Failed to publish CertSignFailed event: eventId={}, terminalSerial={}, error={}",
                        eventId, event.terminalSerial, err.message, err
                    )
                }
            )
        }
    }

    private fun parsePublicKey(publicKeyBase64: String): PublicKey {
        val keyBytes = Base64.getDecoder().decode(publicKeyBase64)
        val keySpec = X509EncodedKeySpec(keyBytes)
        val keyFactory = KeyFactory.getInstance("EC")
        return keyFactory.generatePublic(keySpec)
    }

    /**
     * CA chain в формате PEM bundle: intermediate + root.
     * Терминал использует её для проверки подписи на стороне клиента.
     */
    private fun buildCaChainPem(): String {
        val intermediate = rootCaService.getIntermediateCaCertificate()
        val root = rootCaService.getRootCaCertificate()
        return buildString {
            append(certificateToPem(intermediate))
            append('\n')
            append(certificateToPem(root))
        }
    }

    private fun certificateToPem(certificate: X509Certificate): String {
        val base64Cert = Base64.getMimeEncoder(64, "\n".toByteArray())
            .encodeToString(certificate.encoded)
        return buildString {
            append("-----BEGIN CERTIFICATE-----\n")
            append(base64Cert)
            append("\n-----END CERTIFICATE-----\n")
        }
    }

    private fun parseEventId(header: ByteArray?, fallback: UUID): UUID {
        if (header == null) return fallback
        return try {
            UUID.fromString(String(header))
        } catch (e: IllegalArgumentException) {
            log.warn("Invalid X-Event-Id header, falling back to correlationId")
            fallback
        }
    }

    /**
     * Холодный запуск: Kafka может появиться позже crypto-service.
     * Опрашиваем Kafka раз в 5 секунд через AdminClient; когда брокеры
     * станут доступны — стартуем наш KafkaListener.
     *
     * Заменяет fail-fast поведение {@code @KafkaListener} на устойчивое
     * ожидание готовности Kafka (cold-start race condition устранён).
     */
    @EventListener(ApplicationReadyEvent::class)
    fun startListenerWhenKafkaReady() {
        kafkaStartupDisposable = Mono.fromRunnable<Void> {
            val bootstrapServers =
                System.getenv("SPRING_KAFKA_BOOTSTRAP_SERVERS") ?: "kafka:9093"
            val attempt = retryKafkaReadiness(bootstrapServers, maxAttempts = 30)
            if (attempt == -1) {
                log.error(
                    "Kafka not reachable at {} after retries; cert-sign listener will not start. " +
                        "Restart the service or check Kafka health.",
                    bootstrapServers
                )
                return@fromRunnable
            }
            try {
                val container: MessageListenerContainer? =
                    listenerRegistry.getListenerContainer("crypto-service-cert-commands")
                if (container != null && !container.isRunning && listenerStarted.compareAndSet(false, true)) {
                    container.start()
                    log.info(
                        "Cert command KafkaListener started after {} readiness attempts",
                        attempt
                    )
                }
            } catch (e: Exception) {
                log.error("Failed to start crypto-service-cert-commands listener", e)
                listenerStarted.set(false)
            }
        }.subscribeOn(Schedulers.boundedElastic()).subscribe()
    }

    @PreDestroy
    fun cleanup() {
        kafkaStartupDisposable?.dispose()
    }

    private fun retryKafkaReadiness(bootstrapServers: String, maxAttempts: Int): Int {
        val brokers = bootstrapServers.split(",")
        for (attempt in 1..maxAttempts) {
            try {
                java.net.Socket().use { sock ->
                    sock.connect(java.net.InetSocketAddress(brokers[0].split(":")[0], brokers[0].split(":")[1].toInt()), 3000)
                    return attempt
                }
            } catch (_: Exception) {
                try { Thread.sleep(5000) } catch (_: InterruptedException) {}
            }
        }
        return -1
    }
}