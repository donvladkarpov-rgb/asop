package ru.asop.api.reference.dto.request

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.util.UUID

data class ServiceCreateRequest(
    @field:NotBlank
    @field:Size(max = 100)
    val serviceName: String,

    @field:Size(max = 65535)
    val description: String? = null,

    @field:NotNull
    val priority: Int,

    @field:NotNull
    val regionId: UUID
)
