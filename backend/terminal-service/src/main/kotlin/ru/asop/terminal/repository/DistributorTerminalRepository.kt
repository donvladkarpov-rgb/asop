package ru.asop.terminal.repository

import org.springframework.data.repository.reactive.ReactiveCrudRepository
import org.springframework.stereotype.Repository
import ru.asop.terminal.model.DistributorTerminalEntity
import java.util.UUID

@Repository
interface DistributorTerminalRepository : ReactiveCrudRepository<DistributorTerminalEntity, UUID> {
    fun findByCardsDistributorId(cardsDistributorId: UUID): reactor.core.publisher.Flux<DistributorTerminalEntity>
}