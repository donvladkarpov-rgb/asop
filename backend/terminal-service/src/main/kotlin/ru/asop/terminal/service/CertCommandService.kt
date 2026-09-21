package ru.asop.terminal.service

import org.slf4j.LoggerFactory
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.support.KafkaHeaders
import org.springframework.messaging.handler.annotation.Header
import org.springframework.stereotype.Service
import org.springframework.transaction.reactive.TransactionalOperator
import reactor.core.publisher.Mono
import ru.asop.common.util.UuidUtils
import ru.asop.kafka.events.terminal.CertIssued
import ru.asop.kafka.events.terminal.CertStored
import ru.asop.terminal.model.DistributorTerminalEntity
import ru.asop.terminal.model.TerminalCertEntity
import ru.asop.terminal.model.TerminalEntity
import ru.asop.terminal.repository.DistributorTerminalRepository
import ru.asop.terminal.repository.TerminalRepository
import java.time.Instant
import java.util.UUID

/**
 * Слушает asop.terminal.cert.issued от crypto-service и сохраняет
 * терминал + сертификат в БД атомарно (через TransactionalOperator).
 *
 * Логика:
 *   1. ensureTerminal — найти терминал по serial или создать новый
 *   2. В одной R2DBC-транзакции:
 *        - markAllAsNotCurrent(terminalId) (UPDATE)
 *        - R2dbcEntityTemplate.insert(TerminalCertEntity IS_CURRENT=true)
 *   3. Опубликовать CertStored → asop.terminal.cert.events
 */
