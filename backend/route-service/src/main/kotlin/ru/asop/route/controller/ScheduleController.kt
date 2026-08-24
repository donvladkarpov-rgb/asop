package ru.asop.route.controller

import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import ru.asop.api.route.controller.ScheduleApi
import ru.asop.api.route.dto.request.ScheduleCreateRequest
import ru.asop.api.route.dto.response.ScheduleResponse
import ru.asop.route.repository.GenericRouteRepository
import ru.asop.route.repository.RouteTableRegistry
import ru.asop.route.service.ScheduleService
import java.util.UUID

@RestController
class ScheduleController(
    private val service: ScheduleService,
    private val repository: GenericRouteRepository
) : ScheduleApi {

    @GetMapping("/delta")
    fun listDelta(
        @RequestParam(required = false) versionSince: Long?,
        @RequestParam(required = false) includeDeleted: Boolean,
        @RequestParam(required = false) regionId: UUID?,
        @RequestParam(required = false) carrierId: UUID?,
        @RequestParam(required = false, defaultValue = "10000") limit: Int
    ): Flux<Map<String, Any?>> {
        val info = RouteTableRegistry.resolve("schedule") ?: return Flux.empty()
        return repository.findDelta(info, versionSince, includeDeleted == true, regionId, carrierId, limit)
    }


    override fun list(regionId: UUID?, carrierId: UUID?): Flux<ScheduleResponse> =
        service.list(regionId, carrierId).flatMapMany { Flux.fromIterable(it) }.map { row ->
            ScheduleResponse(
                id = row["schedule_id"]?.toString() ?: "",
                pathId = row["path_id"]?.toString() ?: "",
                stopId = row["stop_id"]?.toString() ?: "",
                dayMask = (row["day_mask"] as? Number)?.toInt() ?: 0,
                arrivalTime = row["arrival_time"]?.toString() ?: "",
                dwellTimeSec = (row["dwell_time_sec"] as? Number)?.toInt(),
                regionId = row["region_id"]?.toString() ?: "",
                isActive = row["is_active"] as? Boolean
            )
        }

    override fun get(id: String): Mono<ResponseEntity<ScheduleResponse>> =
        service.getById(id).flatMap { row ->
            if (row.isEmpty()) Mono.just(ResponseEntity.notFound().build())
            else Mono.just(ResponseEntity.ok(rowToResponse(row)))
        }

    override fun create(request: ScheduleCreateRequest): Mono<ResponseEntity<ScheduleResponse>> {
        val data = mapOf(
            "id" to null,
            "pathId" to request.pathId,
            "stopId" to request.stopId,
            "dayMask" to request.dayMask?.toString(),
            "arrivalTime" to request.arrivalTime,
            "dwellTimeSec" to request.dwellTimeSec?.toString(),
            "regionId" to request.regionId,
            "isActive" to request.isActive?.toString()
        )
        return service.create(data).map { ResponseEntity.status(201).body(rowToResponse(it)) }
    }

    override fun update(id: String, request: ScheduleCreateRequest): Mono<ResponseEntity<ScheduleResponse>> {
        val data = mapOf(
            "pathId" to request.pathId,
            "stopId" to request.stopId,
            "dayMask" to request.dayMask?.toString(),
            "arrivalTime" to request.arrivalTime,
            "dwellTimeSec" to request.dwellTimeSec?.toString(),
            "regionId" to request.regionId,
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

    private fun rowToResponse(row: Map<String, Any?>): ScheduleResponse = ScheduleResponse(
        id = row["schedule_id"]?.toString() ?: "",
        pathId = row["path_id"]?.toString() ?: "",
        stopId = row["stop_id"]?.toString() ?: "",
        dayMask = (row["day_mask"] as? Number)?.toInt() ?: 0,
        arrivalTime = row["arrival_time"]?.toString() ?: "",
        dwellTimeSec = (row["dwell_time_sec"] as? Number)?.toInt(),
        regionId = row["region_id"]?.toString() ?: "",
        isActive = row["is_active"] as? Boolean
    )
}
