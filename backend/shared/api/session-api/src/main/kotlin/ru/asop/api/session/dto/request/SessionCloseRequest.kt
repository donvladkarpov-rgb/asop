package ru.asop.api.session.dto.request

import java.util.UUID

data class SessionCloseRequest(
    val reason: String? = null,

    val regionId: UUID? = null,

    val timezone: String? = null
)
