package ru.asop.fiscal.repository

import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.reactive.ReactiveCrudRepository
import org.springframework.stereotype.Repository
import reactor.core.publisher.Mono
import ru.asop.fiscal.model.FiscalReceiptEntity
import java.util.UUID

@Repository
interface FiscalReceiptRepository : ReactiveCrudRepository<FiscalReceiptEntity, UUID> {

    @Query("SELECT * FROM ASOP_FISCAL_RECEIPTS WHERE TRANSACTION_ID = :transactionId LIMIT 1")
    fun findByTransactionId(transactionId: UUID): Mono<FiscalReceiptEntity>
}
