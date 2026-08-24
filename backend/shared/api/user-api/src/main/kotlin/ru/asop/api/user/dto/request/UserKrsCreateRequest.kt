package ru.asop.api.user.dto.request

import jakarta.validation.constraints.NotNull

data class UserKrsCreateRequest(
    @field:NotNull val userId: String,
    @field:NotNull val auditServiceId: String
)
