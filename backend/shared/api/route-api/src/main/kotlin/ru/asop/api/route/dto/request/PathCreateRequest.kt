package ru.asop.api.route.dto.request

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull

data class PathCreateRequest(
    @field:NotBlank
    val routeId: String,

    @field:NotBlank
    val pathName: String,

    val routeObject: String? = null,

    val benefitPolicy: String? = null,

    val startStopId: String? = null,

    val endStopId: String? = null,

    val pathStartDate: String? = null,

    val pathEndDate: String? = null,

    val description: String? = null,

    @field:NotNull
    val regionId: String
)
