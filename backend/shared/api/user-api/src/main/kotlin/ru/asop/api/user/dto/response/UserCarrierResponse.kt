package ru.asop.api.user.dto.response

data class UserCarrierResponse(
    val userId: String,
    val carrierId: String,
    val carrierName: String? = null,
    val firstName: String? = null,
    val lastNameInitial: String? = null
)
