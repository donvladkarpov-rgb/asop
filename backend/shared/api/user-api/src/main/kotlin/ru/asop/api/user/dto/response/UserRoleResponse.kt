package ru.asop.api.user.dto.response

data class UserRoleResponse(
    val userId: String,
    val roleId: String? = null,
    val roleName: String? = null,
    val firstName: String? = null,
    val lastNameInitial: String? = null
)
