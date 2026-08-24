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

    /**
     * Дельта mifares для терминалов. Только ПЕРСОНАЛЬНЫЕ карты (c.USER_ID IS NOT NULL):
     * анонимные карты (PASSENGER_ANONYMOUS) на терминал не синкаются — их валидация
     * офлайн (нет userId → нет benefit lookup), остаток живёт на самой карте, история
     * транзакций уходит на сервер. Фильтр userIdsIn — каскад от пользователей
     * региона/перевозчика (orchestrator), опционален (налэбл).
     */
    @Query("""
        SELECT m.* FROM ASOP_CARD_MIFARES m
        JOIN ASOP_CARDS c ON c.CARD_ID = m.CARD_ID
        WHERE c.USER_ID IS NOT NULL
          AND (:userIdsInStr IS NULL OR c.USER_ID = ANY(string_to_array(:userIdsInStr, ',')::uuid[]))
          AND (:versionSince IS NULL OR m.VERSION > :versionSince)
          AND (:includeDeleted = TRUE OR m.DELETED_AT IS NULL)
        ORDER BY m.VERSION ASC
        LIMIT :limit
    """)
    fun findDelta(userIdsInStr: String?, versionSince: Long?, includeDeleted: Boolean, limit: Int): Flux<CardMifareEntity>
}
