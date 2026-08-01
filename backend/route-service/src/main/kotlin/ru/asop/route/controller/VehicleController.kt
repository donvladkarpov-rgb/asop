package ru.asop.route.controller

import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import ru.asop.api.route.controller.VehicleApi
import ru.asop.api.route.dto.request.VehicleCreateRequest
import ru.asop.api.route.dto.response.VehicleResponse
import ru.asop.route.repository.GenericRouteRepository
import ru.asop.route.repository.RouteTableRegistry
import ru.asop.route.service.VehicleService
import java.time.Instant
import java.util.UUID

@RestController
class VehicleController(
    private val service: VehicleService,
    private val repository: GenericRouteRepository
) : VehicleApi {

    @GetMapping("/delta")
    fun listDelta(
        @RequestParam(required = false) updatedAtSince: Instant?,
        @RequestParam(required = false) includeDeleted: Boolean,
        @RequestParam(required = false) regionId: UUID?,
        @RequestParam(required = false) carrierId: UUID?,
        @RequestParam(required = false, defaultValue = "10000") limit: Int
    ): Flux<Map<String, Any?>> {
        val info = RouteTableRegistry.resolve("vehicles") ?: return Flux.empty()
        return repository.findDelta(info, updatedAtSince, includeDeleted == true, regionId, carrierId, limit)
    }


    override fun list(): Flux<VehicleResponse> =
        service.list().flatMapMany { Flux.fromIterable(it) }.map { row ->
            VehicleResponse(
                id = row["vehicle_id"]?.toString() ?: "",
                carrierId = row["carrier_id"]?.toString(),
                vehicleTypeId = row["vehicle_type_id"]?.toString() ?: "",
                vehicleModelId = row["vehicle_model_id"]?.toString() ?: "",
                vehicleNumber = row["vehicle_number"]?.toString() ?: "",
                vehicleName = row["vehicle_name"]?.toString() ?: ""
            )
        }

    override fun get(id: String): Mono<ResponseEntity<VehicleResponse>> =
        service.getById(id).flatMap { row ->
            if (row.isEmpty()) Mono.just(ResponseEntity.notFound().build())
            else Mono.just(ResponseEntity.ok(rowToResponse(row)))
        }

    override fun create(request: VehicleCreateRequest): Mono<ResponseEntity<VehicleResponse>> {
        val data = mapOf(
            "id" to null,
            "carrierId" to request.carrierId,
            "vehicleTypeId" to request.vehicleTypeId,
            "vehicleModelId" to request.vehicleModelId,
            "vehicleNumber" to request.vehicleNumber,
            "vehicleName" to request.vehicleName
        )
        return service.create(data).map { ResponseEntity.status(201).body(rowToResponse(it)) }
    }

    override fun update(id: String, request: VehicleCreateRequest): Mono<ResponseEntity<VehicleResponse>> {
        val data = mapOf(
            "carrierId" to request.carrierId,
            "vehicleTypeId" to request.vehicleTypeId,
            "vehicleModelId" to request.vehicleModelId,
            "vehicleNumber" to request.vehicleNumber,
            "vehicleName" to request.vehicleName
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

    private fun rowToResponse(row: Map<String, Any?>): VehicleResponse = VehicleResponse(
        id = row["vehicle_id"]?.toString() ?: "",
        carrierId = row["carrier_id"]?.toString(),
        vehicleTypeId = row["vehicle_type_id"]?.toString() ?: "",
        vehicleModelId = row["vehicle_model_id"]?.toString() ?: "",
        vehicleNumber = row["vehicle_number"]?.toString() ?: "",
        vehicleName = row["vehicle_name"]?.toString() ?: ""
    )
}
