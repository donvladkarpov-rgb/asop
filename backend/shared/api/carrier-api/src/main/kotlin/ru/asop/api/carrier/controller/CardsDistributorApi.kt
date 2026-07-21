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
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import ru.asop.api.carrier.dto.request.CardsDistributorCreateRequest
import ru.asop.api.carrier.dto.request.CardsDistributorUpdateRequest
import ru.asop.api.carrier.dto.response.CardsDistributorResponse
import java.util.UUID

@RequestMapping("/api/v1/cards-distributors")
interface CardsDistributorApi {

    @GetMapping
    fun listCardsDistributors(): Flux<CardsDistributorResponse>

    @GetMapping("/{id}")
    fun getCardsDistributor(
        @PathVariable id: UUID
    ): Mono<ResponseEntity<CardsDistributorResponse>>

    @PostMapping
    fun createCardsDistributor(
        @Valid @RequestBody request: CardsDistributorCreateRequest
    ): Mono<ResponseEntity<CardsDistributorResponse>>

    @PutMapping("/{id}")
    fun updateCardsDistributor(
        @PathVariable id: UUID,
        @Valid @RequestBody request: CardsDistributorUpdateRequest
    ): Mono<ResponseEntity<CardsDistributorResponse>>

    @DeleteMapping("/{id}")
    fun deleteCardsDistributor(
        @PathVariable id: UUID
    ): Mono<ResponseEntity<Void>>
}
