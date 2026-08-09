package ru.asop.api.crypto.dto.request

import jakarta.validation.constraints.NotBlank

data class CardIdentitySignRequest(
    @field:NotBlank val identityJson: String
)
