package ru.asop.api.user.dto.request

import jakarta.validation.constraints.NotNull

data class UserRoleCreateRequest(
    @field:NotNull val userId: String,
    @field:NotNull val roleId: String
)
