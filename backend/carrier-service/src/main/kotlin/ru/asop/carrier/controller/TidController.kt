package ru.asop.carrier.controller

import org.springframework.data.r2dbc.core.R2dbcEntityTemplate
import org.springframework.data.relational.core.query.Criteria
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import ru.asop.api.tid.controller.TidApi
import ru.asop.api.tid.dto.request.TidCreateRequest
import ru.asop.api.tid.dto.request.TidUpdateRequest
import ru.asop.api.tid.dto.response.TidResponse
import ru.asop.carrier.config.DeltaSupport
import ru.asop.carrier.model.TidEntity
import ru.asop.carrier.service.TidService
import java.time.Instant
import java.util.UUID

@RestController
class TidController(
    private val tidService: TidService,
    private val template: R2dbcEntityTemplate
) : TidApi {

    override fun listTids(carrierId: UUID?, regionId: UUID?): Flux<TidResponse> =
        tidService.list(carrierId, regionId)

    override fun getTid(id: UUID): Mono<ResponseEntity<TidResponse>> =
        tidService.getById(id)
            .map { ResponseEntity.ok(it) }
            .defaultIfEmpty(ResponseEntity.notFound().build())

    override fun createTid(request: TidCreateRequest): Mono<ResponseEntity<TidResponse>> =
        tidService.create(request)
            .map { ResponseEntity.status(HttpStatus.CREATED).body(it) }

    override fun updateTid(id: UUID, request: TidUpdateRequest): Mono<ResponseEntity<TidResponse>> =
        tidService.update(id, request)
            .map { ResponseEntity.ok(it) }
            .defaultIfEmpty(ResponseEntity.notFound().build())

    override fun deleteTid(id: UUID): Mono<ResponseEntity<Void>> =
        tidService.delete(id)
            .thenReturn(ResponseEntity.noContent().build<Void>())

    @GetMapping("/delta")
    fun listDelta(
        @RequestParam(required = false) updatedAtSince: Instant?,
        @RequestParam(required = false) includeDeleted: Boolean,
        @RequestParam(required = false) carrierId: UUID?,
        @RequestParam(required = false, defaultValue = "10000") limit: Int
    ): Flux<TidEntity> {
        val extra = mutableListOf<Criteria>()
        carrierId?.let { extra += Criteria.where("carrier_id").`is`(it) }
        val query = if (extra.isEmpty()) {
            DeltaSupport.query(updatedAtSince, includeDeleted, limit)
        } else {
            DeltaSupport.query(updatedAtSince, includeDeleted, limit, Criteria.from(extra))
        }
        return template.select(TidEntity::class.java)
            .matching(query)
            .all()
    }
}
