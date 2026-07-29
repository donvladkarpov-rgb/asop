package ru.asop.carrier.repository

import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.reactive.ReactiveCrudRepository
import org.springframework.stereotype.Repository
import reactor.core.publisher.Flux
import ru.asop.carrier.model.TidEntity
import java.util.UUID

@Repository
interface TidRepository : ReactiveCrudRepository<TidEntity, UUID> {
    fun findByCarrierId(carrierId: UUID): Flux<TidEntity>

    @Query("SELECT t.* FROM ASOP_TIDS t INNER JOIN ASOP_CARRIERS c ON t.carrier_id = c.carrier_id WHERE c.region_id = :regionId")
    fun findByRegionId(regionId: UUID): Flux<TidEntity>
}
