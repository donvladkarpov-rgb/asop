package ru.asop.api.route.dto.request

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull

data class PathDiscountCreateRequest(
    @field:NotBlank(message = "Path ID is required")
    val pathId: String,

    val carrierId: String? = null,

    val vehicleId: String? = null,

    val tariffTypeId: String? = null,

    @field:NotBlank(message = "Discount name is required")
    val discountName: String,

    @field:NotBlank(message = "Discount type is required")
    val discountType: String,

    @field:NotNull(message = "Discount value is required")
    val discountValue: Double,

    @field:NotBlank(message = "Valid from is required")
    val validFrom: String,

    val validUntil: String? = null,

    val isActive: Boolean? = null
)
