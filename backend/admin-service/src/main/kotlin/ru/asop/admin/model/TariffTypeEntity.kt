package ru.asop.admin.model

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.util.UUID

@Table("ASOP_TARIFF_TYPES")
data class TariffTypeEntity(
    @Id
    val tariffTypeId: UUID,
    val code: String,
    val name: String,
    val description: String? = null
)
