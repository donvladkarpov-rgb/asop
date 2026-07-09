package ru.asop.api.reference.dto.response

import java.util.UUID

data class TerritoryResponse(
    val id: UUID,
    val regionId: UUID,
    val municipalDivision: String,
    val adminDivision: String?,
    val federalDistrict: String?,
    val ifnsFlCode: String?,
    val ifnsUlCode: String?,
    val okatoCode: String?,
    val oktmoCode: String?,
    val oktmoBudgetCode: String?,
    val fiasId: String?,
    val registryRecordId: String?
)