@Service
class CertCommandService(
    private val terminalRepository: TerminalRepository,
    private val distributorTerminalRepository: DistributorTerminalRepository,
    private val r2dbcTemplate: R2dbcEntityTemplate,
    private val transactionalOperator: TransactionalOperator,
    private val certEventPublisher: CertEventPublisher
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @KafkaListener(
        topics = ["\${asop.kafka.topics.terminal-cert-issued}"],
        groupId = "terminal-service-cert-issued"
    )
    fun onCertIssued(
        event: CertIssued,
        @Header(name = "X-Event-Id", required = false) eventIdHeader: ByteArray?
    ) {
        val eventId = parseEventId(eventIdHeader, event.correlationId)
        log.info(
            "CertIssued received: eventId={}, terminalId={}, serial={}, certSerial={}, distributor={}",
            eventId, event.terminalId, event.terminalSerial, event.certSerialNumber, event.distributor
        )

        try {
            if (event.distributor) {
                handleDistributor(event, eventId)
                return
            }
            val newCert = buildNewCert(event)
            val savedCert = transactionalOperator.transactional(
                ensureTerminal(event)
                    .flatMap { terminalId ->
                        markAllAsNotCurrent(terminalId).thenReturn(terminalId)
                    }
                    .flatMap { terminalId ->
                        val withTerminalId = newCert.copy(terminalId = terminalId)
                        r2dbcTemplate.insert(withTerminalId)
                    }
            ).block() ?: error("Empty result after transactional insert")

            val stored = CertStored(
                certId = savedCert.certId,
                terminalId = savedCert.terminalId,
                terminalNumber = event.terminalNumber,
                certSerial = savedCert.certSerial,
                certificateBase64 = savedCert.certData,
                validFrom = savedCert.issuedAt,
                validUntil = savedCert.expiresAt,
                caChain = event.caChain,
                correlationId = eventId
            )
            certEventPublisher.publishStored(stored).subscribe()

            log.info(
                "CertStored: eventId={}, certId={}, terminalId={}",
                eventId, savedCert.certId, savedCert.terminalId
            )
        } catch (e: Exception) {
            log.error(
                "Failed to persist cert: eventId={}, terminalSerial={}, error={}",
                eventId, event.terminalSerial, e.message, e
            )
            certEventPublisher.publishFailed(
                eventId = eventId,
                terminalSerial = event.terminalSerial,
                terminalId = event.terminalId,
                reason = e.message ?: e::class.simpleName ?: "Unknown error"
            ).subscribe()
        }
    }

    /**
     * Ветка дистрибьютора: сохраняем ASOP_DISTRIBUTOR_TERMINALS (НЕ ASOP_TERMINALS),
     * сертификат на сервере не храним (mTLS валидируется через CA-цепочку truststore).
     */
    private fun handleDistributor(event: CertIssued, eventId: UUID) {
        val cardsId = event.cardsDistributorId
        val provider = event.paymentProviderId
        if (cardsId == null || provider.isNullOrBlank()) {
            throw IllegalArgumentException(
                "distributor cert-sign requires cardsDistributorId + paymentProviderId"
            )
        }
        val savedTerminalId = transactionalOperator.transactional(
            distributorTerminalRepository.findByTerminalSerial(event.terminalSerial)
                .next()
                .flatMap { existing ->
                    val updated = existing.copy(
                        cardsDistributorId = cardsId,
                        paymentProviderId = provider,
                        terminalNumber = event.terminalNumber ?: existing.terminalNumber,
                        terminalModel = event.terminalModel ?: existing.terminalModel,
                        updatedAt = Instant.now()
                    )
                    distributorTerminalRepository.save(updated).map { it.distributorTerminalId }
                }
                .switchIfEmpty(
                    Mono.defer {
                        val now = Instant.now()
                        val entity = DistributorTerminalEntity(
                            distributorTerminalId = event.terminalId,
                            cardsDistributorId = cardsId,
                            contractId = null,
                            terminalNumber = event.terminalNumber ?: event.terminalSerial,
                            terminalSerial = event.terminalSerial,
                            terminalModel = event.terminalModel,
                            paymentProviderId = provider,
                            status = "WAREHOUSE",
                            molUserId = null,
                            profileId = null,
                            softwareVersionId = null,
                            createdAt = now,
                            updatedAt = now
                        )
                        r2dbcTemplate.insert(entity).map { it.distributorTerminalId }
                    }
                )
        ).block() ?: error("Empty result after distributor terminal upsert")

        val stored = CertStored(
            certId = UuidUtils.newId(),
            terminalId = savedTerminalId,
            terminalNumber = event.terminalNumber,
            certSerial = event.certSerialNumber,
            certificateBase64 = event.certificateBase64,
            validFrom = event.validFrom,
            validUntil = event.validUntil,
            caChain = event.caChain,
            correlationId = eventId
        )
        certEventPublisher.publishStored(stored).subscribe()

        log.info(
            "CertStored (distributor): eventId={}, distributorTerminalId={}",
            eventId, savedTerminalId
        )
    }

    private fun ensureTerminal(event: CertIssued): Mono<UUID> {
        return terminalRepository.findByTerminalSerial(event.terminalSerial)
            .map { it.terminalId }
            .switchIfEmpty(
                Mono.defer {
                    val now = Instant.now()
                    val entity = TerminalEntity(
                        terminalId = event.terminalId,
                        terminalNumber = event.terminalNumber,
                        terminalSerial = event.terminalSerial,
                        terminalModel = null,
                        carrierId = null,
                        status = "WAREHOUSE",
                        createdAt = now,
                        updatedAt = now
                    )
                    r2dbcTemplate.insert(entity).map { it.terminalId }
                }
            )
    }

    private fun markAllAsNotCurrent(terminalId: UUID): Mono<Long> {
        val sql = """
            UPDATE ASOP_TERMINAL_CERTS
            SET IS_CURRENT = false
            WHERE TERMINAL_ID = :terminalId AND IS_CURRENT = true
        """.trimIndent()
        return r2dbcTemplate.databaseClient.sql(sql)
            .bind("terminalId", terminalId)
            .fetch().rowsUpdated()
    }

    private fun buildNewCert(event: CertIssued): TerminalCertEntity {
        return TerminalCertEntity(
            certId = UuidUtils.newId(),
            terminalId = event.terminalId,
            certSerial = event.certSerialNumber,
            issuedAt = event.validFrom,
            expiresAt = event.validUntil,
            isCurrent = true,
            certData = event.certificateBase64,
            caChain = event.caChain,
            createdAt = Instant.now()
        )
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
}