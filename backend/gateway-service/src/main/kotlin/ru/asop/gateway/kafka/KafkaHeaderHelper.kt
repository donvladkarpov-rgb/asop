package ru.asop.gateway.kafka

/**
 * Промпт 012: helper extension для добавления X-Terminal-Seq в Kafka headers.
 * Sync API принимает header `X-Event-Seq` (длинное число типа Long) от терминала;
 * gateway транслирует в Kafka header `X-Terminal-Seq` для всех sync consumers.
 */
const val X_TERMINAL_SEQ = "X-Terminal-Seq"

/**
 * Extension ProducerRecord для добавления X-Terminal-Seq header если seq > 0.
 * seq=0 = legacy event без watermark, header не добавляется (consumer uses legacy path).
 */
fun org.apache.kafka.clients.producer.ProducerRecord<String, Any>.addTerminalSeq(seq: Long) {
    if (seq > 0L) {
        headers().add(X_TERMINAL_SEQ, seq.toString().encodeToByteArray())
    }
}
