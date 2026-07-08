package ru.asop.api.carrier.controller

import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import reactor.core.publisher.Mono
import ru.asop.api.carrier.dto.request.ContractCreateRequest
import ru.asop.api.carrier.dto.response.ContractResponse
import java.security.Principal
import java.util.UUID

@RequestMapping("/api/v1/contracts")
interface ContractApi {

    @PostMapping
    fun createContract(
        @Valid @RequestBody request: ContractCreateRequest,
        principal: Mono<Principal>
    ): Mono<ResponseEntity<ContractResponse>>

    @GetMapping("/{id}")
    fun getContract(
        @PathVariable id: UUID
    ): Mono<ResponseEntity<ContractResponse>>
}
