package ru.asop.api.route.dto.request

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull

data class RouteCreateRequest(
    @field:NotBlank
    val routeNumber: String,

    @field:NotBlank
    val routeName: String,

    @field:NotBlank
    val routeCategory: String,

    val organizerId: String? = null,

    val ministryRegistryNo: String? = null,

    @field:NotNull
    val regionId: String
)
