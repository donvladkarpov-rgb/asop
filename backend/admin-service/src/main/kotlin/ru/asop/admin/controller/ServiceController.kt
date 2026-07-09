package ru.asop.admin.controller

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate
import reactor.core.publisher.Mono
import ru.asop.admin.model.ServiceEntity
import ru.asop.admin.repository.ServiceRepository
import ru.asop.api.reference.controller.ServiceApi
import ru.asop.api.reference.dto.request.ServiceCreateRequest
import ru.asop.api.reference.dto.request.ServiceUpdateRequest
import ru.asop.api.reference.dto.response.ServiceResponse
import ru.asop.common.util.UuidUtils
import java.util.UUID

@RestController
class ServiceController(
    private val repository: ServiceRepository,
    private val template: R2dbcEntityTemplate
) : ServiceApi {

    override fun listServices(): Mono<ResponseEntity<List<ServiceResponse>>> {
        return repository.findAll()
            .map { it.toResponse() }
            .collectList()
            .map { ResponseEntity.ok(it) }
    }

    override fun getService(id: UUID): Mono<ResponseEntity<ServiceResponse>> {
        return repository.findById(id)
            .map { ResponseEntity.ok(it.toResponse()) }
            .defaultIfEmpty(ResponseEntity.notFound().build())
    }

    override fun createService(request: ServiceCreateRequest): Mono<ResponseEntity<ServiceResponse>> {
        val entity = ServiceEntity(
            serviceId = UuidUtils.newId(),
            serviceName = request.serviceName,
            description = request.description,
            priority = request.priority,
            regionId = request.regionId
        )
        return template.insert(entity)
            .map { ResponseEntity.status(HttpStatus.CREATED).body(it.toResponse()) }
    }

    override fun updateService(id: UUID, request: ServiceUpdateRequest): Mono<ResponseEntity<ServiceResponse>> {
        return repository.findById(id)
            .flatMap { existing ->
                val updated = existing.copy(
                    serviceName = request.serviceName ?: existing.serviceName,
                    description = request.description ?: existing.description,
                    priority = request.priority ?: existing.priority,
                    regionId = request.regionId ?: existing.regionId
                )
                repository.save(updated).map { ResponseEntity.ok(it.toResponse()) }
            }
            .defaultIfEmpty(ResponseEntity.notFound().build())
    }

    override fun deleteService(id: UUID): Mono<ResponseEntity<Void>> {
        return repository.deleteById(id).thenReturn(ResponseEntity.noContent().build())
    }

    private fun ServiceEntity.toResponse() = ServiceResponse(
        id = serviceId,
        serviceName = serviceName,
        description = description,
        priority = priority,
        regionId = regionId
    )
}
