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
import ru.asop.api.reference.dto.request.RegionCreateRequest
import ru.asop.api.reference.dto.request.RegionUpdateRequest
import ru.asop.api.reference.dto.response.RegionResponse
import java.util.UUID

@RequestMapping("/api/v1/regions")
interface RegionApi {

    @GetMapping
    fun listRegions(): Mono<ResponseEntity<List<RegionResponse>>>

    @GetMapping("/{id}")
    fun getRegion(@PathVariable id: UUID): Mono<ResponseEntity<RegionResponse>>

    @PostMapping
    fun createRegion(@Valid @RequestBody request: RegionCreateRequest): Mono<ResponseEntity<RegionResponse>>

    @PutMapping("/{id}")
    fun updateRegion(@PathVariable id: UUID, @Valid @RequestBody request: RegionUpdateRequest): Mono<ResponseEntity<RegionResponse>>

    @DeleteMapping("/{id}")
    fun deleteRegion(@PathVariable id: UUID): Mono<ResponseEntity<Void>>
}
