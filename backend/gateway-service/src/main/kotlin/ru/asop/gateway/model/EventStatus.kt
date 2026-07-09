package ru.asop.gateway.model

import java.time.Instant
import java.util.UUID

enum class EventState {
    PENDING,
    COMPLETED,
    FAILED
}

data class EventStatus(
    val eventId: UUID,
    val commandTopic: String,
    val state: EventState,
    val resultData: String? = null,
    val errorMessage: String? = null,
    val createdAt: Instant = Instant.now(),
    val completedAt: Instant? = null
)
