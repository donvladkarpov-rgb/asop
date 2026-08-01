package ru.asop.admin.controller

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate
import reactor.core.publisher.Mono
import ru.asop.admin.model.TerritoryEntity
import ru.asop.admin.repository.TerritoryRepository
import ru.asop.api.reference.controller.TerritoryApi
import ru.asop.api.reference.dto.request.TerritoryCreateRequest
import ru.asop.api.reference.dto.request.TerritoryUpdateRequest
import ru.asop.api.reference.dto.response.TerritoryResponse
import ru.asop.common.util.UuidUtils
import java.util.UUID
import java.time.Instant
import reactor.core.publisher.Flux
import org.springframework.data.domain.Sort
import org.springframework.data.relational.core.query.Criteria
import org.springframework.data.relational.core.query.Query
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import ru.asop.admin.config.DeltaSupport

@RestController
class TerritoryController(
    private val repository: TerritoryRepository,
    private val template: R2dbcEntityTemplate
) : TerritoryApi {

    override fun listTerritories(regionId: UUID?): Mono<ResponseEntity<List<TerritoryResponse>>> {
        val flux = if (regionId != null) {
            repository.findByRegionId(regionId)
        } else {
            repository.findAll()
        }
        return flux
            .map { it.toResponse() }
            .collectList()
            .map { ResponseEntity.ok(it) }
    }

    override fun getTerritory(id: UUID): Mono<ResponseEntity<TerritoryResponse>> {
        return repository.findById(id)
            .map { ResponseEntity.ok(it.toResponse()) }
            .defaultIfEmpty(ResponseEntity.notFound().build())
    }

    override fun createTerritory(request: TerritoryCreateRequest): Mono<ResponseEntity<TerritoryResponse>> {
        val entity = TerritoryEntity(
            territoryId = UuidUtils.newId(),
            regionId = request.regionId,
            municipalDivision = request.municipalDivision,
            adminDivision = request.adminDivision,
            federalDistrict = request.federalDistrict,
            ifnsFlCode = request.ifnsFlCode,
            ifnsUlCode = request.ifnsUlCode,
            okatoCode = request.okatoCode,
            oktmoCode = request.oktmoCode,
            oktmoBudgetCode = request.oktmoBudgetCode,
            fiasId = request.fiasId,
            registryRecordId = request.registryRecordId
        )
        return template.insert(entity)
            .map { ResponseEntity.status(HttpStatus.CREATED).body(it.toResponse()) }
    }

    override fun updateTerritory(id: UUID, request: TerritoryUpdateRequest): Mono<ResponseEntity<TerritoryResponse>> {
        return repository.findById(id)
            .flatMap { existing ->
                val updated = existing.copy(
                    regionId = request.regionId ?: existing.regionId,
                    municipalDivision = request.municipalDivision ?: existing.municipalDivision,
                    adminDivision = request.adminDivision ?: existing.adminDivision,
                    federalDistrict = request.federalDistrict ?: existing.federalDistrict,
                    ifnsFlCode = request.ifnsFlCode ?: existing.ifnsFlCode,
                    ifnsUlCode = request.ifnsUlCode ?: existing.ifnsUlCode,
                    okatoCode = request.okatoCode ?: existing.okatoCode,
                    oktmoCode = request.oktmoCode ?: existing.oktmoCode,
                    oktmoBudgetCode = request.oktmoBudgetCode ?: existing.oktmoBudgetCode,
                    fiasId = request.fiasId ?: existing.fiasId,
                    registryRecordId = request.registryRecordId ?: existing.registryRecordId
                )
                repository.save(updated).map { ResponseEntity.ok(it.toResponse()) }
            }
            .defaultIfEmpty(ResponseEntity.notFound().build())
    }

    override fun deleteTerritory(id: UUID): Mono<ResponseEntity<Void>> {
        return repository.deleteById(id).thenReturn(ResponseEntity.noContent().build())
    }

    private fun TerritoryEntity.toResponse() = TerritoryResponse(
        id = territoryId,
        regionId = regionId,
        municipalDivision = municipalDivision,
        adminDivision = adminDivision,
        federalDistrict = federalDistrict,
        ifnsFlCode = ifnsFlCode,
        ifnsUlCode = ifnsUlCode,
        okatoCode = okatoCode,
        oktmoCode = oktmoCode,
        oktmoBudgetCode = oktmoBudgetCode,
        fiasId = fiasId,
        registryRecordId = registryRecordId
    )


    @GetMapping("/delta")
    fun listDelta(
        @RequestParam(required = false) updatedAtSince: Instant?,
        @RequestParam(required = false) includeDeleted: Boolean,
        @RequestParam(required = false, defaultValue = "10000") limit: Int
    ): Flux<TerritoryEntity> {
        return template.select(TerritoryEntity::class.java)
            .matching(DeltaSupport.query(updatedAtSince, includeDeleted, limit))
            .all()
    }
}
