package ru.asop.route.controller

import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import ru.asop.api.route.controller.VehicleModelApi
import ru.asop.api.route.dto.request.VehicleModelCreateRequest
import ru.asop.api.route.dto.response.VehicleModelResponse
import ru.asop.route.repository.GenericRouteRepository
import ru.asop.route.repository.RouteTableRegistry
import ru.asop.route.service.VehicleModelService
import java.time.Instant
import java.util.UUID

@RestController
class VehicleModelController(
    private val service: VehicleModelService,
    private val repository: GenericRouteRepository
) : VehicleModelApi {

    @GetMapping("/delta")
    fun listDelta(
        @RequestParam(required = false) updatedAtSince: Instant?,
        @RequestParam(required = false) includeDeleted: Boolean,
        @RequestParam(required = false) regionId: UUID?,
        @RequestParam(required = false) carrierId: UUID?,
        @RequestParam(required = false, defaultValue = "10000") limit: Int
    ): Flux<Map<String, Any?>> {
        val info = RouteTableRegistry.resolve("vehicle-models") ?: return Flux.empty()
        return repository.findDelta(info, updatedAtSince, includeDeleted == true, regionId, carrierId, limit)
    }


    override fun list(): Flux<VehicleModelResponse> =
        service.list().flatMapMany { Flux.fromIterable(it) }.map { row ->
            VehicleModelResponse(
                id = row["vehicle_model_id"]?.toString() ?: "",
                modelName = row["model_name"]?.toString() ?: ""
            )
        }

    override fun get(id: String): Mono<ResponseEntity<VehicleModelResponse>> =
        service.getById(id).flatMap { row ->
            if (row.isEmpty()) Mono.just(ResponseEntity.notFound().build())
            else Mono.just(ResponseEntity.ok(rowToResponse(row)))
        }

    override fun create(request: VehicleModelCreateRequest): Mono<ResponseEntity<VehicleModelResponse>> {
        val data = mapOf("modelName" to request.modelName)
        return service.create(data).map { ResponseEntity.status(201).body(rowToResponse(it)) }
    }

    override fun update(id: String, request: VehicleModelCreateRequest): Mono<ResponseEntity<VehicleModelResponse>> {
        val data = mapOf("modelName" to request.modelName)
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

    private fun rowToResponse(row: Map<String, Any?>): VehicleModelResponse = VehicleModelResponse(
        id = row["vehicle_model_id"]?.toString() ?: "",
        modelName = row["model_name"]?.toString() ?: ""
    )
}
