package ru.asop.terminal.repository

import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.reactive.ReactiveCrudRepository
import org.springframework.stereotype.Repository
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import ru.asop.terminal.model.TerminalEntity
import java.util.UUID

@Repository
interface TerminalRepository : ReactiveCrudRepository<TerminalEntity, UUID> {

    fun findByTerminalSerial(terminalSerial: String): Mono<TerminalEntity>

    fun findByCarrierId(carrierId: UUID): Flux<TerminalEntity>

    @Query("SELECT t.* FROM ASOP_TERMINALS t INNER JOIN ASOP_CARRIERS c ON t.carrier_id = c.carrier_id WHERE c.region_id = :regionId")
    fun findByRegionId(regionId: UUID): Flux<TerminalEntity>
}
