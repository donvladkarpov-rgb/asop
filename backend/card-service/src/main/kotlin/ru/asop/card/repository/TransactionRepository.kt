package ru.asop.card.repository

import org.springframework.data.repository.reactive.ReactiveCrudRepository
import org.springframework.stereotype.Repository
import ru.asop.card.model.TransactionEntity
import java.util.UUID

import org.springframework.data.r2dbc.repository.Query
import reactor.core.publisher.Flux

@Repository
interface TransactionRepository : ReactiveCrudRepository<TransactionEntity, UUID> {
    @Query("SELECT * FROM ASOP_TRANSACTIONS WHERE SESSION_ID = :sessionId ORDER BY STARTED_AT DESC")
    fun findBySessionId(sessionId: UUID): Flux<TransactionEntity>
}
