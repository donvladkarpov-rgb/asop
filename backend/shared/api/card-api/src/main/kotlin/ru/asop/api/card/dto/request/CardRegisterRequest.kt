package ru.asop.api.card.dto.request

import jakarta.validation.constraints.NotNull
import java.util.UUID

data class CardRegisterRequest(
    @field:NotNull
    val cardTypeId: UUID,

    val userId: UUID? = null,

    val regionId: UUID? = null,

    val carrierId: UUID? = null,

    val timezone: String? = null
)
