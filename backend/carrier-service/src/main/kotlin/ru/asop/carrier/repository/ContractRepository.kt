package ru.asop.carrier.repository

import org.springframework.data.repository.reactive.ReactiveCrudRepository
import org.springframework.stereotype.Repository
import reactor.core.publisher.Flux
import ru.asop.carrier.model.ContractEntity
import java.util.UUID

@Repository
interface ContractRepository : ReactiveCrudRepository<ContractEntity, UUID> {
    fun findByCardsDistributorId(cardsDistributorId: UUID): Flux<ContractEntity>
    fun findByCarrierId(carrierId: UUID): Flux<ContractEntity>
}
