package ru.asop.admin.repository

import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.reactive.ReactiveCrudRepository
import org.springframework.stereotype.Repository
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import ru.asop.admin.model.OrganizerTerritoryEntity
import java.time.Instant
import java.util.UUID

@Repository
interface OrganizerTerritoryRepository : ReactiveCrudRepository<OrganizerTerritoryEntity, Void> {

    fun findByOrganizerId(organizerId: UUID): Flux<OrganizerTerritoryEntity>

    fun deleteByOrganizerIdAndTerritoryId(organizerId: UUID, territoryId: UUID): Mono<Void>

    @Query("""
        SELECT * FROM ASOP_ORGANIZER_TERRITORIES
        WHERE (:updatedAtSince IS NULL OR UPDATED_AT > :updatedAtSince)
          AND (:includeDeleted = TRUE OR DELETED_AT IS NULL)
        ORDER BY UPDATED_AT ASC
        LIMIT :limit
    """)
    fun findDelta(updatedAtSince: Instant?, includeDeleted: Boolean, limit: Int): Flux<OrganizerTerritoryEntity>
}
