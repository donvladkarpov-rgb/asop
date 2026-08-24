package ru.asop.card.repository

import org.springframework.data.repository.reactive.ReactiveCrudRepository
import org.springframework.data.r2dbc.repository.Query
import org.springframework.stereotype.Repository
import reactor.core.publisher.Flux
import ru.asop.card.model.CardTariffEntity
import java.util.UUID

@Repository
interface CardTariffRepository : ReactiveCrudRepository<CardTariffEntity, UUID> {

    @Query("""
        SELECT t.* FROM ASOP_CARD_TARIFFS t
        JOIN ASOP_CARDS c ON c.CARD_ID = t.CARD_ID
        WHERE c.USER_ID IS NOT NULL
          AND (:userIdsInStr IS NULL OR c.USER_ID = ANY(string_to_array(:userIdsInStr, ',')::uuid[]))
          AND (:versionSince IS NULL OR t.VERSION > :versionSince)
          AND (:includeDeleted = TRUE OR t.DELETED_AT IS NULL)
        ORDER BY t.VERSION ASC
        LIMIT :limit
    """)
    fun findDelta(userIdsInStr: String?, versionSince: Long?, includeDeleted: Boolean, limit: Int): Flux<CardTariffEntity>
}
