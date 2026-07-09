package ru.asop.admin.model

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.util.UUID

@Table("ASOP_ORGANIZERS")
data class OrganizerEntity(
    @Id
    val organizerId: UUID,
    val organizerName: String
)
