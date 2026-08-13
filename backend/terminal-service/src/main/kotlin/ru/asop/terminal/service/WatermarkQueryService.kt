package ru.asop.terminal.service

import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Service
import reactor.core.publisher.Mono
import ru.asop.terminal.model.TerminalEventWatermark
import java.time.Instant
import java.util.UUID

/**
 * Промпт 012: query watermark + pending_seq_count per terminal.
 * Используется Frontend / gateway для bootstrap и catch-up.
 */
@Service
class WatermarkQueryService(
    private val db: DatabaseClient
) {
    fun getWatermark(terminalId: UUID): Mono<TerminalEventWatermark> {
        return Mono.zip(
            db.sql("""
                SELECT last_seq, TO_CHAR(updated_at, 'YYYY-MM-DD"T"HH24:MI:SS.MS"Z"')
                FROM asop_terminal_event_watermark
                WHERE terminal_id = :terminalId
            """.trimIndent())
                .bind("terminalId", terminalId)
                .fetch()
                .one()
                .map { row ->
                    TerminalEventWatermark(
                        terminalId = terminalId,
                        lastSeq = (row["last_seq"] as Number).toLong(),
                        pendingSeqCount = 0L,  // позже добавлю
                        lastEventAt = row["last_event_at"] as? String
                    )
                }
                .defaultIfEmpty(TerminalEventWatermark(terminalId, 0L, 0L, null)),
            db.sql("SELECT COUNT(*) FROM asop_terminal_pending_seq WHERE terminal_id = :terminalId")
                .bind("terminalId", terminalId)
                .fetch()
                .one()
                .map { row -> (row["count"] as Number).toLong() }
                .defaultIfEmpty(0L)
        ).map { tuple ->
            val wm = tuple.t1
            wm.copy(pendingSeqCount = tuple.t2)
        }
    }
}
