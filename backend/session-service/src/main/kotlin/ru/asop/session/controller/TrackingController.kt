package ru.asop.session.controller

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Flux
import ru.asop.session.dto.LiveVehicleDto
import ru.asop.session.dto.TrackPointDto
import ru.asop.session.service.TrackingService
import java.util.UUID

@RestController
@RequestMapping("/api/v1/tracking")
class TrackingController(private val trackingService: TrackingService) {

    @GetMapping("/live")
    fun getLiveVehicles(
        @RequestParam(required = false) regionId: UUID?,
        @RequestParam(required = false) carrierId: UUID?,
        @RequestParam(required = false) vehicleId: UUID?,
        @RequestParam(defaultValue = "600") freshSec: Int
    ): Flux<LiveVehicleDto> {
        return trackingService.getLiveVehicles(regionId, carrierId, vehicleId, freshSec)
    }

    @GetMapping("/vehicle/{vehicleId}/track")
    fun getVehicleTrack(
        @PathVariable vehicleId: UUID,
        @RequestParam(defaultValue = "15") minutes: Int
    ): Flux<TrackPointDto> {
        return trackingService.getVehicleTrack(vehicleId, minutes)
    }
}
