package ru.asop.card.repository

import org.springframework.data.repository.reactive.ReactiveCrudRepository
import org.springframework.data.r2dbc.repository.Query
import org.springframework.stereotype.Repository
import reactor.core.publisher.Flux
import ru.asop.card.model.CardBankEntity
import java.time.Instant
import java.util.UUID

@Repository
interface CardBankRepository : ReactiveCrudRepository<CardBankEntity, UUID> {

    @Query("""
        SELECT b.* FROM ASOP_CARD_BANKS b
        JOIN ASOP_CARDS c ON c.CARD_ID = b.CARD_ID
        WHERE (:userIdsInStr IS NULL OR c.USER_ID = ANY(string_to_array(:userIdsInStr, ',')::uuid[]))
          AND (:updatedAtSince IS NULL OR b.UPDATED_AT > :updatedAtSince)
          AND (:includeDeleted = TRUE OR b.DELETED_AT IS NULL)
        ORDER BY b.UPDATED_AT ASC
        LIMIT :limit
    """)
    fun findDelta(userIdsInStr: String?, updatedAtSince: Instant?, includeDeleted: Boolean, limit: Int): Flux<CardBankEntity>
}
