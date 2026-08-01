package ru.asop.card.controller

import org.springframework.data.r2dbc.core.R2dbcEntityTemplate
import org.springframework.data.relational.core.query.Criteria
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import ru.asop.api.card.controller.CardApi
import ru.asop.api.card.dto.request.CardRegisterRequest
import ru.asop.api.card.dto.request.CardBlockRequest
import ru.asop.api.card.dto.response.CardResponse
import ru.asop.card.config.DeltaSupport
import ru.asop.card.model.CardEntity
import ru.asop.card.service.CardService
import java.security.Principal
import java.time.Instant
import java.util.UUID

@RestController
class CardController(
    private val cardService: CardService,
    private val template: R2dbcEntityTemplate
) : CardApi {

    override fun registerCard(
        request: CardRegisterRequest,
        principal: Mono<Principal>
    ): Mono<ResponseEntity<CardResponse>> {
        return cardService.register(request)
            .map { ResponseEntity.ok(it) }
    }

    override fun blockCard(
        id: UUID,
        request: CardBlockRequest,
        principal: Mono<Principal>
    ): Mono<ResponseEntity<CardResponse>> {
        return Mono.empty()
    }

    override fun getCard(id: UUID): Mono<ResponseEntity<CardResponse>> {
        return cardService.getById(id)
            .map { ResponseEntity.ok(it) }
    }

    @GetMapping("/delta")
    fun listDelta(
        @RequestParam(required = false) updatedAtSince: Instant?,
        @RequestParam(required = false) includeDeleted: Boolean,
        @RequestParam(required = false) userIdsIn: String?,
        @RequestParam(required = false, defaultValue = "10000") limit: Int
    ): Flux<CardEntity> {
        var extra: Criteria? = null
        if (!userIdsIn.isNullOrBlank()) {
            val ids = userIdsIn.split(',').map { it.trim() }.filter { it.isNotEmpty() }.map { UUID.fromString(it) }
            if (ids.isNotEmpty()) extra = Criteria.where("user_id").`in`(ids)
        }
        return template.select(CardEntity::class.java)
            .matching(DeltaSupport.query(updatedAtSince, includeDeleted, limit, extra))
            .all()
    }
}
