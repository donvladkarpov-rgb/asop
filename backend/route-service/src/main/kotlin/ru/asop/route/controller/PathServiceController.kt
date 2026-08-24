package ru.asop.route.controller

import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import ru.asop.api.route.controller.PathServiceApi
import ru.asop.api.route.dto.request.PathServiceCreateRequest
import ru.asop.api.route.dto.response.PathServiceResponse
import ru.asop.route.repository.GenericRouteRepository
import ru.asop.route.repository.RouteTableRegistry
import ru.asop.route.service.PathServiceService
import java.util.UUID

@RestController
class PathServiceController(
    private val service: PathServiceService,
    private val repository: GenericRouteRepository
) : PathServiceApi {

    @GetMapping("/delta")
    fun listDelta(
        @RequestParam(required = false) versionSince: Long?,
        @RequestParam(required = false) includeDeleted: Boolean,
        @RequestParam(required = false) regionId: UUID?,
        @RequestParam(required = false) carrierId: UUID?,
        @RequestParam(required = false, defaultValue = "10000") limit: Int
    ): Flux<Map<String, Any?>> {
        val info = RouteTableRegistry.resolve("path-services") ?: return Flux.empty()
        return repository.findDelta(info, versionSince, includeDeleted == true, regionId, carrierId, limit)
    }


    override fun list(regionId: UUID?, carrierId: UUID?): Flux<PathServiceResponse> =
        service.list(regionId, carrierId).flatMapMany { Flux.fromIterable(it) }.map { row ->
            PathServiceResponse(
                id = row["path_service_id"]?.toString() ?: "",
                pathId = row["path_id"]?.toString() ?: "",
                serviceId = row["service_id"]?.toString() ?: "",
                carrierId = row["carrier_id"]?.toString(),
                vehicleId = row["vehicle_id"]?.toString(),
                tariffTypeId = row["tariff_type_id"]?.toString(),
                price = (row["price"] as? Number)?.toDouble() ?: 0.0,
                isActive = row["is_active"] as? Boolean
            )
        }

    override fun get(id: String): Mono<ResponseEntity<PathServiceResponse>> =
        service.getById(id).flatMap { row ->
            if (row.isEmpty()) Mono.just(ResponseEntity.notFound().build())
            else Mono.just(ResponseEntity.ok(rowToResponse(row)))
        }

    override fun create(request: PathServiceCreateRequest): Mono<ResponseEntity<PathServiceResponse>> {
        val data = mapOf(
            "id" to null,
            "pathId" to request.pathId,
            "serviceId" to request.serviceId,
            "carrierId" to request.carrierId,
            "vehicleId" to request.vehicleId,
            "tariffTypeId" to request.tariffTypeId,
            "price" to request.price.toString(),
            "isActive" to request.isActive?.toString()
        )
        return service.create(data).map { ResponseEntity.status(201).body(rowToResponse(it)) }
    }

    override fun update(id: String, request: PathServiceCreateRequest): Mono<ResponseEntity<PathServiceResponse>> {
        val data = mapOf(
            "pathId" to request.pathId,
            "serviceId" to request.serviceId,
            "carrierId" to request.carrierId,
            "vehicleId" to request.vehicleId,
            "tariffTypeId" to request.tariffTypeId,
            "price" to request.price.toString(),
            "isActive" to request.isActive?.toString()
        )
        return service.update(id, data).flatMap { row ->
            if (row.isEmpty()) Mono.just(ResponseEntity.notFound().build())
            else Mono.just(ResponseEntity.ok(rowToResponse(row)))
        }
    }

    override fun delete(id: String): Mono<ResponseEntity<Void>> =
        service.delete(id).map { rows ->
            if (rows > 0) ResponseEntity.noContent().build()
            else ResponseEntity.notFound().build()
        }

    private fun rowToResponse(row: Map<String, Any?>): PathServiceResponse = PathServiceResponse(
        id = row["path_service_id"]?.toString() ?: "",
        pathId = row["path_id"]?.toString() ?: "",
        serviceId = row["service_id"]?.toString() ?: "",
        carrierId = row["carrier_id"]?.toString(),
        vehicleId = row["vehicle_id"]?.toString(),
        tariffTypeId = row["tariff_type_id"]?.toString(),
        price = (row["price"] as? Number)?.toDouble() ?: 0.0,
        isActive = row["is_active"] as? Boolean
    )
}
