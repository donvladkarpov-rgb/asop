package ru.asop.api.reference.dto.request

import jakarta.validation.constraints.Size

data class OrganizerUpdateRequest(
    @field:Size(max = 255)
    val organizerName: String? = null
)
