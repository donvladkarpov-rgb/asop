package ru.asop.route.controller

import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import ru.asop.api.route.controller.PathBenefitApi
import ru.asop.api.route.dto.request.PathBenefitCreateRequest
import ru.asop.api.route.dto.response.PathBenefitResponse
import ru.asop.route.service.PathBenefitService

@RestController
class PathBenefitController(
    private val service: PathBenefitService
) : PathBenefitApi {

    override fun list(): Flux<PathBenefitResponse> =
        service.list().flatMapMany { Flux.fromIterable(it) }.map { row ->
            PathBenefitResponse(
                id = row["path_benefit_id"]?.toString() ?: "",
                pathId = row["path_id"]?.toString() ?: "",
                benefitId = row["benefit_id"]?.toString() ?: ""
            )
        }

    override fun get(id: String): Mono<ResponseEntity<PathBenefitResponse>> =
        service.getById(id).flatMap { row ->
            if (row.isEmpty()) Mono.just(ResponseEntity.notFound().build())
            else Mono.just(ResponseEntity.ok(rowToResponse(row)))
        }

    override fun create(request: PathBenefitCreateRequest): Mono<ResponseEntity<PathBenefitResponse>> {
        val data = mapOf(
            "id" to null,
            "pathId" to request.pathId,
            "benefitId" to request.benefitId
        )
        return service.create(data).map { ResponseEntity.status(201).body(rowToResponse(it)) }
    }

    override fun update(id: String, request: PathBenefitCreateRequest): Mono<ResponseEntity<PathBenefitResponse>> {
        val data = mapOf(
            "pathId" to request.pathId,
            "benefitId" to request.benefitId
        )
        return service.update(id, data).flatMap { row ->
            if (row.isEmpty()) Mono.just(ResponseEntity.notFound().build())
            else Mono.just(ResponseEntity.ok(rowToResponse(row)))
        }
    }

    override fun delete(id: String): Mono<ResponseEntity<Void>> =
        service.delete(id).map { rows ->
            if (rows > 0) ResponseEntity.noContent().build()
            else ResponseEntity.notFound().build()
        }

    private fun rowToResponse(row: Map<String, Any?>): PathBenefitResponse = PathBenefitResponse(
        id = row["path_benefit_id"]?.toString() ?: "",
        pathId = row["path_id"]?.toString() ?: "",
        benefitId = row["benefit_id"]?.toString() ?: ""
    )
}
