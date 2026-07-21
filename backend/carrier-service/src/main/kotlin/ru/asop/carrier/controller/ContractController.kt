package ru.asop.carrier.controller

import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import ru.asop.api.carrier.controller.ContractApi
import ru.asop.api.carrier.dto.request.ContractCreateRequest
import ru.asop.api.carrier.dto.request.ContractUpdateRequest
import ru.asop.api.carrier.dto.response.ContractResponse
import ru.asop.carrier.service.ContractService
import java.security.Principal
import java.util.UUID

@RestController
class ContractController(
    private val service: ContractService
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
}
