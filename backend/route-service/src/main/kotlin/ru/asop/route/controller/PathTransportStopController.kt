package ru.asop.route.controller

import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import ru.asop.api.route.controller.PathTransportStopApi
import ru.asop.api.route.dto.request.PathTransportStopCreateRequest
import ru.asop.api.route.dto.response.PathTransportStopResponse
import ru.asop.route.repository.GenericRouteRepository
import ru.asop.route.repository.RouteTableRegistry
import ru.asop.route.service.PathTransportStopService
import java.time.Instant
import java.util.UUID

@RestController
class PathTransportStopController(
    private val service: PathTransportStopService,
    private val repository: GenericRouteRepository
) : PathTransportStopApi {

    @GetMapping("/delta")
    fun listDelta(
        @RequestParam(required = false) updatedAtSince: Instant?,
        @RequestParam(required = false) includeDeleted: Boolean,
        @RequestParam(required = false) regionId: UUID?,
        @RequestParam(required = false) carrierId: UUID?,
        @RequestParam(required = false, defaultValue = "10000") limit: Int
    ): Flux<Map<String, Any?>> {
        val info = RouteTableRegistry.resolve("path-transport-stops") ?: return Flux.empty()
        return repository.findDelta(info, updatedAtSince, includeDeleted == true, regionId, carrierId, limit)
    }


    override fun list(): Flux<PathTransportStopResponse> =
        service.list().flatMapMany { Flux.fromIterable(it) }.map { row ->
            PathTransportStopResponse(
                id = row["path_stop_id"]?.toString() ?: "",
                pathId = row["path_id"]?.toString() ?: "",
                stopId = row["stop_id"]?.toString() ?: "",
                serialNumber = (row["serial_number"] as? Number)?.toInt() ?: 0,
                regionId = row["region_id"]?.toString() ?: ""
            )
        }

    override fun get(id: String): Mono<ResponseEntity<PathTransportStopResponse>> =
        service.getById(id).flatMap { row ->
            if (row.isEmpty()) Mono.just(ResponseEntity.notFound().build())
            else Mono.just(ResponseEntity.ok(rowToResponse(row)))
        }

    override fun create(request: PathTransportStopCreateRequest): Mono<ResponseEntity<PathTransportStopResponse>> {
        val data = mapOf(
            "id" to null,
            "pathId" to request.pathId,
            "stopId" to request.stopId,
            "serialNumber" to request.serialNumber.toString(),
            "regionId" to request.regionId
        )
        return service.create(data).map { ResponseEntity.status(201).body(rowToResponse(it)) }
    }

    override fun update(id: String, request: PathTransportStopCreateRequest): Mono<ResponseEntity<PathTransportStopResponse>> {
        val data = mapOf(
            "pathId" to request.pathId,
            "stopId" to request.stopId,
            "serialNumber" to request.serialNumber.toString(),
            "regionId" to request.regionId
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

    private fun rowToResponse(row: Map<String, Any?>): PathTransportStopResponse = PathTransportStopResponse(
        id = row["path_stop_id"]?.toString() ?: "",
        pathId = row["path_id"]?.toString() ?: "",
        stopId = row["stop_id"]?.toString() ?: "",
        serialNumber = (row["serial_number"] as? Number)?.toInt() ?: 0,
        regionId = row["region_id"]?.toString() ?: ""
    )
}
