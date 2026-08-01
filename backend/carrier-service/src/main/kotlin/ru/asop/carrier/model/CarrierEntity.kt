package ru.asop.carrier.model

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant
import java.util.UUID

@Table("ASOP_CARRIERS")
data class CarrierEntity(
    @Id
    val carrierId: UUID,
    val carrierName: String,
    val inn: String,
    val regionId: UUID,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
    val deletedAt: Instant? = null
)
