package ru.asop.carrier.model

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant
import java.util.UUID

@Table("ASOP_CARDS_DISTRIBUTORS")
data class CardsDistributorEntity(
    @Id val cardsDistributorId: UUID,
    val distributorName: String,
    val inn: String,
    val kpp: String? = null,
    val legalAddress: String? = null,
    val contactPhone: String? = null,
    val contactEmail: String? = null,
    val isActive: Boolean = true,
    val createdAt: Instant,
    val updatedAt: Instant
)
