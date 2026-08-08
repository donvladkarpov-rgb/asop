package ru.asop.admin.model

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant
import java.util.UUID

@Table("ASOP_CONFIG_PARAMS")
data class ConfigParamEntity(
    @Id
    val paramId: UUID,
    val regionId: UUID? = null,
    val organizerId: UUID? = null,
    val carrierId: UUID? = null,
    val cardsDistributorId: UUID? = null,
    val krsId: UUID? = null,
    val params: String,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
    val deletedAt: Instant? = null,
    val version: Long? = null
)