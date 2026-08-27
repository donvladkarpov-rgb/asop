package ru.asop.session.dto

import java.util.UUID

data class LiveVehicleDto(
    val vehicleId: UUID,
    val vehicleNumber: String,
    val vehicleName: String,
    val vehicleType: String,
    val latitude: Double,
    val longitude: Double,
    /** Привязка к ближайшей точке маршрута (null если маршрут неизвестен/без геометрии). */
    val snappedLatitude: Double?,
    val snappedLongitude: Double?,
    val speedKmh: Double?,
    val recordedAt: String,
    val pathId: UUID?,
    val pathName: String?,
    val routeId: UUID?,
    val sessionId: UUID?
)

data class TrackPointDto(
    val latitude: Double,
    val longitude: Double,
    val snappedLatitude: Double?,
    val snappedLongitude: Double?,
    val recordedAt: String,
    val speedKmh: Double?
)
