package ru.asop.api.reference.dto.request

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

data class RoleCreateRequest(
    @field:NotBlank
    @field:Size(max = 255)
    val roleName: String
)
