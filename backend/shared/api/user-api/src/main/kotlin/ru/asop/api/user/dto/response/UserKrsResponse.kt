package ru.asop.api.user.dto.response

data class UserKrsResponse(
    val userId: String,
    val auditServiceId: String,
    val serviceName: String? = null,
    val firstName: String? = null,
    val lastNameInitial: String? = null
)
