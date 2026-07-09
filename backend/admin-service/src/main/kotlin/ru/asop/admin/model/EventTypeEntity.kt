package ru.asop.admin.model

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table

@Table("ASOP_EVENT_TYPES")
data class EventTypeEntity(
    @Id
    val eventType: String,
    val eventTypeName: String
)
