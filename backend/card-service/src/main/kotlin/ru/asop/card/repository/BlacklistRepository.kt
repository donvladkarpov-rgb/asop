package ru.asop.card.repository

import org.springframework.data.repository.reactive.ReactiveCrudRepository
import org.springframework.data.r2dbc.repository.Query
import org.springframework.stereotype.Repository
import reactor.core.publisher.Flux
import ru.asop.card.model.BlacklistEntity
import java.util.UUID

@Repository
interface BlacklistRepository : ReactiveCrudRepository<BlacklistEntity, UUID> {

    @Query("""
        SELECT bl.* FROM ASOP_BLACKLISTS bl
        JOIN ASOP_CARDS c ON c.CARD_ID = bl.CARD_ID
        WHERE (:userIdsInStr IS NULL OR c.USER_ID = ANY(string_to_array(:userIdsInStr, ',')::uuid[]))
          AND (:versionSince IS NULL OR bl.VERSION > :versionSince)
          AND (:includeDeleted = TRUE OR bl.DELETED_AT IS NULL)
        ORDER BY bl.VERSION ASC
        LIMIT :limit
    """)
    fun findDelta(userIdsInStr: String?, versionSince: Long?, includeDeleted: Boolean, limit: Int): Flux<BlacklistEntity>
}
