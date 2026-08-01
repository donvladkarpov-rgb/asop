package ru.asop.card.repository

import org.springframework.data.repository.reactive.ReactiveCrudRepository
import org.springframework.data.r2dbc.repository.Query
import org.springframework.stereotype.Repository
import reactor.core.publisher.Flux
import ru.asop.card.model.CardMifareEntity
import java.time.Instant
import java.util.UUID

@Repository
interface CardMifareRepository : ReactiveCrudRepository<CardMifareEntity, UUID> {

    @Query("""
        SELECT m.* FROM ASOP_CARD_MIFARES m
        JOIN ASOP_CARDS c ON c.CARD_ID = m.CARD_ID
        WHERE (:userIdsInStr IS NULL OR c.USER_ID = ANY(string_to_array(:userIdsInStr, ',')::uuid[]))
          AND (:updatedAtSince IS NULL OR m.UPDATED_AT > :updatedAtSince)
          AND (:includeDeleted = TRUE OR m.DELETED_AT IS NULL)
        ORDER BY m.UPDATED_AT ASC
        LIMIT :limit
    """)
    fun findDelta(userIdsInStr: String?, updatedAtSince: Instant?, includeDeleted: Boolean, limit: Int): Flux<CardMifareEntity>
}
