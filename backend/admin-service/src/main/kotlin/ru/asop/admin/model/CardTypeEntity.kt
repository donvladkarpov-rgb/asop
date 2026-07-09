package ru.asop.admin.model

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.util.UUID

@Table("ASOP_CARD_TYPES")
data class CardTypeEntity(
    @Id
    val cardTypeId: UUID,
    val cardTypeName: String
)
