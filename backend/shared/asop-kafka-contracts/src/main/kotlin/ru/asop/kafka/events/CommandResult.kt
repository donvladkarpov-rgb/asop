package ru.asop.kafka.events

import java.util.UUID

data class CommandResult(
    val eventId: UUID,
    val status: String,
    val resultData: String? = null,
    val errorMessage: String? = null
)
