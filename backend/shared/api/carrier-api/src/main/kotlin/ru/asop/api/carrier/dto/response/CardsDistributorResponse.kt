package ru.asop.api.carrier.dto.response

import java.time.Instant
import java.util.UUID

data class CardsDistributorResponse(
    val id: UUID,
    val distributorName: String,
    val inn: String,
    val kpp: String?,
    val legalAddress: String?,
    val contactPhone: String?,
    val contactEmail: String?,
    val isActive: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
    val contracts: List<ContractResponse> = emptyList()
)
