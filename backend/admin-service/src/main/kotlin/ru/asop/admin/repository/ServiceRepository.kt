package ru.asop.admin.repository

import org.springframework.data.r2dbc.repository.R2dbcRepository
import org.springframework.stereotype.Repository
import reactor.core.publisher.Flux
import ru.asop.admin.model.ServiceEntity
import java.util.UUID

@Repository
interface ServiceRepository : R2dbcRepository<ServiceEntity, UUID> {

    fun findByRegionId(regionId: UUID): Flux<ServiceEntity>
}
