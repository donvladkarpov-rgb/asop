package ru.asop.api.reference.dto.request

import jakarta.validation.constraints.Size

data class RoleUpdateRequest(
    @field:Size(max = 255)
    val roleName: String? = null
)
