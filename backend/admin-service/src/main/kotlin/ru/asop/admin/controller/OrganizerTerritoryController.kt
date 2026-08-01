package ru.asop.admin.controller

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Flux
import ru.asop.admin.model.OrganizerTerritoryEntity
import ru.asop.admin.repository.OrganizerTerritoryRepository
import java.time.Instant

/**
 * Отдельный top-level ресурс дельта-синхронизации для связки
 * организатор <-> территория. Отдаётся целиком (таблица маленькая,
 * терминал сам фильтрует по региону/территории).
 */
@RestController
class OrganizerTerritoryController(
    private val repository: OrganizerTerritoryRepository
) {

    @GetMapping("/api/v1/organizer-territories/delta")
    fun listDelta(
        @RequestParam(required = false) updatedAtSince: Instant?,
        @RequestParam(required = false, defaultValue = "false") includeDeleted: Boolean,
        @RequestParam(required = false, defaultValue = "10000") limit: Int
    ): Flux<OrganizerTerritoryEntity> = repository.findDelta(updatedAtSince, includeDeleted, limit)
}
