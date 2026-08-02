package ru.asop.carrier.controller

import org.springframework.data.r2dbc.core.R2dbcEntityTemplate
import org.springframework.data.relational.core.query.Criteria
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import ru.asop.api.carrier.controller.ContractApi
import ru.asop.api.carrier.dto.request.ContractCreateRequest
import ru.asop.api.carrier.dto.request.ContractUpdateRequest
import ru.asop.api.carrier.dto.response.ContractResponse
import ru.asop.carrier.config.DeltaSupport
import ru.asop.carrier.model.ContractEntity
import ru.asop.carrier.service.ContractService
import java.security.Principal
import java.util.UUID

@RestController
class ContractController(
    private val service: ContractService,
    private val template: R2dbcEntityTemplate
) : ContractApi {

    override fun listContracts(): Flux<ContractResponse> = service.findAll()

    override fun getContract(id: UUID): Mono<ResponseEntity<ContractResponse>> =
        service.getById(id).map { ResponseEntity.ok(it) }

    override fun createContract(
        request: ContractCreateRequest,
        principal: Mono<Principal>
    ): Mono<ResponseEntity<ContractResponse>> =
        service.create(request).map { ResponseEntity.ok(it) }

    override fun updateContract(
        id: UUID,
        request: ContractUpdateRequest,
        principal: Mono<Principal>
    ): Mono<ResponseEntity<ContractResponse>> =
        service.update(id, request).map { ResponseEntity.ok(it) }

    override fun deleteContract(id: UUID): Mono<ResponseEntity<Void>> =
        service.delete(id).map { ResponseEntity.noContent().build() }

    @GetMapping("/delta")
    fun listDelta(
        @RequestParam(required = false) versionSince: Long?,
        @RequestParam(required = false) includeDeleted: Boolean,
        @RequestParam(required = false) carrierId: UUID?,
        @RequestParam(required = false) cardsDistributorId: UUID?,
        @RequestParam(required = false, defaultValue = "10000") limit: Int
    ): Flux<ContractEntity> {
        val extra = mutableListOf<Criteria>()
        carrierId?.let { extra += Criteria.where("carrier_id").`is`(it) }
        cardsDistributorId?.let { extra += Criteria.where("cards_distributor_id").`is`(it) }
        val query = if (extra.isEmpty()) {
            DeltaSupport.query(versionSince, includeDeleted, limit)
        } else {
            DeltaSupport.query(versionSince, includeDeleted, limit, Criteria.from(extra))
        }
        return template.select(ContractEntity::class.java)
            .matching(query)
            .all()
    }
}
