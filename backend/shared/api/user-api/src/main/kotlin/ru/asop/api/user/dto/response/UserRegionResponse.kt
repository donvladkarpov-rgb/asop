package ru.asop.api.user.dto.response

data class UserRegionResponse(
    val userId: String,
    val regionId: String,
    val regionName: String? = null,
    val firstName: String? = null,
    val lastNameInitial: String? = null
)
