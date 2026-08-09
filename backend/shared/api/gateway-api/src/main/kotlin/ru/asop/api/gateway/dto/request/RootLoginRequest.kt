package ru.asop.api.gateway.dto.request

import jakarta.validation.constraints.NotBlank

data class RootLoginRequest(
    @field:NotBlank val username: String,
    @field:NotBlank val password: String
)