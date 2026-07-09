package ru.asop.api.reference.controller

import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import reactor.core.publisher.Mono
import ru.asop.api.reference.dto.request.TariffTypeCreateRequest
import ru.asop.api.reference.dto.request.TariffTypeUpdateRequest
import ru.asop.api.reference.dto.response.TariffTypeResponse
import java.util.UUID

@RequestMapping("/api/v1/tariff-types")
interface TariffTypeApi {

    @GetMapping
    fun listTariffTypes(): Mono<ResponseEntity<List<TariffTypeResponse>>>

    @GetMapping("/{id}")
    fun getTariffType(@PathVariable id: UUID): Mono<ResponseEntity<TariffTypeResponse>>

    @PostMapping
    fun createTariffType(@Valid @RequestBody request: TariffTypeCreateRequest): Mono<ResponseEntity<TariffTypeResponse>>

    @PutMapping("/{id}")
    fun updateTariffType(@PathVariable id: UUID, @Valid @RequestBody request: TariffTypeUpdateRequest): Mono<ResponseEntity<TariffTypeResponse>>

    @DeleteMapping("/{id}")
    fun deleteTariffType(@PathVariable id: UUID): Mono<ResponseEntity<Void>>
}
