package ru.asop.admin.controller

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Flux
import ru.asop.admin.model.OrganizerTerritoryEntity
import ru.asop.admin.repository.OrganizerTerritoryDeltaQuery
import java.util.UUID

/**
 * /delta endpoint для организатор↔территория с region-filter через JOIN ASOP_TERRITORIES
 * (промпт 010). При regionId=null возвращаются все строки (для полной выкачки).
 */
@RestController
class OrganizerTerritoryController(
    private val deltaQuery: OrganizerTerritoryDeltaQuery
) {

    @GetMapping("/api/v1/organizer-territories/delta")
    fun listDelta(
        @RequestParam(required = false) regionId: UUID?,
        @RequestParam(required = false) versionSince: Long?,
        @RequestParam(required = false, defaultValue = "false") includeDeleted: Boolean,
        @RequestParam(required = false, defaultValue = "10000") limit: Int
    ): Flux<OrganizerTerritoryEntity> = deltaQuery.findByRegion(versionSince, includeDeleted, limit, regionId)
}
