package ru.asop.route.controller

import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import ru.asop.api.route.controller.RouteApi
import ru.asop.api.route.dto.request.RouteCreateRequest
import ru.asop.api.route.dto.response.RouteResponse
import ru.asop.route.repository.GenericRouteRepository
import ru.asop.route.repository.RouteTableRegistry
import ru.asop.route.service.RouteService
import java.time.Instant
import java.util.UUID

@RestController
class RouteController(
    private val service: RouteService,
    private val repository: GenericRouteRepository
) : RouteApi {

    @GetMapping("/delta")
    fun listDelta(
        @RequestParam(required = false) updatedAtSince: Instant?,
        @RequestParam(required = false) includeDeleted: Boolean,
        @RequestParam(required = false) regionId: UUID?,
        @RequestParam(required = false) carrierId: UUID?,
        @RequestParam(required = false, defaultValue = "10000") limit: Int
    ): Flux<Map<String, Any?>> {
        val info = RouteTableRegistry.resolve("routes") ?: return Flux.empty()
        return repository.findDelta(info, updatedAtSince, includeDeleted == true, regionId, carrierId, limit)
    }


    override fun listRoutes(): Flux<RouteResponse> =
        service.list().flatMapMany { Flux.fromIterable(it) }.map { row ->
            RouteResponse(
                id = row["route_id"]?.toString() ?: "",
                routeNumber = row["route_number"]?.toString() ?: "",
                routeName = row["route_name"]?.toString() ?: "",
                routeCategory = row["route_category"]?.toString() ?: "",
                organizerId = row["organizer_id"]?.toString(),
                ministryRegistryNo = row["ministry_registry_no"]?.toString(),
                regionId = row["region_id"]?.toString() ?: ""
            )
        }

    override fun getRoute(id: String): Mono<ResponseEntity<RouteResponse>> =
        service.getById(id).flatMap { row ->
            if (row.isEmpty()) Mono.just(ResponseEntity.notFound().build())
            else Mono.just(ResponseEntity.ok(rowToResponse(row)))
        }

    override fun createRoute(request: RouteCreateRequest): Mono<ResponseEntity<RouteResponse>> {
        val data = mapOf(
            "id" to null,
            "routeNumber" to request.routeNumber,
            "routeName" to request.routeName,
            "routeCategory" to request.routeCategory,
            "organizerId" to request.organizerId,
            "ministryRegistryNo" to request.ministryRegistryNo,
            "regionId" to request.regionId
        )
        return service.create(data).map { ResponseEntity.status(201).body(rowToResponse(it)) }
    }

    override fun updateRoute(id: String, request: RouteCreateRequest): Mono<ResponseEntity<RouteResponse>> {
        val data = mapOf(
            "routeNumber" to request.routeNumber,
            "routeName" to request.routeName,
            "routeCategory" to request.routeCategory,
            "organizerId" to request.organizerId,
            "ministryRegistryNo" to request.ministryRegistryNo,
            "regionId" to request.regionId
        )
        return service.update(id, data).flatMap { row ->
            if (row.isEmpty()) Mono.just(ResponseEntity.notFound().build())
            else Mono.just(ResponseEntity.ok(rowToResponse(row)))
        }
    }

    override fun deleteRoute(id: String): Mono<ResponseEntity<Void>> =
        service.delete(id).map { rows ->
            if (rows > 0) ResponseEntity.noContent().build()
            else ResponseEntity.notFound().build()
        }

    private fun rowToResponse(row: Map<String, Any?>): RouteResponse = RouteResponse(
        id = row["route_id"]?.toString() ?: "",
        routeNumber = row["route_number"]?.toString() ?: "",
        routeName = row["route_name"]?.toString() ?: "",
        routeCategory = row["route_category"]?.toString() ?: "",
        organizerId = row["organizer_id"]?.toString(),
        ministryRegistryNo = row["ministry_registry_no"]?.toString(),
        regionId = row["region_id"]?.toString() ?: ""
    )
}
