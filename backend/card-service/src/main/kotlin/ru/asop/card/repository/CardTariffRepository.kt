package ru.asop.card.repository

import org.springframework.data.repository.reactive.ReactiveCrudRepository
import org.springframework.data.r2dbc.repository.Query
import org.springframework.stereotype.Repository
import reactor.core.publisher.Flux
import ru.asop.card.model.CardTariffEntity
import java.time.Instant
import java.util.UUID

@Repository
interface CardTariffRepository : ReactiveCrudRepository<CardTariffEntity, UUID> {

    @Query("""
        SELECT t.* FROM ASOP_CARD_TARIFFS t
        JOIN ASOP_CARDS c ON c.CARD_ID = t.CARD_ID
        WHERE (:userIdsInStr IS NULL OR c.USER_ID = ANY(string_to_array(:userIdsInStr, ',')::uuid[]))
          AND (:updatedAtSince IS NULL OR t.UPDATED_AT > :updatedAtSince)
          AND (:includeDeleted = TRUE OR t.DELETED_AT IS NULL)
        ORDER BY t.UPDATED_AT ASC
        LIMIT :limit
    """)
    fun findDelta(userIdsInStr: String?, updatedAtSince: Instant?, includeDeleted: Boolean, limit: Int): Flux<CardTariffEntity>
}
