package ru.asop.api.reference.dto.request

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

data class OrganizerCreateRequest(
    @field:NotBlank
    @field:Size(max = 255)
    val organizerName: String
)
