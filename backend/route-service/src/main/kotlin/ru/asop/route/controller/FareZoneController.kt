package ru.asop.route.controller

import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import ru.asop.api.route.controller.FareZoneApi
import ru.asop.api.route.dto.request.FareZoneCreateRequest
import ru.asop.api.route.dto.response.FareZoneResponse
import ru.asop.route.repository.GenericRouteRepository
import ru.asop.route.repository.RouteTableRegistry
import ru.asop.route.service.FareZoneService
import java.util.UUID

@RestController
class FareZoneController(
    private val service: FareZoneService,
    private val repository: GenericRouteRepository
) : FareZoneApi {

    @GetMapping("/delta")
    fun listDelta(
        @RequestParam(required = false) versionSince: Long?,
        @RequestParam(required = false) includeDeleted: Boolean,
        @RequestParam(required = false) regionId: UUID?,
        @RequestParam(required = false) carrierId: UUID?,
        @RequestParam(required = false, defaultValue = "10000") limit: Int
    ): Flux<Map<String, Any?>> {
        val info = RouteTableRegistry.resolve("fare-zones") ?: return Flux.empty()
        return repository.findDelta(info, versionSince, includeDeleted == true, regionId, carrierId, limit)
    }


    override fun listFareZones(regionId: UUID?, carrierId: UUID?): Flux<FareZoneResponse> =
        service.list(regionId, carrierId).flatMapMany { Flux.fromIterable(it) }.map { row ->
            FareZoneResponse(
                id = row["zone_id"]?.toString() ?: "",
                zoneCode = row["zone_code"]?.toString() ?: "",
                zoneName = row["zone_name"]?.toString() ?: "",
                description = row["description"]?.toString(),
                zonePolygon = row["zone_polygon"]?.toString(),
                regionId = row["region_id"]?.toString() ?: ""
            )
        }

    override fun getFareZone(id: String): Mono<ResponseEntity<FareZoneResponse>> =
        service.getById(id).flatMap { row ->
            if (row.isEmpty()) Mono.just(ResponseEntity.notFound().build())
            else Mono.just(ResponseEntity.ok(rowToResponse(row)))
        }

    override fun createFareZone(request: FareZoneCreateRequest): Mono<ResponseEntity<FareZoneResponse>> {
        val data = mapOf(
            "id" to null,
            "zoneCode" to request.zoneCode,
            "zoneName" to request.zoneName,
            "description" to request.description,
            "zonePolygon" to request.zonePolygon,
            "regionId" to request.regionId
        )
        return service.create(data).map { ResponseEntity.status(201).body(rowToResponse(it)) }
    }

    override fun updateFareZone(id: String, request: FareZoneCreateRequest): Mono<ResponseEntity<FareZoneResponse>> {
        val data = mapOf(
            "zoneCode" to request.zoneCode,
            "zoneName" to request.zoneName,
            "description" to request.description,
            "zonePolygon" to request.zonePolygon,
            "regionId" to request.regionId
        )
        return service.update(id, data).flatMap { row ->
            if (row.isEmpty()) Mono.just(ResponseEntity.notFound().build())
            else Mono.just(ResponseEntity.ok(rowToResponse(row)))
        }
    }

    override fun deleteFareZone(id: String): Mono<ResponseEntity<Void>> =
        service.delete(id).map { rows ->
            if (rows > 0) ResponseEntity.noContent().build()
            else ResponseEntity.notFound().build()
        }

    private fun rowToResponse(row: Map<String, Any?>): FareZoneResponse = FareZoneResponse(
        id = row["zone_id"]?.toString() ?: "",
        zoneCode = row["zone_code"]?.toString() ?: "",
        zoneName = row["zone_name"]?.toString() ?: "",
        description = row["description"]?.toString(),
        zonePolygon = row["zone_polygon"]?.toString(),
        regionId = row["region_id"]?.toString() ?: ""
    )
}
