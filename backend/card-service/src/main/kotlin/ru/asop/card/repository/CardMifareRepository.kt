package ru.asop.card.repository

import org.springframework.data.repository.reactive.ReactiveCrudRepository
import org.springframework.data.r2dbc.repository.Query
import org.springframework.stereotype.Repository
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import ru.asop.card.model.CardMifareEntity
import java.util.UUID

@Repository
interface CardMifareRepository : ReactiveCrudRepository<CardMifareEntity, UUID> {

    fun findByUid(uid: ByteArray): Mono<CardMifareEntity>

    @Query("""
        SELECT m.* FROM ASOP_CARD_MIFARES m
        JOIN ASOP_CARDS c ON c.CARD_ID = m.CARD_ID
        WHERE (:userIdsInStr IS NULL OR c.USER_ID = ANY(string_to_array(:userIdsInStr, ',')::uuid[]))
          AND (:versionSince IS NULL OR m.VERSION > :versionSince)
          AND (:includeDeleted = TRUE OR m.DELETED_AT IS NULL)
        ORDER BY m.VERSION ASC
        LIMIT :limit
    """)
    fun findDelta(userIdsInStr: String?, versionSince: Long?, includeDeleted: Boolean, limit: Int): Flux<CardMifareEntity>
}
