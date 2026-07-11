package ru.asop.kafka.events.terminal

import ru.asop.common.event.BaseDomainEvent
import java.time.Instant
import java.util.UUID

/**
 * Команда: подписать публичный ключ терминала (gateway → crypto-service).
 * Если терминал новый — crypto-service создаёт запись.
 * correlationId == eventId в EventService Gateway (пробрасывается через Kafka header X-Event-Id).
 */
data class CertSignRequested(
    val terminalId: UUID?,
    val terminalSerial: String,
    val terminalNumber: String?,
    val terminalModel: String?,
    val carrierId: UUID?,
    val publicKeyBase64: String,

    override val aggregateType: String = "TerminalCert",
    override val causationId: UUID? = null,
    override val correlationId: UUID = UUID.randomUUID(),
    override val userId: UUID? = null,
    override val version: Int = 1,
    override val occurredAt: Instant = Instant.now()
) : BaseDomainEvent(
    aggregateId = terminalId ?: UUID.randomUUID(),
    aggregateType = aggregateType,
    causationId = causationId,
    correlationId = correlationId,
    userId = userId,
    version = version,
    occurredAt = occurredAt
)

/**
 * Событие: сертификат выпущен (crypto-service → terminal-service).
 * terminalId — стабильный UUID терминала (crypto-service резолвит по serial или создаёт).
 * caChain — PEM-цепочка (Root + Intermediate) для последующего сохранения рядом с сертификатом.
 */
data class CertIssued(
    val terminalId: UUID,
    val terminalSerial: String,
    val terminalNumber: String?,
    val certificateBase64: String,
    val certSerialNumber: String,
    val validFrom: Instant,
    val validUntil: Instant,
    val caChain: String,

    override val aggregateType: String = "TerminalCert",
    override val causationId: UUID? = null,
    override val correlationId: UUID = UUID.randomUUID(),
    override val userId: UUID? = null,
    override val version: Int = 1,
    override val occurredAt: Instant = Instant.now()
) : BaseDomainEvent(
    aggregateId = terminalId,
    aggregateType = aggregateType,
    causationId = causationId,
    correlationId = correlationId,
    userId = userId,
    version = version,
    occurredAt = occurredAt
)

/**
 * Событие: сертификат сохранён в ASOP_TERMINAL_CERTS (terminal-service → gateway).
 * Gateway-consumer обновит EventService (PENDING → COMPLETED) и положит
 * CertStoredResult в resultData для Android-клиента.
 */
data class CertStored(
    val certId: UUID,
    val terminalId: UUID,
    val terminalNumber: String?,
    val certSerial: String,
    val certificateBase64: String,
    val validFrom: Instant,
    val validUntil: Instant,
    val caChain: String,

    override val aggregateType: String = "TerminalCert",
    override val causationId: UUID? = null,
    override val correlationId: UUID = UUID.randomUUID(),
    override val userId: UUID? = null,
    override val version: Int = 1,
    override val occurredAt: Instant = Instant.now()
) : BaseDomainEvent(
    aggregateId = terminalId,
    aggregateType = aggregateType,
    causationId = causationId,
    correlationId = correlationId,
    userId = userId,
    version = version,
    occurredAt = occurredAt
)

/**
 * Событие: ошибка выпуска/сохранения сертификата (terminal-service → gateway).
 * Gateway-consumer переведёт EventService в FAILED.
 */
data class CertSignFailed(
    val terminalId: UUID?,
    val terminalSerial: String?,
    val reason: String,

    override val aggregateType: String = "TerminalCert",
    override val causationId: UUID? = null,
    override val correlationId: UUID = UUID.randomUUID(),
    override val userId: UUID? = null,
    override val version: Int = 1,
    override val occurredAt: Instant = Instant.now()
) : BaseDomainEvent(
    aggregateId = terminalId ?: UUID.randomUUID(),
    aggregateType = aggregateType,
    causationId = causationId,
    correlationId = correlationId,
    userId = userId,
    version = version,
    occurredAt = occurredAt
)