package ru.asop.card.repository

import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.reactive.ReactiveCrudRepository
import org.springframework.stereotype.Repository
import reactor.core.publisher.Flux
import ru.asop.card.controller.TransactionWithCard
import ru.asop.card.model.TransactionEntity
import java.util.UUID

@Repository
interface TransactionRepository : ReactiveCrudRepository<TransactionEntity, UUID> {
    @Query("""
        SELECT t.TRANSACTION_ID, t.SESSION_ID, t.TRANSACTION_TYPE_ID, t.TRANSACTION_RESULT_ID,
               t.AMOUNT, t.CURRENCY, t.METADATA, t.STARTED_AT, t.COMPLETED_AT,
               tc.CARD_ID, c.USER_ID
        FROM ASOP_TRANSACTIONS t
        LEFT JOIN ASOP_TRANSACTION_CARDS tc ON tc.TRANSACTION_ID = t.TRANSACTION_ID
        LEFT JOIN ASOP_CARDS c ON c.CARD_ID = tc.CARD_ID
        WHERE t.SESSION_ID = :sessionId
        ORDER BY t.STARTED_AT DESC
    """)
    fun findBySessionIdWithCard(sessionId: UUID): Flux<TransactionWithCard>
}
