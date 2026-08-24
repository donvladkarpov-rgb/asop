package ru.asop.api.user.dto.request

import jakarta.validation.constraints.NotNull

data class UserDistributorCreateRequest(
    @field:NotNull val userId: String,
    @field:NotNull val cardsDistributorId: String
)
