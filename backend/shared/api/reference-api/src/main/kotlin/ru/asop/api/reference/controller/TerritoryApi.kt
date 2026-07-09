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
import org.springframework.web.bind.annotation.RequestParam
import reactor.core.publisher.Mono
import ru.asop.api.reference.dto.request.TerritoryCreateRequest
import ru.asop.api.reference.dto.request.TerritoryUpdateRequest
import ru.asop.api.reference.dto.response.TerritoryResponse
import java.util.UUID

@RequestMapping("/api/v1/territories")
interface TerritoryApi {

    @GetMapping
    fun listTerritories(@RequestParam(required = false) regionId: UUID?): Mono<ResponseEntity<List<TerritoryResponse>>>

    @GetMapping("/{id}")
    fun getTerritory(@PathVariable id: UUID): Mono<ResponseEntity<TerritoryResponse>>

    @PostMapping
    fun createTerritory(@Valid @RequestBody request: TerritoryCreateRequest): Mono<ResponseEntity<TerritoryResponse>>

    @PutMapping("/{id}")
    fun updateTerritory(@PathVariable id: UUID, @Valid @RequestBody request: TerritoryUpdateRequest): Mono<ResponseEntity<TerritoryResponse>>

    @DeleteMapping("/{id}")
    fun deleteTerritory(@PathVariable id: UUID): Mono<ResponseEntity<Void>>
}
