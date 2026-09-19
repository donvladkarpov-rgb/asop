package ru.asop.payment.repository

import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.reactive.ReactiveCrudRepository
import org.springframework.stereotype.Repository
import reactor.core.publisher.Mono
import ru.asop.payment.model.PaymentEntity
import java.util.UUID

@Repository
interface PaymentRepository : ReactiveCrudRepository<PaymentEntity, UUID> {

    @Query("SELECT * FROM ASOP_BANK_PAYMENTS WHERE REQUEST_ID = :requestId AND DELETED_AT IS NULL LIMIT 1")
    fun findByRequestId(requestId: UUID): Mono<PaymentEntity>

    @Query("SELECT * FROM ASOP_BANK_PAYMENTS WHERE PAYMENT_ID = :paymentId AND DELETED_AT IS NULL")
    fun findActiveById(paymentId: UUID): Mono<PaymentEntity>

    @Query("SELECT * FROM ASOP_BANK_PAYMENTS WHERE ACQUIRER_REFERENCE = :ref AND DELETED_AT IS NULL LIMIT 1")
    fun findByAcquirerReference(ref: String): Mono<PaymentEntity>
}
