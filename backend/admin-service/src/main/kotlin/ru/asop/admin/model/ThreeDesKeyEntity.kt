package ru.asop.admin.model

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant
import java.util.UUID

@Table("ASOP_3DES_KEYS")
data class ThreeDesKeyEntity(
    @Id
    val keyId: UUID,
    val keyMaterial: String,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
    val deletedAt: Instant? = null,
    val version: Long? = null
)