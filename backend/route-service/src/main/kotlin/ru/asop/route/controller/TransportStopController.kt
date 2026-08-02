package ru.asop.route.controller

import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import ru.asop.api.route.controller.TransportStopApi
import ru.asop.api.route.dto.request.TransportStopCreateRequest
import ru.asop.api.route.dto.response.TransportStopResponse
import ru.asop.route.repository.GenericRouteRepository
import ru.asop.route.repository.RouteTableRegistry
import ru.asop.route.service.TransportStopService
import java.util.UUID

@RestController
class TransportStopController(
    private val service: TransportStopService,
    private val repository: GenericRouteRepository
) : TransportStopApi {

    @GetMapping("/delta")
    fun listDelta(
        @RequestParam(required = false) versionSince: Long?,
        @RequestParam(required = false) includeDeleted: Boolean,
        @RequestParam(required = false) regionId: UUID?,
        @RequestParam(required = false) carrierId: UUID?,
        @RequestParam(required = false, defaultValue = "10000") limit: Int
    ): Flux<Map<String, Any?>> {
        val info = RouteTableRegistry.resolve("transport-stops") ?: return Flux.empty()
        return repository.findDelta(info, versionSince, includeDeleted == true, regionId, carrierId, limit)
    }


    override fun list(): Flux<TransportStopResponse> =
        service.list().flatMapMany { Flux.fromIterable(it) }.map { row ->
            TransportStopResponse(
                id = row["stop_id"]?.toString() ?: "",
                stopCode = row["stop_code"]?.toString() ?: "",
                stopName = row["stop_name"]?.toString() ?: "",
                regionId = row["region_id"]?.toString() ?: "",
                fareZoneId = row["fare_zone_id"]?.toString(),
                zonePolygon = row["zone_polygon"]?.toString(),
                stopAddress = row["stop_address"]?.toString(),
                description = row["description"]?.toString(),
                isActive = row["is_active"] as? Boolean ?: false
            )
        }

    override fun get(id: String): Mono<ResponseEntity<TransportStopResponse>> =
        service.getById(id).flatMap { row ->
            if (row.isEmpty()) Mono.just(ResponseEntity.notFound().build())
            else Mono.just(ResponseEntity.ok(rowToResponse(row)))
        }

    override fun create(request: TransportStopCreateRequest): Mono<ResponseEntity<TransportStopResponse>> {
        val data = mapOf(
            "id" to null,
            "stopCode" to request.stopCode,
            "stopName" to request.stopName,
            "regionId" to request.regionId,
            "fareZoneId" to request.fareZoneId,
            "zonePolygon" to request.zonePolygon,
            "stopAddress" to request.stopAddress,
            "description" to request.description,
            "isActive" to request.isActive?.toString()
        )
        return service.create(data).map { ResponseEntity.status(201).body(rowToResponse(it)) }
    }

    override fun update(id: String, request: TransportStopCreateRequest): Mono<ResponseEntity<TransportStopResponse>> {
        val data = mapOf(
            "stopCode" to request.stopCode,
            "stopName" to request.stopName,
            "regionId" to request.regionId,
            "fareZoneId" to request.fareZoneId,
            "zonePolygon" to request.zonePolygon,
            "stopAddress" to request.stopAddress,
            "description" to request.description,
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

    private fun rowToResponse(row: Map<String, Any?>): TransportStopResponse = TransportStopResponse(
        id = row["stop_id"]?.toString() ?: "",
        stopCode = row["stop_code"]?.toString() ?: "",
        stopName = row["stop_name"]?.toString() ?: "",
        regionId = row["region_id"]?.toString() ?: "",
        fareZoneId = row["fare_zone_id"]?.toString(),
        zonePolygon = row["zone_polygon"]?.toString(),
        stopAddress = row["stop_address"]?.toString(),
        description = row["description"]?.toString(),
        isActive = row["is_active"] as? Boolean ?: false
    )
}
