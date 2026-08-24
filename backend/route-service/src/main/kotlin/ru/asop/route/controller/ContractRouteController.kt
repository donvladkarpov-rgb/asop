package ru.asop.route.controller

import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import ru.asop.api.route.controller.ContractRouteApi
import ru.asop.api.route.dto.request.ContractRouteCreateRequest
import ru.asop.api.route.dto.response.ContractRouteResponse
import ru.asop.route.repository.GenericRouteRepository
import ru.asop.route.repository.RouteTableRegistry
import ru.asop.route.service.ContractRouteService
import java.util.UUID

@RestController
class ContractRouteController(
    private val service: ContractRouteService,
    private val repository: GenericRouteRepository
) : ContractRouteApi {

    @GetMapping("/delta")
    fun listDelta(
        @RequestParam(required = false) versionSince: Long?,
        @RequestParam(required = false) includeDeleted: Boolean,
        @RequestParam(required = false) regionId: UUID?,
        @RequestParam(required = false) carrierId: UUID?,
        @RequestParam(required = false, defaultValue = "10000") limit: Int
    ): Flux<Map<String, Any?>> {
        // Промпт 010: GenericRouteRepository.findDelta использует JOIN ASOP_ROUTES.region_id
        // при regionJoinClause в ResourceInfo (contract-routes уже настроен).
        val info = RouteTableRegistry.resolve("contract-routes") ?: return Flux.empty()
        return repository.findDelta(info, versionSince, includeDeleted == true, regionId, carrierId, limit)
    }


    override fun list(contractId: String?, routeId: String?, regionId: UUID?, carrierId: UUID?): Flux<ContractRouteResponse> =
        service.list(contractId, routeId, regionId, carrierId).map { row ->
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
