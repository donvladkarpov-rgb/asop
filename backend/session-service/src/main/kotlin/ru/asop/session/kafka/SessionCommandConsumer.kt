package ru.asop.session.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import org.apache.kafka.clients.producer.ProducerRecord
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.core.reactive.ReactiveKafkaProducerTemplate
import org.springframework.messaging.handler.annotation.Header
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Component
import reactor.core.publisher.Mono
import ru.asop.common.kafka.KafkaTopic
import ru.asop.common.watermark.WatermarkProcessor
import ru.asop.common.watermark.impl.WatermarkProcessorImpl
import ru.asop.kafka.events.CommandResult
import ru.asop.kafka.events.session.SessionOpenedEvent
import ru.asop.kafka.events.session.SessionClosedEvent
import ru.asop.session.repository.SessionRepository
import ru.asop.session.service.SessionService
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Промпт 012: [SessionCommandConsumer] — обрабатывает SessionOpen + SessionClose через
 * [WatermarkProcessor] для per-terminal seq ordering.
 *
 * Watermark header `X-Terminal-Seq` приходит из gateway (который пробрасывает HTTP
 * header `X-Event-Seq` от терминала при POST /api/v1/sync/sessions/...).
 *
 * Логика watermark:
 *   - seq=0 (legacy events без seq): apply напрямую, watermark НЕ двигается
 *   - seq == last_seq + 1: apply + watermark += 1 + cascade drain pending_seq
 *   - seq >  last_seq + 1: defer в asop_terminal_pending_seq table
 *   - seq <= last_seq: ALREADY_APPLIED idempotent no-op
 */
