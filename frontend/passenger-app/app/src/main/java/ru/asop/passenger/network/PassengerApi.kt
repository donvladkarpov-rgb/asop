package ru.asop.passenger.network

import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

interface PassengerApi {

    @GET("public/tracking/live")
    suspend fun getLiveVehicles(
        @Query("freshSec") freshSec: Int = 300,
    ): List<LiveVehicle>

    @GET("public/tracking/vehicle/{vehicleId}/track")
    suspend fun getVehicleTrack(
        @Path("vehicleId") vehicleId: String,
        @Query("minutes") minutes: Int = 15,
    ): List<TrackPoint>

    @GET("public/stops/bbox")
    suspend fun getStopsInBBox(
        @Query("southWest") southWest: String,
        @Query("northEast") northEast: String,
    ): List<Stop>

    @GET("public/stops/{stopId}/routes")
    suspend fun getRoutesForStop(
        @Path("stopId") stopId: String,
    ): List<StopRoute>
}
