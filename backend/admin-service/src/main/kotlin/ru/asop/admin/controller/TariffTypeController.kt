package ru.asop.admin.controller

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate
import reactor.core.publisher.Mono
import ru.asop.admin.model.TariffTypeEntity
import ru.asop.admin.repository.TariffTypeRepository
import ru.asop.api.reference.controller.TariffTypeApi
import ru.asop.api.reference.dto.request.TariffTypeCreateRequest
import ru.asop.api.reference.dto.request.TariffTypeUpdateRequest
import ru.asop.api.reference.dto.response.TariffTypeResponse
import ru.asop.common.util.UuidUtils
import java.util.UUID

@RestController
class TariffTypeController(
    private val repository: TariffTypeRepository,
    private val template: R2dbcEntityTemplate
) : TariffTypeApi {

    override fun listTariffTypes(): Mono<ResponseEntity<List<TariffTypeResponse>>> {
        return repository.findAll()
            .map { it.toResponse() }
            .collectList()
            .map { ResponseEntity.ok(it) }
    }

    override fun getTariffType(id: UUID): Mono<ResponseEntity<TariffTypeResponse>> {
        return repository.findById(id)
            .map { ResponseEntity.ok(it.toResponse()) }
            .defaultIfEmpty(ResponseEntity.notFound().build())
    }

    override fun createTariffType(request: TariffTypeCreateRequest): Mono<ResponseEntity<TariffTypeResponse>> {
        val entity = TariffTypeEntity(
            tariffTypeId = UuidUtils.newId(),
            code = request.code,
            name = request.name,
            description = request.description
        )
        return template.insert(entity)
            .map { ResponseEntity.status(HttpStatus.CREATED).body(it.toResponse()) }
    }

    override fun updateTariffType(id: UUID, request: TariffTypeUpdateRequest): Mono<ResponseEntity<TariffTypeResponse>> {
        return repository.findById(id)
            .flatMap { existing ->
                val updated = existing.copy(
                    code = request.code ?: existing.code,
                    name = request.name ?: existing.name,
                    description = request.description ?: existing.description
                )
                repository.save(updated).map { ResponseEntity.ok(it.toResponse()) }
            }
            .defaultIfEmpty(ResponseEntity.notFound().build())
    }

    override fun deleteTariffType(id: UUID): Mono<ResponseEntity<Void>> {
        return repository.deleteById(id).thenReturn(ResponseEntity.noContent().build())
    }

    private fun TariffTypeEntity.toResponse() = TariffTypeResponse(
        id = tariffTypeId,
        code = code,
        name = name,
        description = description
    )
}
