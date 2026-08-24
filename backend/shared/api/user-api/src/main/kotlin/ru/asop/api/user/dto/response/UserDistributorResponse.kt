package ru.asop.api.user.dto.response

data class UserDistributorResponse(
    val userId: String,
    val cardsDistributorId: String,
    val distributorName: String? = null,
    val firstName: String? = null,
    val lastNameInitial: String? = null
)
