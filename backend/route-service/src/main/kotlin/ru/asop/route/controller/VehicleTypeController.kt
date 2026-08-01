package ru.asop.route.controller

import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import ru.asop.api.route.controller.VehicleTypeApi
import ru.asop.api.route.dto.request.VehicleTypeCreateRequest
import ru.asop.api.route.dto.response.VehicleTypeResponse
import ru.asop.route.repository.GenericRouteRepository
import ru.asop.route.repository.RouteTableRegistry
import ru.asop.route.service.VehicleTypeService
import java.time.Instant
import java.util.UUID

@RestController
class VehicleTypeController(
    private val service: VehicleTypeService,
    private val repository: GenericRouteRepository
) : VehicleTypeApi {

    @GetMapping("/delta")
    fun listDelta(
        @RequestParam(required = false) updatedAtSince: Instant?,
        @RequestParam(required = false) includeDeleted: Boolean,
        @RequestParam(required = false) regionId: UUID?,
        @RequestParam(required = false) carrierId: UUID?,
        @RequestParam(required = false, defaultValue = "10000") limit: Int
    ): Flux<Map<String, Any?>> {
        val info = RouteTableRegistry.resolve("vehicle-types") ?: return Flux.empty()
        return repository.findDelta(info, updatedAtSince, includeDeleted == true, regionId, carrierId, limit)
    }


    override fun list(): Flux<VehicleTypeResponse> =
        service.list().flatMapMany { Flux.fromIterable(it) }.map { row ->
            VehicleTypeResponse(
                id = row["vehicle_type_id"]?.toString() ?: "",
                typeName = row["type_name"]?.toString() ?: ""
            )
        }

    override fun get(id: String): Mono<ResponseEntity<VehicleTypeResponse>> =
        service.getById(id).flatMap { row ->
            if (row.isEmpty()) Mono.just(ResponseEntity.notFound().build())
            else Mono.just(ResponseEntity.ok(rowToResponse(row)))
        }

    override fun create(request: VehicleTypeCreateRequest): Mono<ResponseEntity<VehicleTypeResponse>> {
        val data = mapOf("typeName" to request.typeName)
        return service.create(data).map { ResponseEntity.status(201).body(rowToResponse(it)) }
    }

    override fun update(id: String, request: VehicleTypeCreateRequest): Mono<ResponseEntity<VehicleTypeResponse>> {
        val data = mapOf("typeName" to request.typeName)
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

    private fun rowToResponse(row: Map<String, Any?>): VehicleTypeResponse = VehicleTypeResponse(
        id = row["vehicle_type_id"]?.toString() ?: "",
        typeName = row["type_name"]?.toString() ?: ""
    )
}
