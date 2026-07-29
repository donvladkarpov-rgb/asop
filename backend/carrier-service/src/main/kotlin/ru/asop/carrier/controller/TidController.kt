package ru.asop.carrier.controller

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import ru.asop.api.tid.controller.TidApi
import ru.asop.api.tid.dto.request.TidCreateRequest
import ru.asop.api.tid.dto.request.TidUpdateRequest
import ru.asop.api.tid.dto.response.TidResponse
import ru.asop.carrier.service.TidService
import java.util.UUID

@RestController
class TidController(
    private val tidService: TidService
) : TidApi {

    override fun listTids(carrierId: UUID?): Flux<TidResponse> =
        tidService.list(carrierId)

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
}
