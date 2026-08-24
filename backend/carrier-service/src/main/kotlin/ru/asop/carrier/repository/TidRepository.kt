package ru.asop.carrier.repository

import org.springframework.data.repository.reactive.ReactiveCrudRepository
import org.springframework.stereotype.Repository
import reactor.core.publisher.Flux
import ru.asop.carrier.model.TidEntity
import java.util.UUID

@Repository
interface TidRepository : ReactiveCrudRepository<TidEntity, UUID> {
    fun findByContractId(contractId: UUID): Flux<TidEntity>
}
