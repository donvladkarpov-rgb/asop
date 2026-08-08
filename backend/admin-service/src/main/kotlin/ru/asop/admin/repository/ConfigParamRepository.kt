package ru.asop.admin.repository

import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.r2dbc.repository.R2dbcRepository
import org.springframework.stereotype.Repository
import ru.asop.admin.model.ConfigParamEntity
import java.util.UUID
import reactor.core.publisher.Mono

@Repository
interface ConfigParamRepository : R2dbcRepository<ConfigParamEntity, UUID> {

    @Query(
        """SELECT * FROM ASOP_CONFIG_PARAMS
           WHERE REGION_ID IS NULL AND ORGANIZER_ID IS NULL AND CARRIER_ID IS NULL
             AND CARDS_DISTRIBUTOR_ID IS NULL AND KRS_ID IS NULL AND DELETED_AT IS NULL
           LIMIT 1"""
    )
    fun findBaseRow(): Mono<ConfigParamEntity>
}