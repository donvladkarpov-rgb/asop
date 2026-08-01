package ru.asop.card.repository

import org.springframework.data.repository.reactive.ReactiveCrudRepository
import org.springframework.data.r2dbc.repository.Query
import org.springframework.stereotype.Repository
import reactor.core.publisher.Flux
import ru.asop.card.model.BlacklistEntity
import java.time.Instant
import java.util.UUID

@Repository
interface BlacklistRepository : ReactiveCrudRepository<BlacklistEntity, UUID> {

    @Query("""
        SELECT bl.* FROM ASOP_BLACKLISTS bl
        JOIN ASOP_CARDS c ON c.CARD_ID = bl.CARD_ID
        WHERE (:userIdsInStr IS NULL OR c.USER_ID = ANY(string_to_array(:userIdsInStr, ',')::uuid[]))
          AND (:updatedAtSince IS NULL OR bl.UPDATED_AT > :updatedAtSince)
          AND (:includeDeleted = TRUE OR bl.DELETED_AT IS NULL)
        ORDER BY bl.UPDATED_AT ASC
        LIMIT :limit
    """)
    fun findDelta(userIdsInStr: String?, updatedAtSince: Instant?, includeDeleted: Boolean, limit: Int): Flux<BlacklistEntity>
}
