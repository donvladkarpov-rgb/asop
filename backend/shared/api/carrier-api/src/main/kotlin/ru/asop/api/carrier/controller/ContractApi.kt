package ru.asop.api.carrier.controller

import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import ru.asop.api.carrier.dto.request.ContractCreateRequest
import ru.asop.api.carrier.dto.request.ContractUpdateRequest
import ru.asop.api.carrier.dto.response.ContractResponse
import java.security.Principal
import java.util.UUID

@RequestMapping("/api/v1/contracts")
interface ContractApi {

    @GetMapping
    fun listContracts(
        @RequestParam(required = false) carrierId: UUID? = null,
        @RequestParam(required = false) cardsDistributorId: UUID? = null
    ): Flux<ContractResponse>

    @GetMapping("/{id}")
    fun getContract(
        @PathVariable id: UUID
    ): Mono<ResponseEntity<ContractResponse>>

    @PostMapping
    fun createContract(
        @Valid @RequestBody request: ContractCreateRequest,
        principal: Mono<Principal>
    ): Mono<ResponseEntity<ContractResponse>>

    @PutMapping("/{id}")
    fun updateContract(
        @PathVariable id: UUID,
        @Valid @RequestBody request: ContractUpdateRequest,
        principal: Mono<Principal>
    ): Mono<ResponseEntity<ContractResponse>>

    @DeleteMapping("/{id}")
    fun deleteContract(
        @PathVariable id: UUID
    ): Mono<ResponseEntity<Void>>
}
