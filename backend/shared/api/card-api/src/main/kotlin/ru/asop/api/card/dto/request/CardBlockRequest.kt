package ru.asop.api.card.dto.request

import jakarta.validation.constraints.NotBlank
import java.util.UUID

data class CardBlockRequest(
    @field:NotBlank
    val blockType: String,

    val reason: String? = null,

    val regionId: UUID? = null,

    val carrierId: UUID? = null,

    val timezone: String? = null
)
