package ru.asop.admin.controller

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate
import reactor.core.publisher.Mono
import ru.asop.admin.model.RegionEntity
import ru.asop.admin.repository.RegionRepository
import ru.asop.api.reference.controller.RegionApi
import ru.asop.api.reference.dto.request.RegionCreateRequest
import ru.asop.api.reference.dto.request.RegionUpdateRequest
import ru.asop.api.reference.dto.response.RegionResponse
import ru.asop.common.util.UuidUtils
import java.util.UUID

@RestController
class RegionController(
    private val repository: RegionRepository,
    private val template: R2dbcEntityTemplate
) : RegionApi {

    override fun listRegions(): Mono<ResponseEntity<List<RegionResponse>>> {
        return repository.findAll()
            .map { it.toResponse() }
            .collectList()
            .map { ResponseEntity.ok(it) }
    }

    override fun getRegion(id: UUID): Mono<ResponseEntity<RegionResponse>> {
        return repository.findById(id)
            .map { ResponseEntity.ok(it.toResponse()) }
            .defaultIfEmpty(ResponseEntity.notFound().build())
    }

    override fun createRegion(request: RegionCreateRequest): Mono<ResponseEntity<RegionResponse>> {
        val entity = RegionEntity(
            regionId = UuidUtils.newId(),
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

    override fun updateRegion(id: UUID, request: RegionUpdateRequest): Mono<ResponseEntity<RegionResponse>> {
        return repository.findById(id)
            .flatMap { existing ->
                val updated = existing.copy(
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

    override fun deleteRegion(id: UUID): Mono<ResponseEntity<Void>> {
        return repository.deleteById(id).thenReturn(ResponseEntity.noContent().build())
    }

    private fun RegionEntity.toResponse() = RegionResponse(
        id = regionId,
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
}
