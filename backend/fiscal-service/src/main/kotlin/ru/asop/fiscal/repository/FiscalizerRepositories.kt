package ru.asop.fiscal.repository

import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.reactive.ReactiveCrudRepository
import org.springframework.stereotype.Repository
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import ru.asop.fiscal.model.CarrierFiscalizer
import ru.asop.fiscal.model.DistributorFiscalizer
import ru.asop.fiscal.model.FiscalReceiptAttempt
import ru.asop.fiscal.model.Fiscalizer
import ru.asop.fiscal.model.FiscalizerToken
import java.util.UUID

@Repository
interface FiscalizerRepository : ReactiveCrudRepository<Fiscalizer, UUID> {
    @Query("SELECT * FROM ASOP_FISCALIZERS WHERE FISCALIZER_CODE = :code LIMIT 1")
    fun findByCode(code: String): Mono<Fiscalizer>
}

@Repository
interface CarrierFiscalizerRepository : ReactiveCrudRepository<CarrierFiscalizer, UUID> {
    @Query(
        "SELECT * FROM ASOP_CARRIER_FISCALIZERS " +
            "WHERE CARRIER_ID = :carrierId AND IS_ACTIVE = true " +
            "ORDER BY IS_PRIMARY DESC LIMIT 1"
    )
    fun findActive(carrierId: UUID): Mono<CarrierFiscalizer>
}

@Repository
interface DistributorFiscalizerRepository : ReactiveCrudRepository<DistributorFiscalizer, UUID> {
    @Query(
        "SELECT * FROM ASOP_DISTRIBUTOR_FISCALIZERS " +
            "WHERE CARDS_DISTRIBUTOR_ID = :distributorId AND IS_ACTIVE = true " +
            "ORDER BY IS_PRIMARY DESC LIMIT 1"
    )
    fun findActive(distributorId: UUID): Mono<DistributorFiscalizer>
}

@Repository
interface FiscalizerTokenRepository : ReactiveCrudRepository<FiscalizerToken, UUID> {
    @Query(
        "SELECT * FROM ASOP_FISCALIZER_TOKENS " +
            "WHERE (CARRIER_FISCALIZER_ID = :linkId OR DISTRIBUTOR_FISCALIZER_ID = :linkId) AND STATUS = 'ACTIVE' " +
            "AND VALID_FROM <= NOW() AND VALID_UNTIL >= NOW() " +
            "ORDER BY CREATED_AT DESC LIMIT 1"
    )
    fun findActiveForLink(linkId: UUID): Mono<FiscalizerToken>

    @Query("SELECT * FROM ASOP_FISCALIZER_TOKENS WHERE CARRIER_FISCALIZER_ID = :linkId ORDER BY CREATED_AT DESC")
    fun findAllForLink(linkId: UUID): Flux<FiscalizerToken>
}

@Repository
interface FiscalReceiptAttemptRepository : ReactiveCrudRepository<FiscalReceiptAttempt, UUID>
