package ru.asop.api.route.controller

import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import ru.asop.api.route.dto.request.ContractRouteCreateRequest
import ru.asop.api.route.dto.response.ContractRouteResponse

@RequestMapping("/api/v1/contract-routes")
interface ContractRouteApi {

    @GetMapping
    fun list(
        @RequestParam("contractId", required = false) contractId: String? = null,
        @RequestParam("routeId", required = false) routeId: String? = null
    ): Flux<ContractRouteResponse>

    @PostMapping
    fun create(@Valid @RequestBody request: ContractRouteCreateRequest): Mono<ResponseEntity<ContractRouteResponse>>

    @DeleteMapping
    fun delete(
        @RequestParam("contractId") contractId: String,
        @RequestParam("routeId") routeId: String
    ): Mono<ResponseEntity<Void>>
}
