package ru.asop.api.user.dto.request

import jakarta.validation.constraints.NotNull

data class UserRegionCreateRequest(
    @field:NotNull val userId: String,
    @field:NotNull val regionId: String
)
