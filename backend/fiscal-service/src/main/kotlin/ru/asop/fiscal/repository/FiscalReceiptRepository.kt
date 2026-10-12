package ru.asop.fiscal.repository

import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.reactive.ReactiveCrudRepository
import org.springframework.stereotype.Repository
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import ru.asop.fiscal.model.FiscalReceiptEntity
import java.time.OffsetDateTime
import java.util.UUID

@Repository
interface FiscalReceiptRepository : ReactiveCrudRepository<FiscalReceiptEntity, UUID> {

    @Query("SELECT * FROM ASOP_FISCAL_RECEIPTS WHERE TRANSACTION_ID = :transactionId LIMIT 1")
    fun findByTransactionId(transactionId: UUID): Mono<FiscalReceiptEntity>

    @Query("SELECT * FROM ASOP_FISCAL_RECEIPTS WHERE ACQ_REFERENCE = :acqReference LIMIT 1")
    fun findByAcqReference(acqReference: String): Mono<FiscalReceiptEntity>

    @Query(
        "SELECT * FROM ASOP_FISCAL_RECEIPTS " +
            "WHERE (STATUS = 'PENDING' AND CREATED_AT <= :staleBefore) " +
            "   OR (STATUS = 'RETRY' AND NEXT_RETRY_AT IS NOT NULL AND NEXT_RETRY_AT <= :now) " +
            "ORDER BY COALESCE(NEXT_RETRY_AT, CREATED_AT) ASC LIMIT 200"
    )
    fun findDueForRetry(now: OffsetDateTime, staleBefore: OffsetDateTime): Flux<FiscalReceiptEntity>
}
