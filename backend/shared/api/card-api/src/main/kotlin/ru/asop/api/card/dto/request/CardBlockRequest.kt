package ru.asop.api.card.dto.request

import jakarta.validation.constraints.NotBlank

data class CardBlockRequest(
    @field:NotBlank
    val blockType: String,

    val reason: String? = null
)
