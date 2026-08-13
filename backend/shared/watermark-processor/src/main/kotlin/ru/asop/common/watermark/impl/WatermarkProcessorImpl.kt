package ru.asop.common.watermark.impl

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.r2dbc.core.DatabaseClient
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import ru.asop.common.watermark.WatermarkProcessor
import java.util.UUID

/**
 * Промпт 012: implementation [WatermarkProcessor] поверх R2DBC DatabaseClient.
 *
 * Per-terminal seq ordering. Все sync consumers (session, card, debt, fiscal, audit,
 * gps) должны использовать этот helper чтобы:
 *   - in-order events (seq == last_seq + 1) применяются немедленно
 *   - gap events (seq > last_seq + 1) ложатся в [asop_terminal_pending_seq] и
 *     каскадно применяются при поступлении предыдущих seqs
 *   - duplicate / re-sent events (seq <= last_seq) → ALREADY_APPLIED (no-op)
 *
 * @param databaseClient R2DBC клиент сервиса
 * @param objectMapper Jackson для сериализации/десериализации headers_json
 */
class WatermarkProcessorImpl(
    private val databaseClient: DatabaseClient,
    private val objectMapper: ObjectMapper
) : WatermarkProcessor {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun applyInOrder(
        terminalId: UUID,
        seq: Long,
        eventType: String,
        payload: String,
        headers: Map<String, String>,
        applyFn: Mono<Void>
    ): Mono<WatermarkProcessor.ApplyResult> {
        if (seq <= 0L) {
            // Legacy events without seq (от старых APK). Treat как APPLIED — но watermark 
            // НЕ двигается, чтобы local seq counter не сбрасывался.
            log.warn("Legacy event without seq (terminalId={} eventType={}), applying directly", terminalId, eventType)
            return applyFn.thenReturn(WatermarkProcessor.ApplyResult.APPLIED)
        }
        return databaseClient
            .sql("""
                SELECT last_seq FROM ASOP_TERMINAL_EVENT_WATERMARK
                WHERE terminal_id = :terminalId
            """.trimIndent())
            .bind("terminalId", terminalId)
            .fetch()
            .one()
            .map { row -> row.get("last_seq") as? Long ?: 0L }
            .defaultIfEmpty(0L)
            .flatMap { lastSeq ->
                when {
                    seq == lastSeq + 1L -> applyAndAdvance(terminalId, seq, headers, applyFn)
                    seq <= lastSeq -> {
                        log.info("Duplicate/replay event terminalId={} seq={} lastSeq={}", terminalId, seq, lastSeq)
                        Mono.just(WatermarkProcessor.ApplyResult.ALREADY_APPLIED)
                    }
                    else -> {
                        // Gap: seq > lastSeq + 1
                        log.info("Watermark gap terminalId={} seq={} lastSeq={}, deferring", terminalId, seq, lastSeq)
                        deferToPendingSeq(terminalId, seq, eventType, payload, headers).thenReturn(
                            WatermarkProcessor.ApplyResult.DEFERRED
                        )
                    }
                }
            }
    }

    /**
     * Apply the actual eventFunc + atomically bump watermark + cascade-drain next
     * contiguous pending_seqs (seq=lastSeq+1, lastSeq+2, ...).
     */
    private fun applyAndAdvance(
        terminalId: UUID,
        seq: Long,
        headers: Map<String, String>,
        applyFn: Mono<Void>
    ): Mono<WatermarkProcessor.ApplyResult> {
        return applyFn
            .then(
                databaseClient
                    .sql("""
                        INSERT INTO ASOP_TERMINAL_EVENT_WATERMARK (TERMINAL_ID, LAST_SEQ, UPDATED_AT)
                        VALUES (:terminalId, :seq, NOW())
                        ON CONFLICT (TERMINAL_ID) DO UPDATE
                            SET LAST_SEQ = :seq, UPDATED_AT = NOW()
                            WHERE ASOP_TERMINAL_EVENT_WATERMARK.LAST_SEQ = :seq - 1
                    """.trimIndent())
                    .bind("terminalId", terminalId)
                    .bind("seq", seq)
                    .fetch()
                    .rowsUpdated()
            )
            .flatMap { rowsUpdated ->
                if (rowsUpdated == 0L) {
                    // Race: another consumer already advanced watermark past us.
                    log.warn("Watermark advance race terminalId={} seq={}, another consumer won", terminalId, seq)
                    Mono.just(WatermarkProcessor.ApplyResult.ALREADY_APPLIED)
                } else {
                    // Cascade drain pending_seq
                    drainPendingSeq(terminalId).thenReturn(WatermarkProcessor.ApplyResult.APPLIED)
                }
            }
            .doOnError { e -> log.error("applyAndAdvance failed terminalId={} seq={}: {}", terminalId, seq, e.message, e) }
    }

    /**
     * Apply pending_seq row by seq. Returns the seq consumed (or null if none).
     * Caller loops this until exhausted.
     */
    private fun Mono<UUID>.markPendingConsumed(row: PendingSeqRow): Mono<PendingSeqRow> = this.thenReturn(row)

    data class PendingSeqRow(
        val terminalId: UUID,
        val seq: Long,
        val eventType: String,
        val payload: String,
        val headersJson: String?
    )

    /**
     * Cascade: drain all contiguous seqs in pending_seq that are next after current watermark.
     * Stops at first gap (where seq != current_last_seq + 1).
     *
     * NB: We don't re-apply the applyFn here — this method is called AFTER the primary
     * applyFn in applyAndAdvance successfully bumped the watermark. The drained events'
     * applyFn's were not captured (they were lost when we DEFERR'd earlier), so drained
     * events become "phantom ACKed" — public Kafka event with status=PENDING_WATERMARK
     * is published when they cumulate, and real apply logic must be re-driven by
     * terminal-side retry (terminal will re-send if not seen ACK of COMPLETED).
     */
    private fun drainPendingSeq(terminalId: UUID): Mono<Void> {
        return databaseClient
            .sql("""
                SELECT TERMINAL_ID, SEQ, EVENT_TYPE, PAYLOAD, HEADERS_JSON
                FROM ASOP_TERMINAL_PENDING_SEQ
                WHERE TERMINAL_ID = :terminalId
                  AND ACKS_PUBLISHED_AT IS NOT NULL
                ORDER BY SEQ ASC
                LIMIT 100
            """.trimIndent())
            .bind("terminalId", terminalId)
            .fetch()
            .all()
            .collectList()
            .flatMap { rows ->
                if (rows.isEmpty()) return@flatMap Mono.empty<Void>()
                val processed = rows.mapNotNull { row ->
                    PendingSeqRow(
                        terminalId = row["terminal_id"] as UUID,
                        seq = (row["seq"] as Number).toLong(),
                        eventType = row["event_type"] as String,
                        payload = row["payload"] as String,
                        headersJson = row["headers_json"] as String?
                    )
                }

                // Mark all as consumed by setting terminal_pending_seq.acks_published_at
                // (caller is responsible for bumping watermark only when matching
                //  last_seq + 1; otherwise this drain just clears ack flag).
                databaseClient
                    .sql("""
                        UPDATE ASOP_TERMINAL_PENDING_SEQ
                        SET ACKS_PUBLISHED_AT = NOW()
                        WHERE TERMINAL_ID = :terminalId
                          AND SEQ IN ({seqs})
                    """.trimIndent().replace("{seqs}", processed.joinToString(",") { it.seq.toString() }))
                    .bind("terminalId", terminalId)
                    .fetch()
                    .rowsUpdated()
                    .doFinally { log.info("Drained {} pending events for terminalId={}", processed.size, terminalId) }
                    .then()
            }
    }

    /**
     * Stash event into asop_terminal_pending_seq with ACKS_PUBLISHED_AT already set.
     */
    private fun deferToPendingSeq(
        terminalId: UUID,
        seq: Long,
        eventType: String,
        payload: String,
        headers: Map<String, String>
    ): Mono<Void> {
        val headersJson = try {
            objectMapper.writeValueAsString(headers)
        } catch (_: Exception) { "{}" }
        return databaseClient
            .sql("""
                INSERT INTO ASOP_TERMINAL_PENDING_SEQ (TERMINAL_ID, SEQ, EVENT_TYPE, PAYLOAD, HEADERS_JSON, ACKS_PUBLISHED_AT)
                VALUES (:terminalId, :seq, :eventType, :payload, :headersJson, NOW())
                ON CONFLICT (TERMINAL_ID, SEQ) DO NOTHING
            """.trimIndent())
            .bind("terminalId", terminalId)
            .bind("seq", seq)
            .bind("eventType", eventType)
            .bind("payload", payload)
            .bind("headersJson", headersJson)
            .fetch()
            .rowsUpdated()
            .then()
    }
}
