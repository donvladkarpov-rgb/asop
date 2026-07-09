package ru.asop.api.reference.dto.response

import java.util.UUID

data class TariffTypeResponse(
    val id: UUID,
    val code: String,
    val name: String,
    val description: String?
)
