package ru.asop.api.route.dto.response

data class PathDiscountResponse(
    val id: String,
    val pathId: String,
    val carrierId: String?,
    val vehicleId: String?,
    val tariffTypeId: String?,
    val discountName: String,
    val discountType: String,
    val discountValue: Double,
    val validFrom: String,
    val validUntil: String?,
    val isActive: Boolean?
)
