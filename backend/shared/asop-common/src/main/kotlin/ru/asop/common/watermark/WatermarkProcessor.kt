package ru.asop.common.watermark

import reactor.core.publisher.Mono

/**
 * Промпт 012: контракт WatermarkProcessor для per-terminal event ordering.
 *
 * Все sync consumers (session, card, debt, fiscal, audit, gps) обязаны пропускать
 * каждый event через [WatermarkProcessor.applyInOrder] ПЕРЕД записью в свои таблицы.
 * Это гарантирует:
 *
 *   1. seq == last_seq + 1: APPLIED — реальная функция [applyFn] запускается;
 *      watermark инкрементируется; выполняется cascade-drain pending_seq для
 *      следующих порядковых seq.
 *
 *   2. seq > last_seq + 1: DEFERRED — событие кладётся в [asop_terminal_pending_seq]
 *      с ACKs_PUBLISHED_AT=now() (промежуточный ACK для gateway terminal polling),
 *      applyFn не вызывается. Когда предыдущие seq прибудут, drain вытащит и
 *      применит это событие.
 *
 *   3. seq <= last_seq: ALREADY_APPLIED — идемпотентный нулевой эффект. Защищает
 *      от дублей при retry/offline-reorder. applyFn не вызывается.
 *
 * Реализация — в каждом sync consumer через DatabaseClient-обёртку (inline helper).
 */
interface WatermarkProcessor {

    fun applyInOrder(
        terminalId: java.util.UUID,
        seq: Long,
        eventType: String,
        payload: String,
        headers: Map<String, String>,
        applyFn: Mono<Void>
    ): Mono<ApplyResult>

    enum class ApplyResult {
        /** Event was applied. applyFn completed successfully. */
        APPLIED,

        /** Event was queued in asop_terminal_pending_seq. applyFn was NOT invoked. */
        DEFERRED,

        /** Event seq <= last watermark. Idempotent no-op, applyFn was NOT invoked. */
        ALREADY_APPLIED
    }
}

/**
 * Helper для записи отложенного события в asop_terminal_pending_seq.
 * Headers сериализуются в JSON-строку (raw JSON, без Jackson annotation overhead).
 */
object PendingSeqHeadersCodec {
    fun encode(headers: Map<String, String>): String =
        headers.entries.joinToString(prefix = "{", postfix = "}") { (k, v) ->
            "\"${escape(k)}\":\"${escape(v)}\""
        }

    fun decode(json: String?): Map<String, String> {
        if (json.isNullOrBlank() || json == "{}") return emptyMap()
        // Простой reverse — мы пишем только ASCII без escapes.
        val regex = Regex("\"([^\"]+)\":\"([^\"]+)\"")
        return regex.findAll(json).associate { it.groupValues[1] to it.groupValues[2] }
    }

    private fun escape(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"")
}
