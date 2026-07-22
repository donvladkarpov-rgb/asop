package ru.asop.api.user.dto.response

data class UserResponse(
    val id: String,
    val firstName: String,
    val lastName: String = "",
    val lastNameInitial: String,
    val patronymicInitial: String? = null,
    val phone: String? = null,
    val email: String? = null,
    val keycloakId: String? = null
)
