package ru.asop.api.gateway.dto.response

import java.time.Instant
import java.util.UUID

/**
 * Ответ для всех mutation-запросов (POST/PUT/DELETE).
 * Gateway не ждёт результата обработки — только факт отправки в Kafka.
 *
 * @param eventId UUID события, которое было отправлено в Kafka (UUIDv7)
 * @param topic Kafka-топик, в который ушло событие
 * @param acceptedAt Когда запрос был принят
 * @param locationHint Подсказка, где потом искать ресурс (опционально)
 */
data class AcceptedResponse(
    val eventId: UUID,
    val topic: String,
    val acceptedAt: Instant,
    val locationHint: String? = null
)