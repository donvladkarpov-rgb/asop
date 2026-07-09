package ru.asop.admin.model

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.util.UUID

@Table("ASOP_TERRITORIES")
data class TerritoryEntity(
    @Id
    val territoryId: UUID,
    val regionId: UUID,
    val municipalDivision: String,
    val adminDivision: String? = null,
    val federalDistrict: String? = null,
    val ifnsFlCode: String? = null,
    val ifnsUlCode: String? = null,
    val okatoCode: String? = null,
    val oktmoCode: String? = null,
    val oktmoBudgetCode: String? = null,
    val fiasId: String? = null,
    val registryRecordId: String? = null
)
