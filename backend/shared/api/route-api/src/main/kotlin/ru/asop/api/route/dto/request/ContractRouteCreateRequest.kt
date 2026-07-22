package ru.asop.api.route.dto.request

import jakarta.validation.constraints.NotNull

data class ContractRouteCreateRequest(
    @field:NotNull
    val contractId: String,
    @field:NotNull
    val routeId: String
)
