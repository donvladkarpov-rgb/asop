package ru.asop.admin.controller

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate
import reactor.core.publisher.Mono
import ru.asop.admin.model.SessionTypeEntity
import ru.asop.admin.repository.SessionTypeRepository
import ru.asop.api.reference.controller.SessionTypeApi
import ru.asop.api.reference.dto.request.SessionTypeCreateRequest
import ru.asop.api.reference.dto.request.SessionTypeUpdateRequest
import ru.asop.api.reference.dto.response.SessionTypeResponse
import ru.asop.common.util.UuidUtils
import java.util.UUID

@RestController
class SessionTypeController(
    private val repository: SessionTypeRepository,
    private val template: R2dbcEntityTemplate
) : SessionTypeApi {

    override fun listSessionTypes(): Mono<ResponseEntity<List<SessionTypeResponse>>> {
        return repository.findAll()
            .map { it.toResponse() }
            .collectList()
            .map { ResponseEntity.ok(it) }
    }

    override fun getSessionType(id: UUID): Mono<ResponseEntity<SessionTypeResponse>> {
        return repository.findById(id)
            .map { ResponseEntity.ok(it.toResponse()) }
            .defaultIfEmpty(ResponseEntity.notFound().build())
    }

    override fun createSessionType(request: SessionTypeCreateRequest): Mono<ResponseEntity<SessionTypeResponse>> {
        val entity = SessionTypeEntity(
            sessionTypeId = UuidUtils.newId(),
            sessionTypeCode = request.sessionTypeCode,
            sessionTypeName = request.sessionTypeName
        )
        return template.insert(entity)
            .map { ResponseEntity.status(HttpStatus.CREATED).body(it.toResponse()) }
    }

    override fun updateSessionType(id: UUID, request: SessionTypeUpdateRequest): Mono<ResponseEntity<SessionTypeResponse>> {
        return repository.findById(id)
            .flatMap { existing ->
                val updated = existing.copy(
                    sessionTypeCode = request.sessionTypeCode ?: existing.sessionTypeCode,
                    sessionTypeName = request.sessionTypeName ?: existing.sessionTypeName
                )
                repository.save(updated).map { ResponseEntity.ok(it.toResponse()) }
            }
            .defaultIfEmpty(ResponseEntity.notFound().build())
    }

    override fun deleteSessionType(id: UUID): Mono<ResponseEntity<Void>> {
        return repository.deleteById(id).thenReturn(ResponseEntity.noContent().build())
    }

    private fun SessionTypeEntity.toResponse() = SessionTypeResponse(
        id = sessionTypeId,
        sessionTypeCode = sessionTypeCode,
        sessionTypeName = sessionTypeName
    )
}
