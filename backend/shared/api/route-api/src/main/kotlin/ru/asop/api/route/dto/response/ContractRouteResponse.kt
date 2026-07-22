package ru.asop.api.route.dto.response

data class ContractRouteResponse(
    val contractId: String,
    val routeId: String,
    val routeNumber: String? = null,
    val contractNumber: String? = null
)
