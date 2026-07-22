package ru.asop.api.user.dto.request

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

data class UserCreateRequest(
    @field:NotBlank @field:Size(max = 100)
    val firstName: String,
    @field:NotBlank @field:Size(max = 100)
    val lastName: String,
    @field:Size(max = 1)
    val lastNameInitial: String,
    @field:Size(max = 1)
    val patronymicInitial: String? = null,
    @field:Size(max = 20)
    val phone: String? = null,
    @field:Size(max = 255)
    val email: String? = null,
    val password: String? = null,
    val roleIds: List<String> = emptyList(),
    val carrierIds: List<String> = emptyList(),
    val regionIds: List<String> = emptyList()
)
