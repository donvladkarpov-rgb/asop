package ru.asop.passenger.network

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class LiveVehicle(
    val vehicleId: String,
    val vehicleNumber: String,
    val vehicleName: String,
    val vehicleType: String,
    val latitude: Double,
    val longitude: Double,
    val snappedLatitude: Double?,
    val snappedLongitude: Double?,
    val speedKmh: Double?,
    val recordedAt: String,
    val pathId: String?,
    val pathName: String?,
    val routeId: String?,
    val sessionId: String?,
)

@JsonClass(generateAdapter = true)
data class TrackPoint(
    val latitude: Double,
    val longitude: Double,
    val snappedLatitude: Double?,
    val snappedLongitude: Double?,
    val recordedAt: String,
    val speedKmh: Double?,
)

@JsonClass(generateAdapter = true)
data class Stop(
    val stopId: String,
    val stopName: String,
    val stopCode: String?,
    val stopAddress: String?,
    val latitude: Double,
    val longitude: Double,
)

@JsonClass(generateAdapter = true)
data class StopRoute(
    val routeId: String,
    val routeName: String,
    val routeNumber: String?,
    val pathId: String,
    val pathName: String?,
    val routeObject: String?,
)
