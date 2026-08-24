package ru.asop.admin.controller

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate
import reactor.core.publisher.Mono
import ru.asop.admin.model.OrganizerEntity
import ru.asop.admin.model.OrganizerTerritoryEntity
import ru.asop.admin.repository.OrganizerDeltaQuery
import ru.asop.admin.repository.OrganizerRepository
import ru.asop.admin.repository.OrganizerTerritoryRepository
import ru.asop.admin.repository.RegionRepository
import ru.asop.admin.repository.TerritoryRepository
import ru.asop.api.reference.controller.OrganizerApi
import ru.asop.api.reference.dto.request.OrganizerCreateRequest
import ru.asop.api.reference.dto.request.OrganizerTerritoryAssignRequest
import ru.asop.api.reference.dto.request.OrganizerUpdateRequest
import ru.asop.api.reference.dto.response.OrganizerResponse
import ru.asop.api.reference.dto.response.OrganizerTerritoryResponse
import ru.asop.common.util.UuidUtils
import java.util.UUID
import reactor.core.publisher.Flux
import org.springframework.data.domain.Sort
import org.springframework.data.relational.core.query.Criteria
import org.springframework.data.relational.core.query.Query
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam


@RestController
class OrganizerController(
    private val organizerRepository: OrganizerRepository,
    private val organizerDeltaQuery: OrganizerDeltaQuery,
    private val organizerTerritoryRepository: OrganizerTerritoryRepository,
    private val territoryRepository: TerritoryRepository,
    private val regionRepository: RegionRepository,
    private val template: R2dbcEntityTemplate
) : OrganizerApi {

    override fun listOrganizers(regionId: UUID?): Mono<ResponseEntity<List<OrganizerResponse>>> {
        // Глобальный фильтр web-admin: организаторы, привязанные к территориям региона.
        return organizerDeltaQuery.listByRegion(regionId)
            .map { it.toResponse() }
            .collectList()
            .map { ResponseEntity.ok(it) }
    }

    override fun getOrganizer(id: UUID): Mono<ResponseEntity<OrganizerResponse>> {
        return organizerRepository.findById(id)
            .map { ResponseEntity.ok(it.toResponse()) }
            .defaultIfEmpty(ResponseEntity.notFound().build())
    }

    override fun createOrganizer(request: OrganizerCreateRequest): Mono<ResponseEntity<OrganizerResponse>> {
        val entity = OrganizerEntity(
            organizerId = UuidUtils.newId(),
            organizerName = request.organizerName
        )
        return template.insert(entity)
            .map { ResponseEntity.status(HttpStatus.CREATED).body(it.toResponse()) }
    }

    override fun updateOrganizer(id: UUID, request: OrganizerUpdateRequest): Mono<ResponseEntity<OrganizerResponse>> {
        return organizerRepository.findById(id)
            .flatMap { existing ->
                val updated = existing.copy(
                    organizerName = request.organizerName ?: existing.organizerName
                )
                organizerRepository.save(updated).map { ResponseEntity.ok(it.toResponse()) }
            }
            .defaultIfEmpty(ResponseEntity.notFound().build())
    }

    override fun deleteOrganizer(id: UUID): Mono<ResponseEntity<Void>> {
        return organizerRepository.deleteById(id).thenReturn(ResponseEntity.noContent().build())
    }

    override fun getOrganizerTerritories(id: UUID): Mono<ResponseEntity<List<OrganizerTerritoryResponse>>> {
        return organizerTerritoryRepository.findByOrganizerId(id)
            .flatMap { ot ->
                territoryRepository.findById(ot.territoryId)
                    .flatMap { t ->
                        regionRepository.findById(t.regionId)
                            .map { r ->
                                OrganizerTerritoryResponse(
                                    organizerId = ot.organizerId,
                                    territoryId = ot.territoryId,
                                    territoryName = t.municipalDivision,
                                    regionName = r.municipalDivision
                                )
                            }
                            .defaultIfEmpty(
                                OrganizerTerritoryResponse(
                                    organizerId = ot.organizerId,
                                    territoryId = ot.territoryId,
                                    territoryName = t.municipalDivision
                                )
                            )
                    }
                    .defaultIfEmpty(
                        OrganizerTerritoryResponse(
                            organizerId = ot.organizerId,
                            territoryId = ot.territoryId
                        )
                    )
            }
            .collectList()
            .map { ResponseEntity.ok(it) }
    }

    override fun assignTerritory(id: UUID, request: OrganizerTerritoryAssignRequest): Mono<ResponseEntity<Void>> {
        val entity = OrganizerTerritoryEntity(
            organizerId = id,
            territoryId = request.territoryId
        )
        return template.insert(entity)
            .thenReturn(ResponseEntity.status(HttpStatus.CREATED).build())
    }

    override fun unassignTerritory(id: UUID, territoryId: UUID): Mono<ResponseEntity<Void>> {
        return organizerTerritoryRepository.deleteByOrganizerIdAndTerritoryId(id, territoryId)
            .thenReturn(ResponseEntity.noContent().build())
    }

    private fun OrganizerEntity.toResponse() = OrganizerResponse(
        id = organizerId,
        organizerName = organizerName
    )


    @GetMapping("/delta")
    fun listDelta(
        @RequestParam(required = false) regionId: UUID?,
        @RequestParam(required = false) versionSince: Long?,
        @RequestParam(required = false) includeDeleted: Boolean,
        @RequestParam(required = false, defaultValue = "10000") limit: Int
    ): Flux<OrganizerEntity> = organizerDeltaQuery.findByRegion(
        versionSince, includeDeleted, limit, regionId
    )
}
