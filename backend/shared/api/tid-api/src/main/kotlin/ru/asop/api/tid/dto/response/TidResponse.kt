package ru.asop.api.tid.dto.response

import java.time.Instant
import java.util.UUID

data class TidResponse(
    val id: UUID,
    /** Банковский договор (CONTRACTOR_TYPE='BANK'), на котором висит TID. */
    val contractId: UUID,
    /** Перевозчик-владелец договора (JOIN ASOP_CONTRACTS.CARRIER_ID). */
    val carrierId: UUID,
    val terminalId: UUID?,
    val tidValue: String,
    val status: String,
    val assignedAt: Instant?,
    val unassignedAt: Instant?,
    val createdAt: Instant,
    val updatedAt: Instant
)
