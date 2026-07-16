package ru.asop.admin.model

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant
import java.util.UUID

@Table("ASOP_BENEFITS")
data class BenefitEntity(
    @Id
    val benefitId: UUID,
    val benefitCode: String,
    val benefitName: String,
    val regionId: UUID,
    val description: String? = null,
    val isActive: Boolean = true,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now()
)
