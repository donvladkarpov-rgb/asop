package ru.asop.admin.controller

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate
import reactor.core.publisher.Mono
import ru.asop.admin.model.CardTypeEntity
import ru.asop.admin.repository.CardTypeRepository
import ru.asop.api.reference.controller.CardTypeApi
import ru.asop.api.reference.dto.request.CardTypeCreateRequest
import ru.asop.api.reference.dto.request.CardTypeUpdateRequest
import ru.asop.api.reference.dto.response.CardTypeResponse
import ru.asop.common.util.UuidUtils
import java.util.UUID
import reactor.core.publisher.Flux
import org.springframework.data.domain.Sort
import org.springframework.data.relational.core.query.Criteria
import org.springframework.data.relational.core.query.Query
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import ru.asop.admin.config.DeltaSupport

@RestController
class CardTypeController(
    private val repository: CardTypeRepository,
    private val template: R2dbcEntityTemplate
) : CardTypeApi {

    override fun listCardTypes(): Mono<ResponseEntity<List<CardTypeResponse>>> {
        return repository.findAll()
            .map { it.toResponse() }
            .collectList()
            .map { ResponseEntity.ok(it) }
    }

    override fun getCardType(id: UUID): Mono<ResponseEntity<CardTypeResponse>> {
        return repository.findById(id)
            .map { ResponseEntity.ok(it.toResponse()) }
            .defaultIfEmpty(ResponseEntity.notFound().build())
    }

    override fun createCardType(request: CardTypeCreateRequest): Mono<ResponseEntity<CardTypeResponse>> {
        val entity = CardTypeEntity(
            cardTypeId = UuidUtils.newId(),
            cardTypeName = request.cardTypeName
        )
        return template.insert(entity)
            .map { ResponseEntity.status(HttpStatus.CREATED).body(it.toResponse()) }
    }

    override fun updateCardType(id: UUID, request: CardTypeUpdateRequest): Mono<ResponseEntity<CardTypeResponse>> {
        return repository.findById(id)
            .flatMap { existing ->
                val updated = existing.copy(
                    cardTypeName = request.cardTypeName ?: existing.cardTypeName
                )
                repository.save(updated).map { ResponseEntity.ok(it.toResponse()) }
            }
            .defaultIfEmpty(ResponseEntity.notFound().build())
    }

    override fun deleteCardType(id: UUID): Mono<ResponseEntity<Void>> {
        return repository.deleteById(id).thenReturn(ResponseEntity.noContent().build())
    }

    private fun CardTypeEntity.toResponse() = CardTypeResponse(
        id = cardTypeId,
        cardTypeName = cardTypeName
    )


    @GetMapping("/delta")
    fun listDelta(
        @RequestParam(required = false) versionSince: Long?,
        @RequestParam(required = false) includeDeleted: Boolean,
        @RequestParam(required = false, defaultValue = "10000") limit: Int
    ): Flux<CardTypeEntity> {
        return template.select(CardTypeEntity::class.java)
            .matching(DeltaSupport.query(versionSince, includeDeleted, limit))
            .all()
    }
}
