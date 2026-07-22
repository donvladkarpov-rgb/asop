package ru.asop.api.user.dto.request

import jakarta.validation.constraints.NotNull

data class UserCarrierCreateRequest(
    @field:NotNull val userId: String,
    @field:NotNull val carrierId: String
)
