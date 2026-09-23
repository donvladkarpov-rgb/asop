package ru.asop.payment.repository

import org.springframework.data.repository.reactive.ReactiveCrudRepository
import org.springframework.stereotype.Repository
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import ru.asop.payment.model.PaymentAttemptEntity
import java.util.UUID

@Repository
interface PaymentAttemptRepository : ReactiveCrudRepository<PaymentAttemptEntity, UUID> {

    fun findByPaymentIdOrderByAttemptNumberAsc(paymentId: UUID): Flux<PaymentAttemptEntity>

    fun countByPaymentId(paymentId: UUID): Mono<Long>
}
