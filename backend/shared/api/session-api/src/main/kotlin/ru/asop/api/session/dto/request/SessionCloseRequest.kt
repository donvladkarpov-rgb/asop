package ru.asop.api.session.dto.request

import java.util.UUID

data class SessionCloseRequest(
    val reason: String? = null,

    val regionId: UUID? = null,

    val timezone: String? = null,

    // Промпт 011 §12: кто реально закрывает смену (userId из карты-ключа терминала).
    // Gateway использовал principal.name (CN терминала = hex) — это всегда ноль.
    val closedByUserId: UUID? = null
)