@Component
class SessionCommandConsumer(
    private val sessionRepository: SessionRepository,
    private val db: DatabaseClient,
    private val objectMapper: ObjectMapper,
    private val kafkaTemplate: ReactiveKafkaProducerTemplate<String, Any>,
    private val sessionService: SessionService
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val watermark: WatermarkProcessor = WatermarkProcessorImpl(db, objectMapper)

    @KafkaListener(topics = ["\${asop.kafka.topics.session-commands}"])
    fun handleCommand(
        json: String,
        @Header(name = "X-Event-Id", required = false) eventIdHeader: ByteArray?,
        @Header(name = "X-Terminal-Seq", required = false) terminalSeqHeader: ByteArray?
    ) {
        log.debug("Received session command: {}", json.take(200))
        val eventId = parseEventId(eventIdHeader, json)
        val seq = parseSeq(terminalSeqHeader)

        try {
            val node = objectMapper.readTree(json)
            val eventType = node.get("eventType")?.asText()

            when (eventType) {
                "SessionOpened" -> {
                    val event = objectMapper.treeToValue(node, SessionOpenedEvent::class.java)
                    handleSessionOpened(event, eventId, seq, json)
                }
                "SessionClosed" -> {
                    val event = objectMapper.treeToValue(node, SessionClosedEvent::class.java)
                    handleSessionClosed(event, eventId, seq, json)
                }
                else -> log.warn("Unknown session event type: {}", eventType)
            }
        } catch (e: Exception) {
            log.error("Failed to deserialize session command: {}", e.message, e)
            publishFailed(eventId, e.message ?: "Deserialization error")
        }
    }

    private fun handleSessionOpened(event: SessionOpenedEvent, eventId: UUID, seq: Long, rawJson: String) {
        log.info("Processing SessionOpenedEvent: sessionId={} seq={}", event.sessionId, seq)

        // Промпт 011: 409 Conflict guard если открывается TRIP/рейс при наличии открытого TRIP
        // в той же родительской SHIFT-смене. Проверяем, что нет уже открытой TRIP-сессии.
        val parent0: UUID? = event.parentSessionId
        val conflictCheckFuture: Mono<Void> = if (parent0 != null) {
            val pid = parent0
            db.sql("""
                SELECT 1 FROM ASOP_SESSIONS
                WHERE PARENT_SESSION_ID = :parentSessionId
                  AND STATUS = 'IN_PROGRESS'
                LIMIT 1
            """)
                .bind("parentSessionId", pid)
                .fetch()
                .rowsUpdated()
                .flatMap { count ->
                    if (count > 0L) {
                        Mono.error<Void>(IllegalStateException(
                            "409 Conflict: open TRIP/session already exists under parent=$pid"
                        ))
                    } else {
                        Mono.empty<Void>()
                    }
                }
        } else {
            Mono.empty()
        }

        val startedAt = event.startedAt
        val expirationTime = startedAt.plus(Duration.ofHours(8))

        val insertSql = """INSERT INTO ASOP_SESSIONS
            (SESSION_ID, SESSION_TYPE_ID, PARENT_SESSION_ID, TERMINAL_ID, TID_ID,
             OPENED_BY_USER_ID, CARD_ID, PATH_ID, VEHICLE_ID,
             ATTRIBUTES, STARTED_AT, CLOSED_AT, STARTED_AT_LOCAL, CLOSED_AT_LOCAL, EXPIRATION_TIME, STATUS)
            VALUES (:sessionId, :sessionTypeId, :parentSessionId, :terminalId, :tidId,
                    :openedByUserId, :cardId, :pathId, :vehicleId,
                    :attributes, :startedAt, NULL, :startedAtLocal, NULL, :expirationTime, :status)
            ON CONFLICT (SESSION_ID) DO NOTHING"""

        val builder: (DatabaseClient.GenericExecuteSpec) -> DatabaseClient.GenericExecuteSpec = { base ->
            var spec = base
                .bind("sessionId", event.sessionId)
                .bind("sessionTypeId", event.sessionTypeId)
                .bind("startedAt", startedAt)
                .bind("startedAtLocal", startedAt)
                .bind("expirationTime", expirationTime)
                .bind("status", "IN_PROGRESS")

            val parent: UUID? = event.parentSessionId
            val terminal: UUID? = event.terminalId
            val tid: UUID? = event.tidId
            val openedBy: UUID? = event.openedByUserId
            val card: UUID? = event.cardId
            val path: UUID? = event.pathId
            val vehicle: UUID? = event.vehicleId
            val attr: String? = event.attributes

            spec = if (parent != null) spec.bind("parentSessionId", parent) else spec.bindNull("parentSessionId", UUID::class.java)
            spec = if (terminal != null) spec.bind("terminalId", terminal) else spec.bindNull("terminalId", UUID::class.java)
            spec = if (tid != null) spec.bind("tidId", tid) else spec.bindNull("tidId", UUID::class.java)
            spec = if (openedBy != null) spec.bind("openedByUserId", openedBy) else spec.bindNull("openedByUserId", UUID::class.java)
            spec = if (card != null) spec.bind("cardId", card) else spec.bindNull("cardId", UUID::class.java)
            spec = if (path != null) spec.bind("pathId", path) else spec.bindNull("pathId", UUID::class.java)
            spec = if (vehicle != null) spec.bind("vehicleId", vehicle) else spec.bindNull("vehicleId", UUID::class.java)
            spec = if (attr != null) spec.bind("attributes", attr) else spec.bindNull("attributes", String::class.java)
            spec
        }

        val applyMono: Mono<Void> = conflictCheckFuture.flatMap { _ ->
            db.sql(insertSql).let(builder).fetch().rowsUpdated().then()
        }

        val terminalId: UUID? = event.terminalId
        if (terminalId == null) {
            // legacy path: apply directly without watermark (no terminal_id = нельзя watermark)
            applyMono
                .doOnSuccess {
                    log.info("Session saved: {}", event.sessionId)
                    publishComplete(eventId, mapOf("sessionId" to event.sessionId.toString(), "status" to "IN_PROGRESS"))
                }
                .doOnError { e ->
                    log.error("Failed to save session: {}", e.message, e)
                    val msg = e.message ?: "Save error"
                    publishFailed(eventId, if (msg.startsWith("409")) msg else msg)
                }
                .subscribe()
        } else {
            val headers = mapOf("X-Carrier-Id" to (event.attributes ?: ""))
            val tid = terminalId
            watermark.applyInOrder(tid, seq, "SessionOpened", rawJson, headers, applyMono)
                .doOnSuccess { result ->
                    when (result) {
                        WatermarkProcessor.ApplyResult.APPLIED -> {
                            log.info("Session saved [seq={}]: sessionId={}", seq, event.sessionId)
                            publishComplete(eventId, mapOf("sessionId" to event.sessionId.toString(), "status" to "IN_PROGRESS"))
                        }
                        WatermarkProcessor.ApplyResult.DEFERRED -> {
                            log.info("Session deferred to watermark pending [seq={}]: sessionId={}", seq, event.sessionId)
                            publishPendingWatermark(eventId, mapOf("sessionId" to event.sessionId.toString(), "seq" to seq.toString()))
                        }
                        WatermarkProcessor.ApplyResult.ALREADY_APPLIED -> {
                            log.info("Session already applied [seq={}]: sessionId={}", seq, event.sessionId)
                            publishComplete(eventId, mapOf("sessionId" to event.sessionId.toString(), "status" to "IN_PROGRESS"))
                        }
                    }
                }
                .doOnError { e ->
                    log.error("Failed to save session: {}", e.message, e)
                    val msg = e.message ?: "Save error"
                    publishFailed(eventId, if (msg.startsWith("409")) msg else msg)
                }
                .subscribe()
        }
    }

    private fun handleSessionClosed(event: SessionClosedEvent, eventId: UUID, seq: Long, rawJson: String) {
        log.info("Processing SessionClosedEvent: sessionId={} seq={}", event.sessionId, seq)

        val terminalId: UUID? = sessionRepository.findById(event.sessionId)
            .map { it.terminalId ?: throw IllegalStateException("Session ${event.sessionId} has no terminal_id") }
            .defaultIfEmpty(java.util.UUID(0L, 0L))
            .map { if (it == java.util.UUID(0L, 0L)) null else it }
            .block()

        val closeFn: Mono<Void> = sessionRepository.findById(event.sessionId)
            .switchIfEmpty(Mono.error(IllegalStateException("Session not found: ${event.sessionId}")))
            .flatMap { _ ->
                val requester = event.closedByUserId
                val authCheck = if (requester != null) {
                    sessionService.canClose(event.sessionId, requester)
                } else {
                    // Промпт 011 §4: без реального closedByUserId (term. CN = null после орт.) —
                    // оставляем закрытие для совместимости (легаси-поток без карты-ключа).
                    Mono.just(true)
                }
                authCheck.flatMap { allowed ->
                    if (!allowed) {
                        Mono.error(SecurityException(
                            "Requester $requester is not authorized to close session ${event.sessionId}"
                        ))
                    } else {
                        val sql = """UPDATE ASOP_SESSIONS
                            SET STATUS = 'CLOSED',
                                CLOSED_AT = :closedAt,
                                CLOSED_AT_LOCAL = :closedAt,
                                CLOSED_BY_USER_ID = :closedByUserId
                            WHERE SESSION_ID = :sessionId AND STATUS <> 'CLOSED'"""
                        val spec = db.sql(sql)
                            .bind("closedAt", event.closedAt)
                            .bind("sessionId", event.sessionId)
                        val closed: UUID? = event.closedByUserId
                        val finalSpec = if (closed != null) spec.bind("closedByUserId", closed)
                        else spec.bindNull("closedByUserId", UUID::class.java)
                        finalSpec.fetch().rowsUpdated().then()
                    }
                }
            }

        if (terminalId == null) {
            closeFn
                .doOnSuccess {
                    log.info("Session closed: {}", event.sessionId)
                    publishComplete(eventId, mapOf("sessionId" to event.sessionId.toString(), "status" to "CLOSED"))
                }
                .doOnError { e ->
                    log.error("Failed to close session: {}", e.message, e)
                    publishFailed(eventId, e.message ?: "Close error")
                }
                .subscribe()
        } else {
            val headers = emptyMap<String, String>()
            watermark.applyInOrder(terminalId, seq, "SessionClosed", rawJson, headers, closeFn)
                .doOnSuccess { result ->
                    when (result) {
                        WatermarkProcessor.ApplyResult.APPLIED, WatermarkProcessor.ApplyResult.ALREADY_APPLIED -> {
                            log.info("Session closed [seq={}]: sessionId={}", seq, event.sessionId)
                            publishComplete(eventId, mapOf("sessionId" to event.sessionId.toString(), "status" to "CLOSED"))
                        }
                        WatermarkProcessor.ApplyResult.DEFERRED -> {
                            log.info("Session close deferred [seq={}]: sessionId={}", seq, event.sessionId)
                            publishPendingWatermark(eventId, mapOf("sessionId" to event.sessionId.toString(), "seq" to seq.toString()))
                        }
                    }
                }
                .doOnError { e ->
                    log.error("Failed to close session: {}", e.message, e)
                    publishFailed(eventId, e.message ?: "Close error")
                }
                .subscribe()
        }
    }

    private fun publishComplete(eventId: UUID, data: Map<String, String>) {
        val json = objectMapper.writeValueAsString(data)
        val result = CommandResult(eventId = eventId, status = "COMPLETED", resultData = json)
        kafkaTemplate.send(buildResultRecord(eventId, result)).subscribe()
    }

    private fun publishPendingWatermark(eventId: UUID, data: Map<String, String>) {
        val json = objectMapper.writeValueAsString(data)
        val result = CommandResult(eventId = eventId, status = "PENDING_WATERMARK", resultData = json)
        kafkaTemplate.send(buildResultRecord(eventId, result)).subscribe()
    }

    private fun publishFailed(eventId: UUID, errorMessage: String) {
        val result = CommandResult(eventId = eventId, status = "FAILED", errorMessage = errorMessage)
        kafkaTemplate.send(buildResultRecord(eventId, result)).subscribe()
    }

    private fun buildResultRecord(eventId: UUID, result: CommandResult): ProducerRecord<String, Any> {
        val record = ProducerRecord(KafkaTopic.SESSION_EVENTS, eventId.toString(), result as Any)
        record.headers().add("X-Event-Id", eventId.toString().encodeToByteArray())
        return record
    }

    private fun parseEventId(header: ByteArray?, json: String): UUID {
        if (header != null) {
            try { return UUID.fromString(String(header)) } catch (_: IllegalArgumentException) { }
        }
        val fromPayload = objectMapper.readTree(json).get("eventId")?.asText()
        if (fromPayload != null) {
            try { return UUID.fromString(fromPayload) } catch (_: IllegalArgumentException) { }
        }
        log.error("No valid eventId in header or payload, generating random (correlation will break)")
        return UUID.randomUUID()
    }

    private fun parseSeq(header: ByteArray?): Long {
        if (header == null) return 0L  // legacy events
        return try { String(header).toLong() } catch (_: NumberFormatException) { 0L }
    }
}
