package ru.asop.api.reference.dto.response

import java.util.UUID

data class RoleResponse(
    val id: UUID,
    val roleName: String
)
