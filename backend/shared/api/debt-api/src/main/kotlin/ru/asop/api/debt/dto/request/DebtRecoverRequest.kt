package ru.asop.api.debt.dto.request

import java.util.UUID

data class DebtRecoverRequest(
    val carrierId: UUID? = null,

    val regionId: UUID? = null,

    val timezone: String? = null
)
