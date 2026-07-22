package ru.asop.route.controller

import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import ru.asop.api.route.controller.ContractRouteApi
import ru.asop.api.route.dto.request.ContractRouteCreateRequest
import ru.asop.api.route.dto.response.ContractRouteResponse
import ru.asop.route.service.ContractRouteService

@RestController
class ContractRouteController(
    private val service: ContractRouteService
) : ContractRouteApi {

    override fun list(contractId: String?, routeId: String?): Flux<ContractRouteResponse> =
        service.list(contractId, routeId).map { row ->
            ContractRouteResponse(
                contractId = row["contract_id"]?.toString() ?: "",
                routeId = row["route_id"]?.toString() ?: "",
                routeNumber = row["route_number"]?.toString(),
                contractNumber = row["contract_number"]?.toString()
            )
        }

    override fun create(request: ContractRouteCreateRequest): Mono<ResponseEntity<ContractRouteResponse>> =
        service.create(request.contractId, request.routeId).map {
            ResponseEntity.status(201).body(
                ContractRouteResponse(
                    contractId = it["contract_id"]?.toString() ?: "",
                    routeId = it["route_id"]?.toString() ?: "",
                    routeNumber = it["route_number"]?.toString(),
                    contractNumber = it["contract_number"]?.toString()
                )
            )
        }

    override fun delete(contractId: String, routeId: String): Mono<ResponseEntity<Void>> =
        service.delete(contractId, routeId).map { rows ->
            if (rows > 0) ResponseEntity.noContent().build()
            else ResponseEntity.notFound().build()
        }
}
