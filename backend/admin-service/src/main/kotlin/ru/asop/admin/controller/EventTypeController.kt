package ru.asop.admin.controller

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate
import reactor.core.publisher.Mono
import ru.asop.admin.model.EventTypeEntity
import ru.asop.admin.repository.EventTypeRepository
import ru.asop.api.reference.controller.EventTypeApi
import ru.asop.api.reference.dto.request.EventTypeCreateRequest
import ru.asop.api.reference.dto.response.EventTypeResponse
import reactor.core.publisher.Flux
import org.springframework.data.domain.Sort
import org.springframework.data.relational.core.query.Criteria
import org.springframework.data.relational.core.query.Query
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import ru.asop.admin.config.DeltaSupport

@RestController
class EventTypeController(
    private val repository: EventTypeRepository,
    private val template: R2dbcEntityTemplate
) : EventTypeApi {

    override fun listEventTypes(): Mono<ResponseEntity<List<EventTypeResponse>>> {
        return repository.findAll()
            .map { it.toResponse() }
            .collectList()
            .map { ResponseEntity.ok(it) }
    }

    override fun getEventType(id: String): Mono<ResponseEntity<EventTypeResponse>> {
        return repository.findById(id)
            .map { ResponseEntity.ok(it.toResponse()) }
            .defaultIfEmpty(ResponseEntity.notFound().build())
    }

    override fun createEventType(request: EventTypeCreateRequest): Mono<ResponseEntity<EventTypeResponse>> {
        val entity = EventTypeEntity(
            eventType = request.eventType,
            eventTypeName = request.eventTypeName
        )
        return template.insert(entity)
            .map { ResponseEntity.status(HttpStatus.CREATED).body(it.toResponse()) }
    }

    private fun EventTypeEntity.toResponse() = EventTypeResponse(
        eventType = eventType,
        eventTypeName = eventTypeName
    )


    @GetMapping("/delta")
    fun listDelta(
        @RequestParam(required = false) versionSince: Long?,
        @RequestParam(required = false) includeDeleted: Boolean,
        @RequestParam(required = false, defaultValue = "10000") limit: Int
    ): Flux<EventTypeEntity> {
        return template.select(EventTypeEntity::class.java)
            .matching(DeltaSupport.query(versionSince, includeDeleted, limit))
            .all()
    }
}
