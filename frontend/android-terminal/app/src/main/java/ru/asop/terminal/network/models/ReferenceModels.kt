package ru.asop.terminal.network.models

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class RegionResponse(
    @Json(name = "id") val id: String,
    @Json(name = "municipalDivision") val municipalDivision: String,
    @Json(name = "federalDistrict") val federalDistrict: String?
)

@JsonClass(generateAdapter = true)
data class CarrierResponse(
    @Json(name = "id") val id: String,
    @Json(name = "carrierName") val carrierName: String,
    @Json(name = "inn") val inn: String,
    @Json(name = "regionId") val regionId: String
)
