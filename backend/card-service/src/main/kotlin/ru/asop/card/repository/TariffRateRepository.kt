package ru.asop.card.repository

import org.springframework.data.repository.reactive.ReactiveCrudRepository
import org.springframework.data.r2dbc.repository.Query
import org.springframework.stereotype.Repository
import reactor.core.publisher.Flux
import ru.asop.card.model.TariffRateEntity
import java.util.UUID

@Repository
interface TariffRateRepository : ReactiveCrudRepository<TariffRateEntity, UUID> {

    @Query("""
        SELECT * FROM ASOP_TARIFF_RATES
        WHERE (:versionSince IS NULL OR VERSION > :versionSince)
          AND (:includeDeleted = TRUE OR DELETED_AT IS NULL)
        ORDER BY VERSION ASC
        LIMIT :limit
    """)
    fun findDelta(versionSince: Long?, includeDeleted: Boolean, limit: Int): Flux<TariffRateEntity>
}
