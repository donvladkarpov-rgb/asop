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
import ru.asop.api.reference.dto.request.OrganizerCreateRequest
import ru.asop.api.reference.dto.request.OrganizerTerritoryAssignRequest
import ru.asop.api.reference.dto.request.OrganizerUpdateRequest
import ru.asop.api.reference.dto.response.OrganizerResponse
import ru.asop.api.reference.dto.response.OrganizerTerritoryResponse
import java.util.UUID

@RequestMapping("/api/v1/organizers")
interface OrganizerApi {

    @GetMapping
    fun listOrganizers(): Mono<ResponseEntity<List<OrganizerResponse>>>

    @GetMapping("/{id}")
    fun getOrganizer(@PathVariable id: UUID): Mono<ResponseEntity<OrganizerResponse>>

    @PostMapping
    fun createOrganizer(@Valid @RequestBody request: OrganizerCreateRequest): Mono<ResponseEntity<OrganizerResponse>>

    @PutMapping("/{id}")
    fun updateOrganizer(@PathVariable id: UUID, @Valid @RequestBody request: OrganizerUpdateRequest): Mono<ResponseEntity<OrganizerResponse>>

    @DeleteMapping("/{id}")
    fun deleteOrganizer(@PathVariable id: UUID): Mono<ResponseEntity<Void>>

    @GetMapping("/{id}/territories")
    fun getOrganizerTerritories(@PathVariable id: UUID): Mono<ResponseEntity<List<OrganizerTerritoryResponse>>>

    @PostMapping("/{id}/territories")
    fun assignTerritory(@PathVariable id: UUID, @Valid @RequestBody request: OrganizerTerritoryAssignRequest): Mono<ResponseEntity<Void>>

    @DeleteMapping("/{id}/territories/{territoryId}")
    fun unassignTerritory(@PathVariable id: UUID, @PathVariable territoryId: UUID): Mono<ResponseEntity<Void>>
}
