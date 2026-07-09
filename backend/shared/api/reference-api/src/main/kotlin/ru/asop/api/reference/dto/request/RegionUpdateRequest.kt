package ru.asop.api.reference.dto.request

import jakarta.validation.constraints.Size

data class RegionUpdateRequest(
    @field:Size(max = 255)
    val municipalDivision: String? = null,

    @field:Size(max = 255)
    val adminDivision: String? = null,

    @field:Size(max = 255)
    val federalDistrict: String? = null,

    @field:Size(max = 4)
    val ifnsFlCode: String? = null,

    @field:Size(max = 4)
    val ifnsUlCode: String? = null,

    @field:Size(max = 11)
    val okatoCode: String? = null,

    @field:Size(max = 11)
    val oktmoCode: String? = null,

    @field:Size(max = 11)
    val oktmoBudgetCode: String? = null,

    @field:Size(max = 36)
    val fiasId: String? = null,

    @field:Size(max = 30)
    val registryRecordId: String? = null
)
