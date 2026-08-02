package ru.asop.card.model

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant
import java.util.UUID

@Table("ASOP_USER_BENEFITS")
data class UserBenefitEntity(
    @Id
    val assignmentId: UUID,
    val userId: UUID,
    val benefitId: UUID,
    val validFrom: Instant,
    val validUntil: Instant? = null,
    val syncVersion: Int = 1,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
    val deletedAt: Instant? = null,
    val version: Long? = null
)
