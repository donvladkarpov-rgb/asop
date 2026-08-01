package ru.asop.route.controller

import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import ru.asop.api.route.controller.PathApi
import ru.asop.api.route.dto.request.PathCreateRequest
import ru.asop.api.route.dto.response.PathResponse
import ru.asop.route.repository.GenericRouteRepository
import ru.asop.route.repository.RouteTableRegistry
import ru.asop.route.service.PathService
import java.time.Instant
import java.util.UUID

@RestController
class PathController(
    private val service: PathService,
    private val repository: GenericRouteRepository
) : PathApi {

    @GetMapping("/delta")
    fun listDelta(
        @RequestParam(required = false) updatedAtSince: Instant?,
        @RequestParam(required = false) includeDeleted: Boolean,
        @RequestParam(required = false) regionId: UUID?,
        @RequestParam(required = false) carrierId: UUID?,
        @RequestParam(required = false, defaultValue = "10000") limit: Int
    ): Flux<Map<String, Any?>> {
        val info = RouteTableRegistry.resolve("paths") ?: return Flux.empty()
        return repository.findDelta(info, updatedAtSince, includeDeleted == true, regionId, carrierId, limit)
    }


    override fun listPaths(): Flux<PathResponse> =
        service.list().flatMapMany { Flux.fromIterable(it) }.map { row ->
            PathResponse(
                id = row["path_id"]?.toString() ?: "",
                routeId = row["route_id"]?.toString() ?: "",
                pathName = row["path_name"]?.toString() ?: "",
                routeObject = row["route_object"]?.toString(),
                benefitPolicy = row["benefit_policy"]?.toString() ?: "",
                startStopId = row["start_stop_id"]?.toString(),
                endStopId = row["end_stop_id"]?.toString(),
                pathStartDate = row["path_start_date"]?.toString(),
                pathEndDate = row["path_end_date"]?.toString(),
                description = row["description"]?.toString(),
                regionId = row["region_id"]?.toString() ?: ""
            )
        }

    override fun getPath(id: String): Mono<ResponseEntity<PathResponse>> =
        service.getById(id).flatMap { row ->
            if (row.isEmpty()) Mono.just(ResponseEntity.notFound().build())
            else Mono.just(ResponseEntity.ok(rowToResponse(row)))
        }

    override fun createPath(request: PathCreateRequest): Mono<ResponseEntity<PathResponse>> {
        val data = mapOf(
            "id" to null,
            "routeId" to request.routeId,
            "pathName" to request.pathName,
            "routeObject" to request.routeObject,
            "benefitPolicy" to request.benefitPolicy,
            "startStopId" to request.startStopId,
            "endStopId" to request.endStopId,
            "pathStartDate" to request.pathStartDate,
            "pathEndDate" to request.pathEndDate,
            "description" to request.description,
            "regionId" to request.regionId
        )
        return service.create(data).map { ResponseEntity.status(201).body(rowToResponse(it)) }
    }

    override fun updatePath(id: String, request: PathCreateRequest): Mono<ResponseEntity<PathResponse>> {
        val data = mapOf(
            "routeId" to request.routeId,
            "pathName" to request.pathName,
            "routeObject" to request.routeObject,
            "benefitPolicy" to request.benefitPolicy,
            "startStopId" to request.startStopId,
            "endStopId" to request.endStopId,
            "pathStartDate" to request.pathStartDate,
            "pathEndDate" to request.pathEndDate,
            "description" to request.description,
            "regionId" to request.regionId
        )
        return service.update(id, data).flatMap { row ->
            if (row.isEmpty()) Mono.just(ResponseEntity.notFound().build())
            else Mono.just(ResponseEntity.ok(rowToResponse(row)))
        }
    }

    override fun deletePath(id: String): Mono<ResponseEntity<Void>> =
        service.delete(id).map { rows ->
            if (rows > 0) ResponseEntity.noContent().build()
            else ResponseEntity.notFound().build()
        }

    private fun rowToResponse(row: Map<String, Any?>): PathResponse = PathResponse(
        id = row["path_id"]?.toString() ?: "",
        routeId = row["route_id"]?.toString() ?: "",
        pathName = row["path_name"]?.toString() ?: "",
        routeObject = row["route_object"]?.toString(),
        benefitPolicy = row["benefit_policy"]?.toString() ?: "",
        startStopId = row["start_stop_id"]?.toString(),
        endStopId = row["end_stop_id"]?.toString(),
        pathStartDate = row["path_start_date"]?.toString(),
        pathEndDate = row["path_end_date"]?.toString(),
        description = row["description"]?.toString(),
        regionId = row["region_id"]?.toString() ?: ""
    )
}
