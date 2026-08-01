package ru.asop.card.repository

import org.springframework.data.repository.reactive.ReactiveCrudRepository
import org.springframework.data.r2dbc.repository.Query
import org.springframework.stereotype.Repository
import reactor.core.publisher.Flux
import ru.asop.card.model.TariffRateEntity
import java.time.Instant
import java.util.UUID

@Repository
interface TariffRateRepository : ReactiveCrudRepository<TariffRateEntity, UUID> {

    @Query("""
        SELECT * FROM ASOP_TARIFF_RATES
        WHERE (:updatedAtSince IS NULL OR UPDATED_AT > :updatedAtSince)
          AND (:includeDeleted = TRUE OR DELETED_AT IS NULL)
        ORDER BY UPDATED_AT ASC
        LIMIT :limit
    """)
    fun findDelta(updatedAtSince: Instant?, includeDeleted: Boolean, limit: Int): Flux<TariffRateEntity>
}
