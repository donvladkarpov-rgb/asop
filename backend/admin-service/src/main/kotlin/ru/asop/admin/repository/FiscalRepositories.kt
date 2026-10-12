package ru.asop.admin.repository

import org.springframework.data.r2dbc.repository.R2dbcRepository
import org.springframework.stereotype.Repository
import ru.asop.admin.model.CarrierFiscalizerEntity
import ru.asop.admin.model.DistributorFiscalizerEntity
import ru.asop.admin.model.FiscalizerEntity
import ru.asop.admin.model.FiscalizerTokenEntity
import reactor.core.publisher.Flux
import java.util.UUID

@Repository
interface FiscalizerRepository : R2dbcRepository<FiscalizerEntity, UUID>

@Repository
interface CarrierFiscalizerRepository : R2dbcRepository<CarrierFiscalizerEntity, UUID> {
    fun findByCarrierId(carrierId: UUID): Flux<CarrierFiscalizerEntity>
}

@Repository
interface DistributorFiscalizerRepository : R2dbcRepository<DistributorFiscalizerEntity, UUID> {
    fun findByCardsDistributorId(cardsDistributorId: UUID): Flux<DistributorFiscalizerEntity>
}

@Repository
interface FiscalizerTokenRepository : R2dbcRepository<FiscalizerTokenEntity, UUID> {
    fun findByCarrierFiscalizerId(carrierFiscalizerId: UUID): Flux<FiscalizerTokenEntity>
    fun findByDistributorFiscalizerId(distributorFiscalizerId: UUID): Flux<FiscalizerTokenEntity>
}
